package com.koalaman64.italytravelpocketguide;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import static com.koalaman64.italytravelpocketguide.WalletTypes.*;

/** Process-scoped ownership of the native store. A successor waits for the old
 * operation to actually settle; navigation never creates a replacement worker
 * or releases native file ownership. The supplied executor must be bounded.
 */
public final class WalletLifecycle implements WalletStore.WebEvidence {
    public interface Factory { WalletNativePort open(WalletStore.WebEvidence evidence)throws Exception; }
    private final Factory factory;private final Executor worker;private final LongSupplier clock;
    private WalletNativePort nativePort;
    private Context nativeContext;
    private WalletCommands retiringCommands;
    private volatile Session latest;
    private boolean busy;
    private volatile boolean halted;
    private static final class Session {
        final Context context;final ArchiveWebPort web;final WalletCommands.Host host;
        final CompletableFuture<Result<Checkpoint>> ready=new CompletableFuture<>();
        WalletCommands commands;boolean started;volatile boolean closed;
        Session(Context context,ArchiveWebPort web,WalletCommands.Host host) { this.context=context;this.web=web;this.host=host; }
    }
    public WalletLifecycle(Factory factory,Executor worker) { this(factory,worker,()->System.nanoTime()/1000000L); }
    public WalletLifecycle(Factory factory,Executor worker,LongSupplier clock) { this.factory=factory;this.worker=worker;this.clock=clock; }
    private static boolean same(Context a,Context b) { return a!=null&&b!=null&&a.sessionId.equals(b.sessionId)&&a.documentId.equals(b.documentId)&&a.epoch.equals(b.epoch)&&a.requestId.equals(b.requestId); }
    // Called while command/adapter monitors may be held: never take the lifecycle
    // monitor here, which would invert main-thread retirement lock ordering.
    private boolean current(Session s) { return !halted&&latest==s&&!s.closed; }
    private void retire(Session s) { s.closed=true;s.web.invalidate();if(s.commands!=null){s.commands.invalidate();retiringCommands=s.commands;}if(!s.started)s.ready.complete(Result.uncertain(null)); }
    public synchronized CompletionStage<Result<Checkpoint>> bind(Context context,ArchiveWebPort web) { return bind(context,web,null); }
    public synchronized CompletionStage<Result<Checkpoint>> bind(Context context,ArchiveWebPort web,WalletCommands.Host host) {
        if(halted)return CompletableFuture.completedFuture(Result.uncertain(null));
        if(latest!=null&&same(latest.context,context))return CompletableFuture.completedFuture(Result.failed(Code.BUSY));
        if(latest!=null)retire(latest);
        Session s=new Session(context,web,host);latest=s;schedule();return s.ready;
    }
    public synchronized void invalidate(Context context) {
        if(latest==null||!same(latest.context,context))return;
        retire(latest);latest=null;schedule();
    }
    private void schedule() {
        try { worker.execute(this::advance); }
        catch(RuntimeException rejected) { synchronized(this) { halted=true;if(latest!=null) { retire(latest);latest.ready.complete(Result.uncertain(null)); } } }
    }
    private void advance() {
        final Session target;final Context previous;final WalletCommands retiring;
        synchronized(this) {
            if(halted||busy)return;
            target=latest;
            if(target!=null&&target.started)return;
            if(target==null&&nativeContext==null)return;
            busy=true;previous=nativeContext;retiring=retiringCommands;
        }
        // Opening the disk may block; never hold the main-thread admission lock.
        try { if(nativePort==null)nativePort=factory.open(this); }
        catch(Exception failure) { failTransition(target);return; }
        if(retiring!=null&&retiring.retireArchive().status!=Status.OK){failTransition(target);return;}
        CompletionStage<Result<Void>> released;
        try { released=previous==null?CompletableFuture.completedFuture(Result.ok(null)):nativePort.invalidateContext(previous); }
        catch(RuntimeException failed) { failTransition(target);return; }
        released.whenComplete((result,error)->{
            synchronized(this) {
                if(error!=null||result==null||result.status!=Status.OK) { failTransition(target);return; }
                nativeContext=null;if(retiringCommands==retiring)retiringCommands=null;
                if(target==null||!current(target)) { busy=false;if(target!=null)target.ready.complete(Result.uncertain(null));schedule();return; }
                nativeContext=target.context;target.started=true;
                ArchiveCoordinator coordinator=new ArchiveCoordinator(nativePort,target.web,worker,c->current(target));
                ArchiveProtocol archive=new ArchiveProtocol(coordinator,nativePort,worker,clock,c->current(target));
                target.commands=new WalletCommands(target.context,coordinator,c->current(target),nativePort,archive,target.host,worker);
            }
            try { target.commands.start().whenComplete((started,failed)->{
                synchronized(this) {
                    busy=false;target.ready.complete(failed==null&&current(target)?started:Result.uncertain(null));
                    if(latest!=target)schedule();
                }
            }); }catch(RuntimeException failed) { failTransition(target); }
        });
    }
    private synchronized void failTransition(Session target) {
        halted=true;busy=false;if(target!=null) { retire(target);target.ready.complete(Result.uncertain(null)); }
        if(latest!=null&&latest!=target) { retire(latest);latest.ready.complete(Result.uncertain(null)); }
    }
    public synchronized CompletionStage<String> command(Context context,String raw) {
        Session s=latest;
        if(s==null||!same(context,s.context)||!current(s)||s.commands==null||busy) {
            CompletableFuture<String> rejected=new CompletableFuture<>();rejected.completeExceptionally(new WalletFailure(Code.RECOVERY_REQUIRED));return rejected;
        }
        busy=true;
        try { return s.commands.command(raw).whenComplete((r,e)->{
            synchronized(this) { busy=false;if(latest!=s)schedule(); }
        }); }catch(RuntimeException failed) { failTransition(s);CompletableFuture<String> rejected=new CompletableFuture<>();rejected.completeExceptionally(failed);return rejected; }
    }
    private synchronized ArchiveWebPort evidence(Context context) { return latest!=null&&current(latest)&&same(context,latest.context)?latest.web:null; }
    @Override public CompletionStage<Boolean> verifyPair(Context context,Pair pair) { ArchiveWebPort web=evidence(context);return web==null?CompletableFuture.completedFuture(false):web.verifyPair(context,pair); }
    @Override public CompletionStage<Boolean> verifyRecoveryPair(Context context,Pair pair) { ArchiveWebPort web=evidence(context);return web==null?CompletableFuture.completedFuture(false):web.verifyRecoveryPair(context,pair); }
    @Override public CompletionStage<Boolean> verifyReceipt(Context context,Receipt receipt,String raw) { ArchiveWebPort web=evidence(context);return web==null?CompletableFuture.completedFuture(false):web.verifyReceipt(context,receipt,raw); }
}
