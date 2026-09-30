package com.koalaman64.italytravelpocketguide;

import org.junit.Test;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
import static com.koalaman64.italytravelpocketguide.WalletTypes.*;

public class ArchiveLifecycleTest {
    @Test public void successorWaitsForOldWorkAndReusesTheSoleNativeStore()throws Exception {
        try(ArchiveWebPortTest.Node node=new ArchiveWebPortTest.Node();WalletDisk disk=new WalletDisk(Files.createTempDirectory("archive-lifecycle-"),p->{},b->{})) {
            AtomicInteger opens=new AtomicInteger();
            WalletLifecycle lifecycle=new WalletLifecycle(evidence->{opens.incrementAndGet();return new WalletStore(disk,Runnable::run,Runnable::run,(p,ms)->CompletableFuture.completedFuture(Result.failed(Code.INVALID_DOCUMENT)),evidence,()->1000L);},Runnable::run);
            Context old=new Context("s","old","0","r"),skipped=new Context("s","skipped","1","r"),next=new Context("s","new","2","r");
            CompletableFuture<String> blocked=new CompletableFuture<>();
            ArchiveWebPort oldWeb=new ArchiveWebPort((c,m,a)->blocked,c->true);
            CompletableFuture<Result<Checkpoint>> oldReady=lifecycle.bind(old,oldWeb).toCompletableFuture();
            CompletableFuture<Result<Checkpoint>> skippedReady=lifecycle.bind(skipped,new ArchiveWebPort((c,m,a)->{throw new AssertionError("Skipped session dispatched");},c->true)).toCompletableFuture();
            CompletableFuture<Result<Checkpoint>> nextReady=lifecycle.bind(next,new ArchiveWebPort(node,c->true)).toCompletableFuture();
            assertFalse(nextReady.isDone());assertEquals(Status.UNCERTAIN,skippedReady.join().status);assertEquals(1,opens.get());
            blocked.complete("{\"status\":\"OK\",\"value\":null}");
            assertEquals(Status.UNCERTAIN,oldReady.join().status);assertEquals(Status.OK,nextReady.join().status);assertEquals(1,opens.get());
            assertFalse(lifecycle.verifyPair(old,nextReady.join().value.pair).toCompletableFuture().join());
            lifecycle.invalidate(old); // An old Activity cannot detach its successor.
            assertEquals(1,opens.get());
        }
    }
}
