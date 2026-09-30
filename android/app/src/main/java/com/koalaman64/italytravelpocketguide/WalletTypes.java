package com.koalaman64.italytravelpocketguide;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Frozen Wave3 native seam. POJOs deliberately avoid record runtime requirements. */
public final class WalletTypes {
    private WalletTypes() {}
    public static final long DOCUMENT_BYTES = 20L * 1024 * 1024;
    public static final long GENERATION_BYTES = 250L * 1024 * 1024;
    public static final long ARCHIVE_BYTES = 251L * 1024 * 1024 + 65536;
    public static final long STORE_BYTES = 1024L * 1024 * 1024;
    public static final long HEADROOM_BYTES = 16L * 1024 * 1024;
    public static final int DOCUMENTS = 50, PAGES = 100, EDGE = 16384, TILE_EDGE = 256;
    public static final long PIXELS = 40_000_000L;
    public static final int USER_BYTES = 512 * 1024, ESCROW_BYTES = 2 * 1024 * 1024;
    public static final int MANIFEST_BYTES = 256 * 1024, JOURNAL_BYTES = 16 * 1024, CHUNK_BYTES = 65536;
    public static final int TILE_BYTES = 256 * 256 * 4, PARCEL_BYTES = TILE_BYTES + 1024;
    public static final long PICKER_MS = 120000, NO_PROGRESS_MS = 30000, ARCHIVE_MS = 180000;
    public static final long VALIDATION_MS = 120000, SERVICE_MS = 5000, HOST_MS = 7000;

