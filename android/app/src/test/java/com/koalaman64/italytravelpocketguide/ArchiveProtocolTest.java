package com.koalaman64.italytravelpocketguide;

import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;
import static com.koalaman64.italytravelpocketguide.WalletTypes.*;
import static com.koalaman64.italytravelpocketguide.ArchiveCoordinatorTest.*;

public class ArchiveProtocolTest {
    static PickerTicket ticket(byte[] bytes){return new PickerTicket(){int at;boolean closed;
        public Context context(){return CTX;}
        public int read(byte[] b,int offset,int count)throws WalletFailure{if(closed)throw new WalletFailure(Code.INTERRUPTED);if(at==bytes.length)return -1;int n=Math.min(count,bytes.length-at);System.arraycopy(bytes,at,b,offset,n);at+=n;return n;}
        public void close(){closed=true;}
    };}
    static byte[] personalArchive(boolean wallet)throws Exception{
        Map<String,Object> user=ArchiveFormatTest.user(wallet);
        user.put("saved",Collections.singletonList(JsonTransferJson.object("id","sav_"+"c".repeat(32),"sourcePhraseId",null,"sourceContentVersion",null,"snapshot",JsonTransferJson.object("it","Caffè, per favore","en","Coffee, please"),"createdAtUtc",ArchiveFormatTest.TIME)));
        user.put("progress",Collections.singletonList(JsonTransferJson.object("phraseId","phrase_old_orphan","reviewCount",7,"lastReviewedAtUtc",ArchiveFormatTest.TIME)));
        byte[] raw=JsonTransferJson.encode(user).getBytes(StandardCharsets.UTF_8),doc=wallet?new byte[]{1,2,3}:null;
        Map<String,byte[]> entries=new LinkedHashMap<>();entries.put("manifest.json",JsonTransferJson.encode(ArchiveFormatTest.manifest(raw,doc,"Ticket")).getBytes(StandardCharsets.UTF_8));entries.put("user-data.json",raw);
        if(wallet)entries.put("documents/"+ArchiveFormatTest.DOC+".png",doc);return ArchiveFormatTest.zip(entries);
    }
    @Test public void actualSpoolPreviewCancelRetryConfirmAndConsecutiveExportRoundtrip()throws Exception{
        try(Fixture f=new Fixture()){
            ArchiveProtocol p=new ArchiveProtocol(f.coordinator,f.nativeStore,Runnable::run,()->1000L,c->true);
            byte[] archive=personalArchive(true);
            ArchiveProtocol.Preview first=ok(p.preview(CTX,f.checkpoint.pair,ticket(archive)));
            assertNotNull(f.snapshot());assertEquals(Status.OK,p.cancelPreview(CTX,first.token).status);
            ArchiveProtocol.Preview second=ok(p.preview(CTX,f.checkpoint.pair,ticket(archive)));
            Checkpoint imported=ok(p.confirm(CTX,f.checkpoint.pair,second.token));assertNotNull(imported.pair.nativeIdentity);
            Map<String,Object> live=Web.map(f.snapshot());assertEquals(1,((List<?>)live.get("saved")).size());assertEquals(1,((List<?>)live.get("attachments")).size());
            byte[] exported=null;
            for(int i=0;i<2;i++){
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();ok(p.export(CTX,imported.pair,ArchiveFormatTest.TIME,bytes));exported=bytes.toByteArray();
                ArchiveFormat.Archive read=ArchiveFormatTest.read(exported);assertEquals(1,read.documents.size());assertEquals("Ticket",read.documents.get(0).displayName);
                Map<String,Object> u=Web.map(JsonTransferJson.parse(new String(read.userBytes(),StandardCharsets.UTF_8),USER_BYTES));assertEquals(live.get("saved"),u.get("saved"));assertEquals(live.get("progress"),u.get("progress"));assertEquals(live.get("attachments"),u.get("attachments"));
            }
            ArchiveProtocol.Preview again=ok(p.preview(CTX,imported.pair,ticket(exported)));
            Checkpoint restored=ok(p.confirm(CTX,imported.pair,again.token));assertEquals("2",restored.pair.web.revision);assertNotEquals(imported.pair.nativeIdentity,restored.pair.nativeIdentity);
            ArchiveProtocol.Preview noWallet=ok(p.preview(CTX,restored.pair,ticket(personalArchive(false))));
            Checkpoint cleared=ok(p.confirm(CTX,restored.pair,noWallet.token));assertNull(cleared.pair.nativeIdentity);
            assertEquals(Collections.emptyList(),Web.map(f.snapshot()).get("attachments"));assertEquals(1,((List<?>)Web.map(f.snapshot()).get("saved")).size());
        }
    }
    @Test public void stalePreviewCannotOverwriteInterveningSavedOrLearningWrite()throws Exception{
        try(Fixture f=new Fixture()){
            ArchiveProtocol p=new ArchiveProtocol(f.coordinator,f.nativeStore,Runnable::run,()->1000L,c->true);
            ArchiveProtocol.Preview preview=ok(p.preview(CTX,f.checkpoint.pair,ticket(personalArchive(false))));
            Checkpoint newer=ok(f.coordinator.mutate(CTX,f.checkpoint.pair,"{\"type\":\"preferences\",\"slow\":true}"));
            assertEquals(Code.RESTORE_CONFLICT,p.confirm(CTX,newer.pair,preview.token).toCompletableFuture().join().code);
            assertEquals(Status.OK,p.cancelPreview(CTX,preview.token).status);
        }
    }
    @Test public void outputCloseFailureNeverReportsSuccessAndRetainsOriginalPair()throws Exception{
        try(Fixture f=new Fixture()){
            ArchiveProtocol p=new ArchiveProtocol(f.coordinator,f.nativeStore,Runnable::run,()->1000L,c->true);
            OutputStream fail=new ByteArrayOutputStream(){public void close()throws IOException{throw new IOException("provider close");}};
            assertEquals(Status.UNCERTAIN,p.export(CTX,f.checkpoint.pair,ArchiveFormatTest.TIME,fail).toCompletableFuture().join().status);assertNull(f.snapshot());
            f.web.command("restart");f.restartNative();assertEquals(f.checkpoint.pair,ok(f.coordinator.recover(CTX)).pair);
        }
    }
    @Test public void chunksValidateExactUtf8CountHashAndRejectReplaysAndUnknownFields()throws Exception{
        String raw="😀\n\"\\".repeat(1000),hash=ArchiveCoordinator.sha(raw);
        ArchiveProtocol.Chunks chunks=new ArchiveProtocol.Chunks("transfer",1,hash);
        String frame=JsonTransferJson.encode(JsonTransferJson.object("v",1,"transferId","transfer","sequence",0,"count",1,"sha256",hash,"text",raw));
        assertEquals(raw,chunks.accept(frame));assertThrows(WalletFailure.class,()->chunks.accept(frame));
        String unknown=frame.substring(0,frame.length()-1)+",\"uri\":\"file\"}";
        assertThrows(WalletFailure.class,()->new ArchiveProtocol.Chunks("transfer",1,hash).accept(unknown));
        assertThrows(WalletFailure.class,()->new ArchiveProtocol.Chunks("transfer",1,"0".repeat(64)).accept(frame));
    }
    @Test public void previewWhileViewerIsOpenReturnsBusyOnlyAfterPriorPairReadback()throws Exception{
        try(Fixture f=new Fixture()){
            ArchiveProtocol p=new ArchiveProtocol(f.coordinator,f.nativeStore,Runnable::run,()->1000L,c->true);
            ArchiveProtocol.Preview first=ok(p.preview(CTX,f.checkpoint.pair,ticket(personalArchive(true))));
            Checkpoint imported=ok(p.confirm(CTX,f.checkpoint.pair,first.token));
            Lease viewer=ok(f.nativeStore.acquireViewerLease(CTX,imported.pair.nativeIdentity,ArchiveFormatTest.DOC));
            Result<ArchiveProtocol.Preview> busy=p.preview(CTX,imported.pair,ticket(personalArchive(false))).toCompletableFuture().join();
            assertEquals(Status.FAILED,busy.status);assertEquals(Code.BUSY,busy.code);assertNotNull(f.snapshot());
            ok(f.nativeStore.releaseSnapshotLease(viewer));
            ArchiveProtocol.Preview retry=ok(p.preview(CTX,imported.pair,ticket(personalArchive(false))));assertEquals(Status.OK,p.cancelPreview(CTX,retry.token).status);
        }
    }
    @Test public void expiredConfirmationRetiresNativeSpoolAndAllowsFreshPreview()throws Exception{
        try(Fixture f=new Fixture()){
            java.util.concurrent.atomic.AtomicLong clock=new java.util.concurrent.atomic.AtomicLong(1000);
            ArchiveProtocol p=new ArchiveProtocol(f.coordinator,f.nativeStore,Runnable::run,clock::get,c->true);
            ArchiveProtocol.Preview expired=ok(p.preview(CTX,f.checkpoint.pair,ticket(personalArchive(false))));
            clock.addAndGet(300001);assertEquals(Code.CONFIRMATION_REQUIRED,p.confirm(CTX,f.checkpoint.pair,expired.token).toCompletableFuture().join().code);
            ArchiveProtocol.Preview fresh=ok(p.preview(CTX,f.checkpoint.pair,ticket(personalArchive(false))));assertEquals(Status.OK,p.cancelPreview(CTX,fresh.token).status);
        }
    }
    @Test public void lifecycleRetiresObsoletePreviewWithoutRequiringItsContextToBeCurrent()throws Exception{
        try(Fixture f=new Fixture()){
            java.util.concurrent.atomic.AtomicBoolean current=new java.util.concurrent.atomic.AtomicBoolean(true);
            ArchiveProtocol p=new ArchiveProtocol(f.coordinator,f.nativeStore,Runnable::run,()->1000L,c->current.get());
            ArchiveProtocol.Preview old=ok(p.preview(CTX,f.checkpoint.pair,ticket(personalArchive(false))));current.set(false);
            assertEquals(Code.STALE_SESSION,p.cancelPreview(CTX,old.token).code);
            assertEquals(Status.OK,p.invalidateContext(CTX).status);current.set(true);
            ArchiveProtocol.Preview fresh=ok(p.preview(CTX,f.checkpoint.pair,ticket(personalArchive(false))));assertEquals(Status.OK,p.cancelPreview(CTX,fresh.token).status);
        }
    }
    @Test public void uncertainPreviewClosureRemainsOwnedAndCannotBeRetriedAsSuccessfulClose()throws Exception{
        try(Fixture f=new Fixture()){
            byte[] bytes=personalArchive(false);java.util.concurrent.atomic.AtomicInteger closed=new java.util.concurrent.atomic.AtomicInteger();
            ReadOnlySeekableHandle faulty=new ReadOnlySeekableHandle(){public long byteLength(){return bytes.length;}public int read(long at,byte[] b,int start,int count){int n=(int)Math.min(count,bytes.length-at);System.arraycopy(bytes,(int)at,b,start,n);return n;}public void close()throws WalletFailure{closed.incrementAndGet();throw new WalletFailure(Code.IO_FAILURE);}};
            java.util.concurrent.atomic.AtomicLong clock=new java.util.concurrent.atomic.AtomicLong(1000);
            ArchiveProtocol p=new ArchiveProtocol(f.coordinator,f.nativeStore,Runnable::run,clock::get,c->true);
            ArchiveProtocol.Preview old=ok(p.preview(CTX,f.checkpoint.pair,faulty));clock.addAndGet(300001);
            assertEquals(Status.UNCERTAIN,p.confirm(CTX,f.checkpoint.pair,old.token).toCompletableFuture().join().status);
            assertEquals(Status.UNCERTAIN,p.invalidateContext(CTX).status);assertEquals(1,closed.get());
            assertEquals(Status.UNCERTAIN,p.preview(CTX,f.checkpoint.pair,ticket(bytes)).toCompletableFuture().join().status);
        }
    }
}
