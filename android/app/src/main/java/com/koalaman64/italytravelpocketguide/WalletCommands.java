package com.koalaman64.italytravelpocketguide;

import java.io.OutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Predicate;
import static com.koalaman64.italytravelpocketguide.WalletTypes.*;

/** Personal-data command boundary. Only the authenticated bridge may dispatch.
 * The coordinator owns every write; a cached checkpoint is never treated as a
 * replacement for the coordinator's exact current-pair validation.
 */
public final class WalletCommands {
    /** UI capabilities are issued by the Activity, never reconstructed from JS data. */
    public interface Host {
        boolean supported();
        boolean isCurrentRelation(Ref relation);
        CompletionStage<Result<PickerTicket>> importDocument(Context context);
        CompletionStage<Result<PickerTicket>> importArchive(Context context);
        CompletionStage<Result<OutputStream>> exportArchive(Context context);
        CompletionStage<Result<Void>> openDocument(Context context,WalletNativePort port,Identity identity,String documentId);
    }
    private final Context context;
    private final ArchiveCoordinator coordinator;
    private final Predicate<Context> current;
    private final WalletNativePort nativePort;
    private final ArchiveProtocol archive;
    private final Host host;
    private final Executor worker;
    private volatile Checkpoint checkpoint;
    private long lastRequest;
    private boolean busy,closed;
    public WalletCommands(Context context,ArchiveCoordinator coordinator,Predicate<Context> current) {
        this(context,coordinator,current,null,null,null,null);
    }
    public WalletCommands(Context context,ArchiveCoordinator coordinator,Predicate<Context> current,
            WalletNativePort nativePort,ArchiveProtocol archive,Host host,Executor worker) {
        this.context=context;this.coordinator=coordinator;this.current=current;
        this.nativePort=nativePort;this.archive=archive;this.host=host;this.worker=worker;
    }
    private boolean live() { return !closed&&current.test(context); }
    public synchronized void invalidate() { closed=true;checkpoint=null; }
    /** Called by the lifecycle worker only after the in-flight command settles. */
    Result<Void> retireArchive() { return archive==null?Result.ok(null):archive.invalidateContext(context); }
    public synchronized CompletionStage<Result<Checkpoint>> start() {
        if(!live())return CompletableFuture.completedFuture(Result.failed(Code.STALE_SESSION));
        if(busy)return CompletableFuture.completedFuture(Result.failed(Code.BUSY));
        busy=true;checkpoint=null;
        return coordinator.start(context).handle((r,e)->{
            synchronized(this) {
                busy=false;
                if(!live()||e!=null||r==null)return Result.<Checkpoint>uncertain(null);
                if(r.status==Status.OK)checkpoint=r.value;
                return r;
            }
        });
    }
    private String reply(String id,String status,String code,Checkpoint saved) {
        Map<String,Object> result=WalletCodec.obj("v",1,"kind","mutation-result","context",WalletWebChannel.context(context),"id",id,"status",status);
        if(saved!=null) { result.put("revision",saved.pair.web.revision);result.put("generationId",saved.pair.web.generationId); }
        else result.put("code",code);
        return JsonTransferJson.encode(result);
    }
    public synchronized CompletionStage<String> command(String raw) {
        String id="0";
        try {
            Map<String,Object> m=WalletCodec.parse(WalletCodec.utf8(raw,WalletWebChannel.ENVELOPE_BYTES),WalletWebChannel.ENVELOPE_BYTES);
            if("wallet".equals(m.get("kind")))return wallet(m);
            WalletCodec.keys(m,"v","kind","context","id","expectedRevision","change");
            if(!live()||!WalletWebChannel.context(context).equals(m.get("context")))throw new WalletFailure(Code.STALE_SESSION);
            if(WalletCodec.number(m.get("v"))!=1||!"mutate".equals(m.get("kind")))throw new WalletFailure(Code.INVALID_REQUEST);
            id=WalletTypes.revision(WalletCodec.text(m.get("id")));long request=Long.parseLong(id);
            if(request!=lastRequest+1||lastRequest==Long.MAX_VALUE)throw new WalletFailure(Code.INVALID_REQUEST);
            lastRequest=request;
            if(busy)return CompletableFuture.completedFuture(reply(id,"FAILED","BUSY",null));
            if(checkpoint==null)return CompletableFuture.completedFuture(reply(id,"FAILED","RECOVERY_REQUIRED",null));
            String revision=WalletTypes.revision(WalletCodec.text(m.get("expectedRevision")));
            if(!checkpoint.pair.web.revision.equals(revision))return CompletableFuture.completedFuture(reply(id,"FAILED","REVISION_CONFLICT",null));
            Map<String,Object> change=WalletCodec.map(m.get("change"));
            // Personal writers cannot call native document-binding/attachment operations.
            String type=WalletCodec.text(change.get("type"));
            if(!java.util.Arrays.asList("save","remove","review","preferences","builder").contains(type))throw new WalletFailure(Code.INVALID_REQUEST);
            String input=JsonTransferJson.encode(change),requestId=id;Pair expected=checkpoint.pair;
            busy=true;checkpoint=null;
            return coordinator.mutate(context,expected,input).handle((r,e)->{
                synchronized(this) {
                    busy=false;
                    if(!live()||e!=null||r==null)return reply(requestId,"UNCERTAIN","RECOVERY_REQUIRED",null);
                    if(r.status==Status.OK) { checkpoint=r.value;return reply(requestId,"SAVED",null,checkpoint); }
                    // Even a known pre-decision error needs a verified recovery before retry.
                    return reply(requestId,r.status.name(),r.code==null?"RECOVERY_REQUIRED":r.code.name(),null);
                }
            });
        } catch(Exception invalid) { return CompletableFuture.completedFuture(reply(id,"FAILED",invalid instanceof WalletFailure?((WalletFailure)invalid).code.name():"INVALID_REQUEST",null)); }
    }

