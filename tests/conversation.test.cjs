const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { createProtocol, parseSnapshot, createConversationState, mount } = require('../conversation.js');

function ui(withReadiness = false) {
  const html = fs.readFileSync(path.join(__dirname,'..','index.html'),'utf8');
  const ids=[...html.matchAll(/id="(conversation[A-Za-z]+)"/g)].map(x=>x[1]);
  class Element {
    constructor() { this.hidden=false; this.disabled=false; this.value=''; this.textContent=''; this.children=[]; this.handlers={}; this.open=false; this.firstChild=null; }
    addEventListener(name,fn) { this.handlers[name]=fn; }
    append(child) { this.children.push(child); this.firstChild=this.children[0]; }
    appendChild(child) { this.append(child); }
    removeChild(child) { this.children.splice(this.children.indexOf(child),1); this.firstChild=this.children[0]||null; }
    setAttribute() {}
    querySelectorAll(selector) { return selector==='button' ? this.children.flatMap(x=>x.tagName==='button'?[x]:x.querySelectorAll(selector)) : []; }
    focus() { this.focused=true; }
    showModal() { this.open=true; }
    close() { this.open=false; }
    click() { if(!this.disabled && this.handlers.click) this.handlers.click({preventDefault(){}}); }
  }
  const elements=new Map(ids.map(id=>[id,new Element()]));
  elements.set('conversationEntry',new Element());
  elements.set('conversationNav',new Element());
  const document={hidden:false,handlers:{},getElementById:id=>elements.get(id),createElement:tag=>{const e=new Element();e.tagName=tag;return e;},addEventListener(name,fn){this.handlers[name]=fn;}};
  const messages=[];
  const bridge={postMessage(message){messages.push(JSON.parse(message));}};
  const window={OfflineConversation:bridge,ConversationBuilder:{categories:[{id:'travel',label:'Travel',icon:'T'}],templates:{travel:[{name:'Question',slots:[{label:'Greeting',options:[{en:'Hello',it:'Ciao'},{en:'Good morning',it:'Buongiorno'}]}]}]},compile(t,choices){const option=t.slots[0].options[choices[0]];return {en:option.en,it:option.it};}},addEventListener(){},setInterval(){},clearInterval(){}};
  window.handlers = {};
  window.addEventListener = (name, callback) => { window.handlers[name] = callback; };
  if (withReadiness) window.ItalySpeechReadiness = require('../speech-readiness.js');
  mount(document,window);
  let seq=0, epoch=1;
  function receive(fields={}) { bridge.onmessage({data:{v:1,event:'state',state:'ready',seq:++seq,ack:messages.at(-1).id,requestId:0,sessionEpoch:epoch,source:'en',text:'',translation:'',machineTranslated:false,canReplay:false,progress:'',error:'',recordingMs:0,models:{it:true,en:true},translationReady:true,voices:{it:true,en:true},...fields}}); }
  return { $, elements, document, window, messages, receive, setEpoch:n=>{epoch=n;} };
  function $(id) { return elements.get(id); }
}

test('protocol preserves exact operation fields and monotonic IDs', () => {
  const messages = [];
  const send = createProtocol(x => messages.push(JSON.parse(x)));
  assert.equal(send('present', { en: 'Hello', it: 'Ciao' }), 1);
  assert.equal(send('start', { source: 'it', autoTranslate: true }), 2);
  assert.equal(send('speakText', { text: 'Ciao', language: 'it', slow: true }), 3);
  assert.deepEqual(messages[0], { v:1, id:1, op:'present', en:'Hello', it:'Ciao' });
  assert.equal(messages[1].autoTranslate, true);
  assert.equal(messages[2].slow, true);
  assert.throws(() => send('unknown'), /Unknown/);
});

test('formula chooser shows English above Italian and still selects the formula', () => {
  const u = ui();
  u.window.ConversationBuilder.templates.travel[0].nameEn = 'Ask a question';
  u.window.FaceConversation.open();
  const button = u.$('conversationTemplates').children[0];
  assert.equal(button.children[0].textContent, 'Ask a question');
  assert.equal(button.children[1].textContent, 'Question');
  assert.equal(button.children[1].lang, 'it');
  button.click();
  assert.equal(u.$('conversationDraft').value, 'Hello');
});

test('snapshot validates envelope and limits native text', () => {
  assert.equal(parseSnapshot('{'), null);
  assert.equal(parseSnapshot({v:1,event:'state',state:'unknown'}), null);
  const snapshot = parseSnapshot({v:1,event:'state',state:'result',source:'it',text:'x'.repeat(3000),translation:'Hello',requestId:9,sessionEpoch:4,machineTranslated:true});
  assert.equal(snapshot.text.length, 2000);
  assert.equal(snapshot.requestId, 9);
  assert.equal(snapshot.sessionEpoch, 4);
  assert.equal(snapshot.machineTranslated, true);
});

