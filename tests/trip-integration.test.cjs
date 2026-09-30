const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const {prepareContent,nextMidnight,createClock,mountTransport}=require('../trip.js');
const {romeDate}=require('../today.js');
const read=name=>JSON.parse(fs.readFileSync(require('node:path').join(__dirname,'..',name),'utf8'));
test('bundle adapter freezes exact matching content and rejects mixed generations or broken references',()=>{
  const trip=read('trip-content.json'), anchors=read('trip-content-anchors.json');
  const content=prepareContent(trip,anchors);
  assert.ok(Object.isFrozen(content.trip.days[0]));
  const mismatched=structuredClone(anchors); mismatched.contentVersion='older';
  assert.throws(()=>prepareContent(trip,mismatched),/version mismatch/);
  const missing=structuredClone(trip); missing.events.shift();
  assert.throws(()=>prepareContent(missing,anchors),/event reference/);
  const duplicate=structuredClone(trip); duplicate.days.push(duplicate.days[0]);
  assert.throws(()=>prepareContent(duplicate,anchors),/Duplicate/);
});
for(const [name,start,end,hours] of [
  ['spring transition','2026-03-28T23:00:00Z','2026-03-29T22:00:00Z',23],
  ['autumn transition','2026-10-24T22:00:00Z','2026-10-25T23:00:00Z',25],
  ['ordinary trip day','2026-09-29T22:00:00Z','2026-09-30T22:00:00Z',24]
]) test('Rome midnight handles '+name,()=>{
  const next=nextMidnight(start,romeDate);
  assert.equal(new Date(next).toISOString(),new Date(end).toISOString());
  assert.equal((next-Date.parse(start))/3600000,hours);
});
test('clock detects midnight, resume and manual clock changes and removes all listeners on disposal',()=>{
  let instant=new Date('2026-09-29T21:59:59Z'), timerId=0;
  const timers=new Map();
  const target=()=>({handlers:new Map(),addEventListener(k,f){this.handlers.set(k,f);},removeEventListener(k){this.handlers.delete(k);}});
  const document=Object.assign(target(),{hidden:false}), window=Object.assign(target(),{
    setTimeout(f,ms){timers.set(++timerId,{f,ms});return timerId;},clearTimeout(id){timers.delete(id);}
  });
  const clock=createClock(window,document,romeDate,()=>instant); let notifications=0;
  const unsubscribe=clock.subscribe(()=>notifications++);
  assert.equal([...timers.values()][0].ms,1000);
  instant=new Date('2026-09-29T22:00:00Z'); [...timers.values()][0].f();
  assert.equal(notifications,1); assert.equal(timers.size,1);
  document.hidden=true; document.handlers.get('visibilitychange')(); assert.equal(timers.size,0);
  instant=new Date('2026-10-02T08:00:00Z'); document.hidden=false; document.handlers.get('visibilitychange')();
  assert.equal(notifications,2); assert.equal(timers.size,1);
  instant=new Date('2026-09-28T08:00:00Z'); [...timers.values()][0].f(); assert.equal(notifications,3);
  unsubscribe(); window.handlers.get('focus')(); assert.equal(notifications,3);
  clock.dispose(); assert.equal(timers.size,0); assert.equal(window.handlers.size,0); assert.equal(document.handlers.size,0);
});

test('transport withholds an entire pair if its reply is unreviewed and reports the visible count',()=>{
  const trip=read('trip-content.json'), anchors=read('trip-content-anchors.json');
  const hiddenReply=anchors.transportPack.prompts[0].exampleReplyIds[0];
  trip.phrases.find(phrase=>phrase.id===hiddenReply).review='draft';
  const document={createElement(tag){return {tag,ownerDocument:document,children:[],attrs:{},textContent:'',
    append(...children){this.children.push(...children);},setAttribute(k,v){this.attrs[k]=v;},addEventListener(){},remove(){}};}};
  const element=document.createElement('div'); element.hidden=true;
  const dispose=mountTransport(element,{trip,anchors},{syncSlow(){}});
  const descendants=node=>[node,...node.children.flatMap(descendants)];
  const nodes=descendants(element);
  assert.equal(nodes.filter(node=>node.tag==='article').length,5);
  assert.equal(nodes.find(node=>node.className==='transport-toggle').textContent,'Getting around · 5 phrases');
  assert.ok(!nodes.some(node=>node.textContent===trip.phrases.find(p=>p.id===hiddenReply).snapshot.it));
  assert.equal(element.hidden,false); dispose(); assert.equal(element.hidden,true);
});
