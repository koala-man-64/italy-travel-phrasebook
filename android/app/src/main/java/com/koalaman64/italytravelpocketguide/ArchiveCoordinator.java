package com.koalaman64.italytravelpocketguide;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Function;
import static com.koalaman64.italytravelpocketguide.WalletTypes.*;

/** Cross-store ordering only. Native owns every durable native byte and capability.
 * The root adapter implements WebPort by calling the existing UserData writer.
 * All continuations run on the supplied bounded worker, never the UI thread.
 */
public final class ArchiveCoordinator {
    public interface WebPort {
        CompletionStage<Result<Object>> enterRecovery(Context context);
        CompletionStage<Result<WebRecovery>> readRecovery(Object fence);
        default CompletionStage<Result<Identity>> initializeBaseline(Object fence) { return CompletableFuture.completedFuture(Result.failed(Code.UNSUPPORTED)); }
        CompletionStage<Result<String>> captureExact(Object fence, Identity expected);
        CompletionStage<Result<String>> prepareCandidate(Object fence, Operation operation, Identity prior,
                                                        Identity nativeCandidate, String boundedInput);
        CompletionStage<Result<Receipt>> stageInactive(Object fence, String transactionId, Operation operation,
                Identity prior, String candidateRaw, Identity nativeCandidate, String beginSequence);
        CompletionStage<Result<Receipt>> readStaged(Object fence, String transactionId);
        CompletionStage<Result<WebEscrow>> exportStage(Object fence, String transactionId);
        CompletionStage<Result<Void>> restoreEscrow(Object fence, String raw, String expectedHash);
        CompletionStage<Result<Receipt>> activateStaged(Object fence, String transactionId, Journal prepared);
        CompletionStage<Result<Identity>> restorePrevious(Object fence, String transactionId, RecoveryDecision decision);
        CompletionStage<Result<Identity>> verifyActive(Object fence, Identity expected);
        CompletionStage<Result<Void>> releaseReady(Object fence, Checkpoint checkpoint, Identity nativeVerified);
        CompletionStage<Result<Void>> discardStage(Object fence, String transactionId, Checkpoint subsequentCheckpoint, Checkpoint initialCheckpoint);
    }
    public static final class WebRecovery {
        public final Identity active, previous; public final String transactionId;
        public WebRecovery(Identity active, Identity previous, String tx) { this.active=active;this.previous=previous;transactionId=tx; }
    }
    public static final class WebEscrow {
        public final String raw, sha256;
        public WebEscrow(String raw,String hash) {
            if(raw==null||raw.getBytes(StandardCharsets.UTF_8).length>ESCROW_BYTES)throw new IllegalArgumentException("STORAGE_LIMIT");
            this.raw=raw;sha256=WalletTypes.hash(hash);
        }
    }
    /** Owner-only staging function, never reconstructed from a web message or a receipt. */
    public interface NativeStage {
        CompletionStage<Result<Receipt>> stage(NativeFence fence, TxnPlan plan);
    }
    public interface ExportWork {
        CompletionStage<Void> write(String exactUser, NativeSnapshot snapshot, Lease lease);
    }
    public interface PreviewWork { CompletionStage<Void> read(NativeFence fence); }
    private final WalletNativePort nativePort;
    private final WebPort web;
    private final Executor worker;
    private final Function<Context,Boolean> current;
    private boolean busy, unresolved;
    private Checkpoint knownCheckpoint;

