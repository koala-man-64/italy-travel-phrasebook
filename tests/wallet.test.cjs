const { test } = require('node:test');
const assert = require('node:assert/strict');
const { create } = require('../wallet.js');
const doc = { documentId:'doc_'+'a'.repeat(32),displayName:'Ticket',mediaType:'application/pdf',byteLength:42 };
const state = () => ({ state:'READY',writable:true,snapshot:{revision:'1',wallet:{generationId:'gen_'+'b'.repeat(32),revision:'1'},attachments:[]} });
function fixture() {
  let current=state(), listener, idle; const calls=[];
  const store={ subscribe(fn){listener=fn;fn(current);return()=>{listener=null;};},getSnapshot:async()=>current };
  const host={ subscribeIdle(fn){idle=fn;return()=>{};},async wallet(op,expected,args){calls.push({op,expected,args});return {status:'OK',value:{...expected,documents:[doc]}};} };
  const wallet=create({store,host});
  return {wallet,host,calls,emit(value){current=value;listener(value);},idle:()=>idle()};
}
test('metadata is projected only from matching native and writable web revisions',async()=>{
  const f=fixture(),v=await f.wallet.getSnapshot();assert.equal(v.state,'READY');assert.deepEqual(v.documents,[doc]);assert.ok(Object.isFrozen(v.documents[0]));
  f.emit({state:'RECOVERING',writable:false,snapshot:null});const hidden=await f.wallet.getSnapshot();assert.equal(hidden.state,'LOADING');assert.equal(hidden.documents.length,0);f.wallet.dispose();
});
test('mismatched or malformed native metadata gates document operations',async()=>{
  const f=fixture();f.host.wallet=async()=>({status:'OK',value:{userRevision:'2',walletGenerationId:null,walletRevision:null,documents:[]}});
  const v=await f.wallet.getSnapshot();assert.equal(v.state,'RECOVERY_REQUIRED');assert.equal((await f.wallet.openDocument(v.version,doc.documentId)).status,'FAILED');f.wallet.dispose();
});
test('stale click version cannot open or mutate a successor view',async()=>{
  const f=fixture(),old=await f.wallet.getSnapshot();f.emit(state());await f.wallet.getSnapshot();const before=f.calls.length;
  assert.equal((await f.wallet.unlink(old.version,{tripId:'trip_a',eventId:'event_a',documentId:doc.documentId})).code,'REVISION_CONFLICT');assert.equal(f.calls.length,before);f.wallet.dispose();
});
test('metadata BUSY during startup retries only after native idle signal',async()=>{
  const f=fixture(),original=f.host.wallet;f.host.wallet=async()=>({status:'FAILED',code:'BUSY'});
  assert.equal((await f.wallet.getSnapshot()).state,'LOADING');f.host.wallet=original;f.idle();assert.equal((await f.wallet.getSnapshot()).state,'READY');f.wallet.dispose();
});
test('native ready arriving during pending BUSY refresh is not lost',async()=>{
  const f=fixture(),original=f.host.wallet;let release,lastState;f.wallet.subscribe(value=>{lastState=value;});
  f.host.wallet=()=>new Promise(resolve=>{release=resolve;});
  const first=f.wallet.getSnapshot();await Promise.resolve();
  f.host.wallet=original;f.idle();release({status:'FAILED',code:'BUSY'});await first;
  await new Promise(resolve=>setImmediate(resolve));
  assert.equal(lastState.state,'READY');f.wallet.dispose();
});
test('late metadata cannot republish private state after disposal',async()=>{
  const f=fixture();let release;f.host.wallet=()=>new Promise(r=>release=r);const read=f.wallet.getSnapshot();await Promise.resolve();f.wallet.dispose();release({status:'OK',value:{userRevision:'1',walletGenerationId:'gen_'+'b'.repeat(32),walletRevision:'1',documents:[doc]}});
  assert.notEqual((await read).state,'READY');
});
test('historical relation to missing native document gates projection',async()=>{
  const f=fixture(),s=state();await f.wallet.getSnapshot();s.snapshot.attachments=[{tripId:'trip_old',eventId:'event_old',documentId:'doc_'+'c'.repeat(32)}];f.emit(s);
  assert.equal((await f.wallet.getSnapshot()).state,'RECOVERY_REQUIRED');f.wallet.dispose();
});
