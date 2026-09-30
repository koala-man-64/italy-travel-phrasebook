package com.koalaman64.italytravelpocketguide;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;
import static com.koalaman64.italytravelpocketguide.WalletTypes.*;

public class ArchiveChannelTest {
    static final Context C=ArchiveWebPortTest.C;
    @Test public void actualFramedJavaToJavaScriptRecovery()throws Exception {
        ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
        try(ArchiveWebPortTest.Node node=new ArchiveWebPortTest.Node();WalletDisk disk=new WalletDisk(Files.createTempDirectory("archive-channel-"),p->{},b->{})) {
            node.command("bind-channel",ArchiveWebPortTest.context(C));
            WalletWebChannel channel=new WalletWebChannel(C,c->true,frame->{
                try { synchronized(node) { node.input.write(JsonTransferJson.encode(WalletCodec.obj("method","channel-frame","args",Collections.singletonList(frame))));node.input.newLine();node.input.flush(); } }
                catch(Exception e) { throw new RuntimeException(e); }
            },(ms,task)->{ScheduledFuture<?> f=timer.schedule(task,ms,TimeUnit.MILLISECONDS);return ()->f.cancel(false);});
            Thread reader=new Thread(()->{try { String line;while((line=node.output.readLine())!=null)channel.receive(line); }catch(Exception e) { channel.invalidate(); }});
            reader.setDaemon(true);reader.start();
            ArchiveWebPort web=new ArchiveWebPort(channel,c->true);
            WalletStore store=new WalletStore(disk,Runnable::run,Runnable::run,(p,ms)->CompletableFuture.completedFuture(Result.failed(Code.INVALID_DOCUMENT)),web,()->1000L);
            ArchiveCoordinator coordinator=new ArchiveCoordinator(store,web,Runnable::run,c->true);
            Result<Checkpoint> baseline=coordinator.start(C).toCompletableFuture().get(10,TimeUnit.SECONDS);assertEquals(Status.OK,baseline.status);
            Result<Checkpoint> changed=coordinator.mutate(C,baseline.value.pair,"{\"type\":\"preferences\",\"slow\":true}").toCompletableFuture().get(10,TimeUnit.SECONDS);assertEquals(Status.OK,changed.status);
            Result<Checkpoint> recovered=coordinator.recover(C).toCompletableFuture().get(10,TimeUnit.SECONDS);assertEquals(Status.OK,recovered.status);assertEquals(changed.value.pair,recovered.value.pair);
            channel.invalidate();
        } finally { timer.shutdownNow(); }
    }
    @Test public void timeoutAndDuplicateFrameQuarantineChannel()throws Exception {
        for(boolean timeout:Arrays.asList(true,false)) {
            List<String> sent=new ArrayList<>();List<Runnable> timers=new ArrayList<>();
            WalletWebChannel channel=new WalletWebChannel(C,c->true,sent::add,(ms,task)->{timers.add(task);return ()->{};});
            CompletableFuture<String> result=channel.exchange(C,"enterRecovery","[]").toCompletableFuture();
            assertTrue(channel.exchange(C,"enterRecovery","[]").toCompletableFuture().isCompletedExceptionally());
            if(timeout)timers.get(0).run();
            else {
                Map<String,Object> request=WalletCodec.parse(sent.get(0).getBytes(StandardCharsets.UTF_8),WalletWebChannel.ENVELOPE_BYTES);
                byte[] raw=new byte[20000];Arrays.fill(raw,(byte)'x');
                Map<String,Object> frame=WalletCodec.obj("v",1,"context",WalletWebChannel.context(C),"id",request.get("id"),"seq",0,"count",2,"bytes",20000,"sha256",WalletWebChannel.digest(raw),"data",Base64.getEncoder().encodeToString(Arrays.copyOf(raw,16384)));
                String first=JsonTransferJson.encode(frame);channel.receive(first);channel.receive(first);
            }
            assertTrue(result.isCompletedExceptionally());
            assertTrue(channel.exchange(C,"readRecovery","[]").toCompletableFuture().isCompletedExceptionally());
        }
    }
    @Test public void elapsedDeadlineRejectsReplyBeforeTimerRuns()throws Exception {
        long[] now={0};List<String> sent=new ArrayList<>();
        WalletWebChannel channel=new WalletWebChannel(C,c->true,sent::add,new WalletWebChannel.Timer() {
            public Runnable after(long ms,Runnable action) { return ()->{}; }
            public long now() { return now[0]; }
        });
        CompletableFuture<String> result=channel.exchange(C,"enterRecovery","[]").toCompletableFuture();
        now[0]=HOST_MS;channel.receive(sent.get(0));
        assertTrue(result.isCompletedExceptionally());
        assertTrue(channel.exchange(C,"enterRecovery","[]").toCompletableFuture().isCompletedExceptionally());
    }
}
