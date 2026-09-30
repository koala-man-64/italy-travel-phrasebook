(async () => {
  'use strict';
  const params = new URLSearchParams(location.search), frame = document.getElementById('app');
  frame.width = params.get('width') || innerWidth; frame.height = params.get('height') || innerHeight;
  const stamp = Date.now();
  const source = (await (await fetch('../index.html?fixture=' + stamp, {cache:'no-store'})).text())
    .replace(/(src|href)="([^"?]+\.(?:js|css))"/g, '$1="$2?fixture=' + stamp + '"');
  const bridge = `<script>
    (() => {
      let seq=0, ack=0, epoch=1, active=0;
      const log=[];
      const base={v:1,event:'state',state:'ready',source:'en',text:'',translation:'',machineTranslated:false,canReplay:false,progress:'',error:'',recordingMs:0,models:{it:true,en:true},translationReady:true,voices:{it:true,en:true}};
      const emit=(fields={}) => OfflineConversation.onmessage({data:{...base,seq:++seq,ack,requestId:active,sessionEpoch:epoch,...fields}});
      window.TestNative={log,emit,base};
      window.OfflineConversation={postMessage(raw){
        const m=JSON.parse(raw);log.push(m);ack=m.id;
        queueMicrotask(()=>{
          if(m.op==='clear'){epoch++;active=0;emit();}
          else if(m.op==='status')emit();
          else if(m.op==='present'){active=m.id;emit();}
          else if(m.op==='translate'){active=m.id;emit({state:'translating',source:m.source});setTimeout(()=>emit({state:'result',source:m.source,text:m.text,translation:m.source==='en'?'Messaggio italiano: '+m.text:'Corrected English reply',machineTranslated:true}),30);}
          else if(m.op==='start'){active=m.id;emit({state:'recording',source:'it',recordingMs:1000});}
          else if(m.op==='stop'){active=m.id;emit({state:'result',source:'it',text:'È qui, vicino alla stazione.',translation:'It is here, near the station.',machineTranslated:true,canReplay:true});}
          else if(m.op==='cancel'){active=0;emit();}
          else if(m.op==='setup'){emit({state:'preparing',progress:'Preparing offline models…'});}
          else if(m.op==='cancelSetup')emit();
        });
      }};
    })();
  <\/script>`;
  frame.srcdoc = source.replace('<head>', `<head><base href="${location.origin}/">${bridge}`);
  await new Promise(resolve => { frame.onload = resolve; });
  const w = frame.contentWindow, d = w.document, $ = id => d.getElementById(id);
  const delay = () => new Promise(resolve => setTimeout(resolve, 60));
  const click = async id => { $(id).click(); await delay(); };
  w.FaceConversation.open({ en:'Where is the station?', it:'Dov’è la stazione?' });
  await delay();
  if (params.has('large')) { d.documentElement.style.fontSize='200%'; w.dispatchEvent(new Event('resize')); await delay(); }
  if (params.has('manual')) { document.getElementById('results').remove(); return; }
  const results=[];
  function assert(ok,message){if(!ok)throw Error(message);}
  function check(label) {
    const dialog=$('conversationDialog'), bounds=dialog.getBoundingClientRect(), failures=[];
    for(const el of dialog.querySelectorAll('*')) {
      if(!el.getClientRects().length || el.closest('[hidden]') || el.classList.contains('conversation-measure'))continue;
      const r=el.getBoundingClientRect();
      if(r.width===0 || r.height===0)continue;
      if(el.scrollHeight>el.clientHeight+2 || el.scrollWidth>el.clientWidth+2)failures.push(`${el.id||el.className}: overflow ${el.scrollHeight}/${el.clientHeight} ${el.scrollWidth}/${el.clientWidth}`);
      if(r.top<bounds.top-1||r.bottom>bounds.bottom+1||r.left<bounds.left-1||r.right>bounds.right+1)failures.push(`${el.id||el.className}: outside viewport`);
      if(el.tagName==='BUTTON' && (r.height<47.9 || r.width<47.9))failures.push(`${el.id}: small touch target`);
    }
    for (const button of dialog.querySelectorAll('button.conversation-icon')) {
      assert(button.querySelector('svg') && !button.textContent.trim(), button.id + ': icon only');
      assert(button.getAttribute('aria-label') && button.title, button.id + ': accessible name and tooltip');
    }
    assert(!failures.length,label+': '+failures.join('; '));
    results.push(label+' PASS');
  }
  async function allPages(label) {
    let count=0;
    do { check(`${label} page ${++count}`); if($('conversationNext').disabled)break; await click('conversationNext'); }while(count<500);
    assert(count<500,'Page traversal did not terminate');
  }
  try {
    check('Draft');
    w.TestNative.base.translationReady=false;w.TestNative.emit();await delay();
    await click('conversationEdit');await click('conversationEditorDone');
    assert(!$('conversationShow').disabled,'No-op edit must retain prepared Italian without a translation model');
    w.TestNative.base.translationReady=true;w.TestNative.emit();await delay();
    await click('conversationEdit');
    $('conversationEditor').dispatchEvent(new Event('compositionstart'));
    w.TestNative.emit({state:'error',error:'Deferred native error'});await delay();
    assert($('conversationDialog').dataset.screen==='editor','Error must wait for IME completion');
    $('conversationEditor').dispatchEvent(new Event('compositionend'));await delay();
    assert($('conversationDialog').dataset.screen==='error','No-op IME must deliver deferred error');
    await click('conversationRecover');
    await click('conversationShow');check('Italian');
    assert($('conversationDialog').dataset.rotated==='true','Italian should face the other person');
    assert(!$('conversationTypeItalian').disabled, 'Italian typing button must be enabled when showing Italian');
    assert($('conversationItalianUser').textContent.length > 0, 'Italian message must be shown on user side as well');
    assert(!$('conversationItalianUserCard').hidden, 'User-side Italian card must be visible');
    await click('conversationTypeItalian');
    assert($('conversationDialog').dataset.screen==='editor', 'Editor must open for Italian typing');
    assert($('conversationDialog').dataset.facing==='it', 'Editor must face Italian');
    assert($('conversationDialog').dataset.rotated==='true', 'Browser fallback must retain Italian rotation');
    const keyboardCommands=[];
    w.ItalyKeyboardOrientation={postMessage(command){keyboardCommands.push(command);}};
    w.dispatchEvent(new Event('resize'));await delay();
    assert(keyboardCommands.join(',')==='italian','Native keyboard must rotate once');
    assert($('conversationDialog').dataset.rotated==='false','Native editor must not rotate twice');
    w.dispatchEvent(new Event('resize'));await delay();
    assert(keyboardCommands.length===1,'Resize must not toggle native orientation');
    await click('conversationFlip');
    assert(keyboardCommands.at(-1)==='restore','Flip must restore native orientation');
    await click('conversationFlip');
    assert(keyboardCommands.at(-1)==='italian','Flip back must face Italian keyboard');
    assert($('conversationTitle').textContent==='Scrivi risposta', 'Editor title must be Scrivi risposta');
    assert($('conversationEditorDoneText').textContent==='Traduci', 'Editor Done button must be Traduci');
    assert($('conversationEditorDone').getAttribute('aria-label')==='Traduci la risposta', 'Editor action must announce translation in Italian');
    check('Italian typing editor');
    $('conversationEditor').value='È qui vicino.';
    $('conversationEditor').dispatchEvent(new Event('input'));await delay();
    await click('conversationEditorDone');
    assert(keyboardCommands.at(-1)==='restore','Submitting must restore original orientation');
    assert($('conversationDialog').dataset.screen==='reply','Translated reply must appear');
    check('Typed Italian reply');
    await click('conversationNextMessage');
    await click('conversationShow');
    await click('conversationFlip');check('Italian unrotated');
    assert($('conversationItalianUser').textContent.length > 0, 'Italian message must be shown on user side when unrotated as well');
    await click('conversationRecord');
    const canceledId=w.TestNative.log.at(-1).id;
    await click('conversationCancelOperation');
    w.TestNative.emit({state:'result',source:'it',requestId:canceledId,text:'Late private text',translation:'Late reply'});await delay();
    assert($('conversationDialog').dataset.screen==='italian' && !$('conversationReply').textContent.includes('Late reply'),'Canceled recording result changed the exchange');
    w.TestNative.emit({state:'permission'});await delay();check('Permission recovery');
    assert(!$('conversationError').hidden && $('conversationError').textContent.includes('microphone'),'Permission guidance must be visible');
    await click('conversationRecover');
    w.TestNative.emit();await delay();
    await click('conversationRecord');check('Recording');
    assert($('conversationDialog').dataset.screen==='busy','Recording must show its own screen');
    await click('conversationStop');check('Reply');
    assert($('conversationDialog').dataset.screen==='reply','Completed recording must show their reply');
    await click('conversationMore');await allPages('More');
    await click('conversationOpenSetup');await allPages('Setup');
    await click('conversationLicenses');check('Licenses');
    await click('conversationNext');check('Licenses next page');
    await click('conversationBack');await click('conversationBack');
    await click('conversationChoose');await allPages('Categories');
    $('conversationCategories').querySelector('button:not([hidden])').click();await delay();check('Templates');
    const formulaButton = $('conversationTemplates').querySelector('button');
    const formula = Object.values(w.ConversationBuilder.templates).flat().find(t => t.nameEn === formulaButton.querySelector('span').textContent);
    assert(formula, 'Formula must show its English meaning');
    assert(formulaButton.querySelector('small').textContent === formula.name, 'Formula must retain its Italian name');
    await allPages('Bilingual formulas');
    $('conversationTemplates').querySelector('button:not([hidden])').click();await delay();
    for(let slot=0;slot<4 && $('conversationDialog').dataset.screen==='choices';slot++) {
      await allPages('Slot '+slot);
      const node=Array.from($('conversationSlots').children).find(x=>!x.hidden);
      node.querySelector('button:not([hidden])').click();await delay();
    }
    check('Chosen phrase');
    await click('conversationEdit');
    const composingText=$('conversationEditor').value+' caffè';
    $('conversationEditor').dispatchEvent(new Event('compositionstart'));
    $('conversationEditor').value=composingText;$('conversationEditor').dispatchEvent(new Event('input'));
    w.TestNative.emit();await delay();
    assert($('conversationEditor').value===composingText,'Native status erased active IME composition');
    $('conversationEditor').dispatchEvent(new Event('compositionend'));await delay();
    assert($('conversationDraft').value===composingText,'IME composition was not committed');
    const long=('A station café 👨‍👩‍👧‍👦.\n').repeat(90).slice(0,1990);
    $('conversationEditor').value=long;$('conversationEditor').setSelectionRange(0,0);$('conversationEditor').dispatchEvent(new Event('input'));await delay();
    assert($('conversationDraft').value===long,'Editor lost pasted text');
    check('Long editor');
    frame.height=900;w.dispatchEvent(new Event('resize'));await delay();
    const selectedEnd=Math.min(100,$('conversationEditor').value.length);
    $('conversationEditor').setSelectionRange(3,selectedEnd,'backward');$('conversationEditor').dispatchEvent(new Event('keyup'));
    frame.height=260;w.dispatchEvent(new Event('resize'));await delay();
    frame.height=900;w.dispatchEvent(new Event('resize'));await delay();
    assert($('conversationEditor').selectionStart===3 && $('conversationEditor').selectionEnd===selectedEnd && $('conversationEditor').selectionDirection==='backward','Resize lost editor selection');
    frame.height=params.get('height')||innerHeight;w.dispatchEvent(new Event('resize'));await delay();
    await click('conversationNext');
    const before=$('conversationDraft').value, visible=$('conversationEditor').value;
    $('conversationEditor').value='X'+visible;$('conversationEditor').setSelectionRange(1,1);$('conversationEditor').dispatchEvent(new Event('input'));await delay();
    assert($('conversationDraft').value.length===before.length+1,'Page edit lost adjoining text');
    frame.height=300; w.dispatchEvent(new Event('resize')); await delay();check('Keyboard-sized editor');
    frame.width=640;frame.height=122;w.dispatchEvent(new Event('resize'));await delay();check('Short landscape keyboard editor');
    frame.width=params.get('width')||innerWidth;
    frame.height=params.get('height')||innerHeight; w.dispatchEvent(new Event('resize'));await delay();check('Restored editor');
    await click('conversationEditorDone');await allPages('Long draft');
    await click('conversationShow');await allPages('Long Italian');
    w.TestNative.emit({state:'error',error:'Model unavailable. '.repeat(150)});await delay();await allPages('Long error');
    await click('conversationRecover');
    w.TestNative.base.models={it:false,en:false};w.TestNative.base.translationReady=false;w.TestNative.emit();await delay();
    assert(!$('conversationNeedsSetup').hidden,'Missing setup needs a direct action');
    await click('conversationNeedsSetup');check('Missing models');
    await click('conversationSetup');check('Model preparation');
    await click('conversationCancelSetup');check('Canceled preparation');
    await click('conversationClose');
    assert($('conversationDraft').value==='' && $('conversationTranscript').value==='','Close must clear private text');
    w.FaceConversation.open({en:'Hello',it:'Buongiorno'});await delay();
    await click('conversationShow');await click('conversationTypeItalian');
    assert(keyboardCommands.at(-1)==='italian','Reopened Italian editor must rotate again');
    await click('conversationBack');
    assert(keyboardCommands.at(-1)==='restore','Back from Italian editor must restore orientation');
    await click('conversationTypeItalian');await click('conversationClose');
    assert(keyboardCommands.at(-1)==='restore','Closing Italian editor must restore orientation');
    results.push('Native keyboard enter, flip, submit, back and close PASS');
    results.push('ALL PASS');
  } catch(error) { results.push('FAIL: '+error.message); console.error(error); }
  document.getElementById('results').textContent=results.join('\n');
  document.title=results.at(-1)==='ALL PASS'?'PASS Conversation layout':'FAIL Conversation layout';
})();