    private String walletReply(String id,String status,Object value,Code code) {
        Map<String,Object> out=WalletCodec.obj("v",1,"kind","wallet-result","context",WalletWebChannel.context(context),"id",id,"status",status);
        if(code==null)out.put("value",value);else out.put("code",code.name());
        return JsonTransferJson.encode(out);
    }
    private Code code(Exception e) { return e instanceof WalletFailure?((WalletFailure)e).code:Code.INVALID_REQUEST; }
    private CompletionStage<String> failure(String id,Code code) { return CompletableFuture.completedFuture(walletReply(id,"FAILED",null,code)); }
    private static boolean equal(Object actual,Object expected) { return java.util.Objects.equals(actual,expected); }
    private void expected(Map<String,Object> m,Pair pair)throws Exception {
        WalletCodec.keys(m,"userRevision","walletGenerationId","walletRevision");
        if(!equal(WalletTypes.revision(WalletCodec.text(m.get("userRevision"))),pair.web.revision))throw new WalletFailure(Code.REVISION_CONFLICT);
        Object generation=m.get("walletGenerationId"), revision=m.get("walletRevision");
        if((generation==null)!=(revision==null))throw new WalletFailure(Code.INVALID_REQUEST);
        if(pair.nativeIdentity==null) { if(generation!=null)throw new WalletFailure(Code.REVISION_CONFLICT); }
        else if(!equal(WalletCodec.text(generation),pair.nativeIdentity.generationId)||
                !equal(WalletTypes.revision(WalletCodec.text(revision)),pair.nativeIdentity.revision))throw new WalletFailure(Code.REVISION_CONFLICT);
    }
    private static Ref relation(Object raw)throws Exception {
        Map<String,Object> m=WalletCodec.map(raw);WalletCodec.keys(m,"tripId","eventId","documentId");
        return new Ref(WalletCodec.text(m.get("tripId")),WalletCodec.text(m.get("eventId")),WalletCodec.text(m.get("documentId")));
    }
    private static String argument(Map<String,Object> args,String field)throws Exception {
        WalletCodec.keys(args,field);return WalletCodec.text(args.get(field));
    }
    private boolean closeTicket(PickerTicket ticket) { if(ticket==null)return false;try{ticket.close();return true;}catch(Exception failed){return false;} }
    private boolean closeOutput(OutputStream output) { if(output==null)return false;try{output.close();return true;}catch(Exception failed){return false;} }
    private static final class OwnedTicket implements PickerTicket {
        private final PickerTicket delegate;private boolean closed;private WalletFailure closeFailure;
        OwnedTicket(PickerTicket delegate){this.delegate=delegate;}
        public Context context(){return delegate.context();}
        public int read(byte[] target,int offset,int count)throws WalletFailure{return delegate.read(target,offset,count);}
        public synchronized void close()throws WalletFailure{
            if(closed){if(closeFailure!=null)throw closeFailure;return;}
            closed=true;
            try{delegate.close();}catch(WalletFailure failure){closeFailure=failure;throw failure;}
        }
        synchronized boolean closeVerified(){try{close();return closeFailure==null;}catch(WalletFailure failure){return false;}}
    }
    private static final class OwnedOutput extends OutputStream {
        private final OutputStream delegate;private boolean closed;private IOException closeFailure;
        OwnedOutput(OutputStream delegate){this.delegate=delegate;}
        public void write(int value)throws IOException{delegate.write(value);}
        public void write(byte[] b,int offset,int count)throws IOException{delegate.write(b,offset,count);}
        public void flush()throws IOException{delegate.flush();}
        public synchronized void close()throws IOException{
            if(closed){if(closeFailure!=null)throw closeFailure;return;}
            closed=true;
            try{delegate.close();}catch(IOException failure){closeFailure=failure;throw failure;}
        }
        synchronized boolean closeVerified(){try{close();return closeFailure==null;}catch(IOException failure){return false;}}
    }
    private Map<String,Object> version(Checkpoint cp) {
        Identity n=cp.pair.nativeIdentity;
        return WalletCodec.obj("userRevision",cp.pair.web.revision,"walletGenerationId",n==null?null:n.generationId,"walletRevision",n==null?null:n.revision);
    }
    private CompletionStage<Result<NativeSnapshot>> snapshot(Checkpoint cp) {
        return nativePort.getSnapshot(context).handle((r,e)->{
            if(e!=null||r==null||r.status!=Status.OK||r.value==null)return Result.<NativeSnapshot>uncertain(null);
            if(!equal(r.value.identity,cp.pair.nativeIdentity))return Result.<NativeSnapshot>uncertain(null);
            return r;
        });
    }
    private Map<String,Object> projection(Checkpoint cp,NativeSnapshot snapshot) {
        List<Object> documents=new ArrayList<>();
        for(Document d:snapshot.documents)documents.add(WalletCodec.obj("documentId",d.id,"displayName",d.displayName,
                "mediaType","pdf".equals(d.kind)?"application/pdf":"png".equals(d.kind)?"image/png":"image/jpeg","byteLength",d.byteLength));
        Map<String,Object> out=version(cp);out.put("documents",documents);return out;
    }
    private CompletionStage<String> result(String id,String success,Result<Checkpoint> result) {
        if(result==null||result.status==Status.UNCERTAIN) { checkpoint=null;return CompletableFuture.completedFuture(walletReply(id,"UNCERTAIN",null,Code.RECOVERY_REQUIRED)); }
        if(result.status!=Status.OK) { checkpoint=null;return failure(id,result.code==null?Code.RECOVERY_REQUIRED:result.code); }
        return snapshot(result.value).thenApply(s->{
            if(!live()||s.status!=Status.OK) { checkpoint=null;return walletReply(id,"UNCERTAIN",null,Code.RECOVERY_REQUIRED); }
            checkpoint=result.value;return walletReply(id,success,version(result.value),null);
        });
    }
    private CompletionStage<String> predecisionCancel(String id,Checkpoint prior) {
        return coordinator.preview(context,prior.pair,f->CompletableFuture.completedFuture(null))
            .thenCompose(r->{
                if(r==null||r.status!=Status.OK) {checkpoint=null;return CompletableFuture.completedFuture(walletReply(id,"UNCERTAIN",null,Code.RECOVERY_REQUIRED));}
                return snapshot(prior).thenApply(s->{
                    if(s.status!=Status.OK||!live()) {checkpoint=null;return walletReply(id,"UNCERTAIN",null,Code.RECOVERY_REQUIRED);}
                    checkpoint=prior;return walletReply(id,"CANCELLED",WalletCodec.obj(),null);
                });
            });
    }
    private CompletionStage<String> wallet(Map<String,Object> m) {
        String id="0";
        try {
            WalletCodec.keys(m,"v","kind","context","id","op","expected","args");
            if(!live()||!WalletWebChannel.context(context).equals(m.get("context")))throw new WalletFailure(Code.STALE_SESSION);
            if(WalletCodec.number(m.get("v"))!=1)throw new WalletFailure(Code.INVALID_REQUEST);
            id=WalletTypes.revision(WalletCodec.text(m.get("id")));long request=Long.parseLong(id);
            if(request!=lastRequest+1||lastRequest==Long.MAX_VALUE)throw new WalletFailure(Code.INVALID_REQUEST);
            lastRequest=request;
            if(busy)return failure(id,Code.BUSY);
            if(host==null||!host.supported()||nativePort==null||archive==null)return failure(id,Code.UNSUPPORTED);
            String op=WalletCodec.text(m.get("op"));Map<String,Object> args=WalletCodec.map(m.get("args"));
            if(!"recover".equals(op)&&checkpoint==null)return failure(id,Code.RECOVERY_REQUIRED);
            if(checkpoint!=null)expected(WalletCodec.map(m.get("expected")),checkpoint.pair);
            else { Map<String,Object> x=WalletCodec.map(m.get("expected"));WalletCodec.keys(x,"userRevision","walletGenerationId","walletRevision"); }
            final String requestId=id;final Checkpoint prior=checkpoint;
            CompletionStage<String> operation;
            switch(op) {
                case "snapshot":
                    WalletCodec.keys(args);
                    operation=snapshot(prior).thenApply(s->{if(s.status!=Status.OK){checkpoint=null;return walletReply(requestId,"UNCERTAIN",null,Code.RECOVERY_REQUIRED);}return walletReply(requestId,"OK",projection(prior,s.value),null);});break;
                case "attach":case "unlink": {
                    Ref ref=relation(args.get("relation"));WalletCodec.keys(args,"relation");
                    if(op.equals("attach")&&!host.isCurrentRelation(ref))return failure(id,Code.REFERENCE_MISMATCH);
                    operation=snapshot(prior).thenCompose(s->{
                        if(s.status!=Status.OK)return CompletableFuture.completedFuture(walletReply(requestId,"UNCERTAIN",null,Code.RECOVERY_REQUIRED));
                        boolean exists=s.value.documents.stream().anyMatch(d->d.id.equals(ref.documentId));
                        if(op.equals("attach")&&!exists)return failure(requestId,Code.REFERENCE_MISMATCH);
                        String change=JsonTransferJson.encode(WalletCodec.obj("type",op,"relation",WalletCodec.obj("tripId",ref.tripId,"eventId",ref.eventId,"documentId",ref.documentId)));
                        checkpoint=null;return coordinator.mutate(context,prior.pair,change).thenCompose(r->result(requestId,"SAVED",r));
                    });break;
                }
                case "import-document": {
                    String label=WalletTypes.label(argument(args,"displayName"));
                    operation=host.importDocument(context).thenComposeAsync(p->{
                        if(p==null||p.status==Status.UNCERTAIN){checkpoint=null;return CompletableFuture.completedFuture(walletReply(requestId,"UNCERTAIN",null,Code.RECOVERY_REQUIRED));}
                        if(p.status!=Status.OK)return p.code==Code.CANCELLED?predecisionCancel(requestId,prior):failure(requestId,p.code==null?Code.PROVIDER_UNAVAILABLE:p.code);
                        if(!live()) { closeTicket(p.value);return CompletableFuture.completedFuture(walletReply(requestId,"UNCERTAIN",null,Code.RECOVERY_REQUIRED)); }
                        checkpoint=null;
                        OwnedTicket owned=new OwnedTicket(p.value);
                        return coordinator.importDocument(context,prior.pair,owned,label,"{}").handleAsync((r,e)->
                            !owned.closeVerified()||e!=null?Result.<Checkpoint>uncertain(null):r,worker).thenCompose(r->result(requestId,"SAVED",r));
                    },worker);break;
                }
                case "delete-document": {
                    String documentId=WalletTypes.id(argument(args,"documentId"),"doc");checkpoint=null;
                    operation=coordinator.delete(context,prior.pair,documentId,"{}").thenCompose(r->result(requestId,"SAVED",r));break;
                }
                case "open-document": {
                    String documentId=WalletTypes.id(argument(args,"documentId"),"doc");
                    operation=snapshot(prior).thenCompose(s->{
                        if(s.status!=Status.OK) {checkpoint=null;return CompletableFuture.completedFuture(walletReply(requestId,"UNCERTAIN",null,Code.RECOVERY_REQUIRED));}
                        if(s.value.documents.stream().noneMatch(d->d.id.equals(documentId)))return failure(requestId,Code.NOT_FOUND);
                        return host.openDocument(context,nativePort,prior.pair.nativeIdentity,documentId).thenApply(r->{
                            if(r==null||r.status==Status.UNCERTAIN){checkpoint=null;return walletReply(requestId,"UNCERTAIN",null,Code.RECOVERY_REQUIRED);}
                            return r.status==Status.OK?walletReply(requestId,"OPENED",WalletCodec.obj(),null):walletReply(requestId,"FAILED",null,r.code==null?Code.RECOVERY_REQUIRED:r.code);
                        });
                    });break;
                }
                case "archive-preview": {
                    WalletCodec.keys(args);
                    operation=host.importArchive(context).thenComposeAsync(p->{
                        if(p==null||p.status==Status.UNCERTAIN){checkpoint=null;return CompletableFuture.completedFuture(walletReply(requestId,"UNCERTAIN",null,Code.RECOVERY_REQUIRED));}
                        if(p.status!=Status.OK)return p.code==Code.CANCELLED?predecisionCancel(requestId,prior):failure(requestId,p.code==null?Code.PROVIDER_UNAVAILABLE:p.code);
                        if(!live()){closeTicket(p.value);return CompletableFuture.completedFuture(walletReply(requestId,"UNCERTAIN",null,Code.RECOVERY_REQUIRED));}
                        checkpoint=null;
                        OwnedTicket owned=new OwnedTicket(p.value);
                        return archive.preview(context,prior.pair,owned).handleAsync((r,e)->
                            !owned.closeVerified()||e!=null?Result.<ArchiveProtocol.Preview>uncertain(null):r,worker)
                            .thenApply(r->{if(r.status!=Status.OK)return walletReply(requestId,r.status.name(),null,r.code==null?Code.RECOVERY_REQUIRED:r.code);checkpoint=prior;return walletReply(requestId,"OK",WalletCodec.obj("token",r.value.token,"documents",r.value.documents,"disclosure",r.value.disclosure),null);});
                    },worker);break;
                }
                case "archive-confirm": { String token=argument(args,"token");checkpoint=null;
                    operation=archive.confirm(context,prior.pair,token).thenCompose(r->result(requestId,"SAVED",r));break; }
                case "archive-cancel": { String token=argument(args,"token");Result<Void> cancelled=archive.cancelPreview(context,token);
                    if(cancelled.status==Status.UNCERTAIN){checkpoint=null;operation=CompletableFuture.completedFuture(walletReply(requestId,"UNCERTAIN",null,Code.RECOVERY_REQUIRED));}
                    else operation=cancelled.status==Status.OK?predecisionCancel(requestId,prior):
                        failure(requestId,cancelled.code==null?Code.RECOVERY_REQUIRED:cancelled.code);break; }
                case "archive-export": { WalletCodec.keys(args);
                    operation=host.exportArchive(context).thenComposeAsync(o->{
                        if(o==null||o.status==Status.UNCERTAIN){checkpoint=null;return CompletableFuture.completedFuture(walletReply(requestId,"UNCERTAIN",null,Code.RECOVERY_REQUIRED));}
                        if(o.status!=Status.OK)return o.code==Code.CANCELLED?predecisionCancel(requestId,prior):failure(requestId,o.code==null?Code.PROVIDER_UNAVAILABLE:o.code);
                        if(!live()){closeOutput(o.value);return CompletableFuture.completedFuture(walletReply(requestId,"UNCERTAIN",null,Code.RECOVERY_REQUIRED));}
                        checkpoint=null;
                        String exportedAtUtc=java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'").withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.now());
                        OwnedOutput owned=new OwnedOutput(o.value);
                        return archive.export(context,prior.pair,exportedAtUtc,owned).handleAsync((r,e)->
                            !owned.closeVerified()||e!=null?Result.<Checkpoint>uncertain(null):r,worker).thenCompose(r->result(requestId,"OK",r));
                    },worker);break;
                }
                case "recover": WalletCodec.keys(args);checkpoint=null;operation=coordinator.recover(context).thenCompose(r->result(requestId,"OK",r));break;
                default:return failure(id,Code.INVALID_REQUEST);
            }
            busy=true;
            return operation.handle((response,error)->{
                synchronized(this){busy=false;if(!live()||error!=null){checkpoint=null;return walletReply(requestId,"UNCERTAIN",null,Code.RECOVERY_REQUIRED);}return response;}
            });
        } catch(Exception invalid) { return failure(id,code(invalid)); }
    }
}