    public enum Code {
        BUSY, UNSUPPORTED, INVALID_REQUEST, INVALID_ID, STALE_SESSION, REVISION_CONFLICT,
        REVISION_LIMIT, GENERATION_COLLISION, NOT_FOUND, REFERENCE_MISMATCH, CANCELLED,
        ALREADY_COMMITTED, PICKER_TIMEOUT, COPY_TIMEOUT, VALIDATION_TIMEOUT,
        PROVIDER_UNAVAILABLE, PERMISSION_DENIED, IO_FAILURE, NO_PROGRESS, EMPTY_DOCUMENT,
        BYTE_LIMIT, COUNT_LIMIT, STORAGE_LIMIT, NO_SPACE, HASH_MISMATCH, UNSUPPORTED_FORMAT,
        ENCRYPTED_OR_INVALID_PDF, INVALID_DOCUMENT, PAGE_LIMIT, DIMENSION_LIMIT,
        RENDERER_DIED, RENDERER_TIMEOUT, INVALID_RENDER_REPLY, LEASE_EXPIRED, RECOVERY_REQUIRED,
        UNSUPPORTED_ARCHIVE_PROFILE, UNSUPPORTED_VERSION, INVALID_ARCHIVE, INVALID_UTF8,
        INVALID_DATA, DUPLICATE_KEY, DUPLICATE_ID, FILE_LIMIT, DOCUMENT_LIMIT, CRC_MISMATCH,
        TRANSACTION_CONFLICT, CONFIRMATION_REQUIRED, RESTORE_CONFLICT, STORAGE_FULL,
        STORAGE_ERROR, IO_ERROR, TIMEOUT, INTERRUPTED
    }
    public enum Status { OK, FAILED, UNCERTAIN }
    public enum Operation { RESTORE, DELETE, IMPORT, WEB_MUTATION }
    public enum Phase { BEGIN, PREPARED, COMMITTED }
    public enum ReceiptPhase { STAGED, ACTIVATED }
    public enum Store { WEB, NATIVE }
    public enum Outcome { BASELINE, COMMITTED, RECOVERED_OLD }
    public enum Decision { FORWARD, ROLLBACK }
    public enum Purpose { EXPORT, VIEW }
    public static final class WalletFailure extends IOException {
        private static final long serialVersionUID = 1L;
        public final Code code;
        public WalletFailure(Code code) { super(code.name()); this.code = Objects.requireNonNull(code); }
    }
    public static final class Result<T> {
        public final Status status; public final T value; public final Code code; public final String transactionId;
        private Result(Status s, T v, Code c, String tx) { status=s; value=v; code=c; transactionId=tx; }
        public static <T> Result<T> ok(T value) { return new Result<>(Status.OK, value, null, null); }
        public static <T> Result<T> failed(Code code) { return new Result<>(Status.FAILED, null, Objects.requireNonNull(code), null); }
        public static <T> Result<T> uncertain(String tx) {
            if (tx != null) id(tx,"txn");
            return new Result<>(Status.UNCERTAIN,null,Code.RECOVERY_REQUIRED,tx);
        }
    }
    public static String id(String value, String prefix) {
        if (value == null || !value.matches(prefix + "_[0-9a-f]{32}")) throw new IllegalArgumentException("INVALID_ID");
        return value;
    }
    public static String revision(String value) {
        if (value == null || !value.matches("0|[1-9][0-9]{0,18}") ||
                (value.length()==19 && value.compareTo("9223372036854775807")>0)) throw new IllegalArgumentException("REVISION_LIMIT");
        return value;
    }
    public static String hash(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("HASH_MISMATCH");
        return value;
    }
    public static String label(String value) {
        if (value == null || value.isEmpty() || value.length()>240 || value.codePointCount(0,value.length())>120)
            throw new IllegalArgumentException("INVALID_DATA");
        for (int i=0;i<value.length();) {
            int cp=value.codePointAt(i), type=Character.getType(cp);
            if (type==Character.CONTROL || type==Character.FORMAT || type==Character.SURROGATE)
                throw new IllegalArgumentException("INVALID_DATA");
            i+=Character.charCount(cp);
        }
        return value;
    }
    static String contextText(String value) {
        if (value==null || value.isEmpty() || value.length()>128) throw new IllegalArgumentException("INVALID_REQUEST");
        for(int i=0;i<value.length();i++) if(value.charAt(i)<33 || value.charAt(i)>126) throw new IllegalArgumentException("INVALID_REQUEST");
        return value;
    }
    static <T> List<T> copy(List<T> values, int max) {
        if(values==null || values.size()>max || values.contains(null)) throw new IllegalArgumentException("COUNT_LIMIT");
        return Collections.unmodifiableList(new ArrayList<>(values));
    }
    public static final class Identity {
        public final String generationId, revision, sha256; public final int schemaVersion=1;
        public Identity(String generationId,String revision,String sha256) {
            this.generationId=id(generationId,"gen"); this.revision=revision(revision); this.sha256=hash(sha256);
        }
        @Override public boolean equals(Object o) {
            if(!(o instanceof Identity))return false; Identity v=(Identity)o;
            return generationId.equals(v.generationId)&&revision.equals(v.revision)&&sha256.equals(v.sha256);
        }
        @Override public int hashCode(){return Objects.hash(generationId,revision,sha256);}
    }
    public static final class Pair {
        public final Identity web, nativeIdentity; // wire key for nativeIdentity is "native"
        public Pair(Identity web,Identity nativeIdentity){this.web=Objects.requireNonNull(web);this.nativeIdentity=nativeIdentity;}
        @Override public boolean equals(Object o){return o instanceof Pair && web.equals(((Pair)o).web)&&Objects.equals(nativeIdentity,((Pair)o).nativeIdentity);}
        @Override public int hashCode(){return Objects.hash(web,nativeIdentity);}
    }
    public static final class Context {
        public final String sessionId,documentId,epoch,requestId;
        public Context(String s,String d,String e,String r){sessionId=contextText(s);documentId=contextText(d);epoch=revision(e);requestId=contextText(r);}
    }
    public static final class Receipt {
        public final int v=1; public final String transactionId; public final Operation operation;
        public final Store store; public final ReceiptPhase phase; public final Identity prior,candidate;
        public Receipt(String tx,Operation op,Store store,ReceiptPhase phase,Identity prior,Identity candidate){
            transactionId=id(tx,"txn");operation=Objects.requireNonNull(op);this.store=Objects.requireNonNull(store);
            this.phase=Objects.requireNonNull(phase);this.prior=prior;this.candidate=candidate;
            if(store==Store.WEB && (prior==null||candidate==null))throw new IllegalArgumentException("INVALID_DATA");
        }
    }
    public static final class Change {
        public final Identity prior,candidate;
        public Change(Identity prior,Identity candidate){this.prior=prior;this.candidate=candidate;}
    }
    public static final class Journal {
        public final int v=1; public final String transactionId,sequence,webEscrowHash,planHash;
        public final Operation operation; public final Phase phase; public final Change web,nativeChange;
        public Journal(String tx,Operation op,Phase phase,String sequence,Change web,Change nativeChange,String escrow,String plan){
            transactionId=id(tx,"txn");operation=Objects.requireNonNull(op);this.phase=Objects.requireNonNull(phase);
            this.sequence=revision(sequence);this.web=Objects.requireNonNull(web);this.nativeChange=Objects.requireNonNull(nativeChange);
            webEscrowHash=escrow==null?null:hash(escrow);planHash=plan==null?null:hash(plan);
            if(web.prior==null || (phase!=Phase.BEGIN && (web.candidate==null||escrow==null||plan==null)))throw new IllegalArgumentException("INVALID_DATA");
        }
        public Pair priorPair(){return new Pair(web.prior,nativeChange.prior);}
        public Pair candidatePair(){return new Pair(web.candidate,nativeChange.candidate);}
    }
    public static final class Checkpoint {
        public final int v=1; public final String sequence,transactionId; public final Pair pair; public final Outcome outcome;
        public Checkpoint(String seq,Pair pair,String tx,Outcome outcome){sequence=revision(seq);this.pair=Objects.requireNonNull(pair);transactionId=tx==null?null:id(tx,"txn");this.outcome=Objects.requireNonNull(outcome);}
    }
    public static final class RecoveryDecision {
        public final String transactionId,reason,sequence; public final Decision decision; public final Pair pair;
        public RecoveryDecision(String tx,Decision d,Pair pair,String reason,String seq){transactionId=id(tx,"txn");decision=Objects.requireNonNull(d);this.pair=Objects.requireNonNull(pair);this.reason=contextText(reason);sequence=revision(seq);}
    }
    public static final class EscrowReceipt {
        public final String transactionId,sha256; public final long byteLength;
        public EscrowReceipt(String tx,long count,String sha){transactionId=id(tx,"txn");if(count<1||count>ESCROW_BYTES)throw new IllegalArgumentException("STORAGE_LIMIT");byteLength=count;sha256=hash(sha);}
    }
    /** Constructor and identity intentionally package-private: store owns capability issuance. */
    public static final class NativeFence {
        final Object owner; final Context context; final Pair expected; final boolean recovery;
        NativeFence(Object owner,Context c,Pair p,boolean recovery){this.owner=owner;context=c;expected=p;this.recovery=recovery;}
    }
    public static final class TxnPlan {
        public final String transactionId,nativeGenerationId,nativeRevision; public final Operation operation;
        TxnPlan(String tx,String gen,String rev,Operation op){transactionId=id(tx,"txn");nativeGenerationId=gen==null?null:id(gen,"gen");nativeRevision=rev==null?null:revision(rev);operation=op;}
    }
    public static final class Lease {
        public final String leaseId; public final Identity identity; public final Purpose purpose; public final long deadlineMs;
        final Object owner;
        Lease(Object owner,String id,Identity identity,Purpose p,long deadline){this.owner=owner;leaseId=WalletTypes.id(id,"lease");this.identity=identity;purpose=p;deadlineMs=deadline;}
    }
    public static final class PageSize {
        public final int width,height;
        public PageSize(int w,int h){if(w<1||h<1||w>EDGE||h>EDGE||(long)w*h>PIXELS)throw new IllegalArgumentException("DIMENSION_LIMIT");width=w;height=h;}
    }
    public static final class Document {
        public final String id,kind,sha256,displayName,importedAtUtc;
        public final long byteLength; public final List<PageSize> pages; public final int orientation;
        public Document(String id,String kind,long bytes,String hash,String label,String utc,List<PageSize> pages,int orientation){
            this.id=WalletTypes.id(id,"doc");if(!"pdf".equals(kind)&&!"png".equals(kind)&&!"jpeg".equals(kind))throw new IllegalArgumentException("UNSUPPORTED_FORMAT");this.kind=kind;
            if(bytes<1||bytes>DOCUMENT_BYTES)throw new IllegalArgumentException("BYTE_LIMIT");byteLength=bytes;sha256=WalletTypes.hash(hash);displayName=WalletTypes.label(label);
            if(utc==null||!utc.matches("20[0-9]{2}-[0-9]{2}-[0-9]{2}T(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]Z"))throw new IllegalArgumentException("INVALID_DATA");
            java.time.Instant.parse(utc);importedAtUtc=utc;
            this.pages=copy(pages,PAGES);if(this.pages.isEmpty()||(!kind.equals("pdf")&&this.pages.size()!=1)||orientation<1||orientation>8)throw new IllegalArgumentException("INVALID_DOCUMENT");this.orientation=orientation;
        }
    }
    public static final class Ref {
        public final String tripId,eventId,documentId;
        public Ref(String trip,String event,String doc){if(trip==null||!trip.matches("trip_[a-z0-9][a-z0-9_-]{0,63}")||event==null||!event.matches("event_[a-z0-9][a-z0-9_-]{0,63}"))throw new IllegalArgumentException("INVALID_ID");tripId=trip;eventId=event;documentId=id(doc,"doc");}
    }
    public static final class ValidatedDocument {
        public final String stageId; public final Document document;
        ValidatedDocument(String stage,Document d){stageId=stage;document=d;}
    }
    public static final class ValidatedArchive {
        public final List<Document> documents; public final List<Ref> references;public final boolean walletPresent;
        public ValidatedArchive(List<Document> d,List<Ref> r){this(d,r,true);}
        public ValidatedArchive(List<Document> d,List<Ref> r,boolean walletPresent){documents=copy(d,DOCUMENTS);references=copy(r,1000);this.walletPresent=walletPresent;if(!walletPresent&&(!d.isEmpty()||!r.isEmpty()))throw new IllegalArgumentException("REFERENCE_MISMATCH");}
    }
    public static final class NativeStagingHandles {
        public final List<String> opaqueStageIds;
        public NativeStagingHandles(List<String> ids){opaqueStageIds=copy(ids,DOCUMENTS);}
    }
    public static final class NativeSnapshot {
        public final Identity identity;public final List<Document> documents;
        public NativeSnapshot(Identity identity,List<Document> documents){this.identity=identity;this.documents=copy(documents,DOCUMENTS);if(identity==null&&!documents.isEmpty())throw new IllegalArgumentException("REFERENCE_MISMATCH");}
    }
    public static final class CollectionCounts {
        public final int objectsRemoved,retainedFailures; public final long bytesRemoved;
        public CollectionCounts(int n,long b,int f){objectsRemoved=n;bytesRemoved=b;retainedFailures=f;}
    }
    public static final class NativeRecoveryImage {
        public final Journal journal; public final Checkpoint checkpoint; public final RecoveryDecision decision;
        public final Identity active; public final List<Identity> validatedIdentities; public final List<EscrowReceipt> escrows;
        public final boolean uncertain;
        public NativeRecoveryImage(Journal j,Checkpoint c,RecoveryDecision d,Identity a,List<Identity> ids,List<EscrowReceipt> escrows,boolean u){journal=j;checkpoint=c;decision=d;active=a;validatedIdentities=copy(ids,1000);this.escrows=copy(escrows,1000);uncertain=u;}
    }
    public interface BoundedSink extends AutoCloseable {
        void write(byte[] bytes,int offset,int length) throws WalletFailure;
        long bytesWritten(); void cancel(); void close() throws WalletFailure;
    }
    public interface BoundedSpoolSink extends BoundedSink { String spoolId(); }
    public interface BoundedDocumentSink extends BoundedSink { String stageId(); }
    public interface ReadOnlySeekableHandle extends AutoCloseable {
        long byteLength(); int read(long offset,byte[] target,int start,int length) throws WalletFailure;
        void close() throws WalletFailure;
    }
    public interface ReadOnlyDocumentHandle extends ReadOnlySeekableHandle {}
    /** SAF-owned input capability. Not constructible from a JS URI or string. */
    public interface PickerTicket extends AutoCloseable {
        Context context(); int read(byte[] target,int offset,int count) throws WalletFailure;
        void close() throws WalletFailure;
    }
}