test('draft remains separate from published sentence and next publish clears the reply', () => {
  const s = createConversationState();
  s.draft('Where is the station?', {en:'Where is the station?',it:'Dov’è la stazione?'});
  s.publish('Where is the station?', 'Dov’è la stazione?', false);
  s.draft('A different question');
  assert.equal(s.get().published.it, 'Dov’è la stazione?');
  s.transcript('È laggiù.', true);
  s.reply('It is over there.', true);
  assert.equal(s.get().reply, 'It is over there.');
  s.publish('Thank you', 'Grazie', false);
  assert.equal(s.get().reply, '');
  assert.equal(s.get().canReplay, false);
});

test('late async results cannot overwrite edited, cleared, or newer exchanges', () => {
  const s = createConversationState();
  s.draft('Hello'); s.begin('publish', 5);
  assert.equal(s.accept({requestId:5}), true);
  s.draft('Changed');
  assert.equal(s.accept({requestId:5}), false);
  s.begin('publish', 6); s.publish('Changed','Cambiato',true);
  assert.equal(s.accept({requestId:6}), false);
  s.begin('record', 7); s.transcript('Buongiorno', true); s.begin('record', 8);
  assert.equal(s.accept({requestId:7}), false);
  assert.equal(s.accept({requestId:8}), true);
  s.clear();
  assert.equal(s.accept({requestId:8}), false);
  assert.equal(s.get().published, null);
});

test('UI contains a single turn viewport and memory-only conversation fields', () => {
  const markup = fs.readFileSync(path.join(__dirname,'..','index.html'),'utf8');
  const css = fs.readFileSync(path.join(__dirname,'..','conversation.css'),'utf8');
  for (const id of ['conversationItalian','conversationRecord','conversationStop','conversationDraft','conversationTranscript','conversationReply','conversationSetup','conversationClear']) {
    assert.match(markup, new RegExp(`id="${id}"`));
  }
  assert.doesNotMatch(css, /overflow(?:-[xy])?:\s*(auto|scroll)/);
  assert.match(css, /data-rotated="true"[^}]*rotate\(180deg\)/);
  assert.match(markup, /data-screen="editor"/);
  assert.match(markup, /maxlength="2000"/);
});

test('mounted UI preserves imported draft across its own clear epoch and publishes prepared pair', () => {
  const v=ui();
  v.receive();
  v.window.FaceConversation.open({en:'Where is the station?',it:'Dov’è la stazione?'});
  assert.equal(v.$('conversationDraft').value,'Where is the station?');
  v.setEpoch(2); v.receive({requestId:0});
  assert.equal(v.$('conversationDraft').value,'Where is the station?');
  v.$('conversationShow').click();
  assert.equal(v.messages.at(-1).op,'present');
  assert.equal(v.$('conversationItalian').textContent,'Dov’è la stazione?');
  v.$('conversationClear').click();
  assert.equal(v.$('conversationDraft').value,'');
  assert.equal(v.$('conversationItalian').textContent,'Il messaggio apparirà qui.');
});

test('mounted UI custom translation, recording stop, auto reply, and stale edit result', () => {
  const v=ui(); v.receive(); v.window.FaceConversation.open();
  v.setEpoch(2); v.receive({requestId:0});
  v.$('conversationDraft').value='Custom question'; v.$('conversationDraft').handlers.input();
  v.$('conversationShow').click();
  const id=v.messages.at(-1).id;
  assert.equal(v.messages.at(-1).op,'translate');
  v.receive({state:'result',source:'en',text:'Custom question',translation:'Domanda personalizzata',requestId:id});
  assert.equal(v.$('conversationItalian').textContent,'Domanda personalizzata');
  v.$('conversationRecord').click();
  const start=v.messages.at(-1);
  assert.deepEqual({op:start.op,source:start.source,autoTranslate:start.autoTranslate},{op:'start',source:'it',autoTranslate:true});
  v.receive({state:'recording',source:'it',requestId:start.id});
  v.$('conversationStop').click();
  const stop=v.messages.at(-1);
  assert.equal(stop.op,'stop');
  v.receive({state:'result',source:'it',text:'È qui',translation:'It is here',canReplay:true,machineTranslated:true,requestId:stop.id});
  assert.equal(v.$('conversationReply').textContent,'It is here');
  v.$('conversationTranscript').value='È là'; v.$('conversationTranscript').handlers.input();
  v.receive({state:'result',source:'it',text:'È qui',translation:'It is here',requestId:stop.id});
  assert.equal(v.$('conversationReply').textContent,'Their reply will appear here in English.');
  v.$('conversationTranslateReply').click();
  const correction=v.messages.at(-1);
  const beforeAck=v.messages.length;
  v.$('conversationTranslateReply').click();
  assert.equal(v.messages.length,beforeAck);
  v.receive({state:'translating',source:'it',text:'È là',requestId:correction.id});
  assert.equal(v.$('conversationTranslateReply').disabled,true);
  assert.equal(v.$('conversationRecord').disabled,true);
  const sent=v.messages.length;
  v.$('conversationTranslateReply').click();
  assert.equal(v.messages.length,sent);
  v.receive({state:'result',source:'it',text:'È là',translation:'It is there',machineTranslated:true,requestId:correction.id});
  assert.equal(v.$('conversationReply').textContent,'It is there');
  v.receive({state:'error',error:'Playback failed',canReplay:false,requestId:correction.id});
  assert.equal(v.$('conversationReplay').disabled,true);
  assert.equal(v.$('conversationItalian').textContent,'Domanda personalizzata');
});

