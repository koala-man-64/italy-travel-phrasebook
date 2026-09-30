package com.koalaman64.italytravelpocketguide;

import java.util.concurrent.CompletionStage;
import static com.koalaman64.italytravelpocketguide.WalletTypes.*;

/** Sole native persistence seam. No caller receives a path or writable descriptor. */
public interface WalletNativePort {
    CompletionStage<Result<NativeFence>> acquireFence(Context context,Pair expected);
    CompletionStage<Result<NativeFence>> acquireRecoveryFence(Context context);
    CompletionStage<Result<Void>> invalidateContext(Context context);
    CompletionStage<Result<NativeRecoveryImage>> readRecovery(NativeFence fence);
    CompletionStage<Result<NativeSnapshot>> readSnapshot(NativeFence fence,Identity expected);
    CompletionStage<Result<NativeSnapshot>> getSnapshot(Context context);
    CompletionStage<Result<BoundedSpoolSink>> createSpool(NativeFence fence,long maximumBytes);
    CompletionStage<Result<ReadOnlySeekableHandle>> sealSpool(NativeFence fence,String spoolId);
    CompletionStage<Result<BoundedDocumentSink>> createStagedDocument(NativeFence fence,String transactionId,String generationId,String documentId,long maximumBytes);
    CompletionStage<Result<ValidatedDocument>> sealStagedDocument(NativeFence fence,String transactionId,String documentId,long expectedActualBytes,String expectedHash);
    CompletionStage<Result<EscrowReceipt>> writeWebEscrow(NativeFence fence,String transactionId,String boundedWebStage,String expectedHash);
    CompletionStage<Result<String>> readWebEscrow(NativeFence fence,String transactionId,String expectedHash);
    CompletionStage<Result<TxnPlan>> newTransaction(NativeFence fence,Operation operation);
    CompletionStage<Result<Journal>> beginJournal(NativeFence fence,String transactionId,Operation operation,Pair prior,Pair planned);
    CompletionStage<Result<Receipt>> stageArchive(NativeFence fence,String transactionId,ValidatedArchive archive,NativeStagingHandles handles);
    CompletionStage<Result<Receipt>> stageDelete(NativeFence fence,String transactionId,Identity expected,String documentId);
    CompletionStage<Result<Receipt>> stageImport(NativeFence fence,String transactionId,PickerTicket ticket,String displayName);
    CompletionStage<Result<Receipt>> stageUnchanged(NativeFence fence,String transactionId,Identity expected);
    CompletionStage<Result<Receipt>> readStaged(NativeFence fence,String transactionId);
    CompletionStage<Result<Journal>> writeJournal(NativeFence fence,Journal expected,Journal next);
    CompletionStage<Result<Receipt>> activateStaged(NativeFence fence,String transactionId,Journal prepared);
    CompletionStage<Result<Identity>> restorePrevious(NativeFence fence,String transactionId,RecoveryDecision decision);
    CompletionStage<Result<Receipt>> verifyActive(NativeFence fence,Identity expected);
    CompletionStage<Result<RecoveryDecision>> recordRecoveryDecision(NativeFence fence,Journal expected,RecoveryDecision next);
    CompletionStage<Result<Checkpoint>> checkpoint(NativeFence fence,Pair pair,String transactionId);
    CompletionStage<Result<Void>> releaseFence(NativeFence fence,Checkpoint checkpoint);
    CompletionStage<Result<Lease>> acquireSnapshotLease(NativeFence fence,Identity expected);
    CompletionStage<Result<ReadOnlyDocumentHandle>> openSnapshotDocument(Lease lease,String documentId);
    CompletionStage<Result<Void>> releaseSnapshotLease(Lease lease);
    CompletionStage<Result<Long>> renewSnapshotLease(Lease lease);
    CompletionStage<Result<Lease>> acquireViewerLease(Context context,Identity expected,String documentId);
    CompletionStage<Result<CollectionCounts>> collectEligible(NativeFence fence,Checkpoint checkpoint);
}
