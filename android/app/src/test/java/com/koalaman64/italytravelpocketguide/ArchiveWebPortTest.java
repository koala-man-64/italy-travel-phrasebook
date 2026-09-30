package com.koalaman64.italytravelpocketguide;

import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;
import static com.koalaman64.italytravelpocketguide.WalletTypes.*;

public class ArchiveWebPortTest {
    static final Context C=new Context("session","document","0","request");
    static <T>T ok(CompletionStage<Result<T>> s) { Result<T> r=s.toCompletableFuture().join();assertEquals(String.valueOf(r.code),Status.OK,r.status);return r.value; }
    static Object context(Context c) { return WalletCodec.obj("sessionId",c.sessionId,"documentId",c.documentId,"epoch",c.epoch,"requestId",c.requestId); }
    static class Node implements AutoCloseable,ArchiveWebPort.Transport {
        final Process p;final BufferedWriter input;final BufferedReader output;
        Node()throws Exception { p=new ProcessBuilder("node","tests/fixtures/archive-user-data.cjs","--production-port").redirectError(ProcessBuilder.Redirect.INHERIT).start();input=new BufferedWriter(new OutputStreamWriter(p.getOutputStream(),StandardCharsets.UTF_8));output=new BufferedReader(new InputStreamReader(p.getInputStream(),StandardCharsets.UTF_8)); }
        synchronized String command(String method,Object... args)throws Exception { input.write(JsonTransferJson.encode(WalletCodec.obj("method",method,"args",Arrays.asList(args))));input.newLine();input.flush();String s=output.readLine();if(s==null)throw new IOException("Node exited");return s; }
        public CompletionStage<String> exchange(Context c,String method,String args) {
            try { return CompletableFuture.completedFuture(method.equals("enterRecovery")?command(method,context(c)):command("port",context(c),method,JsonTransferJson.parse(args,8*1024*1024))); }
            catch(Exception e) { CompletableFuture<String> f=new CompletableFuture<>();f.completeExceptionally(e);return f; }
        }
        public void close()throws Exception { input.close();p.waitFor(5,TimeUnit.SECONDS);p.destroy();output.close(); }
    }
    @Test public void realJavaAndJavaScriptAdaptersBootstrapMutateAndRecover()throws Exception {
        try(Node node=new Node();WalletDisk disk=new WalletDisk(Files.createTempDirectory("archive-port-"),p->{},b->{})) {
            Identity existing=WalletCodec.identity(WalletCodec.parse(node.command("init").getBytes(StandardCharsets.UTF_8),USER_BYTES).get("value"));
            ArchiveWebPort web=new ArchiveWebPort(node,c->true);
            WalletStore nativeStore=new WalletStore(disk,Runnable::run,Runnable::run,(p,ms)->CompletableFuture.completedFuture(Result.failed(Code.INVALID_DOCUMENT)),web,()->1000L);
            ArchiveCoordinator coordinator=new ArchiveCoordinator(nativeStore,web,Runnable::run,c->true);
            Checkpoint baseline=ok(coordinator.bootstrap(C,existing));assertEquals(Outcome.BASELINE,baseline.outcome);
            Checkpoint changed=ok(coordinator.mutate(C,baseline.pair,"{\"type\":\"preferences\",\"slow\":true}"));
            assertFalse(baseline.pair.equals(changed.pair));
            Checkpoint recovered=ok(coordinator.recover(C));assertEquals(changed.pair,recovered.pair);
            node.command("fault",WalletCodec.obj("key",".manifest","after",true));
            Result<Checkpoint> lost=coordinator.mutate(C,recovered.pair,"{\"type\":\"preferences\",\"slow\":false}").toCompletableFuture().join();
            assertEquals(Status.UNCERTAIN,lost.status);
            Checkpoint repaired=ok(coordinator.recover(C));assertEquals("2",repaired.pair.web.revision);
        }
    }
    @Test public void fabricatedFenceAndLateReplyCannotBecomeEvidence() {
        CompletableFuture<String> response=new CompletableFuture<>();AtomicBoolean current=new AtomicBoolean(true);
        ArchiveWebPort web=new ArchiveWebPort((c,m,a)->response,c->current.get());
        assertEquals(Code.STALE_SESSION,web.readRecovery(new Object()).toCompletableFuture().join().code);
        CompletionStage<Result<Object>> pending=web.enterRecovery(C);
        assertEquals(Code.BUSY,web.enterRecovery(C).toCompletableFuture().join().code);
        current.set(false);web.invalidate();response.complete("{\"status\":\"OK\",\"value\":null}");
        assertEquals(Status.UNCERTAIN,pending.toCompletableFuture().join().status);
    }
    @Test public void startupInitializesOnlyUnderEmptyNativeAuthority()throws Exception {
        for(boolean existing:Arrays.asList(false,true))try(Node node=new Node();WalletDisk disk=new WalletDisk(Files.createTempDirectory("archive-start-"),p->{},b->{})) {
            node.command("legacy",WalletCodec.obj("itguide.slow","true","itguide.builder","{ \"private\": \"legacy raw\" }"));
            if(existing)node.command("init");
            ArchiveWebPort web=new ArchiveWebPort(node,c->true);
            WalletStore nativeStore=new WalletStore(disk,Runnable::run,Runnable::run,(p,ms)->CompletableFuture.completedFuture(Result.failed(Code.INVALID_DOCUMENT)),web,()->1000L);
            ArchiveCoordinator coordinator=new ArchiveCoordinator(nativeStore,web,Runnable::run,c->true);
            Checkpoint baseline=ok(coordinator.start(C));assertEquals(Outcome.BASELINE,baseline.outcome);
            assertEquals("0",baseline.pair.web.revision);assertNull(baseline.pair.nativeIdentity);
            Checkpoint again=ok(coordinator.start(C));assertEquals(baseline.pair,again.pair);
            node.command("corrupt-all");String before=node.command("raw");
            assertEquals(Status.UNCERTAIN,coordinator.start(C).toCompletableFuture().join().status);
            assertEquals(before,node.command("raw")); // Existing native checkpoint forbids recreating web data.
        }
    }
    @Test public void uncertainOrMalformedReplyNeverBecomesOrdinaryFailureOrSuccess() {
        for(String reply:Arrays.asList("{\"status\":\"UNCERTAIN\"}","{\"status\":\"OK\",\"extra\":true}","{\"status\":\"OK\",\"status\":\"FAILED\"}")) {
            ArchiveWebPort web=new ArchiveWebPort((c,m,a)->CompletableFuture.completedFuture(reply),c->true);
            assertEquals(Status.UNCERTAIN,web.enterRecovery(C).toCompletableFuture().join().status);
        }
    }
    @Test public void firstManifestLostAcknowledgmentDoesNotRecreateBaseline()throws Exception {
        try(Node node=new Node();WalletDisk disk=new WalletDisk(Files.createTempDirectory("archive-first-ack-"),p->{},b->{})) {
            ArchiveWebPort web=new ArchiveWebPort(node,c->true);
            WalletStore nativeStore=new WalletStore(disk,Runnable::run,Runnable::run,(p,ms)->CompletableFuture.completedFuture(Result.failed(Code.INVALID_DOCUMENT)),web,()->1000L);
            ArchiveCoordinator coordinator=new ArchiveCoordinator(nativeStore,web,Runnable::run,c->true);
            node.command("fault",WalletCodec.obj("key",".manifest","after",true));
            assertEquals(Status.UNCERTAIN,coordinator.start(C).toCompletableFuture().join().status);
            String retained=node.command("raw");
            Checkpoint recovered=ok(coordinator.start(C));assertEquals("0",recovered.pair.web.revision);
            assertEquals(retained,node.command("raw"));
        }
    }
}