    public ArchiveCoordinator(WalletNativePort nativePort,WebPort web,Executor worker,Function<Context,Boolean> current) {
        this.nativePort=Objects.requireNonNull(nativePort);this.web=Objects.requireNonNull(web);
        this.worker=Objects.requireNonNull(worker);this.current=Objects.requireNonNull(current);
    }
    private static final class Stop extends RuntimeException {
        final Code code;
        Stop(Code code){super(code.name());this.code=code;}
    }
    private static void need(boolean valid,Code code){if(!valid)throw new Stop(code);}
    private static <T> T value(Result<T> result){
        need(result!=null,Code.RECOVERY_REQUIRED);
        need(result.status==Status.OK,result.code==null?Code.RECOVERY_REQUIRED:result.code);return result.value;
    }
    private final class Run {
        final Context context; Object webFence; NativeFence nativeFence; TxnPlan plan; Journal journal;
        boolean gated, durable;
        Run(Context c){context=c;}
        void live(){need(Boolean.TRUE.equals(current.apply(context)),Code.STALE_SESSION);}
        <T> CompletionStage<T> call(CompletionStage<Result<T>> stage){
            return stage.thenApplyAsync(r->{live();return value(r);},worker);
        }
    }
    private synchronized boolean admit(boolean recovery) {
        if(busy||(!recovery&&unresolved))return false;busy=true;return true;
    }
    public synchronized boolean recoveryRequired(){return unresolved;}
    private CompletionStage<Result<Checkpoint>> execute(Context context,boolean recovery,Function<Run,CompletionStage<Checkpoint>> action) {
        if(!admit(recovery))return CompletableFuture.completedFuture(Result.failed(Code.BUSY));
        Run r=new Run(context);
        return CompletableFuture.completedFuture(r).thenComposeAsync(run->{run.live();return action.apply(run);},worker)
            .handle((checkpoint,error)->{
                synchronized(this){
                    if(error==null)knownCheckpoint=checkpoint;
                    unresolved=error!=null&&(unresolved||r.gated);
                    busy=false;
                }
                if(error==null)return Result.ok(checkpoint);
                if(r.gated)return Result.<Checkpoint>uncertain(r.plan==null?null:r.plan.transactionId);
                Throwable cause=error;while(cause.getCause()!=null)cause=cause.getCause();
                Code code=cause instanceof Stop?((Stop)cause).code:cause instanceof WalletFailure?((WalletFailure)cause).code:Code.IO_ERROR;
                if(cause instanceof ArchiveFormat.Failure)try{code=Code.valueOf(((ArchiveFormat.Failure)cause).code);}catch(IllegalArgumentException ignored){code=Code.INVALID_ARCHIVE;}
                return Result.<Checkpoint>failed(code);
            });
    }
    private CompletionStage<Void> gate(Run r,Pair expected,boolean recovery) {
        r.gated=true; // A thrown/lost enter acknowledgement may already have fenced the web writer.
        CompletionStage<Void> reset = recovery && unresolved ? r.call(nativePort.invalidateContext(r.context)) : CompletableFuture.completedFuture(null);
        return reset.thenComposeAsync(v->r.call(web.enterRecovery(r.context)),worker).thenComposeAsync(f->{
            r.webFence=f;
            return r.call(recovery?nativePort.acquireRecoveryFence(r.context):nativePort.acquireFence(r.context,expected));
        },worker).thenAccept(f->r.nativeFence=f);
    }
    private CompletionStage<Void> verify(Run r,Pair pair) {
        return r.call(nativePort.readSnapshot(r.nativeFence,pair.nativeIdentity)).thenComposeAsync(snapshot->{
            need(snapshot!=null&&Objects.equals(snapshot.identity,pair.nativeIdentity),Code.REFERENCE_MISMATCH);
            return r.call(web.captureExact(r.webFence,pair.web)).thenComposeAsync(raw->{
                references(raw,snapshot.identity,snapshot.documents,null);
                return r.call(web.verifyActive(r.webFence,pair.web));
            },worker);
        },worker).thenAccept(id->need(pair.web.equals(id),Code.REFERENCE_MISMATCH));
    }
    private static void references(String raw,Identity nativeIdentity,List<Document> docs,String removed) {
        try {
            Map<String,Object> user=JsonTransferProtocol.map(JsonTransferJson.parse(raw,USER_BYTES));
            if(nativeIdentity==null)need(user.get("wallet")==null,Code.REFERENCE_MISMATCH);
            else {Map<String,Object> binding=JsonTransferProtocol.map(user.get("wallet"));need(nativeIdentity.generationId.equals(binding.get("generationId"))&&nativeIdentity.revision.equals(binding.get("revision")),Code.REFERENCE_MISMATCH);}
            Set<String> ids=new HashSet<>();for(Document d:docs)if(!d.id.equals(removed))ids.add(d.id);
            need(user.get("attachments") instanceof List,Code.REFERENCE_MISMATCH);
            for(Object item:(List<?>)user.get("attachments"))need(ids.contains(JsonTransferProtocol.map(item).get("documentId")),Code.REFERENCE_MISMATCH);
        }catch(JsonTransferJson.Failure e){throw new Stop(Code.INVALID_DATA);}
    }
    private CompletionStage<Checkpoint> finish(Run r,Pair pair,String tx) {
        return verify(r,pair).thenComposeAsync(v->r.call(nativePort.checkpoint(r.nativeFence,pair,tx)),worker)
            .thenComposeAsync(cp->{
                need(cp!=null&&cp.pair.equals(pair)&&Objects.equals(cp.transactionId,tx),Code.RECOVERY_REQUIRED);
                // Native release first: web remains gated until the actual native release acknowledges.
                return r.call(nativePort.releaseFence(r.nativeFence,cp)).thenComposeAsync(v->
                    r.call(web.releaseReady(r.webFence,cp,pair.nativeIdentity)),worker).thenApply(v->cp);
            },worker);
    }
    private CompletionStage<Void> retirePriorShadow(Run r,Pair prior) {
        return r.call(web.readRecovery(r.webFence)).thenComposeAsync(image->{
            if(image.transactionId==null)return CompletableFuture.completedFuture(null);
            Checkpoint initial=knownCheckpoint;
            need(initial!=null&&initial.pair.equals(prior)&&Objects.equals(initial.transactionId,image.transactionId),Code.RECOVERY_REQUIRED);
            return verify(r,prior).thenComposeAsync(v->r.call(nativePort.checkpoint(r.nativeFence,prior,image.transactionId)),worker)
                .thenComposeAsync(cp->r.call(web.discardStage(r.webFence,image.transactionId,cp,initial)),worker);
        },worker);
    }
    public CompletionStage<Result<Checkpoint>> mutate(Context context,Pair expected,String boundedChange) {
        return transact(context,expected,Operation.WEB_MUTATION,boundedChange,
            (f,p)->nativePort.stageUnchanged(f,p.transactionId,expected.nativeIdentity));
    }
    public CompletionStage<Result<Checkpoint>> delete(Context context,Pair expected,String documentId,String boundedChange) {
        WalletTypes.id(documentId,"doc");
        return transact(context,expected,Operation.DELETE,JsonTransferJson.encode(JsonTransferJson.object("type","delete-document","documentId",documentId)),
            (f,p)->nativePort.stageDelete(f,p.transactionId,expected.nativeIdentity,documentId));
    }
    public CompletionStage<Result<Checkpoint>> importDocument(Context context,Pair expected,PickerTicket ticket,String label,String boundedChange) {
        WalletTypes.label(label);
        return transact(context,expected,Operation.IMPORT,"{\"type\":\"native-binding\"}",
            (f,p)->nativePort.stageImport(f,p.transactionId,ticket,label));
    }
    public CompletionStage<Result<Checkpoint>> export(Context context,Pair expected,ExportWork output) {
        return execute(context,false,r->gate(r,expected,false).thenComposeAsync(v->verify(r,expected),worker)
            .thenComposeAsync(v->r.call(nativePort.acquireSnapshotLease(r.nativeFence,expected.nativeIdentity)),worker)
            .thenComposeAsync(lease->{
                CompletionStage<Void> copy=r.call(nativePort.readSnapshot(r.nativeFence,expected.nativeIdentity)).thenComposeAsync(snapshot->
                    r.call(web.captureExact(r.webFence,expected.web)).thenComposeAsync(raw->output.write(raw,snapshot,lease),worker),worker);
                // Always close the lease after the output worker has actually stopped, including failure.
                return copy.handleAsync((v,error)->error,worker).thenComposeAsync(error->
                    r.call(nativePort.releaseSnapshotLease(lease)).thenApply(v->{if(error!=null)throw new java.util.concurrent.CompletionException(error);return v;}),worker);
            },worker).thenComposeAsync(v->{
                Checkpoint cp=knownCheckpoint;need(cp!=null&&cp.pair.equals(expected),Code.RECOVERY_REQUIRED);
                return verify(r,expected).thenComposeAsync(ignored->r.call(nativePort.releaseFence(r.nativeFence,cp)),worker)
                    .thenComposeAsync(ignored->r.call(web.releaseReady(r.webFence,cp,expected.nativeIdentity)),worker).thenApply(ignored->cp);
            },worker));
    }
    /** Hold paired admission while copying/validating preview, then release it before confirmation UI. */
    public CompletionStage<Result<Checkpoint>> preview(Context context,Pair expected,PreviewWork input) {
        return execute(context,false,r->gate(r,expected,false).thenComposeAsync(v->verify(r,expected),worker)
            .thenComposeAsync(v->input.read(r.nativeFence).handle((ignored,error)->error),worker)
            .thenComposeAsync(error->{Checkpoint cp=knownCheckpoint;need(cp!=null&&cp.pair.equals(expected),Code.RECOVERY_REQUIRED);
                return verify(r,expected).thenComposeAsync(ignored->r.call(nativePort.releaseFence(r.nativeFence,cp)),worker)
                    .thenComposeAsync(ignored->r.call(web.releaseReady(r.webFence,cp,expected.nativeIdentity)),worker).thenApply(ignored->{
                        r.gated=false;if(error!=null)throw new java.util.concurrent.CompletionException(error);return cp;});
            },worker));
    }
    /** Restore input comes only from a validated, still-live preview owned by ArchiveProtocol. */
    CompletionStage<Result<Checkpoint>> restore(Context context,Pair expected,String exactUser,NativeStage stage) {
        return transact(context,expected,Operation.RESTORE,exactUser,stage);
    }
    private CompletionStage<Result<Checkpoint>> transact(Context context,Pair expected,Operation op,String input,NativeStage nativeStage) {
        if(input==null||input.getBytes(StandardCharsets.UTF_8).length>USER_BYTES)return CompletableFuture.completedFuture(Result.failed(Code.FILE_LIMIT));
        return execute(context,false,r->gate(r,expected,false).thenComposeAsync(v->verify(r,expected),worker)
            .thenComposeAsync(v->retirePriorShadow(r,expected),worker)
            .thenComposeAsync(v->r.call(nativePort.newTransaction(r.nativeFence,op)),worker)
            .thenComposeAsync(plan->{
                r.plan=plan;need(plan!=null&&plan.operation==op,Code.TRANSACTION_CONFLICT);r.durable=true;
                return r.call(nativePort.beginJournal(r.nativeFence,plan.transactionId,op,expected,expected));
            },worker).thenComposeAsync(begin->{
                need(begin!=null&&begin.phase==Phase.BEGIN&&begin.priorPair().equals(expected)&&begin.operation==op&&begin.transactionId.equals(r.plan.transactionId),Code.TRANSACTION_CONFLICT);
                r.journal=begin;return r.call(nativeStage.stage(r.nativeFence,r.plan));
            },worker).thenComposeAsync(nativeReceipt->{
                checkReceipt(nativeReceipt,r.plan,Store.NATIVE,ReceiptPhase.STAGED,expected.nativeIdentity);
                return r.call(web.prepareCandidate(r.webFence,op,expected.web,nativeReceipt.candidate,input))
                    .thenComposeAsync(raw->{
                        CompletionStage<Void> validation;
                        if(op==Operation.RESTORE)validation=CompletableFuture.completedFuture(null); // Protocol validates complete archive references against sealed stages.
                        else validation=r.call(nativePort.readSnapshot(r.nativeFence,expected.nativeIdentity)).thenAccept(snapshot->{
                            String removed=null;if(op==Operation.DELETE)try{removed=(String)JsonTransferProtocol.map(JsonTransferJson.parse(input,USER_BYTES)).get("documentId");}catch(JsonTransferJson.Failure e){throw new Stop(Code.INVALID_DATA);}
                            references(raw,nativeReceipt.candidate,snapshot.documents,removed);
                        });
                        return validation.thenComposeAsync(v->r.call(web.stageInactive(r.webFence,r.plan.transactionId,op,expected.web,raw,nativeReceipt.candidate,r.journal.sequence)),worker);
                    },worker)
                    .thenComposeAsync(w->{checkReceipt(w,r.plan,Store.WEB,ReceiptPhase.STAGED,expected.web);
                        return prepare(r,expected,new Pair(w.candidate,nativeReceipt.candidate));},worker);
            },worker).thenComposeAsync(prepared->forward(r,prepared),worker));
    }
    private static void checkReceipt(Receipt receipt,TxnPlan plan,Store store,ReceiptPhase phase,Identity prior) {
        need(receipt!=null&&receipt.transactionId.equals(plan.transactionId)&&receipt.operation==plan.operation&&
            receipt.store==store&&receipt.phase==phase&&Objects.equals(receipt.prior,prior),Code.TRANSACTION_CONFLICT);
    }
    private CompletionStage<Journal> prepare(Run r,Pair prior,Pair candidate) {
        return r.call(nativePort.readStaged(r.nativeFence,r.plan.transactionId)).thenComposeAsync(n->{
            checkReceipt(n,r.plan,Store.NATIVE,ReceiptPhase.STAGED,prior.nativeIdentity);
            need(Objects.equals(n.candidate,candidate.nativeIdentity),Code.REFERENCE_MISMATCH);
            return r.call(web.readStaged(r.webFence,r.plan.transactionId));
        },worker).thenComposeAsync(w->{
            checkReceipt(w,r.plan,Store.WEB,ReceiptPhase.STAGED,prior.web);need(candidate.web.equals(w.candidate),Code.REFERENCE_MISMATCH);
            return r.call(web.exportStage(r.webFence,r.plan.transactionId));
        },worker).thenComposeAsync(escrow->{
            need(sha(escrow.raw).equals(escrow.sha256),Code.HASH_MISMATCH);
            return r.call(nativePort.writeWebEscrow(r.nativeFence,r.plan.transactionId,escrow.raw,escrow.sha256))
                .thenComposeAsync(receipt->{
                    need(receipt.transactionId.equals(r.plan.transactionId)&&receipt.sha256.equals(escrow.sha256)&&receipt.byteLength==escrow.raw.getBytes(StandardCharsets.UTF_8).length,Code.HASH_MISMATCH);
                    return r.call(nativePort.readWebEscrow(r.nativeFence,r.plan.transactionId,escrow.sha256));
                },worker).thenComposeAsync(readback->{
                    need(readback.equals(escrow.raw),Code.HASH_MISMATCH);
                    Journal next=new Journal(r.plan.transactionId,r.plan.operation,Phase.PREPARED,r.journal.sequence,
                        new Change(prior.web,candidate.web),new Change(prior.nativeIdentity,candidate.nativeIdentity),escrow.sha256,
                        sha(r.plan.transactionId+"\n"+r.plan.operation+"\n"+identityKey(prior.web)+"\n"+identityKey(prior.nativeIdentity)+"\n"+identityKey(candidate.web)+"\n"+identityKey(candidate.nativeIdentity)+"\n"+escrow.sha256));
                    return r.call(nativePort.writeJournal(r.nativeFence,r.journal,next)).thenApply(j->{sameJournal(j,next);r.journal=j;return j;});
                },worker);
        },worker);
    }
    private CompletionStage<Checkpoint> forward(Run r,Journal journal) {
        need(journal.phase!=Phase.BEGIN,Code.RECOVERY_REQUIRED);
        return r.call(nativePort.activateStaged(r.nativeFence,journal.transactionId,journal)).thenComposeAsync(n->{
            need(n!=null&&n.store==Store.NATIVE&&n.phase==ReceiptPhase.ACTIVATED&&Objects.equals(n.candidate,journal.nativeChange.candidate)&&n.transactionId.equals(journal.transactionId),Code.REFERENCE_MISMATCH);
            return r.call(web.activateStaged(r.webFence,journal.transactionId,journal));
        },worker).thenComposeAsync(w->{
            need(w!=null&&w.store==Store.WEB&&w.phase==ReceiptPhase.ACTIVATED&&w.candidate.equals(journal.web.candidate)&&w.transactionId.equals(journal.transactionId),Code.REFERENCE_MISMATCH);
            return verify(r,journal.candidatePair());
        },worker).thenComposeAsync(v->{
            if(journal.phase==Phase.COMMITTED)return CompletableFuture.completedFuture(journal);
            Journal committed=new Journal(journal.transactionId,journal.operation,Phase.COMMITTED,journal.sequence,journal.web,journal.nativeChange,journal.webEscrowHash,journal.planHash);
            return r.call(nativePort.writeJournal(r.nativeFence,journal,committed)).thenApply(j->{sameJournal(j,committed);return j;});
        },worker).thenComposeAsync(j->finish(r,j.candidatePair(),j.transactionId),worker);
    }
    public CompletionStage<Result<Checkpoint>> recover(Context context) {
        return recover(context,false);
    }
    /** Startup may initialize web data only under verified empty native authority. */
    public CompletionStage<Result<Checkpoint>> start(Context context) {
        return recover(context,true);
    }
    private static boolean empty(NativeRecoveryImage n) {
        return n!=null&&!n.uncertain&&n.journal==null&&n.checkpoint==null&&n.active==null&&n.decision==null&&n.escrows.isEmpty()&&n.validatedIdentities.isEmpty();
    }
    private CompletionStage<Result<Checkpoint>> recover(Context context,boolean startup) {
        return execute(context,true,r->gate(r,null,true).thenComposeAsync(v->r.call(nativePort.readRecovery(r.nativeFence)),worker)
            .thenComposeAsync(image->{
                need(image!=null&&!image.uncertain,Code.RECOVERY_REQUIRED);
                if(startup&&empty(image))return r.call(web.initializeBaseline(r.webFence)).thenComposeAsync(identity->{
                    need(identity!=null,Code.RECOVERY_REQUIRED);return finish(r,new Pair(identity,null),null);
                },worker);
                Journal j=image.journal;
                if(j==null)return baseline(r,image);
                if(image.checkpoint!=null&&Long.parseLong(image.checkpoint.sequence)>Long.parseLong(j.sequence))return baseline(r,image);
                r.journal=j;r.durable=true;
                if(image.decision!=null){
                    RecoveryDecision d=image.decision;
                    need(d.transactionId.equals(j.transactionId)&&Long.parseLong(d.sequence)>Long.parseLong(j.sequence),Code.RECOVERY_REQUIRED);
                    if(d.decision==Decision.ROLLBACK){need(d.pair.equals(j.priorPair()),Code.REFERENCE_MISMATCH);return rollback(r,j,d);}
                    need(d.pair.equals(j.candidatePair()),Code.REFERENCE_MISMATCH);
                }
                if(j.phase==Phase.BEGIN)return chooseRollback(r,j);
                // PREPARED is the decision: never silently fall back on a candidate I/O failure.
                return hydrate(r,j).thenComposeAsync(v->forward(r,j),worker);
            },worker));
    }
    /** Gated startup only: existing validated web data and a completely empty native authority. */
    public CompletionStage<Result<Checkpoint>> bootstrap(Context context,Identity exactExistingWeb) {
        return execute(context,true,r->gate(r,null,true).thenComposeAsync(v->r.call(nativePort.readRecovery(r.nativeFence)),worker)
            .thenComposeAsync(n->{
                need(empty(n),Code.RECOVERY_REQUIRED);
                return r.call(web.readRecovery(r.webFence)).thenComposeAsync(w->{need(w.transactionId==null,Code.RECOVERY_REQUIRED);return finish(r,new Pair(exactExistingWeb,null),null);},worker);
            },worker));
    }
    private CompletionStage<Checkpoint> baseline(Run r,NativeRecoveryImage image) {
        need(image.checkpoint!=null,Code.RECOVERY_REQUIRED);
        return r.call(web.readRecovery(r.webFence)).thenComposeAsync(w->{
                need(w.transactionId==null||Objects.equals(w.transactionId,image.checkpoint.transactionId),Code.RECOVERY_REQUIRED);return verify(r,image.checkpoint.pair);
            },worker).thenComposeAsync(v->r.call(nativePort.releaseFence(r.nativeFence,image.checkpoint)),worker)
            .thenComposeAsync(v->r.call(web.releaseReady(r.webFence,image.checkpoint,image.checkpoint.pair.nativeIdentity)),worker)
            .thenApply(v->image.checkpoint);
    }
    private CompletionStage<Void> hydrate(Run r,Journal j) {
        need(j.webEscrowHash!=null,Code.RECOVERY_REQUIRED);
        return r.call(nativePort.readWebEscrow(r.nativeFence,j.transactionId,j.webEscrowHash)).thenComposeAsync(raw->{
            need(sha(raw).equals(j.webEscrowHash),Code.HASH_MISMATCH);return r.call(web.restoreEscrow(r.webFence,raw,j.webEscrowHash));
        },worker);
    }
    /** Explicit recovery choice; native persists and independently verifies this decision. */
    public CompletionStage<Result<Checkpoint>> recoverPrevious(Context context) {
        return execute(context,true,r->gate(r,null,true).thenComposeAsync(v->r.call(nativePort.readRecovery(r.nativeFence)),worker)
            .thenComposeAsync(n->{
                need(n!=null&&!n.uncertain&&n.journal!=null,Code.RECOVERY_REQUIRED);
                need(n.checkpoint==null||Long.parseLong(n.checkpoint.sequence)<=Long.parseLong(n.journal.sequence),Code.TRANSACTION_CONFLICT);
                return chooseRollback(r,n.journal);
            },worker));
    }
    private CompletionStage<Checkpoint> chooseRollback(Run r,Journal j) {
        RecoveryDecision proposed=new RecoveryDecision(j.transactionId,Decision.ROLLBACK,j.priorPair(),"explicit-prior",increment(j.sequence));
        return r.call(nativePort.recordRecoveryDecision(r.nativeFence,j,proposed)).thenComposeAsync(d->{
            need(d!=null&&d.decision==Decision.ROLLBACK&&d.pair.equals(j.priorPair())&&d.transactionId.equals(j.transactionId),Code.RECOVERY_REQUIRED);
            return rollback(r,j,d);
        },worker);
    }
    private CompletionStage<Checkpoint> rollback(Run r,Journal j,RecoveryDecision decision) {
        CompletionStage<Void> shadow=j.webEscrowHash==null?CompletableFuture.completedFuture(null):hydrate(r,j);
        return shadow.thenComposeAsync(v->r.call(nativePort.restorePrevious(r.nativeFence,j.transactionId,decision)),worker)
            .thenComposeAsync(n->{need(Objects.equals(n,j.nativeChange.prior),Code.REFERENCE_MISMATCH);return r.call(web.readRecovery(r.webFence));},worker)
            .thenComposeAsync(w->{
                if(w.transactionId==null)return r.call(web.verifyActive(r.webFence,j.web.prior));
                need(w.transactionId.equals(j.transactionId),Code.TRANSACTION_CONFLICT);
                return r.call(web.restorePrevious(r.webFence,j.transactionId,decision));
            },worker).thenComposeAsync(w->{need(w.equals(j.web.prior),Code.REFERENCE_MISMATCH);return finish(r,j.priorPair(),j.transactionId);},worker);
    }
    static String increment(String value){long n=Long.parseLong(value);need(n<Long.MAX_VALUE,Code.REVISION_LIMIT);return Long.toString(n+1);}
    static String identityKey(Identity i){return i==null?"null":i.generationId+":"+i.revision+":"+i.sha256;}
    static String sha(String raw){
        try {byte[] bytes=MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder(64);
            for(byte b:bytes){out.append(Character.forDigit((b&255)>>>4,16));out.append(Character.forDigit(b&15,16));}return out.toString();
        }catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private static void sameJournal(Journal a,Journal b){need(a!=null&&a.transactionId.equals(b.transactionId)&&a.operation==b.operation&&a.phase==b.phase&&a.sequence.equals(b.sequence)&&a.priorPair().equals(b.priorPair())&&a.candidatePair().equals(b.candidatePair())&&Objects.equals(a.webEscrowHash,b.webEscrowHash)&&Objects.equals(a.planHash,b.planHash),Code.TRANSACTION_CONFLICT);}
}
