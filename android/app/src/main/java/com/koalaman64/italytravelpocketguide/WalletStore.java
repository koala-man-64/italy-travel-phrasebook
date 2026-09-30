package com.koalaman64.italytravelpocketguide;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import static com.koalaman64.italytravelpocketguide.WalletTypes.*;

/** Serialized sole native writer. Inject real decoder and trusted web readback adapter. */
public final class WalletStore implements WalletNativePort {
    public interface Decoder { CompletionStage<Result<Decoded>> validate(Path nativeOwnedRegularFile,long remainingMs); }
    public interface WebEvidence {
        CompletionStage<Boolean> verifyReceipt(Context context,Receipt receipt,String exactEscrow);
        CompletionStage<Boolean> verifyPair(Context context,Pair pair);
        /** Validate retained complete web image, not necessarily its current active pointer. */
        default CompletionStage<Boolean> verifyRecoveryPair(Context context,Pair pair){return CompletableFuture.completedFuture(false);}
    }
    public static final class Decoded {
        public final String kind; public final List<PageSize> pages; public final int orientation;
        public Decoded(String kind,List<PageSize> pages,int orientation){this.kind=kind;this.pages=WalletTypes.copy(pages,PAGES);this.orientation=orientation;}
    }
    private interface Work<T>{T run()throws Exception;}
    private final Object owner=new Object();
    private final WalletDisk disk; private final Executor serial,io; private final Decoder decoder; private final WebEvidence web;
    private final LongSupplier clock;
    private volatile NativeFence fence; private TxnPlan plan; private Journal journal; private Checkpoint checkpoint;
    private Context currentContext;
    private RecoveryDecision decision; private Identity active; private Receipt staged;
    private boolean ready,uncertain; private long reserved;
    private String lastNativeRevision;
    private final Map<String,Sink> sinks=new HashMap<>();
    private final Map<String,ValidatedDocument> validated=new HashMap<>();
    private final Map<String,LeaseState> leases=new HashMap<>();
    private final Map<String,java.util.concurrent.atomic.AtomicInteger> openFiles=new HashMap<>();
    private final Map<Identity,Map<String,Stored>> verifiedManifests=new LinkedHashMap<Identity,Map<String,Stored>>(4,.75f,true){@Override protected boolean removeEldestEntry(Map.Entry<Identity,Map<String,Stored>> entry){return size()>4;}};
    public WalletStore(WalletDisk disk,Executor serial,Executor boundedIo,Decoder decoder,WebEvidence web,LongSupplier clock){
        this.disk=Objects.requireNonNull(disk);this.serial=Objects.requireNonNull(serial);io=Objects.requireNonNull(boundedIo);this.decoder=Objects.requireNonNull(decoder);this.web=Objects.requireNonNull(web);this.clock=Objects.requireNonNull(clock);
    }
    private <T> CompletionStage<Result<T>> run(Work<T> work){
        CompletableFuture<Result<T>> future=new CompletableFuture<>();
        serial.execute(()->{try{future.complete(Result.ok(work.run()));}
            catch(WalletFailure e){future.complete(e.code==Code.RECOVERY_REQUIRED?Result.uncertain(journal==null?null:journal.transactionId):Result.failed(e.code));}
            catch(Exception e){uncertain=true;ready=false;future.complete(Result.uncertain(journal==null?null:journal.transactionId));}});
        return future;
    }
    private CompletionStage<Boolean> evidence(java.util.function.Supplier<CompletionStage<Boolean>> call){try{return call.get().handle((verified,error)->error==null&&Boolean.TRUE.equals(verified));}catch(Exception e){return CompletableFuture.completedFuture(false);}}
    private void require(boolean ok,Code code)throws WalletFailure{if(!ok)throw new WalletFailure(code);}
    private void check(NativeFence f)throws WalletFailure{require(f!=null&&f==fence&&f.owner==owner,Code.STALE_SESSION);}
    private boolean sameDocument(Context a,Context b){return a!=null&&b!=null&&a.sessionId.equals(b.sessionId)&&a.documentId.equals(b.documentId)&&a.epoch.equals(b.epoch);}
    private void transaction(NativeFence f,String tx)throws WalletFailure{check(f);require(journal!=null&&journal.transactionId.equals(tx),Code.TRANSACTION_CONFLICT);}
    private String next(String revision)throws WalletFailure{long v=Long.parseLong(revision);require(v<Long.MAX_VALUE,Code.REVISION_LIMIT);return Long.toString(v+1);}
    private String random(String prefix){return prefix+"_"+UUID.randomUUID().toString().replace("-", "");}
    private String sequence()throws WalletFailure{return next(checkpoint==null?"0":checkpoint.sequence);}
    private String checkpointSequence()throws WalletFailure{return next(Long.toString(Math.max(Math.max(checkpoint==null?0:Long.parseLong(checkpoint.sequence),journal==null?0:Long.parseLong(journal.sequence)),decision==null?0:Long.parseLong(decision.sequence))));}
    private void write(String name,Object body,int cap)throws Exception{disk.write(name,WalletCodec.encode(body,cap),cap,false);}
    private boolean same(Object a,Object b)throws Exception{return Arrays.equals(WalletCodec.encode(a,JOURNAL_BYTES),WalletCodec.encode(b,JOURNAL_BYTES));}
    @Override public CompletionStage<Result<NativeFence>> acquireFence(Context context,Pair expected){return run(()->{
        require(fence==null&&ready&&!uncertain,Code.BUSY);require(checkpoint!=null&&checkpoint.pair.equals(expected),Code.REVISION_CONFLICT);
        require(sameDocument(context,currentContext),Code.STALE_SESSION);
        require(leases.values().stream().noneMatch(l->l.lease.purpose==Purpose.EXPORT),Code.BUSY);
        fence=new NativeFence(owner,context,expected,false);ready=false;return fence;
    });}
    @Override public CompletionStage<Result<NativeFence>> acquireRecoveryFence(Context context){return run(()->{
        require(fence==null,Code.BUSY);require(currentContext==null||sameDocument(context,currentContext),Code.STALE_SESSION);fence=new NativeFence(owner,context,null,true);ready=false;return fence;
    });}
    @Override public CompletionStage<Result<Void>> invalidateContext(Context context){return run(()->{
        if(fence!=null){require(sameDocument(fence.context,context),Code.STALE_SESSION);fence=null;}
        else require(currentContext==null||sameDocument(currentContext,context),Code.STALE_SESSION);
        currentContext=null;ready=false;plan=null;uncertain=true;for(Sink sink:sinks.values())sink.cancel();return null;
    });}
    @Override public CompletionStage<Result<NativeRecoveryImage>> readRecovery(NativeFence f){return run(()->{
        try {
        check(f);require(f.recovery,Code.INVALID_REQUEST);
        verifiedManifests.clear();
        // Parse all persisted authorities. Any malformed data remains untouched and gated.
        journal=disk.exists("journal.json")?WalletCodec.journal(disk.read("journal.json",JOURNAL_BYTES)):null;
        checkpoint=disk.exists("checkpoint.json")?WalletCodec.checkpoint(disk.read("checkpoint.json",JOURNAL_BYTES)):null;
        decision=disk.exists("decision.json")?WalletCodec.decision(disk.read("decision.json",JOURNAL_BYTES)):null;
        lastNativeRevision=null;if(disk.exists("counter.json")){Map<String,Object> counter=WalletCodec.parse(disk.read("counter.json",128),128);WalletCodec.keys(counter,"revision");lastNativeRevision=counter.get("revision")==null?null:WalletTypes.revision(WalletCodec.text(counter.get("revision")));}
        active=null;if(disk.exists("active.json")){Map<String,Object> pointer=WalletCodec.parse(disk.read("active.json",2048),2048);WalletCodec.keys(pointer,"identity");active=WalletCodec.identity(pointer.get("identity"));}
        uncertain=false;plan=null;staged=null;
        List<Identity> ids=new ArrayList<>();
        for(Identity i:Arrays.asList(active,journal==null?null:journal.nativeChange.prior,journal==null?null:journal.nativeChange.candidate)){
            if(i!=null&&!ids.contains(i)){try{manifest(i);ids.add(i);}catch(Exception bad){uncertain=true;}}
        }
        List<EscrowReceipt> escrows=new ArrayList<>();
        if(journal!=null&&journal.webEscrowHash!=null){byte[] raw=disk.read("escrow_"+journal.transactionId,ESCROW_BYTES);require(WalletDisk.sha(raw).equals(journal.webEscrowHash),Code.RECOVERY_REQUIRED);escrows.add(new EscrowReceipt(journal.transactionId,raw.length,journal.webEscrowHash));}
        if(journal!=null&&checkpoint!=null&&Long.parseLong(checkpoint.sequence)>Long.parseLong(journal.sequence)&&!Objects.equals(checkpoint.transactionId,journal.transactionId))uncertain=true;
        return new NativeRecoveryImage(journal,checkpoint,decision,active,ids,escrows,uncertain);
        } catch(Exception invalidAuthority) {throw new IOException("RECOVERY_READ_FAILED",invalidAuthority);}
    });}
    @Override public CompletionStage<Result<TxnPlan>> newTransaction(NativeFence f,Operation op){return run(()->{
        check(f);require(!uncertain&&plan==null,Code.BUSY);require(checkpoint!=null,Code.RECOVERY_REQUIRED);
        require(Long.parseLong(checkpoint.sequence)<=Long.MAX_VALUE-3,Code.REVISION_LIMIT);
        // A completed journal is retained until this later transaction; never override unresolved work.
        require(journal==null||(checkpoint.transactionId!=null&&checkpoint.transactionId.equals(journal.transactionId)),Code.RECOVERY_REQUIRED);
        require(leases.values().stream().noneMatch(l->l.lease.purpose==Purpose.VIEW),Code.BUSY);
        require(sinks.values().stream().noneMatch(s->s.doc==null&&!s.closeVerified),Code.BUSY);
        boolean change=op!=Operation.WEB_MUTATION;String gen=change?random("gen"):null;String previousRevision=active==null?lastNativeRevision:active.revision;String rev=change?(previousRevision==null?"0":next(previousRevision)):null;
        plan=new TxnPlan(random("txn"),gen,rev,op);staged=null;validated.clear();sinks.entrySet().removeIf(e->e.getValue().doc!=null&&e.getValue().closeVerified);return plan;
    });}
    private NativeSnapshot snapshot(Identity identity)throws Exception{List<Document> documents=new ArrayList<>();for(Stored s:manifest(identity).values())documents.add(s.doc);return new NativeSnapshot(identity,documents);}
    @Override public CompletionStage<Result<NativeSnapshot>> readSnapshot(NativeFence f,Identity expected){return run(()->{check(f);require(Objects.equals(expected,active),Code.REVISION_CONFLICT);return snapshot(expected);});}
    @Override public CompletionStage<Result<NativeSnapshot>> getSnapshot(Context context){return run(()->{require(ready&&fence==null&&!uncertain,Code.RECOVERY_REQUIRED);require(sameDocument(context,currentContext),Code.STALE_SESSION);return snapshot(active);});}
    @Override public CompletionStage<Result<Journal>> beginJournal(NativeFence f,String tx,Operation op,Pair prior,Pair planned){return run(()->{
        check(f);require(plan!=null&&plan.transactionId.equals(tx)&&plan.operation==op,Code.TRANSACTION_CONFLICT);
        require(checkpoint.pair.equals(prior)&&Objects.equals(active,prior.nativeIdentity),Code.REVISION_CONFLICT);
        Journal j=new Journal(tx,op,Phase.BEGIN,sequence(),new Change(prior.web,planned==null?null:planned.web),new Change(prior.nativeIdentity,planned==null?null:planned.nativeIdentity),null,null);
        disk.write("counter-prior_"+tx,WalletCodec.encode(WalletCodec.obj("revision",lastNativeRevision),128),128,true);
        byte[] previous=disk.exists("previous.json")?disk.read("previous.json",2048):WalletCodec.encode(WalletCodec.obj("identity",null),2048);
        disk.write("previous-prior_"+tx,previous,2048,true);
        write("journal.json",WalletCodec.journal(j),JOURNAL_BYTES);journal=j;decision=null;return j;
    });}
    private synchronized void reserve(long bytes)throws Exception{disk.reserve(Math.addExact(reserved,bytes));reserved+=bytes;}
    private final class Sink implements BoundedSpoolSink,BoundedDocumentSink {
        final String name,handleId,doc,tx; final NativeFence capability; final long cap; final FileChannel out;
        long count; volatile boolean closed,cancelled,failed,closeVerified; long reservation;
        Sink(NativeFence f,String doc,String tx,long cap,boolean spool)throws Exception{
            this.cap=cap;this.doc=doc;this.tx=tx;capability=f;reserve(cap);reservation=cap;name=random(spool?"spool":"blob");handleId=random("stage");
            out=FileChannel.open(disk.path(name),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
        }
        public String spoolId(){return handleId;}public String stageId(){return handleId;}public synchronized long bytesWritten(){return count;}
        public synchronized void write(byte[] bytes,int offset,int length)throws WalletFailure{
            require(!closed&&!cancelled&&!failed&&fence==capability,Code.INTERRUPTED);
            if(bytes==null||offset<0||length<0||length>CHUNK_BYTES||offset>bytes.length-length){failed=true;throw new WalletFailure(Code.INVALID_REQUEST);}
            if(length>cap-count){failed=true;throw new WalletFailure(Code.BYTE_LIMIT);}
            try{disk.hit(name+":before-write");ByteBuffer b=ByteBuffer.wrap(bytes,offset,length);while(b.hasRemaining())out.write(b);count+=length;disk.hit(name+":after-write");}
            catch(IOException e){failed=true;throw new WalletFailure(Code.IO_FAILURE);}
        }
        public synchronized void cancel(){cancelled=true;try{close();}catch(WalletFailure ignored){/* retained */}}
        public synchronized void close()throws WalletFailure{
            if(closed)return;closed=true;
            try{try{disk.hit(name+":before-sync");out.force(true);disk.hit(name+":after-sync");}finally{out.close();}disk.hit(name+":after-close");disk.syncFile(name);disk.hit(name+":after-dir-sync");closeVerified=true;}catch(IOException e){failed=true;throw new WalletFailure(Code.IO_FAILURE);}
            finally{synchronized(WalletStore.this){reserved-=reservation;reservation=0;}}
        }
    }
    @Override public CompletionStage<Result<BoundedSpoolSink>> createSpool(NativeFence f,long max){return run(()->{
        check(f);require(max>0&&max<=ARCHIVE_BYTES,Code.BYTE_LIMIT);
        require(leases.values().stream().noneMatch(l->l.lease.purpose==Purpose.VIEW),Code.BUSY);
        // A rejected/cancelled pre-BEGIN preview has no newTransaction to retire its spool.
        // Retire only the capability after verified writer closure and all readers closing.
        // Physical bytes remain in disk.used() and remain subject to checkpoint-gated GC.
        sinks.entrySet().removeIf(e->{Sink s=e.getValue();return s.doc==null&&s.closeVerified&&(!openFiles.containsKey(s.name)||openFiles.get(s.name).get()==0);});
        require(sinks.values().stream().noneMatch(s->s.doc==null),Code.BUSY);Sink sink=new Sink(f,null,null,max,true);sinks.put(sink.handleId,sink);return sink;
    });}
    @Override public CompletionStage<Result<ReadOnlySeekableHandle>> sealSpool(NativeFence f,String id){return run(()->{check(f);Sink s=sinks.get(id);require(s!=null&&s.capability==f&&s.doc==null&&s.closed&&!s.failed&&!s.cancelled,Code.INVALID_REQUEST);return new Handle(s.name,s.count,null);});}
    @Override public CompletionStage<Result<BoundedDocumentSink>> createStagedDocument(NativeFence f,String tx,String gen,String doc,long max){return run(()->{
        transaction(f,tx);require(journal.phase==Phase.BEGIN&&plan!=null&&Objects.equals(gen,plan.nativeGenerationId),Code.TRANSACTION_CONFLICT);WalletTypes.id(doc,"doc");
        require(max>0&&max<=DOCUMENT_BYTES,Code.BYTE_LIMIT);require(sinks.values().stream().filter(s->tx.equals(s.tx)).count()<DOCUMENTS,Code.COUNT_LIMIT);
        require(sinks.values().stream().noneMatch(s->tx.equals(s.tx)&&doc.equals(s.doc)),Code.DUPLICATE_ID);
        Sink sink=new Sink(f,doc,tx,max,false);sinks.put(sink.handleId,sink);return sink;
    });}
    @Override public CompletionStage<Result<ValidatedDocument>> sealStagedDocument(NativeFence f,String tx,String doc,long bytes,String sha){
        return run(()->{transaction(f,tx);Sink s=sinks.values().stream().filter(v->tx.equals(v.tx)&&doc.equals(v.doc)).findFirst().orElse(null);
            require(s!=null&&s.closed&&!s.failed&&!s.cancelled&&s.count>0,Code.INVALID_DOCUMENT);require(s.count==bytes,Code.BYTE_LIMIT);require(disk.hashFile(s.name,DOCUMENT_BYTES).equals(sha),Code.HASH_MISMATCH);return s;
        }).thenCompose(r->{if(r.status!=Status.OK)return CompletableFuture.completedFuture(cast(r));Sink s=r.value;
            try{return decoder.validate(disk.path(s.name),VALIDATION_MS).thenCompose(decoded->run(()->{
                transaction(f,tx);require(journal.phase==Phase.BEGIN,Code.TRANSACTION_CONFLICT);require(decoded.status==Status.OK,decoded.code==null?Code.INVALID_DOCUMENT:decoded.code);
                Decoded d=decoded.value;Document descriptor=new Document(doc,d.kind,bytes,sha,"Document",Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString(),d.pages,d.orientation);
                ValidatedDocument v=new ValidatedDocument(s.handleId,descriptor);validated.put(s.handleId,v);return v;
            }));}catch(IOException e){return CompletableFuture.completedFuture(Result.failed(Code.IO_FAILURE));}
        });
    }
    private static <T> Result<T> cast(Result<?> r){return r.status==Status.UNCERTAIN?Result.uncertain(r.transactionId):Result.failed(r.code);}
    @Override public CompletionStage<Result<EscrowReceipt>> writeWebEscrow(NativeFence f,String tx,String raw,String sha){return run(()->{
        transaction(f,tx);require(journal.phase==Phase.BEGIN,Code.TRANSACTION_CONFLICT);byte[] b=WalletCodec.utf8(raw,ESCROW_BYTES);require(WalletDisk.sha(b).equals(sha),Code.HASH_MISMATCH);
        disk.write("escrow_"+tx,b,ESCROW_BYTES,true);return new EscrowReceipt(tx,b.length,sha);
    });}
    @Override public CompletionStage<Result<String>> readWebEscrow(NativeFence f,String tx,String sha){return run(()->{check(f);WalletTypes.id(tx,"txn");byte[] b=disk.read("escrow_"+tx,ESCROW_BYTES);require(WalletDisk.sha(b).equals(sha),Code.HASH_MISMATCH);return WalletCodec.decode(b,ESCROW_BYTES);});}
    private static final class Stored {final Document doc;final String blob;Stored(Document d,String b){doc=d;blob=b;}}
    private Map<String,Stored> manifest(Identity identity)throws Exception{
        if(identity==null)return new LinkedHashMap<>();if(verifiedManifests.containsKey(identity))return new LinkedHashMap<>(verifiedManifests.get(identity));byte[] b=disk.read(identity.generationId+".json",MANIFEST_BYTES);require(WalletDisk.sha(b).equals(identity.sha256),Code.HASH_MISMATCH);
        Map<String,Object> m=WalletCodec.parse(b,MANIFEST_BYTES);WalletCodec.keys(m,"schemaVersion","generationId","revision","documents");
        require(WalletCodec.number(m.get("schemaVersion"))==1&&identity.generationId.equals(m.get("generationId"))&&identity.revision.equals(m.get("revision")),Code.INVALID_DATA);
        require(m.get("documents") instanceof List,Code.INVALID_DATA);List<?> list=(List<?>)m.get("documents");require(list.size()<=DOCUMENTS,Code.COUNT_LIMIT);Map<String,Stored> result=new LinkedHashMap<>();long total=0;
        for(Object entry:list){Map<String,Object> d=WalletCodec.map(entry);WalletCodec.keys(d,"id","kind","bytes","sha256","label","utc","pages","orientation","blob");
            List<PageSize> pages=new ArrayList<>();require(d.get("pages") instanceof List,Code.INVALID_DATA);require(((List<?>)d.get("pages")).size()<=PAGES,Code.PAGE_LIMIT);
            for(Object size:(List<?>)d.get("pages")){Map<String,Object> p=WalletCodec.map(size);WalletCodec.keys(p,"w","h");pages.add(new PageSize(Math.toIntExact(WalletCodec.number(p.get("w"))),Math.toIntExact(WalletCodec.number(p.get("h")))));}
            Document doc=new Document(WalletCodec.text(d.get("id")),WalletCodec.text(d.get("kind")),WalletCodec.number(d.get("bytes")),WalletCodec.text(d.get("sha256")),WalletCodec.text(d.get("label")),WalletCodec.text(d.get("utc")),pages,Math.toIntExact(WalletCodec.number(d.get("orientation"))));
            Instant.parse(doc.importedAtUtc);String blob=WalletCodec.text(d.get("blob"));WalletTypes.id(blob,"blob");require(Files.size(disk.path(blob))==doc.byteLength&&disk.hashFile(blob,DOCUMENT_BYTES).equals(doc.sha256),Code.HASH_MISMATCH);
            require(result.put(doc.id,new Stored(doc,blob))==null,Code.DUPLICATE_ID);total+=doc.byteLength;require(total<=GENERATION_BYTES,Code.BYTE_LIMIT);
        }verifiedManifests.put(identity,new LinkedHashMap<>(result));return result;
    }
    private Receipt seal(List<Stored> docs)throws Exception{
        require(plan!=null&&plan.nativeGenerationId!=null&&docs.size()<=DOCUMENTS,Code.TRANSACTION_CONFLICT);List<Object> list=new ArrayList<>();long total=0;Set<String> ids=new HashSet<>();
        for(Stored s:docs){Document d=s.doc;require(ids.add(d.id),Code.DUPLICATE_ID);total+=d.byteLength;require(total<=GENERATION_BYTES,Code.BYTE_LIMIT);List<Object> pages=new ArrayList<>();for(PageSize p:d.pages)pages.add(WalletCodec.obj("w",p.width,"h",p.height));list.add(WalletCodec.obj("id",d.id,"kind",d.kind,"bytes",d.byteLength,"sha256",d.sha256,"label",d.displayName,"utc",d.importedAtUtc,"pages",pages,"orientation",d.orientation,"blob",s.blob));}
        byte[] bytes=WalletCodec.encode(WalletCodec.obj("schemaVersion",1,"generationId",plan.nativeGenerationId,"revision",plan.nativeRevision,"documents",list),MANIFEST_BYTES);
        disk.write(plan.nativeGenerationId+".json",bytes,MANIFEST_BYTES,true);Identity candidate=new Identity(plan.nativeGenerationId,plan.nativeRevision,WalletDisk.sha(bytes));manifest(candidate);
        staged=new Receipt(plan.transactionId,plan.operation,Store.NATIVE,ReceiptPhase.STAGED,journal.nativeChange.prior,candidate);return staged;
    }
    @Override public CompletionStage<Result<Receipt>> stageArchive(NativeFence f,String tx,ValidatedArchive archive,NativeStagingHandles handles){return run(()->{
        transaction(f,tx);require(journal.phase==Phase.BEGIN&&journal.operation==Operation.RESTORE,Code.TRANSACTION_CONFLICT);
        require(archive.documents.size()==handles.opaqueStageIds.size(),Code.REFERENCE_MISMATCH);Map<String,ValidatedDocument> byId=new HashMap<>();
        if(!archive.walletPresent){require(archive.documents.isEmpty()&&archive.references.isEmpty()&&handles.opaqueStageIds.isEmpty(),Code.REFERENCE_MISMATCH);staged=new Receipt(tx,journal.operation,Store.NATIVE,ReceiptPhase.STAGED,journal.nativeChange.prior,null);return staged;}
        for(String key:handles.opaqueStageIds){ValidatedDocument d=validated.get(key);require(d!=null&&sinks.get(key).tx.equals(tx)&&byId.put(d.document.id,d)==null,Code.INVALID_DOCUMENT);}
        List<Stored> docs=new ArrayList<>();for(Document supplied:archive.documents){ValidatedDocument v=byId.remove(supplied.id);require(v!=null&&v.document.byteLength==supplied.byteLength&&v.document.sha256.equals(supplied.sha256)&&v.document.kind.equals(supplied.kind),Code.HASH_MISMATCH);Instant.parse(supplied.importedAtUtc);Document measured=new Document(supplied.id,v.document.kind,v.document.byteLength,v.document.sha256,supplied.displayName,supplied.importedAtUtc,v.document.pages,v.document.orientation);docs.add(new Stored(measured,sinks.get(v.stageId).name));}
        Set<String> refs=new HashSet<>();for(Ref ref:archive.references){require(docs.stream().anyMatch(d->d.doc.id.equals(ref.documentId)),Code.REFERENCE_MISMATCH);require(refs.add(ref.tripId+"/"+ref.eventId+"/"+ref.documentId),Code.DUPLICATE_ID);}return seal(docs);
    });}
    @Override public CompletionStage<Result<Receipt>> stageDelete(NativeFence f,String tx,Identity expected,String doc){return run(()->{transaction(f,tx);require(journal.phase==Phase.BEGIN&&journal.operation==Operation.DELETE&&Objects.equals(expected,active),Code.REVISION_CONFLICT);Map<String,Stored> docs=manifest(active);require(docs.remove(doc)!=null,Code.NOT_FOUND);return seal(new ArrayList<>(docs.values()));});}
    @Override public CompletionStage<Result<Receipt>> stageUnchanged(NativeFence f,String tx,Identity expected){return run(()->{transaction(f,tx);require(journal.phase==Phase.BEGIN&&journal.operation==Operation.WEB_MUTATION&&Objects.equals(expected,active),Code.REVISION_CONFLICT);manifest(active);staged=new Receipt(tx,Operation.WEB_MUTATION,Store.NATIVE,ReceiptPhase.STAGED,active,active);return staged;});}
    @Override public CompletionStage<Result<Receipt>> readStaged(NativeFence f,String tx){return run(()->{transaction(f,tx);if(staged==null&&journal.phase!=Phase.BEGIN){manifest(journal.nativeChange.candidate);staged=new Receipt(tx,journal.operation,Store.NATIVE,ReceiptPhase.STAGED,journal.nativeChange.prior,journal.nativeChange.candidate);}require(staged!=null,Code.NOT_FOUND);manifest(staged.candidate);return staged;});}
    // SAF is copied off the serialized executor; caller worker remains single and bounded.
    @Override public CompletionStage<Result<Receipt>> stageImport(NativeFence f,String tx,PickerTicket ticket,String displayName){
        return run(()->{transaction(f,tx);require(journal.phase==Phase.BEGIN&&journal.operation==Operation.IMPORT,Code.TRANSACTION_CONFLICT);require(ticket.context()==f.context,Code.STALE_SESSION);WalletTypes.label(displayName);return plan;}).thenCompose(r->{
            if(r.status!=Status.OK)return CompletableFuture.completedFuture(cast(r));String doc=random("doc");
            return createStagedDocument(f,tx,r.value.nativeGenerationId,doc,DOCUMENT_BYTES).thenCompose(sinkResult->{
                if(sinkResult.status!=Status.OK)return CompletableFuture.completedFuture(cast(sinkResult));BoundedDocumentSink sink=sinkResult.value;
                CompletableFuture<Result<String>> copied=new CompletableFuture<>();
                String blob=((Sink)sink).name;
                try{io.execute(()->{try{byte[] buffer=new byte[8192];long start=clock.getAsLong();try(PickerTicket input=ticket;BoundedDocumentSink output=sink){while(true){require(clock.getAsLong()-start<NO_PROGRESS_MS,Code.COPY_TIMEOUT);int n=input.read(buffer,0,buffer.length);if(n<0)break;require(n>0,Code.NO_PROGRESS);output.write(buffer,0,n);}}copied.complete(Result.ok(disk.hashFile(blob,DOCUMENT_BYTES)));}catch(Exception failure){sink.cancel();copied.complete(Result.failed(failure instanceof WalletFailure?((WalletFailure)failure).code:Code.IO_FAILURE));}});}
                catch(java.util.concurrent.RejectedExecutionException rejected){
                    sink.cancel();boolean ticketClosed;try{ticket.close();ticketClosed=true;}catch(Exception closeFailure){ticketClosed=false;}
                    final boolean verified=ticketClosed&&((Sink)sink).closeVerified;
                    // BEGIN remains unresolved; cleanup is not transaction cancellation.
                    return run(()->{if(!verified){uncertain=true;ready=false;throw new WalletFailure(Code.RECOVERY_REQUIRED);}throw new WalletFailure(Code.BUSY);});
                }
                return copied.thenCompose(hashResult->{
                    if(hashResult.status!=Status.OK)return CompletableFuture.completedFuture(cast(hashResult));return sealStagedDocument(f,tx,doc,sink.bytesWritten(),hashResult.value).thenCompose(valid->run(()->{
                        transaction(f,tx);require(valid.status==Status.OK,valid.code==null?Code.INVALID_DOCUMENT:valid.code);Map<String,Stored> docs=manifest(active);Document d=valid.value.document;Document named=new Document(d.id,d.kind,d.byteLength,d.sha256,displayName,d.importedAtUtc,d.pages,d.orientation);docs.put(doc,new Stored(named,sinks.get(valid.value.stageId).name));return seal(new ArrayList<>(docs.values()));
                    }));
                });
            });
        });
    }
    @Override public CompletionStage<Result<Journal>> writeJournal(NativeFence f,Journal expected,Journal next){
        return run(()->{transaction(f,expected.transactionId);boolean replay=same(WalletCodec.journal(journal),WalletCodec.journal(next));require(replay||same(WalletCodec.journal(journal),WalletCodec.journal(expected)),Code.TRANSACTION_CONFLICT);
            require(next.transactionId.equals(journal.transactionId)&&next.operation==journal.operation&&next.sequence.equals(journal.sequence),Code.TRANSACTION_CONFLICT);
            require(replay||next.phase.ordinal()==journal.phase.ordinal()+1,Code.TRANSACTION_CONFLICT);require(staged!=null&&Objects.equals(staged.candidate,next.nativeChange.candidate)&&Objects.equals(staged.prior,next.nativeChange.prior),Code.TRANSACTION_CONFLICT);
            require(next.web.prior.equals(journal.web.prior),Code.REVISION_CONFLICT);byte[] escrow=disk.read("escrow_"+journal.transactionId,ESCROW_BYTES);require(WalletDisk.sha(escrow).equals(next.webEscrowHash),Code.HASH_MISMATCH);
            return WalletCodec.decode(escrow,ESCROW_BYTES);
        }).thenCompose(r->{if(r.status!=Status.OK)return CompletableFuture.completedFuture(cast(r));Receipt receipt=new Receipt(next.transactionId,next.operation,Store.WEB,next.phase==Phase.PREPARED?ReceiptPhase.STAGED:ReceiptPhase.ACTIVATED,next.web.prior,next.web.candidate);
            return evidence(()->web.verifyReceipt(f.context,receipt,r.value)).thenCompose(verified->run(()->{transaction(f,next.transactionId);require(Boolean.TRUE.equals(verified),Code.RECOVERY_REQUIRED);if(same(WalletCodec.journal(journal),WalletCodec.journal(next)))return journal;require(same(WalletCodec.journal(journal),WalletCodec.journal(expected)),Code.TRANSACTION_CONFLICT);if(next.phase==Phase.COMMITTED){require(Objects.equals(active,next.nativeChange.candidate),Code.RECOVERY_REQUIRED);if(active!=null){write("counter.json",WalletCodec.obj("revision",active.revision),128);lastNativeRevision=active.revision;}}write("journal.json",WalletCodec.journal(next),JOURNAL_BYTES);journal=next;return next;}));
        });
    }
    @Override public CompletionStage<Result<Receipt>> activateStaged(NativeFence f,String tx,Journal prepared){return run(()->{transaction(f,tx);
        Journal comparable=journal.phase==Phase.COMMITTED&&prepared.phase==Phase.PREPARED?new Journal(journal.transactionId,journal.operation,Phase.PREPARED,journal.sequence,journal.web,journal.nativeChange,journal.webEscrowHash,journal.planHash):journal;
        require(journal.phase!=Phase.BEGIN&&same(WalletCodec.journal(comparable),WalletCodec.journal(prepared)),Code.TRANSACTION_CONFLICT);manifest(journal.nativeChange.candidate);if(!Objects.equals(journal.nativeChange.prior,journal.nativeChange.candidate))write("previous.json",WalletCodec.obj("identity",WalletCodec.identity(journal.nativeChange.prior)),2048);write("active.json",WalletCodec.obj("identity",WalletCodec.identity(journal.nativeChange.candidate)),2048);active=journal.nativeChange.candidate;staged=new Receipt(tx,journal.operation,Store.NATIVE,ReceiptPhase.STAGED,journal.nativeChange.prior,active);return new Receipt(tx,journal.operation,Store.NATIVE,ReceiptPhase.ACTIVATED,journal.nativeChange.prior,active);});}
    @Override public CompletionStage<Result<RecoveryDecision>> recordRecoveryDecision(NativeFence f,Journal expected,RecoveryDecision next){
        return run(()->{transaction(f,expected.transactionId);require(same(WalletCodec.journal(journal),WalletCodec.journal(expected))&&next.transactionId.equals(journal.transactionId),Code.TRANSACTION_CONFLICT);require(checkpoint==null||Long.parseLong(checkpoint.sequence)<=Long.parseLong(journal.sequence)||Objects.equals(checkpoint.transactionId,journal.transactionId),Code.RECOVERY_REQUIRED);require(next.pair.equals(next.decision==Decision.FORWARD?journal.candidatePair():journal.priorPair()),Code.TRANSACTION_CONFLICT);manifest(next.pair.nativeIdentity);return true;}).thenCompose(preflight->{
            if(preflight.status!=Status.OK)return CompletableFuture.completedFuture(cast(preflight));return evidence(()->web.verifyRecoveryPair(f.context,next.pair)).thenCompose(verified->run(()->{
                transaction(f,next.transactionId);require(Boolean.TRUE.equals(verified),Code.RECOVERY_REQUIRED);require(same(WalletCodec.journal(journal),WalletCodec.journal(expected)),Code.TRANSACTION_CONFLICT);
                if(decision!=null&&decision.transactionId.equals(next.transactionId))require(same(WalletCodec.decision(decision),WalletCodec.decision(next)),Code.TRANSACTION_CONFLICT);
                else require(next.sequence.equals(checkpointSequence()),Code.REVISION_CONFLICT);
                write("decision.json",WalletCodec.decision(next),JOURNAL_BYTES);decision=next;return next;
            }));
        });
    }
    @Override public CompletionStage<Result<Identity>> restorePrevious(NativeFence f,String tx,RecoveryDecision d){return run(()->{transaction(f,tx);require(decision!=null&&decision.decision==Decision.ROLLBACK&&same(WalletCodec.decision(decision),WalletCodec.decision(d)),Code.RECOVERY_REQUIRED);manifest(journal.nativeChange.prior);Map<String,Object> priorCounter=WalletCodec.parse(disk.read("counter-prior_"+tx,128),128);WalletCodec.keys(priorCounter,"revision");String revision=priorCounter.get("revision")==null?null:WalletTypes.revision(WalletCodec.text(priorCounter.get("revision")));write("counter.json",priorCounter,128);lastNativeRevision=revision;byte[] previous=disk.read("previous-prior_"+tx,2048);Map<String,Object> prev=WalletCodec.parse(previous,2048);WalletCodec.keys(prev,"identity");manifest(WalletCodec.identity(prev.get("identity")));disk.write("previous.json",previous,2048,false);write("active.json",WalletCodec.obj("identity",WalletCodec.identity(journal.nativeChange.prior)),2048);active=journal.nativeChange.prior;return active;});}
    @Override public CompletionStage<Result<Receipt>> verifyActive(NativeFence f,Identity expected){return run(()->{check(f);require(Objects.equals(expected,active)&&journal!=null,Code.REVISION_CONFLICT);manifest(active);return new Receipt(journal.transactionId,journal.operation,Store.NATIVE,ReceiptPhase.ACTIVATED,journal.nativeChange.prior,active);});}
    @Override public CompletionStage<Result<Checkpoint>> checkpoint(NativeFence f,Pair pair,String tx){
        return evidence(()->web.verifyPair(f.context,pair)).thenCompose(verified->run(()->{
            check(f);require(Boolean.TRUE.equals(verified)&&Objects.equals(active,pair.nativeIdentity),Code.RECOVERY_REQUIRED);manifest(active);Outcome outcome;
            if(journal==null){require(tx==null&&pair.nativeIdentity==null,Code.RECOVERY_REQUIRED);outcome=Outcome.BASELINE;}
            else{require(Objects.equals(tx,journal.transactionId),Code.TRANSACTION_CONFLICT);if(journal.phase==Phase.COMMITTED&&pair.equals(journal.candidatePair()))outcome=Outcome.COMMITTED;
                else{require(decision!=null&&decision.decision==Decision.ROLLBACK&&pair.equals(decision.pair),Code.RECOVERY_REQUIRED);outcome=Outcome.RECOVERED_OLD;}}
            Checkpoint c=new Checkpoint(checkpointSequence(),pair,tx,outcome);write("checkpoint.json",WalletCodec.checkpoint(c),JOURNAL_BYTES);checkpoint=c;uncertain=false;return c;
        }));
    }
    @Override public CompletionStage<Result<Void>> releaseFence(NativeFence f,Checkpoint c){return run(()->{check(f);require(checkpoint!=null&&same(WalletCodec.checkpoint(c),WalletCodec.checkpoint(checkpoint))&&!uncertain,Code.RECOVERY_REQUIRED);
        require(journal==null||(Objects.equals(c.transactionId,journal.transactionId)&&((journal.phase==Phase.COMMITTED&&c.outcome==Outcome.COMMITTED)||(decision!=null&&decision.decision==Decision.ROLLBACK&&c.outcome==Outcome.RECOVERED_OLD))),Code.RECOVERY_REQUIRED);
        require(sinks.values().stream().noneMatch(s->!s.closeVerified),Code.BUSY);currentContext=f.context;fence=null;plan=null;ready=true;return null;});}
    private static final class LeaseState {final Lease lease;final java.util.concurrent.atomic.AtomicInteger handles=new java.util.concurrent.atomic.AtomicInteger();volatile boolean released;volatile long deadline,lastProgress;LeaseState(Lease l){lease=l;deadline=l.deadlineMs;lastProgress=l.deadlineMs-120000;}}
    private Lease issue(Identity expected,Purpose purpose)throws Exception{manifest(expected);Lease l=new Lease(owner,random("lease"),expected,purpose,clock.getAsLong()+120000);write(l.leaseId+".json",WalletCodec.obj("identity",WalletCodec.identity(expected),"purpose",purpose.name()),2048);leases.put(l.leaseId,new LeaseState(l));return l;}
    @Override public CompletionStage<Result<Lease>> acquireSnapshotLease(NativeFence f,Identity expected){return run(()->{check(f);require(plan==null&&checkpoint!=null&&!uncertain&&Objects.equals(active,expected)&&Objects.equals(checkpoint.pair.nativeIdentity,expected)&&(journal==null||Objects.equals(checkpoint.transactionId,journal.transactionId))&&leases.values().stream().noneMatch(l->l.lease.purpose==Purpose.EXPORT),Code.BUSY);return issue(expected,Purpose.EXPORT);});}
    CompletionStage<Result<Path>> rendererInput(Lease l,String doc){return run(()->{LeaseState state=leases.get(l.leaseId);require(state!=null&&state.lease==l&&l.owner==owner&&l.purpose==Purpose.VIEW&&!state.released&&clock.getAsLong()<state.deadline,Code.LEASE_EXPIRED);Stored s=manifest(l.identity).get(doc);require(s!=null,Code.NOT_FOUND);require(disk.hashFile(s.blob,DOCUMENT_BYTES).equals(s.doc.sha256),Code.HASH_MISMATCH);return disk.path(s.blob);});}
    @Override public CompletionStage<Result<Lease>> acquireViewerLease(Context c,Identity expected,String doc){return run(()->{require(ready&&!uncertain&&fence==null&&Objects.equals(active,expected)&&leases.isEmpty(),Code.BUSY);require(sameDocument(c,currentContext),Code.STALE_SESSION);require(manifest(expected).containsKey(doc),Code.NOT_FOUND);return issue(expected,Purpose.VIEW);});}
    private final class Handle implements ReadOnlyDocumentHandle {
        final FileChannel file;final long length;final LeaseState lease;final java.util.concurrent.atomic.AtomicInteger filePins;boolean closed;
        Handle(String name,long length,LeaseState lease)throws IOException{this.file=FileChannel.open(disk.path(name),StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS);this.length=length;this.lease=lease;filePins=openFiles.computeIfAbsent(name,k->new java.util.concurrent.atomic.AtomicInteger());filePins.incrementAndGet();if(lease!=null)lease.handles.incrementAndGet();}
        public long byteLength(){return length;}
        public synchronized int read(long offset,byte[] bytes,int start,int count)throws WalletFailure{
            require(!closed&&(lease==null||(!lease.released&&clock.getAsLong()<lease.deadline)),Code.LEASE_EXPIRED);require(bytes!=null&&start>=0&&count>=0&&count<=CHUNK_BYTES&&start<=bytes.length-count&&offset>=0&&offset<=length,Code.INVALID_REQUEST);
            if(count>0&&offset==length)return -1;
            try{int n=file.read(ByteBuffer.wrap(bytes,start,(int)Math.min(count,length-offset)),offset);if(n>0&&lease!=null)lease.lastProgress=clock.getAsLong();return n;}catch(IOException e){throw new WalletFailure(Code.IO_FAILURE);}
        }
        public synchronized void close()throws WalletFailure{if(closed)return;try{file.close();closed=true;filePins.decrementAndGet();if(lease!=null)lease.handles.decrementAndGet();}catch(IOException e){throw new WalletFailure(Code.IO_FAILURE);}}
    }
    @Override public CompletionStage<Result<ReadOnlyDocumentHandle>> openSnapshotDocument(Lease l,String doc){return run(()->{LeaseState state=leases.get(l.leaseId);require(l.owner==owner&&state!=null&&state.lease==l&&!state.released&&clock.getAsLong()<state.deadline,Code.LEASE_EXPIRED);Stored s=manifest(l.identity).get(doc);require(s!=null,Code.NOT_FOUND);return new Handle(s.blob,s.doc.byteLength,state);});}
    @Override public CompletionStage<Result<Long>> renewSnapshotLease(Lease l){return run(()->{LeaseState state=leases.get(l.leaseId);long now=clock.getAsLong();require(state!=null&&state.lease==l&&l.owner==owner&&!state.released&&now<state.deadline,Code.LEASE_EXPIRED);long hard=l.purpose==Purpose.EXPORT?l.deadlineMs-120000+ARCHIVE_MS:Long.MAX_VALUE;require(now<hard,Code.LEASE_EXPIRED);if(l.purpose==Purpose.EXPORT)require(fence!=null&&plan==null&&now-state.lastProgress<=NO_PROGRESS_MS,Code.NO_PROGRESS);else require(ready&&!uncertain&&currentContext!=null,Code.LEASE_EXPIRED);state.deadline=Math.min(now+120000,hard);return state.deadline;});}
    @Override public CompletionStage<Result<Void>> releaseSnapshotLease(Lease l){return run(()->{LeaseState state=leases.get(l.leaseId);require(l.owner==owner&&state!=null&&state.lease==l,Code.LEASE_EXPIRED);require(state.handles.get()==0,Code.BUSY);state.released=true;Files.delete(disk.path(l.leaseId+".json"));leases.remove(l.leaseId);return null;});}
    @Override public CompletionStage<Result<CollectionCounts>> collectEligible(NativeFence f,Checkpoint c){return run(()->{
        check(f);require(checkpoint!=null&&same(WalletCodec.checkpoint(c),WalletCodec.checkpoint(checkpoint))&&!uncertain&&leases.isEmpty(),Code.RECOVERY_REQUIRED);
        require(journal!=null&&Long.parseLong(c.sequence)>Long.parseLong(journal.sequence)&&Long.parseLong(c.sequence)-Long.parseLong(journal.sequence)>1,Code.RECOVERY_REQUIRED);
        Set<String> keep=new HashSet<>();for(Identity identity:Arrays.asList(active,journal.nativeChange.prior,journal.nativeChange.candidate)){if(identity!=null)keep.add(identity.generationId+".json");for(Stored s:manifest(identity).values())keep.add(s.blob);}
        if(disk.exists("previous.json")){Map<String,Object> previous=WalletCodec.parse(disk.read("previous.json",2048),2048);WalletCodec.keys(previous,"identity");Identity previousIdentity=WalletCodec.identity(previous.get("identity"));if(previousIdentity!=null)keep.add(previousIdentity.generationId+".json");for(Stored s:manifest(previousIdentity).values())keep.add(s.blob);}
        int n=0,failures=0;long bytes=0;
        try(java.nio.file.DirectoryStream<Path> files=Files.newDirectoryStream(disk.root)){
            for(Path p:files)if(p.getFileName().toString().startsWith("lease_"))return new CollectionCounts(0,0,0); // unknown crash-surviving pins retained
        }
        try(java.nio.file.DirectoryStream<Path> files=Files.newDirectoryStream(disk.root)){
            for(Path p:files){String name=p.getFileName().toString();boolean object=name.matches("(?:blob|spool)_[0-9a-f]{32}")||name.matches("gen_[0-9a-f]{32}\\.json");boolean oldRecovery=name.matches("(?:escrow|counter-prior|previous-prior)_txn_[0-9a-f]{32}")&&!name.endsWith(journal.transactionId);
                if((!object&&!oldRecovery)||keep.contains(name)||(openFiles.containsKey(name)&&openFiles.get(name).get()>0)||sinks.values().stream().anyMatch(s->s.name.equals(name)&&!s.closed))continue;
                try{long size=Files.size(disk.path(name));Files.delete(disk.path(name));n++;bytes+=size;}catch(IOException e){failures++;}}
        }return new CollectionCounts(n,bytes,failures);
    });}
}
