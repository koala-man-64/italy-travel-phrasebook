(function(root,factory){'use strict';const api=factory();if(typeof module==='object'&&module.exports)module.exports=api;else root.ItalyWalletUI=api;})(typeof globalThis!=='undefined'?globalThis:this,function(){
  'use strict';
  const same=(a,b)=>a&&b&&['userRevision','walletGenerationId','walletRevision','epoch'].every(k=>a[k]===b[k]);
  function mount(element,{wallet,reload,relationLabel=()=> 'Linked to an unavailable itinerary item'}) {
    const doc=element.ownerDocument,section=doc.createElement('section');section.className='italy-attachments';section.setAttribute('aria-label','Travel documents and backup');element.append(section);
    let view=null,busy=false,disposed=false,pending=null,message='';
    const node=(tag,text)=>{const n=doc.createElement(tag);if(text!==undefined)n.textContent=text;return n;};
    function button(text,action,disabled=false) { const b=node('button',text);b.type='button';b.disabled=disabled;b.addEventListener('click',()=>{if(!b.disabled&&!disposed)void action();});return b; }
    async function run(action,after) {
      if(busy||disposed)return;busy=true;render();
      try {const result=await action();if(disposed)return;
        message=({SAVED:'Saved on this device.',OPENED:'Viewer launch accepted.',CANCELLED:'Cancelled.',OK:'Completed.',FAILED:'Could not complete this action. Check your documents and try again.',UNCERTAIN:'The result is uncertain. Reload to recover before continuing.'})[result.status]||'Could not confirm this action.';
        if(after)await after(result);
      }catch(_){message='Could not confirm this action. Reload to recover before continuing.';}
      finally{busy=false;if(!disposed)render();}
    }
    function render() {
      if(disposed)return;section.replaceChildren(node('h3','Travel documents and backup'));
      const status=node('p',message);status.setAttribute('role','status');section.append(status);
      if(!view||view.state==='LOADING'){section.append(node('p','Checking documents…'));return;}
      if(view.state==='UNSUPPORTED'){section.append(node('p','Document and full-backup tools require the Android app on Android 11 or newer.'));return;}
      if(view.state!=='READY'){section.append(node('p','Documents are unavailable until recovery finishes.'),button('Reload to recover',reload));return;}
      const version=view.version;
      section.append(node('p','Private copies stay on this device. Import PDF, PNG or JPEG files up to 20 MB each.'));
      section.append(button('Import document',()=>run(()=>wallet.importDocument(version)),busy||!!pending));
      for(const d of view.documents) {
        const row=node('div');row.className='attachment-panel';row.append(node('h4',d.displayName),node('p',d.mediaType+' · '+Math.ceil(d.byteLength/1024)+' KB'));
        row.append(button('Open '+d.displayName,()=>run(()=>wallet.openDocument(version,d.documentId)),busy||!!pending),
          button('Delete '+d.displayName,()=>{pending={type:'delete',document:d,version};render();},busy||!!pending));
        for(const relation of view.attachments.filter(r=>r.documentId===d.documentId))row.append(node('p',relationLabel(relation)),
          button('Unlink from this itinerary item',()=>{pending={type:'unlink',relation,version};render();},busy||!!pending));
        section.append(row);
      }
      if(!view.documents.length)section.append(node('p','No documents imported yet.'));
      section.append(node('h4','Full backup'),node('p','This unencrypted archive includes Saved phrases, learning history, preferences and documents. Keep it private. Restoring replaces all current personal data and documents.'));
      section.append(button('Export full backup',()=>run(()=>wallet.exportArchive(version),r=>{if(r.status==='OK')message='Backup written to the selected location.';}),busy||!!pending),
        button('Choose backup to restore',()=>run(()=>wallet.previewArchive(version),async r=>{
          if(r.status==='OK'&&r.value&&typeof r.value.token==='string'&&/^preview_[0-9a-f]{32}$/.test(r.value.token)&&Number.isSafeInteger(r.value.documents)&&r.value.documents>=0&&r.value.documents<=50){
            const current=await wallet.getSnapshot();pending={type:'archive',token:r.value.token,documents:r.value.documents,version:current.version};
          }
        }),busy||!!pending));
      if(pending) {
        const p=pending,panel=node('div');panel.className='attachment-panel';
        panel.append(node('p',p.type==='archive'?'Replace all personal data and documents with this backup ('+p.documents+' documents)?':p.type==='delete'?'Delete this document and all its itinerary links?':'Remove this link? The document will stay in your wallet.'));
        if(!same(p.version,version))panel.append(node('p','Your data changed. Cancel and review it before continuing.'));
        panel.append(button('Cancel',()=>p.type==='archive'?run(()=>wallet.cancelArchive(view.version,p.token),r=>{if(r.status==='CANCELLED')pending=null;}):(pending=null,render()),busy),
          button(p.type==='archive'?'Replace all personal data':p.type==='delete'?'Delete document':'Remove link',()=>run(()=>p.type==='archive'?wallet.confirmArchive(p.version,p.token):p.type==='delete'?wallet.deleteDocument(p.version,p.document.documentId):wallet.unlink(p.version,p.relation),r=>{if(r.status==='SAVED')pending=null;}),busy||!same(p.version,version)));
        section.append(panel);
      }
    }
    const unsubscribe=wallet.subscribe(next=>{if(!disposed){view=next;render();}});
    void wallet.getSnapshot().then(next=>{if(!disposed&&(!view||BigInt(next.version.epoch)>=BigInt(view.version.epoch))){view=next;render();}});
    return()=>{disposed=true;unsubscribe();section.remove();};
  }
  return Object.freeze({mount});
});
