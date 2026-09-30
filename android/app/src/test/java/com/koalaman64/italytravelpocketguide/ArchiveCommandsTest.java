package com.koalaman64.italytravelpocketguide;

import org.junit.Test;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import static org.junit.Assert.*;
import static com.koalaman64.italytravelpocketguide.WalletTypes.*;

public class ArchiveCommandsTest {
    static final Context C=ArchiveWebPortTest.C;
    static String request(String id,String revision,Object change) { return JsonTransferJson.encode(WalletCodec.obj("v",1,"kind","mutate","context",WalletWebChannel.context(C),"id",id,"expectedRevision",revision,"change",change)); }
    static Map<String,Object> response(WalletCommands commands,String raw)throws Exception { return WalletCodec.parse(commands.command(raw).toCompletableFuture().join().getBytes(StandardCharsets.UTF_8),102400); }
    @Test public void personalCommandsUseActualCoordinatorAndRejectReplayAndStaleRevision()throws Exception {
        try(ArchiveWebPortTest.Node node=new ArchiveWebPortTest.Node();WalletDisk disk=new WalletDisk(Files.createTempDirectory("archive-commands-"),p->{},b->{})) {
            ArchiveWebPort web=new ArchiveWebPort(node,c->true);
            WalletStore store=new WalletStore(disk,Runnable::run,Runnable::run,(p,ms)->CompletableFuture.completedFuture(Result.failed(Code.INVALID_DOCUMENT)),web,()->1000L);
            WalletCommands commands=new WalletCommands(C,new ArchiveCoordinator(store,web,Runnable::run,c->true),c->true);
            assertEquals(Status.OK,commands.start().toCompletableFuture().join().status);
            String first=request("1","0",WalletCodec.obj("type","preferences","slow",true));
            Map<String,Object> saved=response(commands,first);assertEquals("SAVED",saved.get("status"));assertEquals("1",saved.get("revision"));
            assertEquals("FAILED",response(commands,first).get("status"));
            assertEquals("REVISION_CONFLICT",response(commands,request("2","0",WalletCodec.obj("type","preferences","slow",false))).get("code"));
            assertEquals("INVALID_REQUEST",response(commands,request("3","1",WalletCodec.obj("type","native-binding"))).get("code"));
            assertEquals("SAVED",response(commands,request("4","1",WalletCodec.obj("type","review","phraseId","phrase_test","reviewedAtUtc","2030-06-10T12:00:00Z"))).get("status"));
            commands.invalidate();assertEquals("STALE_SESSION",response(commands,request("5","2",WalletCodec.obj("type","preferences","slow",false))).get("code"));
        }
    }
}