test('mounted UI permission and native error release controls for explicit retry', () => {
  const v=ui(); v.receive(); v.window.FaceConversation.open({en:'Hello',it:'Ciao'});
  v.setEpoch(2); v.receive(); v.$('conversationShow').click();
  v.$('conversationRecord').click(); const start=v.messages.at(-1);
  v.receive({state:'permission',requestId:start.id});
  v.receive({state:'ready',error:'Microphone enabled. Tap Record to begin.',requestId:start.id});
  assert.equal(v.$('conversationRecord').disabled,false);
  v.$('conversationRecord').click();
  const retry=v.messages.at(-1);
  assert.equal(retry.op,'start');
  v.receive({state:'error',error:'No speech detected',requestId:retry.id});
  assert.equal(v.$('conversationRecord').disabled,false);
  assert.equal(v.$('conversationItalian').textContent,'Ciao');
});

test('prepared bilingual display works without translation, speech, or voices', () => {
  const v=ui(); v.receive({translationReady:false,models:{},voices:{}});
  v.window.FaceConversation.open({en:'Thank you',it:'Grazie'});
  v.setEpoch(2); v.receive({translationReady:false,models:{},voices:{}});
  assert.equal(v.$('conversationShow').disabled,false);
  v.$('conversationShow').click();
  assert.equal(v.messages.at(-1).op,'present');
  assert.equal(v.$('conversationItalian').textContent,'Grazie');
  assert.equal(v.$('conversationRecord').disabled,true);
  assert.equal(v.$('conversationSpeakItalian').disabled,true);
});

test('background clears all displayed text and rejects a late recording result', () => {
  const v=ui(); v.receive(); v.window.FaceConversation.open({en:'Hello',it:'Ciao'});
  v.setEpoch(2); v.receive(); v.$('conversationShow').click();
  v.$('conversationRecord').click(); const start=v.messages.at(-1);
  v.receive({state:'recording',source:'it',requestId:start.id});
  assert.equal(v.$('conversationDraft').disabled,true);
  v.document.hidden=true; v.document.handlers.visibilitychange();
  assert.equal(v.messages.at(-1).op,'clear');
  assert.equal(v.$('conversationDraft').value,'');
  v.receive({state:'result',source:'it',text:'Ciao',translation:'Hello',requestId:start.id});
  assert.equal(v.$('conversationItalian').textContent,'Il messaggio apparirà qui.');
  assert.equal(v.$('conversationReply').textContent,'Their reply will appear here in English.');
});

test('external native lifecycle epoch clears published text even without a browser visibility event', () => {
  const v=ui(); v.receive(); v.window.FaceConversation.open({en:'Hello',it:'Ciao'});
  v.setEpoch(2); v.receive(); v.$('conversationShow').click();
  v.setEpoch(3); v.receive();
  assert.equal(v.$('conversationDraft').value,'');
  assert.equal(v.$('conversationItalian').textContent,'Il messaggio apparirà qui.');
});

test('mounted readiness uses the real projector and existing bridge while the dialog is closed', () => {
  const v=ui(true), service=v.window.FaceConversation.speech;
  assert.deepEqual(v.messages.map(m=>m.op),['status']);
  assert.equal(service.getSnapshot().freshness,'stale');
  service.subscribe(()=>{throw Error('broken observer');});
  v.receive({text:'Private text',translation:'Private translation'});
  const report=service.getSnapshot();
  assert.equal(report.voices.it,'reported-ready');
  assert.equal(report.offlineDeviceProof,'unverified');
  assert.equal(JSON.stringify(report).includes('Private'),false);
  v.receive({text:'x'.repeat(2001)});
  assert.equal(service.getSnapshot().freshness,'unknown');
  assert.equal(service.getSnapshot().voices.it,'unknown');
});

