package com.koalaman64.italytravelpocketguide;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.LongSupplier;
import static com.koalaman64.italytravelpocketguide.WalletTypes.*;

/** Bounded native archive orchestration. SAF admission and trusted-frame routing are host-owned. */
public final class ArchiveProtocol {
    public static final int CHUNK=16*1024, CHUNKS=32, ENVELOPE=100*1024;
    private final ArchiveCoordinator coordinator;
    private final WalletNativePort nativePort;
    private final Executor worker;
    private final LongSupplier clock;
    private final Function<Context,Boolean> current;
    private Preview preview;
    private boolean busy;
    public ArchiveProtocol(ArchiveCoordinator coordinator,WalletNativePort port,Executor worker,LongSupplier clock,Function<Context,Boolean> current){
        this.coordinator=coordinator;nativePort=port;this.worker=worker;this.clock=clock;this.current=current;
    }
    public static final class Preview {
        public final String token; public final Pair expected; public final int documents;
        public final String disclosure="Unencrypted archive: replaces Saved phrases, learning, preferences and all documents.";
        private final Context context;private final long expires;private final ArchiveFormat.Archive archive;
        private final ReadOnlySeekableHandle source;
        private boolean retirementUncertain;
        private Preview(Context c,Pair p,long expires,ArchiveFormat.Archive archive,ReadOnlySeekableHandle source){
            context=c;expected=p;this.expires=expires;this.archive=archive;this.source=source;
            token="preview_"+UUID.randomUUID().toString().replace("-", "");documents=archive.documents.size();
        }
    }
    private void check(Context context,long deadline)throws IOException{
        if(!Boolean.TRUE.equals(current.apply(context)))throw new WalletFailure(Code.STALE_SESSION);
        if(clock.getAsLong()>=deadline)throw new WalletFailure(Code.TIMEOUT);
    }
    private static ArchiveFormat.Source source(ReadOnlySeekableHandle handle){return new ArchiveFormat.Source(){
        public long size(){return handle.byteLength();}
        public int read(long offset,byte[] b,int start,int count)throws IOException{return handle.read(offset,b,start,count);}
    };}
    private static <T> T value(Result<T> r){if(r.status!=Status.OK)throw new java.util.concurrent.CompletionException(new WalletFailure(r.code));return r.value;}
    private static Code code(Throwable error){
        while(error.getCause()!=null)error=error.getCause();
        if(error instanceof WalletFailure)return ((WalletFailure)error).code;
        if(error instanceof ArchiveFormat.Failure){try{return Code.valueOf(((ArchiveFormat.Failure)error).code);}catch(IllegalArgumentException ignored){return Code.INVALID_ARCHIVE;}}
        return Code.IO_ERROR;
    }
    private boolean sameDocument(Context a,Context b){return a.sessionId.equals(b.sessionId)&&a.documentId.equals(b.documentId)&&a.epoch.equals(b.epoch);}
    private Result<Void> retire(Preview p){
        if(p.retirementUncertain)return Result.uncertain(null);
        try{p.source.close();if(preview==p)preview=null;return Result.ok(null);}
        catch(Exception close){p.retirementUncertain=true;return Result.uncertain(null);}
    }
    private Result<Void> retireObsolete(Context context){
        Preview p=preview;
        if(p!=null&&(clock.getAsLong()>=p.expires||!sameDocument(p.context,context)||!Boolean.TRUE.equals(current.apply(p.context))))return retire(p);
        return Result.ok(null);
    }
    /** Host lifecycle hook; deliberately does not require an obsolete context to remain current. */
    public synchronized Result<Void> invalidateContext(Context oldContext){
        if(busy)return Result.failed(Code.BUSY); // Host invalidates current(context); active worker must actually stop.
        if(preview==null)return Result.ok(null);
        if(!sameDocument(preview.context,oldContext))return Result.failed(Code.STALE_SESSION);
        return retire(preview);
    }
    /** SAF ticket is issued by the host; copy uses only a native-owned bounded spool. */
    public synchronized CompletionStage<Result<Preview>> preview(Context context,Pair expected,PickerTicket ticket){
        if(!busy&&retireObsolete(context).status!=Status.OK)return CompletableFuture.completedFuture(Result.uncertain(null));
        if(busy||preview!=null)return CompletableFuture.completedFuture(Result.failed(Code.BUSY));
        busy=true;long deadline=clock.getAsLong()+180000;
        final Preview[] result=new Preview[1];
        return coordinator.preview(context,expected,fence->nativePort.createSpool(fence,ARCHIVE_BYTES)
            .thenApplyAsync(ArchiveProtocol::value,worker).thenComposeAsync(sink->{
                try{
                    Context issued=ticket.context();if(!issued.sessionId.equals(context.sessionId)||!issued.documentId.equals(context.documentId)||!issued.epoch.equals(context.epoch))throw new WalletFailure(Code.STALE_SESSION);
                    byte[] buffer=new byte[CHUNK];
                    while(true){check(context,deadline);long readStarted=clock.getAsLong();int n=ticket.read(buffer,0,buffer.length);
                        check(context,deadline);if(clock.getAsLong()-readStarted>=30000)throw new WalletFailure(Code.NO_PROGRESS);
                        if(n==-1)break;if(n<=0||n>buffer.length)throw new WalletFailure(Code.NO_PROGRESS);sink.write(buffer,0,n);}
                    ticket.close();sink.close();
                }catch(Exception e){sink.cancel();try{ticket.close();}catch(Exception close){e.addSuppressed(close);}throw new java.util.concurrent.CompletionException(e);}
                return nativePort.sealSpool(fence,sink.spoolId());
            },worker).thenAcceptAsync(sealed->{
                ReadOnlySeekableHandle handle=value(sealed);
                try{ArchiveFormat.Archive archive=ArchiveFormat.read(source(handle),()->check(context,deadline));
                    result[0]=new Preview(context,expected,clock.getAsLong()+300000,archive,handle);
                }catch(Exception e){try{handle.close();}catch(Exception close){e.addSuppressed(close);}throw new java.util.concurrent.CompletionException(e);}
            },worker)).handleAsync((done,error)->{
                synchronized(this){busy=false;}
                if(error==null&&done.status==Status.OK){synchronized(this){preview=result[0];}return Result.ok(result[0]);}
                if(result[0]!=null)try{result[0].source.close();}catch(Exception ignored){/* native retains physical bytes */}
                try{ticket.close();}catch(Exception ignored){/* no success or cancellation receipt */}
                return error==null&&done.status==Status.FAILED?Result.<Preview>failed(done.code):Result.<Preview>uncertain(null);
            },worker);
    }
    /** Takes ownership of a native-sealed immutable spool handle; no provider URI/path is accepted. */
    public synchronized CompletionStage<Result<Preview>> preview(Context context,Pair expected,ReadOnlySeekableHandle spool){
        if(!busy&&retireObsolete(context).status!=Status.OK)return CompletableFuture.completedFuture(Result.uncertain(null));
        if(busy||preview!=null)return CompletableFuture.completedFuture(Result.failed(Code.BUSY));
        busy=true;long deadline=clock.getAsLong()+180000;
        return CompletableFuture.supplyAsync(()->{
            try{ArchiveFormat.Archive archive=ArchiveFormat.read(source(spool),()->check(context,deadline));
                Preview p=new Preview(context,expected,clock.getAsLong()+300000,archive,spool);
                synchronized(this){preview=p;}return Result.ok(p);
            }catch(Exception e){try{spool.close();}catch(Exception close){e.addSuppressed(close);}return Result.<Preview>failed(code(e));}
            finally{synchronized(this){busy=false;}}
        },worker);
    }
    public synchronized Result<Void> cancelPreview(Context context,String token){
        if(busy)return Result.failed(Code.BUSY);
        Preview p=preview;if(!matches(p,context,token))return Result.failed(Code.STALE_SESSION);
        return retire(p);
    }
    private boolean matches(Preview p,Context c,String token){return p!=null&&p.token.equals(token)&&p.context.sessionId.equals(c.sessionId)&&p.context.documentId.equals(c.documentId)&&p.context.epoch.equals(c.epoch)&&Boolean.TRUE.equals(current.apply(c));}
    public synchronized CompletionStage<Result<Checkpoint>> confirm(Context context,Pair expected,String token){
        Preview p=preview;
        if(busy)return CompletableFuture.completedFuture(Result.failed(Code.BUSY));
        Result<Void> retired=retireObsolete(context);
        if(retired.status!=Status.OK)return CompletableFuture.completedFuture(Result.uncertain(null));
        if(preview!=p||!matches(p,context,token))return CompletableFuture.completedFuture(Result.failed(Code.CONFIRMATION_REQUIRED));
        if(!p.expected.equals(expected))return CompletableFuture.completedFuture(Result.failed(Code.RESTORE_CONFLICT));
        busy=true;preview=null;long deadline=clock.getAsLong()+180000;
        return coordinator.restore(context,expected,new String(p.archive.userBytes(),StandardCharsets.UTF_8),
            (f,plan)->stage(context,deadline,p,f,plan)).handleAsync((result,error)->{
                try{p.source.close();}catch(Exception close){return Result.<Checkpoint>uncertain(null);}
                finally{synchronized(this){busy=false;}}
                return error==null?result:Result.<Checkpoint>uncertain(null);
            },worker);
    }
    private CompletionStage<Result<Receipt>> stage(Context context,long deadline,Preview p,NativeFence fence,TxnPlan plan){
        List<Document> measured=new ArrayList<>();List<String> ids=new ArrayList<>();
        CompletionStage<Void> chain=CompletableFuture.runAsync(()->{
            try{ArchiveFormat.Archive fresh=ArchiveFormat.read(source(p.source),()->check(context,deadline));
                if(!Arrays.equals(fresh.userBytes(),p.archive.userBytes())||!Arrays.equals(fresh.manifestBytes(),p.archive.manifestBytes()))throw new WalletFailure(Code.HASH_MISMATCH);
            }catch(IOException e){throw new java.util.concurrent.CompletionException(e);}
        },worker);
        for(int index=0;index<p.archive.documents.size();index++){
            ArchiveFormat.Document declared=p.archive.documents.get(index);ArchiveFormat.Entry entry=p.archive.entries.get(index+2);
            chain=chain.thenComposeAsync(v->nativePort.createStagedDocument(fence,plan.transactionId,plan.nativeGenerationId,declared.id,declared.byteLength),worker)
                .thenApplyAsync(ArchiveProtocol::value,worker).thenComposeAsync(sink->{
                    try{
                        byte[] buffer=new byte[CHUNK];long copied=0;
                        while(copied<entry.size){check(context,deadline);int count=(int)Math.min(buffer.length,entry.size-copied);
                            int n=p.source.read(entry.offset+copied,buffer,0,count);if(n<=0||n>count)throw new WalletFailure(Code.NO_PROGRESS);
                            sink.write(buffer,0,n);copied+=n;}
                        sink.close();
                    }catch(Exception e){sink.cancel();throw new java.util.concurrent.CompletionException(e);}
                    return nativePort.sealStagedDocument(fence,plan.transactionId,declared.id,declared.byteLength,declared.sha256);
                },worker).thenAcceptAsync(result->{
                    ValidatedDocument sealed=value(result);Document d=sealed.document;
                    if(!d.id.equals(declared.id)||!d.kind.equals(declared.kind)||d.byteLength!=declared.byteLength||!d.sha256.equals(declared.sha256))throw new java.util.concurrent.CompletionException(new WalletFailure(Code.REFERENCE_MISMATCH));
                    // Only metadata is portable. Dimensions/orientation always come from isolated native validation.
                    measured.add(new Document(d.id,d.kind,d.byteLength,d.sha256,declared.displayName,declared.importedAtUtc,d.pages,d.orientation));ids.add(sealed.stageId);
                },worker);
        }
        return chain.thenComposeAsync(v->{
            try{
                Map<String,Object> user=JsonTransferProtocol.map(JsonTransferJson.parse(new String(p.archive.userBytes(),StandardCharsets.UTF_8),USER_BYTES));
                List<Ref> refs=new ArrayList<>();for(Object item:(List<?>)user.get("attachments")){
                    Map<String,Object> r=JsonTransferProtocol.map(item);refs.add(new Ref((String)r.get("tripId"),(String)r.get("eventId"),(String)r.get("documentId")));}
                check(context,deadline);return nativePort.stageArchive(fence,plan.transactionId,new ValidatedArchive(measured,refs,user.get("wallet")!=null),new NativeStagingHandles(ids));
            }catch(Exception e){return CompletableFuture.completedFuture(Result.<Receipt>failed(code(e)));}
        },worker).exceptionally(e->Result.failed(code(e)));
    }
    /** Output belongs to the SAF adapter. Success requires write, flush and close acknowledgement. */
    public CompletionStage<Result<Checkpoint>> export(Context context,Pair expected,String exportedAtUtc,OutputStream output){
        long deadline=clock.getAsLong()+180000;
        return coordinator.export(context,expected,(raw,snapshot,lease)->{
            List<Document> docs=new ArrayList<>(snapshot.documents);docs.sort(Comparator.comparing(d->d.id));
            Map<String,ArchiveFormat.Source> sources=new LinkedHashMap<>();List<ReadOnlyDocumentHandle> handles=new ArrayList<>();
            CompletionStage<Void> chain=CompletableFuture.completedFuture(null);
            for(Document doc:docs)chain=chain.thenComposeAsync(v->nativePort.openSnapshotDocument(lease,doc.id),worker).thenAcceptAsync(result->{
                ReadOnlyDocumentHandle handle=value(result);handles.add(handle);sources.put("documents/"+doc.id+"."+doc.kind,source(handle));
            },worker);
            return chain.thenRunAsync(()->{
                try{check(context,deadline);byte[] user=raw.getBytes(StandardCharsets.UTF_8);
                    List<Object> descriptions=new ArrayList<>();for(Document d:docs)descriptions.add(JsonTransferJson.object("id",d.id,"kind",d.kind,"byteLength",d.byteLength,"sha256",d.sha256,"displayName",d.displayName,"importedAtUtc",d.importedAtUtc));
                    Object wallet=snapshot.identity==null?null:JsonTransferJson.object("generationId",snapshot.identity.generationId,"revision",snapshot.identity.revision,"documents",descriptions);
                    byte[] manifest=JsonTransferJson.encode(JsonTransferJson.object("format","itguide-full-archive","schemaVersion",1,"exportedAtUtc",exportedAtUtc,
                        "user",JsonTransferJson.object("entry","user-data.json","byteLength",user.length,"sha256",ArchiveCoordinator.sha(raw),"generationId",expected.web.generationId,"revision",expected.web.revision,"schemaVersion",1),"wallet",wallet)).getBytes(StandardCharsets.UTF_8);
                    ArchiveFormat.write(manifest,user,sources,output,()->check(context,deadline));output.flush();
                }catch(Exception e){throw new java.util.concurrent.CompletionException(e);}
            },worker).handleAsync((v,error)->{
                Throwable failure=error;
                for(ReadOnlyDocumentHandle h:handles)try{h.close();}catch(Exception close){if(failure==null)failure=close;else failure.addSuppressed(close);}
                try{output.close();}catch(Exception close){if(failure==null)failure=close;else failure.addSuppressed(close);}
                if(failure!=null)throw new java.util.concurrent.CompletionException(failure);return null;
            },worker);
        });
    }
    /** Personal JSON only; no archive/document bytes cross this framing protocol. */
    public static final class Chunks {
        private final String transferId,hash;private final int count;private int next,total;private boolean failed;
        private final StringBuilder raw=new StringBuilder();
        public Chunks(String transferId,int count,String sha){
            if(transferId==null||!transferId.matches("[a-zA-Z0-9_-]{1,128}")||count<1||count>CHUNKS)throw new IllegalArgumentException("INVALID_REQUEST");
            this.transferId=transferId;this.count=count;hash=WalletTypes.hash(sha);
        }
        public synchronized String accept(String envelope)throws JsonTransferJson.Failure,WalletFailure{
            try{
                if(failed)throw new WalletFailure(Code.INVALID_REQUEST);
                Map<String,Object> m=JsonTransferProtocol.map(JsonTransferJson.parse(envelope,ENVELOPE));
                if(!m.keySet().equals(new java.util.HashSet<>(Arrays.asList("v","transferId","sequence","count","sha256","text"))))throw new WalletFailure(Code.INVALID_REQUEST);
                integer(m.get("v"),1);
                if(!transferId.equals(m.get("transferId"))||!hash.equals(m.get("sha256")))throw new WalletFailure(Code.TRANSACTION_CONFLICT);
                integer(m.get("count"),count);integer(m.get("sequence"),next);
                if(!(m.get("text") instanceof String))throw new WalletFailure(Code.INVALID_REQUEST);
                String part=(String)m.get("text");int length=part.getBytes(StandardCharsets.UTF_8).length;
                if(length>CHUNK||length==0||total+length>USER_BYTES)throw new WalletFailure(Code.FILE_LIMIT);
                total+=length;next++;raw.append(part);
                if(next!=count)return null;
                if(!ArchiveCoordinator.sha(raw.toString()).equals(hash))throw new WalletFailure(Code.HASH_MISMATCH);
                failed=true;return raw.toString();
            }catch(JsonTransferJson.Failure|WalletFailure|RuntimeException e){failed=true;throw e;}
        }
        private static void integer(Object value,int expected)throws WalletFailure{
            if(!(value instanceof Number)||!new java.math.BigDecimal(value.toString()).equals(new java.math.BigDecimal(expected)))throw new WalletFailure(Code.INVALID_REQUEST);
        }
    }
}
