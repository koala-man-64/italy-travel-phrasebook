package com.koalaman64.italytravelpocketguide;

import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
import static com.koalaman64.italytravelpocketguide.WalletTypes.*;

public class WalletStoreTest {
    static <T> Result<T> result(CompletionStage<Result<T>> future){return future.toCompletableFuture().join();}
    static <T> T ok(CompletionStage<Result<T>> future){Result<T> r=result(future);assertEquals(r.code==null?"OK":r.code.name(),Status.OK,r.status);return r.value;}
    static Identity identity(char c,String rev){return new Identity("gen_"+String.valueOf(c).repeat(32),rev,String.valueOf(c).repeat(64));}
    static final Context CONTEXT=new Context("session","page","0","request");
    static final byte[] CONTENT={1,2,3};
    static final String DOC="doc_"+"d".repeat(32), ESCROW="{\"synthetic\":true}";
    static final class Fixture {
        final Path root;final WalletDisk disk;final AtomicReference<String> fail=new AtomicReference<>();WalletStore store;
        java.util.concurrent.Executor copyExecutor=Runnable::run;
        boolean webAccepted=true;Pair baseline=new Pair(identity('a',"0"),null);NativeFence fence;TxnPlan plan;Checkpoint baselineCheckpoint;
        Fixture()throws Exception{
            root=Files.createTempDirectory("wallet-store-test-");
            disk=new WalletDisk(root,p->{},boundary->{String cut=fail.get();if(boundary.equals(cut)||(cut!=null&&cut.startsWith("*:")&&boundary.startsWith("blob_")&&boundary.endsWith(cut.substring(1))))throw new java.io.IOException("injected");});
            restart();NativeFence f=ok(store.acquireRecoveryFence(CONTEXT));ok(store.readRecovery(f));Checkpoint c=ok(store.checkpoint(f,baseline,null));baselineCheckpoint=c;ok(store.releaseFence(f,c));
        }
        void restart(){store=new WalletStore(disk,Runnable::run,task->copyExecutor.execute(task),(p,ms)->CompletableFuture.completedFuture(Result.ok(new WalletStore.Decoded("png",Collections.singletonList(new PageSize(1,1)),1))),new WalletStore.WebEvidence(){
            public CompletionStage<Boolean> verifyReceipt(Context c,Receipt r,String raw){return CompletableFuture.completedFuture(webAccepted);}
            public CompletionStage<Boolean> verifyPair(Context c,Pair p){return CompletableFuture.completedFuture(webAccepted);}
            public CompletionStage<Boolean> verifyRecoveryPair(Context c,Pair p){return CompletableFuture.completedFuture(webAccepted);}
        },()->1000);}
        Journal begin() {fence=ok(store.acquireFence(CONTEXT,baseline));plan=ok(store.newTransaction(fence,Operation.RESTORE));return ok(store.beginJournal(fence,plan.transactionId,Operation.RESTORE,baseline,null));}
        Receipt stage(Journal j)throws Exception{Result<Receipt> r=stageResult(j);assertEquals(Status.OK,r.status);return r.value;}
        Result<Receipt> stageResult(Journal j)throws Exception{
            BoundedDocumentSink s=ok(store.createStagedDocument(fence,j.transactionId,plan.nativeGenerationId,DOC,CONTENT.length));s.write(CONTENT,0,CONTENT.length);s.close();ValidatedDocument v=ok(store.sealStagedDocument(fence,j.transactionId,DOC,CONTENT.length,WalletDisk.sha(CONTENT)));
            return result(store.stageArchive(fence,j.transactionId,new ValidatedArchive(Collections.singletonList(v.document),Collections.emptyList()),new NativeStagingHandles(Collections.singletonList(v.stageId))));
        }
        Journal prepare(Journal j,Receipt r)throws Exception{
            String sha=WalletDisk.sha(ESCROW.getBytes(java.nio.charset.StandardCharsets.UTF_8));ok(store.writeWebEscrow(fence,j.transactionId,ESCROW,sha));
            long revision=Long.parseLong(j.web.prior.revision)+1;Journal p=new Journal(j.transactionId,j.operation,Phase.PREPARED,j.sequence,new Change(j.web.prior,identity((char)('a'+revision),Long.toString(revision))),new Change(j.nativeChange.prior,r.candidate),sha,"c".repeat(64));return ok(store.writeJournal(fence,j,p));
        }
        Checkpoint commit(Journal p){ok(store.activateStaged(fence,p.transactionId,p));Journal c=new Journal(p.transactionId,p.operation,Phase.COMMITTED,p.sequence,p.web,p.nativeChange,p.webEscrowHash,p.planHash);ok(store.writeJournal(fence,p,c));return ok(store.checkpoint(fence,c.candidatePair(),c.transactionId));}
    }
    @Test public void realFilesImportCommitReadLeaseAndReopen()throws Exception{
        Fixture f=new Fixture();Journal j=f.begin();Receipt r=f.stage(j);Journal p=f.prepare(j,r);Checkpoint c=f.commit(p);assertTrue(Long.parseLong(c.sequence)>Long.parseLong(p.sequence));ok(f.store.releaseFence(f.fence,c));
        Lease lease=ok(f.store.acquireViewerLease(CONTEXT,r.candidate,DOC));ReadOnlyDocumentHandle handle=ok(f.store.openSnapshotDocument(lease,DOC));byte[] b=new byte[3];assertEquals(3,handle.read(0,b,0,3));assertArrayEquals(CONTENT,b);assertEquals(-1,handle.read(3,b,0,1));assertEquals(Code.BUSY,result(f.store.releaseSnapshotLease(lease)).code);handle.close();ok(f.store.releaseSnapshotLease(lease));
        f.restart();NativeFence recovery=ok(f.store.acquireRecoveryFence(CONTEXT));NativeRecoveryImage image=ok(f.store.readRecovery(recovery));assertEquals(r.candidate,image.active);assertEquals(Phase.COMMITTED,image.journal.phase);assertEquals(c.pair,image.checkpoint.pair);
    }
    @Test public void forgedCandidateAndHashCannotSeal()throws Exception{
        Fixture f=new Fixture();Journal j=f.begin();Receipt r=f.stage(j);
        Result<Receipt> forged=result(f.store.stageArchive(f.fence,j.transactionId,new ValidatedArchive(Collections.emptyList(),Collections.emptyList()),new NativeStagingHandles(Collections.singletonList("blob_"+"e".repeat(32)))));assertEquals(Status.FAILED,forged.status);
        assertEquals(Code.HASH_MISMATCH,result(f.store.writeWebEscrow(f.fence,j.transactionId,ESCROW,"0".repeat(64))).code);
    }
    @Test public void untrustedWebReceiptCannotPrepare()throws Exception{
        Fixture f=new Fixture();Journal j=f.begin();Receipt r=f.stage(j);f.webAccepted=false;
        String sha=WalletDisk.sha(ESCROW.getBytes(java.nio.charset.StandardCharsets.UTF_8));ok(f.store.writeWebEscrow(f.fence,j.transactionId,ESCROW,sha));Journal p=new Journal(j.transactionId,j.operation,Phase.PREPARED,j.sequence,new Change(j.web.prior,identity('b',"1")),new Change(null,r.candidate),sha,"c".repeat(64));
        assertEquals(Status.UNCERTAIN,result(f.store.writeJournal(f.fence,j,p)).status);assertEquals(Phase.BEGIN,WalletCodec.journal(f.disk.read("journal.json",JOURNAL_BYTES)).phase);
    }
    @Test public void journalWriteFaultsPreserveOldOrCompleteAuthority()throws Exception{
        for(String cut:Arrays.asList("before-write","after-write","after-sync","after-replace","after-dir-sync","after-readback")){
            Fixture f=new Fixture();f.fence=ok(f.store.acquireFence(CONTEXT,f.baseline));TxnPlan plan=ok(f.store.newTransaction(f.fence,Operation.RESTORE));f.fail.set("journal.json:"+cut);
            assertEquals(cut,Status.UNCERTAIN,result(f.store.beginJournal(f.fence,plan.transactionId,Operation.RESTORE,f.baseline,null)).status);
            assertTrue(Files.exists(f.root.resolve("checkpoint.json")));f.fail.set(null);f.restart();NativeFence recovery=ok(f.store.acquireRecoveryFence(CONTEXT));NativeRecoveryImage image=ok(f.store.readRecovery(recovery));assertNull(image.active);assertEquals(f.baseline,image.checkpoint.pair);
            assertEquals(Code.BUSY,result(f.store.acquireViewerLease(CONTEXT,null,DOC)).code);
        }
    }
    @Test public void activeWriteFaultsNeverMakeStoreReady()throws Exception{
        for(String cut:Arrays.asList("before-write","after-write","after-sync","after-replace","after-dir-sync","after-readback")){
            Fixture f=new Fixture();Journal j=f.begin();Receipt r=f.stage(j);Journal p=f.prepare(j,r);f.fail.set("active.json:"+cut);
            assertEquals(Status.UNCERTAIN,result(f.store.activateStaged(f.fence,j.transactionId,p)).status);assertEquals(Code.BUSY,result(f.store.acquireViewerLease(CONTEXT,r.candidate,DOC)).code);
            f.fail.set(null);f.restart();NativeFence recovery=ok(f.store.acquireRecoveryFence(CONTEXT));NativeRecoveryImage image=ok(f.store.readRecovery(recovery));assertEquals(Phase.PREPARED,image.journal.phase);assertEquals(f.baseline,image.checkpoint.pair);assertTrue(image.active==null||image.active.equals(r.candidate));
        }
    }
    @Test public void preparedRollbackRequiresPersistedDecision()throws Exception{
        Fixture f=new Fixture();Journal j=f.begin();Receipt r=f.stage(j);Journal p=f.prepare(j,r);ok(f.store.activateStaged(f.fence,j.transactionId,p));RecoveryDecision d=new RecoveryDecision(j.transactionId,Decision.ROLLBACK,f.baseline,"candidate-unavailable","3");
        assertEquals(Status.UNCERTAIN,result(f.store.restorePrevious(f.fence,j.transactionId,d)).status);ok(f.store.recordRecoveryDecision(f.fence,p,d));ok(f.store.restorePrevious(f.fence,j.transactionId,d));Checkpoint c=ok(f.store.checkpoint(f.fence,f.baseline,j.transactionId));assertEquals(Outcome.RECOVERED_OLD,c.outcome);
    }
    @Test public void duplicatePreparedRetryIsIdempotent()throws Exception{Fixture f=new Fixture();Journal j=f.begin();Receipt r=f.stage(j);Journal p=f.prepare(j,r);assertEquals(p.transactionId,ok(f.store.writeJournal(f.fence,j,p)).transactionId);}
    @Test public void malformedDiskFailsClosed()throws Exception{
        Fixture f=new Fixture();Files.write(f.root.resolve("active.json"),"{\"identity\":null,\"extra\":1}".getBytes());f.restart();NativeFence r=ok(f.store.acquireRecoveryFence(CONTEXT));assertEquals(Status.UNCERTAIN,result(f.store.readRecovery(r)).status);
    }
    @Test public void staleCheckpointCannotReleaseUnresolvedBegin()throws Exception{
        Fixture f=new Fixture();f.begin();assertEquals(Status.UNCERTAIN,result(f.store.releaseFence(f.fence,f.baselineCheckpoint)).status);assertEquals(Status.UNCERTAIN,result(f.store.getSnapshot(CONTEXT)).status);
    }
    @Test public void escrowAndCheckpointFaultCutsNeverPublishReady()throws Exception{
        for(String cut:Arrays.asList("before-write","after-write","after-sync","after-replace","after-dir-sync","after-readback")){
            Fixture f=new Fixture();Journal j=f.begin();f.stage(j);String sha=WalletDisk.sha(ESCROW.getBytes());f.fail.set("escrow_"+j.transactionId+":"+cut);
            assertEquals(Status.UNCERTAIN,result(f.store.writeWebEscrow(f.fence,j.transactionId,ESCROW,sha)).status);assertEquals(Status.UNCERTAIN,result(f.store.getSnapshot(CONTEXT)).status);
            Fixture committed=new Fixture();Journal cj=committed.begin();Journal p=committed.prepare(cj,committed.stage(cj));ok(committed.store.activateStaged(committed.fence,cj.transactionId,p));Journal c=new Journal(p.transactionId,p.operation,Phase.COMMITTED,p.sequence,p.web,p.nativeChange,p.webEscrowHash,p.planHash);ok(committed.store.writeJournal(committed.fence,p,c));committed.fail.set("checkpoint.json:"+cut);
            assertEquals(Status.UNCERTAIN,result(committed.store.checkpoint(committed.fence,c.candidatePair(),c.transactionId)).status);assertEquals(Status.UNCERTAIN,result(committed.store.getSnapshot(CONTEXT)).status);
        }
    }
    @Test public void deleteRetainsRollbackBytesAndRequiresLaterCheckpoint()throws Exception{
        Fixture f=new Fixture();Journal j=f.begin();Receipt imported=f.stage(j);Checkpoint first=f.commit(f.prepare(j,imported));ok(f.store.releaseFence(f.fence,first));
        f.fence=ok(f.store.acquireFence(CONTEXT,first.pair));TxnPlan delete=ok(f.store.newTransaction(f.fence,Operation.DELETE));Journal begin=ok(f.store.beginJournal(f.fence,delete.transactionId,Operation.DELETE,first.pair,null));Receipt removed=ok(f.store.stageDelete(f.fence,delete.transactionId,imported.candidate,DOC));assertEquals(Code.REVISION_CONFLICT,result(f.store.readSnapshot(f.fence,removed.candidate)).code);
        Checkpoint committed=f.commit(f.prepare(begin,removed));assertEquals(Status.UNCERTAIN,result(f.store.collectEligible(f.fence,committed)).status);
        Checkpoint later=ok(f.store.checkpoint(f.fence,committed.pair,committed.transactionId));ok(f.store.collectEligible(f.fence,later));try(java.util.stream.Stream<Path> files=Files.list(f.root)){assertEquals(1,files.filter(p->p.getFileName().toString().startsWith("blob_")).count());}assertTrue(ok(f.store.readSnapshot(f.fence,removed.candidate)).documents.isEmpty());assertEquals(Code.REVISION_CONFLICT,result(f.store.readSnapshot(f.fence,imported.candidate)).code);
    }
    @Test public void archivePreviewWaitsForViewerLeaseRetirement()throws Exception{
        Fixture f=new Fixture();Journal j=f.begin();Receipt imported=f.stage(j);Checkpoint checkpoint=f.commit(f.prepare(j,imported));ok(f.store.releaseFence(f.fence,checkpoint));
        Lease viewer=ok(f.store.acquireViewerLease(CONTEXT,imported.candidate,DOC));
        NativeFence preview=ok(f.store.acquireFence(CONTEXT,checkpoint.pair));
        assertEquals(Code.BUSY,result(f.store.createSpool(preview,3)).code);
        try(java.util.stream.Stream<Path> files=Files.list(f.root)){assertEquals(0,files.filter(p->p.getFileName().toString().startsWith("spool_")).count());}
        ReadOnlyDocumentHandle reader=ok(f.store.openSnapshotDocument(viewer,DOC));
        assertEquals(Code.BUSY,result(f.store.releaseSnapshotLease(viewer)).code);
        assertEquals(Code.BUSY,result(f.store.createSpool(preview,3)).code);
        reader.close();ok(f.store.releaseSnapshotLease(viewer));
        BoundedSpoolSink spool=ok(f.store.createSpool(preview,3));spool.cancel();
        ok(f.store.releaseFence(preview,checkpoint));
    }
    @Test public void webMutationDoesNotAdvanceNativeRevision()throws Exception{
        Fixture f=new Fixture();Journal j=f.begin();Receipt imported=f.stage(j);Checkpoint first=f.commit(f.prepare(j,imported));ok(f.store.releaseFence(f.fence,first));f.fence=ok(f.store.acquireFence(CONTEXT,first.pair));TxnPlan mutation=ok(f.store.newTransaction(f.fence,Operation.WEB_MUTATION));assertNull(mutation.nativeGenerationId);
        Journal begin=ok(f.store.beginJournal(f.fence,mutation.transactionId,Operation.WEB_MUTATION,first.pair,null));Receipt same=ok(f.store.stageUnchanged(f.fence,mutation.transactionId,imported.candidate));Checkpoint second=f.commit(f.prepare(begin,same));assertEquals(first.pair.nativeIdentity,second.pair.nativeIdentity);
    }
    @Test public void nullWalletRestoreDoesNotManufactureEmptyGeneration()throws Exception{
        Fixture f=new Fixture();Journal j=f.begin();Receipt empty=ok(f.store.stageArchive(f.fence,j.transactionId,new ValidatedArchive(Collections.emptyList(),Collections.emptyList(),false),new NativeStagingHandles(Collections.emptyList())));assertNull(empty.candidate);Checkpoint c=f.commit(f.prepare(j,empty));ok(f.store.releaseFence(f.fence,c));assertNull(ok(f.store.getSnapshot(CONTEXT)).identity);
    }
    @Test public void rejectedCopySubmissionClosesOwnedResourcesAndPreservesBegin()throws Exception{
        Fixture f=new Fixture();f.copyExecutor=task->{throw new java.util.concurrent.RejectedExecutionException();};
        f.fence=ok(f.store.acquireFence(CONTEXT,f.baseline));f.plan=ok(f.store.newTransaction(f.fence,Operation.IMPORT));
        Journal j=ok(f.store.beginJournal(f.fence,f.plan.transactionId,Operation.IMPORT,f.baseline,null));
        boolean[] closed={false};PickerTicket ticket=new PickerTicket(){public Context context(){return CONTEXT;}public int read(byte[] b,int o,int n){fail("rejected task must not read");return -1;}public void close(){closed[0]=true;}};
        assertEquals(Code.BUSY,result(f.store.stageImport(f.fence,j.transactionId,ticket,"test")).code);assertTrue(closed[0]);
        assertEquals(j.transactionId,WalletCodec.journal(Files.readAllBytes(f.root.resolve("journal.json"))).transactionId);
        assertEquals(Status.UNCERTAIN,result(f.store.releaseFence(f.fence,f.baselineCheckpoint)).status);
    }
    @Test public void rejectedCopyWithUncertainCloseRequiresRecovery()throws Exception{
        Fixture f=new Fixture();f.copyExecutor=task->{throw new java.util.concurrent.RejectedExecutionException();};
        f.fence=ok(f.store.acquireFence(CONTEXT,f.baseline));f.plan=ok(f.store.newTransaction(f.fence,Operation.IMPORT));
        Journal j=ok(f.store.beginJournal(f.fence,f.plan.transactionId,Operation.IMPORT,f.baseline,null));f.fail.set("*:after-sync");
        PickerTicket ticket=new PickerTicket(){public Context context(){return CONTEXT;}public int read(byte[] b,int o,int n){return -1;}public void close(){}};
        assertEquals(Status.UNCERTAIN,result(f.store.stageImport(f.fence,j.transactionId,ticket,"test")).status);
        assertEquals(Status.UNCERTAIN,result(f.store.releaseFence(f.fence,f.baselineCheckpoint)).status);
    }
    @Test public void utf8CountsActualBytesBeforeAllocation()throws Exception{
        assertEquals(4,WalletCodec.utf8("😀",4).length);assertThrows(WalletFailure.class,()->WalletCodec.utf8("😀",3));assertThrows(WalletFailure.class,()->WalletCodec.utf8("\ud800",100));
    }
    @Test public void recoveryDecisionRequiresActualWebEvidence()throws Exception{
        Fixture f=new Fixture();Journal j=f.begin();Receipt r=f.stage(j);Journal p=f.prepare(j,r);f.webAccepted=false;RecoveryDecision d=new RecoveryDecision(j.transactionId,Decision.ROLLBACK,f.baseline,"candidate-unavailable","3");assertEquals(Status.UNCERTAIN,result(f.store.recordRecoveryDecision(f.fence,p,d)).status);assertFalse(Files.exists(f.root.resolve("decision.json")));
    }
    @Test public void boundedSinkCannotSealAfterOverrun()throws Exception{
        Fixture f=new Fixture();Journal j=f.begin();BoundedDocumentSink sink=ok(f.store.createStagedDocument(f.fence,j.transactionId,f.plan.nativeGenerationId,DOC,3));sink.write(CONTENT,0,3);assertThrows(WalletFailure.class,()->sink.write(CONTENT,0,1));sink.close();assertEquals(Code.INVALID_DOCUMENT,result(f.store.sealStagedDocument(f.fence,j.transactionId,DOC,3,WalletDisk.sha(CONTENT))).code);
    }
    @Test public void lifecycleInvalidatesOldFenceAndPreservesRecoveryJournal()throws Exception{
        Fixture f=new Fixture();Journal j=f.begin();NativeFence old=f.fence;ok(f.store.invalidateContext(CONTEXT));assertEquals(Code.STALE_SESSION,result(f.store.readStaged(old,j.transactionId)).code);NativeFence recovery=ok(f.store.acquireRecoveryFence(new Context("session","new-page","1","request")));NativeRecoveryImage image=ok(f.store.readRecovery(recovery));assertEquals(j.transactionId,image.journal.transactionId);assertNull(image.active);
    }
    @Test public void manifestCounterPreviousAndDecisionBoundaryFailuresStayGated()throws Exception{
        for(String cut:Arrays.asList("before-write","after-write","after-sync","after-replace","after-dir-sync","after-readback")){
            Fixture m=new Fixture();Journal mj=m.begin();m.fail.set(m.plan.nativeGenerationId+".json:"+cut);assertEquals(Status.UNCERTAIN,m.stageResult(mj).status);assertEquals(Status.UNCERTAIN,result(m.store.getSnapshot(CONTEXT)).status);
            Fixture p=new Fixture();Journal pj=p.begin();Journal pp=p.prepare(pj,p.stage(pj));p.fail.set("previous.json:"+cut);assertEquals(Status.UNCERTAIN,result(p.store.activateStaged(p.fence,pj.transactionId,pp)).status);
            Fixture c=new Fixture();Journal cj=c.begin();Journal cp=c.prepare(cj,c.stage(cj));ok(c.store.activateStaged(c.fence,cj.transactionId,cp));Journal committed=new Journal(cp.transactionId,cp.operation,Phase.COMMITTED,cp.sequence,cp.web,cp.nativeChange,cp.webEscrowHash,cp.planHash);c.fail.set("counter.json:"+cut);assertEquals(Status.UNCERTAIN,result(c.store.writeJournal(c.fence,cp,committed)).status);assertEquals(Phase.PREPARED,WalletCodec.journal(c.disk.read("journal.json",JOURNAL_BYTES)).phase);
            Fixture d=new Fixture();Journal dj=d.begin();Journal dp=d.prepare(dj,d.stage(dj));d.fail.set("decision.json:"+cut);assertEquals(Status.UNCERTAIN,result(d.store.recordRecoveryDecision(d.fence,dp,new RecoveryDecision(dj.transactionId,Decision.ROLLBACK,d.baseline,"synthetic","3"))).status);
        }
    }
    @Test public void priorImageBoundaryFailuresCannotOverwriteBaseline()throws Exception{
        for(String prefix:Arrays.asList("counter-prior_","previous-prior_"))for(String cut:Arrays.asList("before-write","after-write","after-sync","after-replace","after-dir-sync","after-readback")){
            Fixture f=new Fixture();f.fence=ok(f.store.acquireFence(CONTEXT,f.baseline));TxnPlan p=ok(f.store.newTransaction(f.fence,Operation.RESTORE));f.fail.set(prefix+p.transactionId+":"+cut);assertEquals(Status.UNCERTAIN,result(f.store.beginJournal(f.fence,p.transactionId,p.operation,f.baseline,null)).status);assertEquals(f.baseline,WalletCodec.checkpoint(f.disk.read("checkpoint.json",JOURNAL_BYTES)).pair);assertFalse(Files.exists(f.root.resolve("active.json")));
        }
    }
    @Test public void stagedBlobWriteAndSyncFaultsCannotSeal()throws Exception{
        for(String cut:Arrays.asList("before-write","after-write","before-sync","after-sync","after-close","after-dir-sync")){
            Fixture f=new Fixture();Journal j=f.begin();BoundedDocumentSink s=ok(f.store.createStagedDocument(f.fence,j.transactionId,f.plan.nativeGenerationId,DOC,3));f.fail.set("*:"+cut);
            try{s.write(CONTENT,0,3);s.close();fail("fault not reached");}catch(WalletFailure expected){try{s.close();}catch(WalletFailure retained){}}
            assertEquals(Code.INVALID_DOCUMENT,result(f.store.sealStagedDocument(f.fence,j.transactionId,DOC,3,WalletDisk.sha(CONTENT))).code);assertNull(WalletCodec.checkpoint(f.disk.read("checkpoint.json",JOURNAL_BYTES)).pair.nativeIdentity);
        }
    }
    @Test public void secondDiskOwnerIsRejectedUntilLockReleased()throws Exception{
        Fixture f=new Fixture();assertThrows(java.io.IOException.class,()->new WalletDisk(f.root,p->{},boundary->{}));f.disk.close();try(WalletDisk reopened=new WalletDisk(f.root,p->{},boundary->{})){assertTrue(reopened.exists("checkpoint.json"));}
    }
    @Test public void snapshotRejectsStaleDocumentContext()throws Exception{
        Fixture f=new Fixture();assertEquals(Code.STALE_SESSION,result(f.store.getSnapshot(new Context("session","different","1","request"))).code);assertNull(ok(f.store.getSnapshot(CONTEXT)).identity);
    }
    @Test public void sequentialArchiveSpoolsAfterReaderClosureCanReuseStore()throws Exception{
        Fixture f=new Fixture();NativeFence first=ok(f.store.acquireFence(CONTEXT,f.baseline));
        BoundedSpoolSink a=ok(f.store.createSpool(first,3));a.write(CONTENT,0,3);a.close();
        ReadOnlySeekableHandle firstReader=ok(f.store.sealSpool(first,a.spoolId()));firstReader.close();
        ok(f.store.releaseFence(first,f.baselineCheckpoint));
        NativeFence second=ok(f.store.acquireFence(CONTEXT,f.baseline));
        BoundedSpoolSink b=ok(f.store.createSpool(second,3));assertNotEquals(a.spoolId(),b.spoolId());
        b.write(CONTENT,0,3);b.close();ReadOnlySeekableHandle secondReader=ok(f.store.sealSpool(second,b.spoolId()));
        byte[] bytes=new byte[3];assertEquals(3,secondReader.read(0,bytes,0,3));assertArrayEquals(CONTENT,bytes);secondReader.close();
        assertEquals(Code.INVALID_REQUEST,result(f.store.sealSpool(second,a.spoolId())).code);
        // Retirement relinquishes the capability, not physical bytes or admission accounting.
        try(java.util.stream.Stream<Path> files=Files.list(f.root)){assertEquals(2,files.filter(p->p.getFileName().toString().startsWith("spool_")).count());}
        assertTrue(f.disk.used()>=6);
    }
    @Test public void openSpoolWriterAndReadersPreventReplacement()throws Exception{
        Fixture f=new Fixture();NativeFence fence=ok(f.store.acquireFence(CONTEXT,f.baseline));BoundedSpoolSink sink=ok(f.store.createSpool(fence,3));
        assertEquals(Code.BUSY,result(f.store.createSpool(fence,3)).code);sink.write(CONTENT,0,3);sink.close();
        ReadOnlySeekableHandle a=ok(f.store.sealSpool(fence,sink.spoolId())),b=ok(f.store.sealSpool(fence,sink.spoolId()));
        assertEquals(Code.BUSY,result(f.store.createSpool(fence,3)).code);a.close();assertEquals(Code.BUSY,result(f.store.createSpool(fence,3)).code);
        b.close();ok(f.store.createSpool(fence,3)).cancel();
    }
    @Test public void cancelledPreBeginSpoolCanRetryAfterVerifiedClosure()throws Exception{
        Fixture f=new Fixture();NativeFence fence=ok(f.store.acquireFence(CONTEXT,f.baseline));BoundedSpoolSink cancelled=ok(f.store.createSpool(fence,3));cancelled.write(CONTENT,0,1);cancelled.cancel();
        assertEquals(Code.INVALID_REQUEST,result(f.store.sealSpool(fence,cancelled.spoolId())).code);BoundedSpoolSink retry=ok(f.store.createSpool(fence,3));assertNotEquals(cancelled.spoolId(),retry.spoolId());retry.cancel();
    }
    @Test public void uncertainSpoolClosureStaysQuarantinedAcrossTransactionAdmission()throws Exception{
        Fixture f=new Fixture();NativeFence fence=ok(f.store.acquireFence(CONTEXT,f.baseline));BoundedSpoolSink sink=ok(f.store.createSpool(fence,3));sink.write(CONTENT,0,3);
        try(java.util.stream.Stream<Path> files=Files.list(f.root)){String name=files.filter(p->p.getFileName().toString().startsWith("spool_")).findFirst().get().getFileName().toString();f.fail.set(name+":after-sync");}
        assertThrows(WalletFailure.class,sink::close);assertEquals(Code.BUSY,result(f.store.createSpool(fence,3)).code);assertEquals(Code.BUSY,result(f.store.newTransaction(fence,Operation.RESTORE)).code);assertEquals(Code.BUSY,result(f.store.releaseFence(fence,f.baselineCheckpoint)).code);
    }
}
