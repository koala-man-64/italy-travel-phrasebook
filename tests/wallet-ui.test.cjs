'use strict';
const test=require('node:test'),assert=require('node:assert/strict');
const {mount}=require('../wallet-ui.js');
const {document,nodes,flush}=require('./fixtures/3b/dom.cjs');
function fixture(){
  const doc=document(),root=doc.createElement('div'),calls=[];
  let listener,view={state:'READY',version:{userRevision:'1',walletGenerationId:'gen_'+'a'.repeat(32),walletRevision:'1',epoch:'1'},documents:[{documentId:'doc_'+'b'.repeat(32),displayName:'Ticket',mediaType:'application/pdf',byteLength:100}],attachments:[]};
  const wallet={subscribe(fn){listener=fn;fn(view);return()=>{};},getSnapshot:async()=>view};
  for(const op of ['deleteDocument','confirmArchive','cancelArchive','previewArchive','exportArchive'])wallet[op]=async(...args)=>{calls.push({op,args});return{status:op==='cancelArchive'?'CANCELLED':op==='previewArchive'?'OK':'SAVED',value:{token:'preview_'+'c'.repeat(32),documents:1}};};
  const dispose=mount(root,{wallet,reload:()=>calls.push({op:'reload'})});
  return{root,wallet,calls,dispose,emit(patch){view={...view,...patch};listener(view);},button(text){return nodes(root).find(n=>n.tagName==='button'&&n.textContent===text);}};
}
test('deletion requires explicit confirmation and stale confirmation cannot execute',async()=>{
 const f=fixture();await flush();f.button('Delete Ticket').click();assert.equal(f.calls.length,0);
 f.emit({version:{userRevision:'2',walletGenerationId:'gen_'+'a'.repeat(32),walletRevision:'2',epoch:'2'}});
 assert.equal(f.button('Delete document').disabled,true);f.button('Delete document').click();assert.equal(f.calls.length,0);f.dispose();
});
test('archive preview discloses replacement and confirmation carries exact token',async()=>{
 const f=fixture();await flush();f.button('Choose backup to restore').click();await flush();
 assert.match(f.root.textContent,/unencrypted/);assert.match(f.root.textContent,/Replace all personal data/);
 assert.deepEqual(f.calls.map(c=>c.op),['previewArchive']);f.button('Replace all personal data').click();await flush();
 assert.equal(f.calls[1].op,'confirmArchive');assert.equal(f.calls[1].args[1],'preview_'+'c'.repeat(32));f.dispose();
});
test('changed archive preview stays cancellable but cannot replace successor data',async()=>{
 const f=fixture();await flush();f.button('Choose backup to restore').click();await flush();
 f.emit({version:{userRevision:'2',walletGenerationId:null,walletRevision:null,epoch:'3'}});
 assert.equal(f.button('Replace all personal data').disabled,true);f.button('Cancel').click();await flush();
 assert.equal(f.calls.at(-1).op,'cancelArchive');assert.equal(f.calls.at(-1).args[0].userRevision,'2');f.dispose();
});
test('recovery and unsupported states hide document actions',async()=>{
 const f=fixture();await flush();f.emit({state:'RECOVERY_REQUIRED'});assert.equal(f.button('Import document'),undefined);f.button('Reload to recover').click();assert.equal(f.calls[0].op,'reload');
 f.emit({state:'UNSUPPORTED'});assert.match(f.root.textContent,/Android 11/);assert.equal(f.button('Choose backup to restore'),undefined);f.dispose();
});
