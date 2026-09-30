package com.koalaman64.italytravelpocketguide;

import org.junit.Test;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import static org.junit.Assert.*;
import static com.koalaman64.italytravelpocketguide.WalletTypes.*;

/** Wire checks against the actual native store and Node-backed UserData writer. */
public class ArchiveWalletCommandsTest {
    private static final Context C=ArchiveWebPortTest.C;
    private static class Host implements WalletCommands.Host {
        public boolean supported(){return true;}
        public boolean isCurrentRelation(Ref relation){return true;}
        public CompletionStage<Result<PickerTicket>> importDocument(Context c){return CompletableFuture.completedFuture(Result.failed(Code.CANCELLED));}
        public CompletionStage<Result<PickerTicket>> importArchive(Context c){return CompletableFuture.completedFuture(Result.failed(Code.CANCELLED));}
        public CompletionStage<Result<OutputStream>> exportArchive(Context c){return CompletableFuture.completedFuture(Result.failed(Code.CANCELLED));}
        public CompletionStage<Result<Void>> openDocument(Context c,WalletNativePort p,Identity i,String id){return CompletableFuture.completedFuture(Result.failed(Code.NOT_FOUND));}
    }
    private static String packet(String id,String revision,String op,Object args) {
        return JsonTransferJson.encode(WalletCodec.obj("v",1,"kind","wallet","context",WalletWebChannel.context(C),
                "id",id,"op",op,"expected",WalletCodec.obj("userRevision",revision,"walletGenerationId",null,"walletRevision",null),"args",args));
    }
    private static Map<String,Object> send(WalletCommands c,String raw)throws Exception {
        return WalletCodec.parse(c.command(raw).toCompletableFuture().join().getBytes(StandardCharsets.UTF_8),WalletWebChannel.ENVELOPE_BYTES);
    }
    @Test public void snapshotAndSharedIdStreamUseVerifiedStore()throws Exception {
        try(ArchiveWebPortTest.Node node=new ArchiveWebPortTest.Node();WalletDisk disk=new WalletDisk(Files.createTempDirectory("archive-wallet-command-"),p->{},b->{})) {
            ArchiveWebPort web=new ArchiveWebPort(node,c->true);
            WalletStore store=new WalletStore(disk,Runnable::run,Runnable::run,(p,ms)->CompletableFuture.completedFuture(Result.failed(Code.INVALID_DOCUMENT)),web,()->1000L);
            ArchiveCoordinator coordinator=new ArchiveCoordinator(store,web,Runnable::run,c->true);
            ArchiveProtocol protocol=new ArchiveProtocol(coordinator,store,Runnable::run,()->1000L,c->true);
            WalletCommands commands=new WalletCommands(C,coordinator,c->true,store,protocol,new Host(),Runnable::run);
            assertEquals(Status.OK,commands.start().toCompletableFuture().join().status);
            Map<String,Object> snapshot=send(commands,packet("1","0","snapshot",WalletCodec.obj()));
            assertEquals("OK",snapshot.get("status"));
            Map<String,Object> value=WalletCodec.map(snapshot.get("value"));
            assertEquals("0",value.get("userRevision"));assertNull(value.get("walletGenerationId"));assertNull(value.get("walletRevision"));
            assertTrue(((java.util.List<?>)value.get("documents")).isEmpty());
            assertEquals("INVALID_REQUEST",send(commands,packet("1","0","snapshot",WalletCodec.obj())).get("code"));
            assertEquals("REVISION_CONFLICT",send(commands,packet("2","1","snapshot",WalletCodec.obj())).get("code"));
            assertEquals("REFERENCE_MISMATCH",send(commands,packet("3","0","attach",WalletCodec.obj("relation",WalletCodec.obj("tripId","trip_a","eventId","event_a","documentId","doc_00000000000000000000000000000000")))).get("code"));
            Map<String,Object> mutation=send(commands,ArchiveCommandsTest.request("4","0",WalletCodec.obj("type","preferences","slow",true)));
            assertEquals("SAVED",mutation.get("status"));
            assertEquals("OK",send(commands,packet("5","1","snapshot",WalletCodec.obj())).get("status"));
            assertEquals("CANCELLED",send(commands,packet("6","1","import-document",WalletCodec.obj("displayName","receipt.pdf"))).get("status"));
            assertEquals("OK",send(commands,packet("7","1","snapshot",WalletCodec.obj())).get("status"));
        }
    }
}
