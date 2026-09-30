package com.koalaman64.italytravelpocketguide;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Predicate;
import static com.koalaman64.italytravelpocketguide.WalletTypes.*;

/** Framed internal coordinator channel; never accepts UI operations or file bytes.
 * The Android owner must authenticate origin/main frame before receive(), and
 * dispatch send() only to the exact document. Timeouts permanently quarantine
 * this instance: an absent acknowledgment does not establish cancellation.
 */
public final class WalletWebChannel implements ArchiveWebPort.Transport {
    public interface Timer {
        Runnable after(long millis,Runnable action);
        default long now() { return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()); }
    }
    static final int FRAME_BYTES=16*1024, ENVELOPE_BYTES=100*1024, TOTAL_BYTES=8*1024*1024;
    private final Context owner;
    private final Predicate<Context> current;
    private final Consumer<String> send;
    private final Timer timer;
    private Pending pending;
    private boolean closed;
    private long nextId;
    private static final class Pending {
        final Context context;final String id;
        final CompletableFuture<String> result=new CompletableFuture<>();
        final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        int sequence,count,total;String hash;Runnable cancel=()->{};
        long started;
        Pending(Context context,String id) { this.context=context;this.id=id; }
    }
    public WalletWebChannel(Context owner,Predicate<Context> current,Consumer<String> send,Timer timer) {
        this.owner=java.util.Objects.requireNonNull(owner);this.current=java.util.Objects.requireNonNull(current);
        this.send=java.util.Objects.requireNonNull(send);this.timer=java.util.Objects.requireNonNull(timer);
    }
    private boolean same(Context c) { return c!=null&&owner.sessionId.equals(c.sessionId)&&owner.documentId.equals(c.documentId)&&owner.epoch.equals(c.epoch); }
    static Map<String,Object> context(Context c) { return WalletCodec.obj("sessionId",c.sessionId,"documentId",c.documentId,"epoch",c.epoch,"requestId",c.requestId); }
    static String digest(byte[] bytes)throws Exception {
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder out=new StringBuilder();
        for(byte b:digest)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();
    }
    public synchronized CompletionStage<String> exchange(Context c,String method,String arguments) {
        if(closed||!same(c)||!current.test(c)) return rejected(Code.STALE_SESSION);
        if(pending!=null)return rejected(Code.BUSY);
        if(nextId==Long.MAX_VALUE) { invalidate();return rejected(Code.REVISION_LIMIT); }
        Pending p=new Pending(c,Long.toString(++nextId));p.started=timer.now();pending=p;
        try {
            Object args=JsonTransferJson.parse(arguments,TOTAL_BYTES);
            if(!(args instanceof java.util.List))throw new WalletFailure(Code.INVALID_DATA);
            byte[] payload=WalletCodec.encode(WalletCodec.obj("method",method,"args",args),TOTAL_BYTES);
            String hash=digest(payload);int count=(payload.length+FRAME_BYTES-1)/FRAME_BYTES;
            p.cancel=timer.after(HOST_MS,()->timeout(p));
            for(int seq=0;seq<count;seq++) {
                if(closed||pending!=p||!current.test(c)||timer.now()-p.started>=HOST_MS) { invalidate();break; }
                String frame=JsonTransferJson.encode(WalletCodec.obj("v",1,"context",context(c),"id",p.id,"seq",seq,"count",count,"bytes",payload.length,"sha256",hash,
                        "data",Base64.getEncoder().encodeToString(Arrays.copyOfRange(payload,seq*FRAME_BYTES,Math.min(payload.length,(seq+1)*FRAME_BYTES)))));
                WalletCodec.utf8(frame,ENVELOPE_BYTES);send.accept(frame);
            }
        } catch(Exception failed) { invalidate(); }
        return p.result;
    }
    private static CompletionStage<String> rejected(Code code) { CompletableFuture<String> f=new CompletableFuture<>();f.completeExceptionally(new WalletFailure(code));return f; }
    private synchronized void timeout(Pending p) { if(pending==p)invalidate(); }
    public synchronized void invalidate() {
        if(closed)return;closed=true;Pending p=pending;pending=null;
        try { send.accept(JsonTransferJson.encode(WalletCodec.obj("v",1,"kind","invalidate","context",context(owner)))); } catch(Exception ignored) { /* Old document may already be gone. */ }
        if(p!=null) { p.cancel.run();p.result.completeExceptionally(new WalletFailure(Code.RECOVERY_REQUIRED)); }
    }
    /** Only the authenticated current main-frame listener may call this method. */
    public synchronized void receive(String frame) {
        Pending p=pending;if(closed||p==null)return;
        try {
            if(!current.test(p.context)||timer.now()-p.started>=HOST_MS)throw new WalletFailure(Code.STALE_SESSION);
            Map<String,Object> m=WalletCodec.parse(WalletCodec.utf8(frame,ENVELOPE_BYTES),ENVELOPE_BYTES);
            WalletCodec.keys(m,"v","context","id","seq","count","bytes","sha256","data");
            if(!p.id.equals(m.get("id")))return; // Late completed request cannot complete a successor.
            Map<String,Object> c=WalletCodec.map(m.get("context"));WalletCodec.keys(c,"sessionId","documentId","epoch","requestId");
            if(!context(p.context).equals(c)||WalletCodec.number(m.get("v"))!=1)throw new WalletFailure(Code.STALE_SESSION);
            int seq=Math.toIntExact(WalletCodec.number(m.get("seq"))),count=Math.toIntExact(WalletCodec.number(m.get("count"))),total=Math.toIntExact(WalletCodec.number(m.get("bytes")));
            String hash=WalletTypes.hash(WalletCodec.text(m.get("sha256")));
            if(total<1||total>TOTAL_BYTES||count!=(total+FRAME_BYTES-1)/FRAME_BYTES||seq!=p.sequence||seq>=count)throw new WalletFailure(Code.INVALID_DATA);
            if(seq==0) { p.count=count;p.total=total;p.hash=hash; }
            if(p.count!=count||p.total!=total||!p.hash.equals(hash))throw new WalletFailure(Code.INVALID_DATA);
            String encoded=WalletCodec.text(m.get("data"));byte[] chunk=Base64.getDecoder().decode(encoded);
            if(chunk.length!=Math.min(FRAME_BYTES,total-seq*FRAME_BYTES)||!Base64.getEncoder().encodeToString(chunk).equals(encoded))throw new WalletFailure(Code.INVALID_DATA);
            p.bytes.write(chunk);p.sequence++;
            if(p.sequence==p.count) {
                byte[] raw=p.bytes.toByteArray();if(raw.length!=total||!digest(raw).equals(hash))throw new WalletFailure(Code.HASH_MISMATCH);
                String result=WalletCodec.decode(raw,TOTAL_BYTES);
                if(timer.now()-p.started>=HOST_MS||!current.test(p.context))throw new WalletFailure(Code.STALE_SESSION);
                p.cancel.run();pending=null;p.result.complete(result);
            }
        } catch(Exception invalid) { invalidate(); }
    }
}
