package com.koalaman64.italytravelpocketguide;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Predicate;
import static com.koalaman64.italytravelpocketguide.WalletTypes.*;

/** Native half of the internal UserData channel. The transport must authenticate
 * the current main frame, bound/fracture messages and quarantine timed-out work.
 * This class never accepts a fence supplied by the page or infers write rollback.
 */
public final class ArchiveWebPort implements ArchiveCoordinator.WebPort, WalletStore.WebEvidence {
    public interface Transport {
        CompletionStage<String> exchange(Context context, String method, String arguments);
    }
    private interface Decode<T> { T read(Object value) throws Exception; }
    private static final int WIRE_LIMIT = 4 * ESCROW_BYTES;
    private final Transport transport;
    private final Predicate<Context> current;
    private Fence active;
    private boolean entering;
    private static final class Fence { final Context context; Fence(Context c) { context=c; } }
    public ArchiveWebPort(Transport transport, Predicate<Context> current) {
        this.transport=Objects.requireNonNull(transport);this.current=Objects.requireNonNull(current);
    }
    private static boolean same(Context a,Context b) {
        return a!=null&&b!=null&&a.sessionId.equals(b.sessionId)&&a.documentId.equals(b.documentId)&&a.epoch.equals(b.epoch);
    }
    private synchronized boolean live(Fence f) { return f!=null&&active==f&&current.test(f.context); }
    /** Host must also invalidate the JavaScript adapter synchronously on lifecycle loss. */
    public synchronized void invalidate() { active=null; }
    private <T> CompletionStage<Result<T>> call(Object token,String method,Decode<T> decode,Object... args) {
        if(!(token instanceof Fence)||!live((Fence)token)) return CompletableFuture.completedFuture(Result.failed(Code.STALE_SESSION));
        Fence f=(Fence)token;
        try {
            String wire=WalletCodec.decode(WalletCodec.encode(Arrays.asList(args),WIRE_LIMIT),WIRE_LIMIT);
            return transport.exchange(f.context,method,wire).handle((raw,error)->{
                if(!live(f)) return Result.<T>uncertain(null);
                if(error!=null) return Result.<T>uncertain(null);
                try {
                    Map<String,Object> result=WalletCodec.parse(WalletCodec.utf8(raw,WIRE_LIMIT),WIRE_LIMIT);
                    for(String key:result.keySet()) if(!Arrays.asList("status","value","code","transactionId").contains(key)) throw new WalletFailure(Code.INVALID_DATA);
                    String status=WalletCodec.text(result.get("status"));
                    if("UNCERTAIN".equals(status)) return Result.<T>uncertain(null);
                    if("FAILED".equals(status)) {
                        Code code;
                        try { code=Code.valueOf(WalletCodec.text(result.get("code"))); }
                        catch(Exception unknown) { code=Code.RECOVERY_REQUIRED; }
                        return Result.<T>failed(code);
                    }
                    if(!"OK".equals(status)||result.get("code")!=null||result.get("transactionId")!=null) throw new WalletFailure(Code.INVALID_DATA);
                    return Result.ok(decode.read(result.get("value")));
                } catch(Exception invalid) { return Result.<T>uncertain(null); }
            });
        } catch(Exception unavailable) { return CompletableFuture.completedFuture(Result.uncertain(null)); }
    }
    public synchronized CompletionStage<Result<Object>> enterRecovery(Context c) {
        if(entering) return CompletableFuture.completedFuture(Result.failed(Code.BUSY));
        if(!current.test(c)) return CompletableFuture.completedFuture(Result.failed(Code.STALE_SESSION));
        Fence f=new Fence(c);active=f;entering=true;
        return call(f,"enterRecovery",v->(Object)f).whenComplete((r,e)->{
            synchronized(this) { entering=false;if(e!=null||r.status!=Status.OK) { if(active==f) active=null; } }
        });
    }
    public CompletionStage<Result<ArchiveCoordinator.WebRecovery>> readRecovery(Object f) {
        return call(f,"readRecovery",v->{Map<String,Object> m=WalletCodec.map(v);WalletCodec.keys(m,"active","previous","transactionId");String tx=m.get("transactionId")==null?null:WalletTypes.id(WalletCodec.text(m.get("transactionId")),"txn");return new ArchiveCoordinator.WebRecovery(WalletCodec.identity(m.get("active")),WalletCodec.identity(m.get("previous")),tx);});
    }
    private static String user(Object v)throws Exception { String s=WalletCodec.text(v);WalletCodec.utf8(s,USER_BYTES);return s; }
    private static Receipt receipt(Object v)throws Exception {
        Map<String,Object> m=WalletCodec.map(v);WalletCodec.keys(m,"v","transactionId","operation","store","phase","prior","candidate");
        if(WalletCodec.number(m.get("v"))!=1||!"web".equals(m.get("store"))) throw new WalletFailure(Code.INVALID_DATA);
        return new Receipt(WalletCodec.text(m.get("transactionId")),WalletCodec.operation(m.get("operation")),Store.WEB,ReceiptPhase.valueOf(WalletCodec.text(m.get("phase"))),WalletCodec.identity(m.get("prior")),WalletCodec.identity(m.get("candidate")));
    }
    public CompletionStage<Result<String>> captureExact(Object f,Identity i) { return call(f,"captureExact",ArchiveWebPort::user,WalletCodec.identity(i)); }
    public CompletionStage<Result<Identity>> initializeBaseline(Object f) { return call(f,"initializeBaseline",WalletCodec::identity); }
    public CompletionStage<Result<String>> prepareCandidate(Object f,Operation o,Identity p,Identity n,String raw) { return call(f,"prepareCandidate",ArchiveWebPort::user,WalletCodec.operation(o),WalletCodec.identity(p),WalletCodec.identity(n),raw); }
    public CompletionStage<Result<Receipt>> stageInactive(Object f,String tx,Operation o,Identity p,String raw,Identity n,String seq) { return call(f,"stageInactive",ArchiveWebPort::receipt,tx,WalletCodec.operation(o),WalletCodec.identity(p),raw,WalletCodec.identity(n),seq); }
    public CompletionStage<Result<Receipt>> readStaged(Object f,String tx) { return call(f,"readStaged",ArchiveWebPort::receipt,tx); }
    public CompletionStage<Result<ArchiveCoordinator.WebEscrow>> exportStage(Object f,String tx) { return call(f,"exportStage",v->{Map<String,Object> m=WalletCodec.map(v);WalletCodec.keys(m,"raw","sha256","byteLength");String raw=WalletCodec.text(m.get("raw"));if(WalletCodec.utf8(raw,ESCROW_BYTES).length!=WalletCodec.number(m.get("byteLength")))throw new WalletFailure(Code.INVALID_DATA);return new ArchiveCoordinator.WebEscrow(raw,WalletCodec.text(m.get("sha256")));},tx); }
    public CompletionStage<Result<Void>> restoreEscrow(Object f,String raw,String hash) { return call(f,"restoreEscrow",v->null,raw,hash); }
    public CompletionStage<Result<Receipt>> activateStaged(Object f,String tx,Journal j) { return call(f,"activateStaged",ArchiveWebPort::receipt,tx,WalletCodec.journal(j)); }
    public CompletionStage<Result<Identity>> restorePrevious(Object f,String tx,RecoveryDecision d) { return call(f,"restorePrevious",WalletCodec::identity,tx,WalletCodec.decision(d)); }
    public CompletionStage<Result<Identity>> verifyActive(Object f,Identity i) { return call(f,"verifyActive",WalletCodec::identity,WalletCodec.identity(i)); }
    public CompletionStage<Result<Void>> releaseReady(Object f,Checkpoint cp,Identity n) { return call(f,"releaseReady",v->null,WalletCodec.checkpoint(cp),WalletCodec.identity(n)); }
    public CompletionStage<Result<Void>> discardStage(Object f,String tx,Checkpoint next,Checkpoint initial) { return call(f,"discardStage",v->null,tx,WalletCodec.checkpoint(next),WalletCodec.checkpoint(initial)); }
    private CompletionStage<Boolean> evidence(Context c,String method,Object... args) {
        Fence f; synchronized(this) { f=active; }
        if(f==null||!same(c,f.context)||!current.test(c)) return CompletableFuture.completedFuture(false);
        return call(f,method,v->{if(!(v instanceof Boolean))throw new WalletFailure(Code.INVALID_DATA);return (Boolean)v;},args).thenApply(r->r.status==Status.OK&&Boolean.TRUE.equals(r.value));
    }
    public CompletionStage<Boolean> verifyPair(Context c,Pair p) { return evidence(c,"verifyPair",WalletCodec.pair(p)); }
    public CompletionStage<Boolean> verifyRecoveryPair(Context c,Pair p) { return evidence(c,"verifyRecoveryPair",WalletCodec.pair(p)); }
    public CompletionStage<Boolean> verifyReceipt(Context c,Receipt r,String raw) {
        if(r.store!=Store.WEB) return CompletableFuture.completedFuture(false);
        return evidence(c,"verifyReceipt",WalletCodec.obj("v",1,"transactionId",r.transactionId,"operation",WalletCodec.operation(r.operation),"store","web","phase",r.phase.name(),"prior",WalletCodec.identity(r.prior),"candidate",WalletCodec.identity(r.candidate)),raw);
    }
}
