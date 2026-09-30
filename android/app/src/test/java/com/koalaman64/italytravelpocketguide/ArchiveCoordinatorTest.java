package com.koalaman64.italytravelpocketguide;

import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import static org.junit.Assert.*;
import static com.koalaman64.italytravelpocketguide.WalletTypes.*;

public class ArchiveCoordinatorTest {
    static final Context CTX=new Context("session","document","0","request");
    static <T>T ok(CompletionStage<Result<T>> future){Result<T> r=future.toCompletableFuture().join();assertEquals(String.valueOf(r.code),Status.OK,r.status);return r.value;}
    static class Web implements ArchiveCoordinator.WebPort, WalletStore.WebEvidence, AutoCloseable {
        final Process process; final BufferedReader reader;final BufferedWriter writer;
        Web()throws Exception{process=new ProcessBuilder("node","tests/fixtures/archive-user-data.cjs").redirectError(ProcessBuilder.Redirect.INHERIT).start();reader=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8));writer=new BufferedWriter(new OutputStreamWriter(process.getOutputStream(),StandardCharsets.UTF_8));}
        synchronized Map<String,Object> command(String method,Object... args)throws Exception{
            writer.write(JsonTransferJson.encode(JsonTransferJson.object("method",method,"args",Arrays.asList(args))));writer.newLine();writer.flush();
            String line=reader.readLine();if(line==null)throw new IOException("Node exited");return JsonTransferProtocol.map(JsonTransferJson.parse(line,3*1024*1024));
        }
        <T>CompletionStage<Result<T>> call(String method,Function<Object,T> convert,Object... args){
            try{Map<String,Object> m=command(method,args);if(!"OK".equals(m.get("status"))){Code code;try{code=Code.valueOf((String)m.get("code"));}catch(Exception e){code=Code.RECOVERY_REQUIRED;}return CompletableFuture.completedFuture(Result.failed(code));}
                return CompletableFuture.completedFuture(Result.ok(convert.apply(m.get("value"))));
            }catch(Exception e){return CompletableFuture.completedFuture(Result.failed(Code.IO_ERROR));}
        }
        static Map<String,Object> map(Object v){try{return JsonTransferProtocol.map(v);}catch(Exception e){throw new RuntimeException(e);}}
        static Identity identity(Object v){try{return WalletCodec.identity(v);}catch(Exception e){throw new RuntimeException(e);}}
        static Receipt receipt(Object v){Map<String,Object> m=map(v);return new Receipt((String)m.get("transactionId"),operation((String)m.get("operation")),Store.WEB,ReceiptPhase.valueOf((String)m.get("phase")),identity(m.get("prior")),identity(m.get("candidate")));}
        static Operation operation(String s){return "web-mutation".equals(s)?Operation.WEB_MUTATION:Operation.valueOf(s.toUpperCase(Locale.ROOT));}
        static String op(Operation o){return o==Operation.WEB_MUTATION?"web-mutation":o.name().toLowerCase(Locale.ROOT);}
        static Object wireReceipt(Receipt r){return JsonTransferJson.object("v",1,"transactionId",r.transactionId,"operation",op(r.operation),"store","web","phase",r.phase.name(),"prior",WalletCodec.identity(r.prior),"candidate",WalletCodec.identity(r.candidate));}
        public CompletionStage<Result<Object>> enterRecovery(Context c){return call("enterRecovery",v->v,JsonTransferJson.object("sessionId",c.sessionId,"documentId",c.documentId,"epoch",c.epoch,"requestId",c.requestId));}
        public CompletionStage<Result<ArchiveCoordinator.WebRecovery>> readRecovery(Object f){return call("readRecovery",v->{Map<String,Object> m=map(v);return new ArchiveCoordinator.WebRecovery(identity(m.get("active")),identity(m.get("previous")),(String)m.get("transactionId"));});}
        public CompletionStage<Result<String>> captureExact(Object f,Identity i){return call("captureExact",v->(String)v,WalletCodec.identity(i));}
        public CompletionStage<Result<String>> prepareCandidate(Object f,Operation o,Identity p,Identity n,String raw){return call("prepareCandidate",v->(String)v,op(o),WalletCodec.identity(p),WalletCodec.identity(n),raw);}
        public CompletionStage<Result<Receipt>> stageInactive(Object f,String tx,Operation o,Identity p,String raw,Identity n,String seq){return call("stageInactive",Web::receipt,tx,op(o),WalletCodec.identity(p),raw,WalletCodec.identity(n),seq);}
        public CompletionStage<Result<Receipt>> readStaged(Object f,String tx){return call("readStaged",Web::receipt,tx);}
        public CompletionStage<Result<ArchiveCoordinator.WebEscrow>> exportStage(Object f,String tx){return call("exportStage",v->{Map<String,Object> m=map(v);return new ArchiveCoordinator.WebEscrow((String)m.get("raw"),(String)m.get("sha256"));},tx);}
        public CompletionStage<Result<Void>> restoreEscrow(Object f,String raw,String hash){return call("restoreEscrow",v->null,raw,hash);}
        public CompletionStage<Result<Receipt>> activateStaged(Object f,String tx,Journal j){return call("activateStaged",Web::receipt,tx,WalletCodec.journal(j));}
        public CompletionStage<Result<Identity>> restorePrevious(Object f,String tx,RecoveryDecision d){return call("restorePrevious",Web::identity,tx,WalletCodec.decision(d));}
        public CompletionStage<Result<Identity>> verifyActive(Object f,Identity i){return call("verifyActive",Web::identity,WalletCodec.identity(i));}
        public CompletionStage<Result<Void>> releaseReady(Object f,Checkpoint cp,Identity n){return call("releaseReady",v->null,WalletCodec.checkpoint(cp),WalletCodec.identity(n));}
        public CompletionStage<Result<Void>> discardStage(Object f,String tx,Checkpoint cp,Checkpoint initial){return call("discardStage",v->null,tx,WalletCodec.checkpoint(cp),WalletCodec.checkpoint(initial));}
        public CompletionStage<Boolean> verifyReceipt(Context c,Receipt r,String raw){return call("verifyReceipt",Boolean.TRUE::equals,wireReceipt(r),raw).thenApply(result->result.status==Status.OK&&result.value);}
        public CompletionStage<Boolean> verifyPair(Context c,Pair p){return call("verifyPair",Boolean.TRUE::equals,WalletCodec.pair(p)).thenApply(result->result.status==Status.OK&&result.value);}
        public CompletionStage<Boolean> verifyRecoveryPair(Context c,Pair p){return call("verifyRecoveryPair",Boolean.TRUE::equals,WalletCodec.pair(p)).thenApply(result->result.status==Status.OK&&result.value);}
        public void close()throws Exception{writer.close();process.waitFor(5,TimeUnit.SECONDS);process.destroy();reader.close();}
    }
    static class Fixture implements AutoCloseable {
        final Web web;final Path directory=Files.createTempDirectory("archive-real-stores-");final WalletDisk disk;
        final AtomicReference<String> fault=new AtomicReference<>();int skipFault;WalletStore nativeStore;ArchiveCoordinator coordinator;Checkpoint checkpoint;
        Fixture()throws Exception{this(new Web());}
        Fixture(Web web)throws Exception{
            this.web=web;
            disk=new WalletDisk(directory,p->{},b->{String expected=fault.get();if(expected!=null&&b.equals(expected)){if(skipFault>0){skipFault--;return;}if(fault.compareAndSet(expected,null))throw new IOException("injected");}});
            restartNative();Identity existing=ok(web.call("init",Web::identity));checkpoint=ok(coordinator.bootstrap(CTX,existing));
        }
        void restartNative(){nativeStore=new WalletStore(disk,Runnable::run,Runnable::run,(p,ms)->CompletableFuture.completedFuture(Result.ok(new WalletStore.Decoded("png",Collections.singletonList(new PageSize(1,1)),1))),web,()->1000L);coordinator=new ArchiveCoordinator(nativeStore,web,Runnable::run,c->true);}
        Object snapshot()throws Exception{return Web.map(web.command("state").get("value")).get("snapshot");}
        public void close()throws Exception{web.close();disk.close();}
    }
    @Test public void bootstrapExistingRealUserDataAndConsecutiveWebMutations()throws Exception{
        try(Fixture f=new Fixture()){
            assertEquals(Outcome.BASELINE,f.checkpoint.outcome);assertNotNull(f.snapshot());
            Checkpoint one=ok(f.coordinator.mutate(CTX,f.checkpoint.pair,"{\"type\":\"preferences\",\"slow\":true}"));
            Checkpoint two=ok(f.coordinator.mutate(CTX,one.pair,"{\"type\":\"review\",\"phraseId\":\"phrase_test\",\"reviewedAtUtc\":\"2030-06-10T12:00:00Z\"}"));
            assertEquals("2",two.pair.web.revision);assertNull(two.pair.nativeIdentity);assertNotNull(f.snapshot());
            f.web.command("restart");f.restartNative();Checkpoint recovered=ok(f.coordinator.recover(CTX));assertEquals(two.pair,recovered.pair);assertNotNull(f.snapshot());
        }
    }
    @Test public void realWebManifestLostAckRecoversPreparedForwardWithoutMixedSnapshot()throws Exception{
        try(Fixture f=new Fixture()){
            f.web.command("fault",JsonTransferJson.object("key",".manifest","after",true));
            Result<Checkpoint> failed=f.coordinator.mutate(CTX,f.checkpoint.pair,"{\"type\":\"preferences\",\"slow\":true}").toCompletableFuture().join();
            assertEquals(Status.UNCERTAIN,failed.status);assertNull(f.snapshot());
            f.web.command("restart");f.restartNative();Checkpoint recovered=ok(f.coordinator.recover(CTX));assertEquals("1",recovered.pair.web.revision);assertNotNull(f.snapshot());
        }
    }
    @Test public void beginWithoutShadowRollsBackActualStores()throws Exception{
        try(Fixture f=new Fixture()){
            f.web.command("fault",JsonTransferJson.object("key",".transaction","after",false));
            Result<Checkpoint> failed=f.coordinator.mutate(CTX,f.checkpoint.pair,"{\"type\":\"preferences\",\"slow\":true}").toCompletableFuture().join();
            assertEquals(Status.UNCERTAIN,failed.status);assertNull(f.snapshot());
            f.web.command("restart");f.restartNative();Checkpoint recovered=ok(f.coordinator.recover(CTX));assertEquals(f.checkpoint.pair,recovered.pair);assertEquals(Outcome.RECOVERED_OLD,recovered.outcome);
        }
    }
    @Test public void bootstrapNeverOverridesExistingNativeCheckpoint()throws Exception{
        try(Fixture f=new Fixture()){
            Result<Checkpoint> r=f.coordinator.bootstrap(CTX,f.checkpoint.pair.web).toCompletableFuture().join();assertEquals(Status.UNCERTAIN,r.status);assertNull(f.snapshot());
        }
    }
    @Test public void actualJournalAndCheckpointCrashBoundariesRecoverWithoutMixedPair()throws Exception{
        int exercised=0;
        for(String file:Arrays.asList("journal.json","active.json","checkpoint.json"))
            for(String boundary:Arrays.asList("before-write","after-write","after-sync","after-replace","after-dir-sync","after-readback"))
                for(int phase=0;phase<(file.equals("journal.json")?3:1);phase++){
                    try(Fixture f=new Fixture()){
                        f.fault.set(file+":"+boundary);f.skipFault=phase;
                        Result<Checkpoint> stopped=f.coordinator.mutate(CTX,f.checkpoint.pair,"{\"type\":\"preferences\",\"slow\":true}").toCompletableFuture().join();
                        assertEquals(file+":"+boundary+":"+phase,Status.UNCERTAIN,stopped.status);assertNull(f.snapshot());assertNull(f.fault.get());
                        f.web.command("restart");f.restartNative();Checkpoint recovered=ok(f.coordinator.recover(CTX));
                        assertTrue(recovered.pair.web.revision.equals("0")||recovered.pair.web.revision.equals("1"));assertNull(recovered.pair.nativeIdentity);assertNotNull(f.snapshot());
                        // Repeated recovery is idempotent and cannot replay a mutation.
                        f.web.command("restart");f.restartNative();assertEquals(recovered.pair,ok(f.coordinator.recover(CTX)).pair);exercised++;
                    }
                }
        assertEquals(30,exercised);
    }
    @Test public void explicitPreparedRollbackRestoresCompleteBeforeImage()throws Exception{
        try(Fixture f=new Fixture()){
            Map<String,Object> before=Web.map(f.web.command("raw").get("value"));
            f.web.command("fault",JsonTransferJson.object("key",".manifest","after",true));
            assertEquals(Status.UNCERTAIN,f.coordinator.mutate(CTX,f.checkpoint.pair,"{\"type\":\"preferences\",\"slow\":true}").toCompletableFuture().join().status);
            f.web.command("restart");f.restartNative();Checkpoint recovered=ok(f.coordinator.recoverPrevious(CTX));assertEquals(f.checkpoint.pair,recovered.pair);
            Map<String,Object> after=Web.map(f.web.command("raw").get("value"));for(String key:before.keySet())assertEquals(before.get(key),after.get(key));
        }
    }
    @Test public void failedRecoveryWithMissingActiveAndRetainedImagesRemainsGated()throws Exception{
        try(Fixture f=new Fixture()){
            f.web.command("fault",JsonTransferJson.object("key",".transaction","after",false));
            assertEquals(Status.UNCERTAIN,f.coordinator.mutate(CTX,f.checkpoint.pair,"{\"type\":\"preferences\",\"slow\":true}").toCompletableFuture().join().status);
            f.web.command("corrupt-all");f.web.command("restart");f.restartNative();
            assertEquals(Status.UNCERTAIN,f.coordinator.recover(CTX).toCompletableFuture().join().status);assertNull(f.snapshot());assertTrue(f.coordinator.recoveryRequired());
        }
    }
    static class DelayedReadyWeb extends Web {
        volatile boolean delay;volatile Checkpoint published;volatile Result<Void> reply;
        final CompletableFuture<Result<Void>> acknowledgement=new CompletableFuture<>();
        final CountDownLatch reached=new CountDownLatch(1);
        DelayedReadyWeb()throws Exception{}
        @Override public CompletionStage<Result<Void>> releaseReady(Object fence,Checkpoint cp,Identity nativeIdentity){
            CompletionStage<Result<Void>> actual=super.releaseReady(fence,cp,nativeIdentity);
            if(!delay)return actual;
            published=cp;reply=actual.toCompletableFuture().join();reached.countDown();return acknowledgement;
        }
    }
    @Test public void checkpointPublicationAndAdmissionHaveOneMonitorTransition()throws Exception{
        DelayedReadyWeb web=new DelayedReadyWeb();
        try(Fixture f=new Fixture(web)){
            web.delay=true;CompletionStage<Result<Checkpoint>> pending=f.coordinator.mutate(CTX,f.checkpoint.pair,"{\"type\":\"preferences\",\"slow\":true}");
            assertTrue(web.reached.await(5,TimeUnit.SECONDS));assertFalse(pending.toCompletableFuture().isDone());
            assertEquals(Code.BUSY,f.coordinator.export(CTX,web.published.pair,(raw,snapshot,lease)->CompletableFuture.completedFuture(null)).toCompletableFuture().join().code);
            java.lang.reflect.Field busy=ArchiveCoordinator.class.getDeclaredField("busy"),known=ArchiveCoordinator.class.getDeclaredField("knownCheckpoint");busy.setAccessible(true);known.setAccessible(true);
            CountDownLatch waiting=new CountDownLatch(1);AtomicReference<Throwable> failure=new AtomicReference<>();
            Thread contender=new Thread(()->{waiting.countDown();try{
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(System.nanoTime()<deadline){synchronized(f.coordinator){if(!busy.getBoolean(f.coordinator)){
                    assertEquals(web.published.pair,((Checkpoint)known.get(f.coordinator)).pair);return;
                }}Thread.yield();}throw new AssertionError("admission did not reopen");
            }catch(Throwable e){failure.set(e);}});
            contender.start();assertTrue(waiting.await(5,TimeUnit.SECONDS));web.delay=false;web.acknowledgement.complete(web.reply);
            assertEquals(web.published.pair,ok(pending).pair);contender.join(5000);assertFalse(contender.isAlive());if(failure.get()!=null)throw new AssertionError(failure.get());
            ok(f.coordinator.export(CTX,web.published.pair,(raw,snapshot,lease)->CompletableFuture.completedFuture(null)));
        }
    }
}