test('mounted readiness refresh preserves the active conversation request and shared counter', () => {
  const v=ui(true); v.receive(); v.window.FaceConversation.open({en:'Hello',it:'Ciao'});
  v.setEpoch(2); v.receive(); v.$('conversationShow').click(); v.$('conversationRecord').click();
  const start=v.messages.at(-1); v.receive({state:'recording',source:'it',requestId:start.id});
  v.window.FaceConversation.speech.refresh(); const status=v.messages.at(-1);
  assert.equal(status.op,'status'); assert.equal(status.id,start.id+1);
  v.receive({state:'recording',source:'it',requestId:start.id});
  assert.equal(v.$('conversationStop').disabled,false);
  v.$('conversationStop').click(); const stop=v.messages.at(-1);
  assert.equal(stop.op,'stop'); assert.equal(stop.id,status.id+1);
  v.receive({state:'result',source:'it',text:'È qui',translation:'It is here',requestId:stop.id,canReplay:true});
  assert.equal(v.$('conversationReply').textContent,'It is here');
});

test('mounted lifecycle invalidates reports until the resume status fence is acknowledged', () => {
  const v=ui(true), service=v.window.FaceConversation.speech; v.receive();
  const before=v.messages.at(-1).id;
  v.document.hidden=true; v.document.handlers.visibilitychange();
  assert.equal(service.getSnapshot().freshness,'stale');
  v.receive({ack:before}); assert.equal(service.getSnapshot().freshness,'stale');
  v.document.hidden=false; v.document.handlers.visibilitychange();
  assert.equal(v.messages.at(-1).op,'status');
  v.receive({ack:before}); assert.equal(service.getSnapshot().freshness,'stale');
  v.setEpoch(2); v.receive(); assert.equal(service.getSnapshot().freshness,'current');
  const current=service.getSnapshot();
  v.receive({seq:1}); assert.equal(service.getSnapshot(),current);
  v.window.handlers.pagehide(); assert.equal(service.getSnapshot().freshness,'stale');
  v.window.handlers.pageshow(); assert.equal(v.messages.at(-1).op,'status');
});

test('opening readiness setup is explicit and never starts downloads or recording', () => {
  const v=ui(true); v.receive();
  assert.equal(v.window.FaceConversation.speech.openSetup(),true);
  assert.equal(v.$('conversationDialog').open,true);
  assert.ok(v.messages.every(m=>['status','clear'].includes(m.op)));
});

for (const [label,data] of [
  ['malformed JSON','{bad'],
  ['unsupported wire version',{v:2,event:'state',state:'ready'}],
  ['unknown native state',{v:1,event:'state',state:'unexpected'}]
]) test('mounted readiness rejects '+label+' and recovers on a valid newer frame',()=>{
  const v=ui(true), service=v.window.FaceConversation.speech; v.receive();
  assert.equal(service.getSnapshot().voices.it,'reported-ready');
  const count=v.messages.length;
  v.window.OfflineConversation.onmessage({data});
  assert.equal(service.getSnapshot().freshness,'unknown');
  assert.equal(service.getSnapshot().voices.it,'unknown');
  assert.equal(v.messages.length,count);
  v.receive(); assert.equal(service.getSnapshot().voices.it,'reported-ready');
});

test('Italian typing button enabled when published and disabled while busy', () => {
  const v = ui();
  v.receive();
  v.window.FaceConversation.open({ en: 'Hello', it: 'Ciao' });
  assert.equal(v.$('conversationTypeItalian').disabled, true);
  v.$('conversationShow').click();
  assert.equal(v.$('conversationTypeItalian').disabled, false);
  v.$('conversationRecord').click();
  assert.equal(v.$('conversationTypeItalian').disabled, true);
});

test('Italian message shown on user side as well when published', () => {
  const v = ui();
  v.receive();
  v.window.FaceConversation.open({ en: 'Where is the station?', it: 'Dov’è la stazione?' });
  v.$('conversationShow').click();
  assert.equal(v.$('conversationItalian').textContent, 'Dov’è la stazione?');
  assert.equal(v.$('conversationItalianUser').textContent, 'Dov’è la stazione?');
  assert.equal(v.$('conversationItalianUserCard').hidden, false);
});
