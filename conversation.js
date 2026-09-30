(function (root) {
  'use strict';
  const STATES = new Set(['ready','setup','preparing','permission','recording','finalizing','review','translating','result','playing','error']);
  const COMMANDS = new Set(['status','setup','cancelSetup','start','stop','cancel','clear','translate','present','replay','speak','speakText','stopPlayback','installVoice']);
  const LIMIT = 2000;
  function createProtocol(postMessage) {
    let id = 0;
    return (op, fields = {}) => {
      if (!COMMANDS.has(op)) throw Error('Unknown conversation command');
      id = id >= 2147483647 ? 1 : id + 1;
      postMessage(JSON.stringify({ v: 1, id, op, ...fields })); return id;
    };
  }
  function parseSnapshot(data) {
    let v;
    try { v = typeof data === 'string' ? JSON.parse(data) : data; } catch (_) { return null; }
    if (!v || v.v !== 1 || v.event !== 'state' || !STATES.has(v.state)) return null;
    return { state: v.state, source: v.source === 'it' ? 'it' : 'en',
      text: typeof v.text === 'string' ? v.text.slice(0,LIMIT) : '',
      translation: typeof v.translation === 'string' ? v.translation.slice(0,LIMIT) : '',
      machineTranslated: v.machineTranslated === true, canReplay: v.canReplay === true,
      models: { en: !!v.models && v.models.en === true, it: !!v.models && v.models.it === true },
      voices: { en: !!v.voices && v.voices.en === true, it: !!v.voices && v.voices.it === true },
      translationReady: v.translationReady === true,
      progress: typeof v.progress === 'string' ? v.progress : '',
      error: typeof v.error === 'string' ? v.error : '',
      recordingMs: Number.isFinite(v.recordingMs) ? Math.max(0, Math.min(30000,v.recordingMs)) : 0,
      seq: Number.isSafeInteger(v.seq) && v.seq > 0 ? v.seq : null,
      ack: Number.isSafeInteger(v.ack) && v.ack >= 0 ? v.ack : null,
      requestId: Number.isSafeInteger(v.requestId) && v.requestId > 0 ? v.requestId : null,
      sessionEpoch: Number.isSafeInteger(v.sessionEpoch) ? v.sessionEpoch : null };
  }
  function initialState(exchange = 0, revision = 0) {
    return { exchange, revision, draft: '', prepared: null, published: null, transcript: '', reply: '', replyMachine: false, canReplay: false, pending: null };
  }
  function createConversationState() {
    let s = initialState();
    return { get: () => ({ ...s }),
      draft(text, prepared = null) { s = { ...s, revision:s.revision+1, draft:text.slice(0,LIMIT), prepared, pending:null }; },
      begin(kind,id) { s = { ...s, pending:{kind,id,exchange:s.exchange,revision:s.revision} }; },
      accept(n) { const p=s.pending; return !!p && n.requestId===p.id && p.exchange===s.exchange && p.revision===s.revision; },
      publish(en,it,machine) { s = { ...s,exchange:s.exchange+1,revision:s.revision+1,published:{en,it,machine},transcript:'',reply:'',replyMachine:false,canReplay:false,pending:null }; },
      transcript(text,replay=false) { s={...s,revision:s.revision+1,transcript:text.slice(0,LIMIT),reply:'',replyMachine:false,canReplay:replay,pending:null}; },
      reply(text,machine=true) { s={...s,reply:text.slice(0,LIMIT),replyMachine:machine,pending:null}; },
      replay(value) { s={...s,canReplay:value}; },
      reject() { s={...s,pending:null}; },
      clear() { s=initialState(s.exchange+1,s.revision+1); } };
  }
  // A read-only consumer of the existing dispatcher. It owns no bridge handler,
  // request counter or engine, and stores only projected capability reports.
  function createSpeechReadiness({ projectCapabilities, source, requestStatus, openSetup }) {
    const listeners = new Set();
    let active = true, disposed = false, issuingStatus = false;
    let latestSequence = 0, latestEpoch = -1, statusFence = 0;
    const projectUnknown = (fresh = true) => projectCapabilities(null, {
      source: source(), protocolBaseline: 'selected-1.9', fresh, lastSequence: latestSequence
    });
    let current = projectUnknown();
    const supported = () => source() === 'OfflineConversation-v1';
    const snapshot = () => supported() ? current : projectUnknown();
    function notify() {
      for (const listener of [...listeners]) {
        // A failed view cannot interrupt native conversation handling or peers.
        try { listener(snapshot()); } catch (_) { /* observer isolation */ }
      }
    }
    function invalidate() { current = projectUnknown(false); notify(); }
    function refresh() {
      if (disposed || !active || !supported()) return false;
      // Suppress any reentrant callback until the existing dispatcher returns
      // the request ID. WebMessage callbacks normally arrive asynchronously.
      statusFence = null;
      issuingStatus = true;
      invalidate();
      try {
        const id = requestStatus();
        if (!Number.isSafeInteger(id) || id < 1 || id > 2147483647) return false;
        statusFence = id;
        return true;
      } catch (_) { return false; }
      finally { issuingStatus = false; }
    }
    const facade = Object.freeze({
      getSnapshot: snapshot,
      subscribe(listener) {
        if (disposed) return () => {};
        if (typeof listener !== 'function') throw new TypeError('A readiness listener is required');
        listeners.add(listener);
        try { listener(snapshot()); } catch (_) { /* observer isolation */ }
        return () => listeners.delete(listener);
      },
      refresh,
      openSetup() {
        if (disposed || !active || !supported()) return false;
        openSetup();
        return true;
      }
    });
    return Object.freeze({
      facade,
      accept(raw, previousSequence = 0) {
        if (disposed || !active || issuingStatus || statusFence === null || !supported()) return false;
        const next = projectCapabilities(raw, { source: source(), protocolBaseline: 'selected-1.9',
          fresh: true, lastSequence: Math.max(latestSequence, previousSequence) });
        if (next.freshness !== 'current') { current = projectUnknown(); notify(); return false; }
        // Projection must validate the full original frame before these scalar
        // identity fields are read. Never retain its text or translation.
        const frame = typeof raw === 'string' ? JSON.parse(raw) : raw;
        if (frame.sessionEpoch < latestEpoch || frame.ack < statusFence) return false;
        latestEpoch = frame.sessionEpoch;
        latestSequence = next.sequence;
        current = next;
        notify();
        return true;
      },
      invalidate,
      suspend() {
        if (disposed) return;
        active = false;
        statusFence = null;
        invalidate();
      },
      resume() {
        if (disposed) return false;
        active = true;
        return refresh();
      },
      dispose() {
        if (disposed) return;
        disposed = true;
        active = false;
        invalidate();
        listeners.clear();
      }
    });
  }
  function mount(document, window) {
    const $ = id => document.getElementById(id);
    const dialog=$('conversationDialog'); if (!dialog) return;
    const entries=['conversationEntry','conversationNav','builderConversation'];
    const bridge=()=>window.OfflineConversation;
    const supported=()=>!!bridge() && typeof bridge().postMessage==='function';
    const send=createProtocol(message=>bridge().postMessage(message));
    const command=(op,fields)=>supported()?send(op,fields):0;
    const readiness = window.ItalySpeechReadiness ? createSpeechReadiness({
      projectCapabilities: window.ItalySpeechReadiness.projectCapabilities,
      source: () => supported() ? 'OfflineConversation-v1' : window.AndroidSpeech ? 'legacy-native' : 'web',
      requestStatus: () => command('status'),
      openSetup: () => { open(); if (view) view.showSetup(); }
    }) : null;
    let documentActive = !document.hidden;
    const conversation=createConversationState();
    let native=parseSnapshot({v:1,event:'state',state:'setup'});
    let lastSeq=0, resetId=0, epoch=null, errorMessage='';
    let category='', templateIndex=0, choices=[];
    const view = window.ConversationView ? window.ConversationView.mount(document, window, {
      edit(target, text) {
        if (conversation.get()[target] === text) return;
        if (target === 'draft') { $('conversationDraft').value=text; conversation.draft(text); }
        else { $('conversationTranscript').value=text; conversation.transcript(text,conversation.get().canReplay); }
        render();
      },
      translateReply,
      dismissError() { errorMessage=''; $('conversationError').textContent=''; },
      cancel() { conversation.reject(); conversation.replay(false); resetId=command('cancel'); render(); }
    }) : null;
    const busy=()=>['preparing','permission','recording','finalizing','translating'].includes(native.state);
    const pendingWork=()=>{const p=conversation.get().pending;return !!p && ['publish','reply','record'].includes(p.kind);};
    function available() {
      const ok=supported() && typeof dialog.showModal==='function';
      entries.forEach(id=>{if($(id)) $(id).hidden=!ok;});
      $('conversationUnsupported').hidden=ok; return ok;
    }
    function resetComposer() {
      const b=window.ConversationBuilder;
      if(!b || !b.categories || !b.categories.length) return;
      const first=b.categories.find(c=>b.templates[c.id]);
      if(!first) return;
      category=first.id; templateIndex=0; choices=b.templates[category][0].slots.map(()=>0);
      renderComposer();
    }
    function clear() { conversation.clear(); errorMessage=''; if(view) view.reset(); $('conversationDraft').value=''; $('conversationError').textContent=''; $('conversationItalianError').textContent=''; resetComposer(); resetId=command('clear'); render(); }
    function close() { clear(); command('cancel'); if(dialog.open) dialog.close(); if($('conversationEntry')) $('conversationEntry').focus(); }
    function replace(node,children) { while(node.firstChild) node.removeChild(node.firstChild); children.forEach(child=>node.appendChild(child)); }
    function chip(label,sub,pressed,click) {
      const b=document.createElement('button'); b.type='button'; b.className='conversation-chip';
      b.setAttribute('aria-pressed',String(pressed));
      const t=document.createElement('span'); t.textContent=label; b.append(t);
      if(sub) { const small=document.createElement('small'); small.lang='it'; small.textContent=sub; b.append(small); }
      b.addEventListener('click',click); return b;
    }
    function prepared() {
      const b=window.ConversationBuilder, t=b && b.templates && b.templates[category] && b.templates[category][templateIndex];
      return t ? b.compile(t,choices) : null;
    }
    function selectCategory(id) { if(!window.ConversationBuilder || !window.ConversationBuilder.templates || !window.ConversationBuilder.templates[id]) return; category=id; selectTemplate(0); }
    function selectTemplate(i) {
      const b=window.ConversationBuilder, t=b && b.templates && b.templates[category] && b.templates[category][i]; if(!t) return;
      templateIndex=i; choices=t.slots.map(()=>0); setDraftFromBuilder();
    }
    function setDraftFromBuilder() {
      const p=prepared(); if(!p) return;
      $('conversationDraft').value=p.en; conversation.draft(p.en,p); renderComposer(); render();
    }
    function renderComposer() {
      const b=window.ConversationBuilder; if(!b || !category) return;
      replace($('conversationCategories'),b.categories.filter(c=>b.templates[c.id]).map(c=>chip(c.label,c.icon,c.id===category,()=>selectCategory(c.id))));
      replace($('conversationTemplates'),b.templates[category].map((t,i)=>chip(t.nameEn || t.name,t.nameEn ? t.name : '',i===templateIndex,()=>selectTemplate(i))));
      const t=b.templates[category][templateIndex];
      replace($('conversationSlots'),t.slots.map((slot,slotIndex)=>{
        const wrap=document.createElement('div'); wrap.className='conversation-slot';
        const label=document.createElement('strong'); label.textContent=slot.label; wrap.append(label);
        const options=document.createElement('div'); options.className='conversation-chips';
        replace(options,slot.options.map((option,i)=>chip(option.en,option.it,choices[slotIndex]===i,()=>{choices[slotIndex]=i;setDraftFromBuilder();})));
        wrap.append(options); return wrap;
      }));
    }
    function open(pair) {
      if(!available()) return;
      clear();
      if(pair && pair.en && pair.it) {
        const en=String(pair.en).trim().slice(0,LIMIT), it=String(pair.it).trim().slice(0,LIMIT);
        $('conversationDraft').value=en; conversation.draft(en,{en,it});
      } else setDraftFromBuilder();
      dialog.showModal(); $('conversationClose').focus(); command('status'); render();
    }
    function publish() {
      const s=conversation.get(), en=$('conversationDraft').value.trim();
      if(!en || en.length>LIMIT || busy() || pendingWork()) return;
      errorMessage=''; $('conversationError').textContent=''; $('conversationItalianError').textContent='';
      if(s.prepared && en===s.prepared.en && s.prepared.it) {
        const id=command('present',{en,it:s.prepared.it});
        conversation.publish(en,s.prepared.it,false); conversation.begin('present',id); render(); return;
      }
      if(!native.translationReady) return;
      const id=command('translate',{source:'en',text:en});
      conversation.begin('publish',id); render();
    }
    function record() {
      if(!conversation.get().published || !native.models.it || busy() || pendingWork() || native.state==='playing') return;
      errorMessage=''; $('conversationError').textContent=''; $('conversationItalianError').textContent='';
      conversation.transcript('',false);
      conversation.begin('record',command('start',{source:'it',autoTranslate:true})); render();
    }
    function translateReply() {
      const text=$('conversationTranscript').value.trim();
      if(!text || text.length>LIMIT || !native.translationReady || busy() || pendingWork()) return;
      errorMessage=''; $('conversationError').textContent=''; $('conversationItalianError').textContent='';
      conversation.transcript(text,conversation.get().canReplay);
      conversation.begin('reply',command('translate',{source:'it',text})); render();
    }
    function receive(event) {
      const n=parseSnapshot(event.data);
      if(!n) { if(readiness) readiness.accept(event.data,lastSeq); return; }
      if(n.seq!==null && n.seq<=lastSeq) return;
      // Pass the original frame, never the lenient conversation projection.
      // This observer has its own strict admission and cannot alter turn state.
      if(readiness) readiness.accept(event.data,lastSeq);
      if(n.seq!==null) lastSeq=n.seq;
      native=n;
      if(epoch!==null && n.sessionEpoch!==null && n.sessionEpoch!==epoch) {
        epoch=n.sessionEpoch;
        if (!resetId) { conversation.clear(); errorMessage=''; if(view) view.reset(); $('conversationDraft').value=''; $('conversationError').textContent=''; $('conversationItalianError').textContent=''; resetComposer(); render(); return; }
      }
      if(n.sessionEpoch!==null) epoch=n.sessionEpoch;
      if(resetId && (n.ack===null || n.ack<resetId)) return;
      if(resetId && n.ack>=resetId) resetId=0;
      if(!dialog.open) return;
      const s=conversation.get(), p=s.pending;
      if(p && conversation.accept(n)) {
        if(p.kind==='publish' && n.state==='result' && n.source==='en' && n.translation) conversation.publish(s.draft.trim(),n.translation,true);
        else if(p.kind==='record' && n.source==='it') {
          if(n.text && n.text!==s.transcript) { conversation.transcript(n.text,n.canReplay); conversation.begin('record',p.id); }
          if(n.state==='result' && n.translation) conversation.reply(n.translation,n.machineTranslated);
          else if(n.canReplay) conversation.replay(true);
        } else if(p.kind==='reply' && n.state==='result' && n.source==='it' && n.text===s.transcript && n.translation) conversation.reply(n.translation,true);
      }
      if(n.error) {
        errorMessage=n.error;
        $('conversationError').textContent=n.error;
        if(n.state==='error') $('conversationItalianError').textContent='Si è verificato un problema. Riprova o correggi il testo.';
      }
      if(n.state==='error') { conversation.reject(); conversation.replay(n.canReplay); }
      if(n.state==='permission') { conversation.reject(); errorMessage='Allow microphone access, then tap Parla again.'; }
      render();
    }
    function render() {
      const s=conversation.get(), recording=native.state==='recording' && !!s.pending && s.pending.kind==='record';
      const pending=!!s.pending && ['publish','reply'].includes(s.pending.kind);
      $('conversationReady').textContent=(native.models.it?'Italian speech ready':'Italian speech needs setup')+' · '+(native.translationReady?'translation ready':'translation needs setup');
      $('conversationReadiness').textContent=`English speech: ${native.models.en?'ready':'setup needed'} · Italian speech: ${native.models.it?'ready':'setup needed'}\nTranslation: ${native.translationReady?'ready':'setup needed'} · English voice: ${native.voices.en?'ready':'setup needed'} · Italian voice: ${native.voices.it?'ready':'setup needed'}`;
      const itText=s.published ? s.published.it : 'Il messaggio apparirà qui.';
      $('conversationItalian').textContent=itText;
      if($('conversationItalianUser')) $('conversationItalianUser').textContent=itText;
      if($('conversationItalianUserCard')) $('conversationItalianUserCard').hidden=!s.published;
      $('conversationPublishedEnglish').textContent=s.published ? s.published.en : '';
      $('conversationPublishedEnglish').hidden=!s.published;
      $('conversationItalianAttribution').hidden=!(s.published && s.published.machine);
      $('conversationReply').textContent=s.reply || 'Their reply will appear here in English.';
      $('conversationReplyCard').hidden=!s.published;
      $('conversationReplyAttribution').hidden=!s.replyMachine;
      if($('conversationTranscript').value!==s.transcript) $('conversationTranscript').value=s.transcript;
      $('conversationTranscript').disabled=!s.published || busy() || pendingWork();
      $('conversationShow').disabled=!s.draft.trim() || busy() || pendingWork() || (!s.prepared && !native.translationReady);
      $('conversationRecord').hidden=recording;
      $('conversationRecord').disabled=!s.published || !native.models.it || busy() || pendingWork() || native.state==='playing';
      if($('conversationTypeItalian')) $('conversationTypeItalian').disabled=!s.published || busy() || pendingWork();
      $('conversationStop').hidden=!recording;
      $('conversationTimer').textContent=recording?`${Math.floor(native.recordingMs/1000)} / 30 s`:'';
      $('conversationTranslateReply').disabled=!s.published || !s.transcript.trim() || !native.translationReady || busy() || pendingWork();
      $('conversationReplay').disabled=!s.canReplay || busy();
      $('conversationSpeakItalian').disabled=!s.published || !native.voices.it || busy();
      $('conversationSpeakItalianSlow').disabled=!s.published || !native.voices.it || busy();
      $('conversationSpeakEnglish').disabled=!s.reply || !native.voices.en || busy();
      $('conversationDraft').disabled=busy() || pendingWork();
      Array.prototype.forEach.call($('conversationCategories').querySelectorAll('button'), b=>{b.disabled=$('conversationDraft').disabled;});
      Array.prototype.forEach.call($('conversationTemplates').querySelectorAll('button'), b=>{b.disabled=$('conversationDraft').disabled;});
      Array.prototype.forEach.call($('conversationSlots').querySelectorAll('button'), b=>{b.disabled=$('conversationDraft').disabled;});
      $('conversationStatus').textContent=pending || native.state==='translating'?'Translating on this phone…':native.state==='permission'?'Allow microphone access, then tap Parla again.':native.state==='finalizing'?'Transcribing Italian…':recording?'Registrazione in corso…':'Ready to talk.';
      $('conversationItalianStatus').textContent=native.state==='permission'?'Consenti il microfono, poi tocca Parla di nuovo.':native.state==='finalizing'?'Sto trascrivendo…':native.state==='translating'?'Sto traducendo…':recording?'Registrazione in corso…':'';
      $('conversationProgress').textContent=native.progress;
      $('conversationError').textContent=errorMessage;
      $('conversationError').hidden=!$('conversationError').textContent;
      $('conversationItalianError').hidden=!$('conversationItalianError').textContent;
      $('conversationCancelSetup').hidden=native.state!=='preparing';
      $('conversationInstallVoice').hidden=native.voices.en && native.voices.it;
      if(view) view.update(s,native,errorMessage);
    }
    ['conversationEntry','conversationNav'].forEach(id=>{if($(id))$(id).addEventListener('click',()=>open());});
    $('conversationClose').addEventListener('click',close);
    dialog.addEventListener('cancel',e=>{e.preventDefault();close();});
    function suspendDocument() {
      documentActive=false;
      if(readiness) readiness.suspend();
      clear();
    }
    function resumeDocument() {
      if(document.hidden || documentActive) return;
      documentActive=true;
      if(readiness) readiness.resume();
    }
    document.addEventListener('visibilitychange',()=>{if(document.hidden) suspendDocument();else resumeDocument();});
    window.addEventListener('pagehide',suspendDocument);
    window.addEventListener('pageshow',resumeDocument);
    window.addEventListener('focus',()=>{if(readiness && !document.hidden) readiness.facade.refresh();});
    $('conversationClear').addEventListener('click',clear);
    $('conversationDraft').addEventListener('input',()=>{conversation.draft($('conversationDraft').value);render();});
    $('conversationShow').addEventListener('click',publish);
    $('conversationRecord').addEventListener('click',record);
    $('conversationStop').addEventListener('click',()=>{const p=conversation.get().pending;if(p && p.kind==='record'){conversation.begin('record',command('stop'));render();}});
    $('conversationTranscript').addEventListener('input',()=>{conversation.transcript($('conversationTranscript').value,conversation.get().canReplay);render();});
    $('conversationTranslateReply').addEventListener('click',translateReply);
    $('conversationReplay').addEventListener('click',()=>command('replay'));
    $('conversationSpeakItalian').addEventListener('click',()=>command('speakText',{text:conversation.get().published.it,language:'it',slow:false}));
    $('conversationSpeakItalianSlow').addEventListener('click',()=>command('speakText',{text:conversation.get().published.it,language:'it',slow:true}));
    $('conversationSpeakEnglish').addEventListener('click',()=>command('speakText',{text:conversation.get().reply,language:'en',slow:false}));
    $('conversationSetup').addEventListener('click',()=>command('setup'));
    $('conversationCancelSetup').addEventListener('click',()=>command('cancelSetup'));
    $('conversationInstallVoice').addEventListener('click',()=>command('installVoice'));
    const b=window.ConversationBuilder;
    if(b && b.categories && b.categories.length) { const first=b.categories.find(c=>b.templates[c.id]); category=first?first.id:''; selectTemplate(0); }
    window.FaceConversation={open,clear,speech:readiness && readiness.facade};
    const bindBridge=()=>{bridge().onmessage=receive;if(readiness) readiness.facade.refresh();else command('status');};
    if(supported()) bindBridge();
    else { let attempts=0; const timer=window.setInterval(()=>{attempts++;if(supported())bindBridge();if(available()||attempts>=20)window.clearInterval(timer);},250); }
    available();render();
  }
  if(typeof module==='object' && module.exports) module.exports={createProtocol,parseSnapshot,createConversationState,createSpeechReadiness,mount};
  if(root.document) mount(root.document,root);
})(typeof window!=='undefined'?window:globalThis);
