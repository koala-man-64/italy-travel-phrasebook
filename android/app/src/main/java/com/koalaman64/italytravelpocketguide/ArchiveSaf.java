package com.koalaman64.italytravelpocketguide;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.OperationCanceledException;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static com.koalaman64.italytravelpocketguide.WalletTypes.*;

/** Archive-only SAF admission. The host routes activity results and owns rejected handles. */
public final class ArchiveSaf implements AutoCloseable {
    private static final AtomicInteger NEXT = new AtomicInteger(0xc000);
    private static final AtomicInteger DOCUMENT_NEXT = new AtomicInteger(0x8000);
    private final boolean documentsOnly;
    // A descriptor whose retirement failed cannot be replaced safely, even by a new Activity.
    private static final ArchiveSafRetirement RETIREMENT = new ArchiveSafRetirement();
    private static final String MIME = "application/zip";
    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    // A stuck provider occupies this sole opener rather than spawning replacement workers.
    private static final ThreadPoolExecutor OPENER = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new java.util.concurrent.SynchronousQueue<>());
    private static final ThreadPoolExecutor CLEANUP = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4));
    static { OPENER.allowCoreThreadTimeOut(true); CLEANUP.allowCoreThreadTimeOut(true); }
    private Request request;
    private Request opening;
    private volatile HandleMarker handle;
    private boolean closed;

    public ArchiveSaf(Activity activity) { this(activity,false); }
    private ArchiveSaf(Activity activity,boolean documentsOnly) { this.activity = activity;this.documentsOnly=documentsOnly; }
    public static ArchiveSaf documents(Activity activity) { return new ArchiveSaf(activity,true); }

    public CompletionStage<Result<PickerTicket>> requestImport(Context context) {
        CompletableFuture<Result<PickerTicket>> answer = new CompletableFuture<>();
        main.post(() -> begin(context, false, answer));
        return answer;
    }

    /** The caller passes the returned stream to ArchiveProtocol.export, which closes it. */
    public CompletionStage<Result<OutputStream>> requestExport(Context context) {
        CompletableFuture<Result<OutputStream>> answer = new CompletableFuture<>();
        main.post(() -> begin(context, true, answer));
        return answer;
    }

    private void begin(Context context, boolean export, CompletableFuture<?> answer) {
        if(documentsOnly&&export) { failAnswer(answer,Code.UNSUPPORTED);return; }
        if (RETIREMENT.quarantined()) { uncertainAnswer(answer); return; }
        if (closed || request != null || opening != null || handle != null ||
                OPENER.getActiveCount() != 0 || CLEANUP.getActiveCount() != 0 ||
                !CLEANUP.getQueue().isEmpty()) {
            failAnswer(answer, Code.BUSY); return;
        }
        if (context == null) { failAnswer(answer, Code.INVALID_REQUEST); return; }
        AtomicInteger sequence=documentsOnly?DOCUMENT_NEXT:NEXT;
        int code = sequence.get();
        if (code > (documentsOnly?0xbfff:0xffff) || code < (documentsOnly?0x8000:0xc000)) { failAnswer(answer, Code.UNSUPPORTED); return; }
        sequence.incrementAndGet();
        Request selected = new Request(code, context, export, answer);
        request = selected;
        selected.timer = () -> expire(selected, Code.PICKER_TIMEOUT);
        main.postDelayed(selected.timer, PICKER_MS);
        Intent intent = new Intent(export ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType(documentsOnly?"*/*":MIME)
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
                .addFlags(export ? Intent.FLAG_GRANT_WRITE_URI_PERMISSION : Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (export) intent.putExtra(Intent.EXTRA_TITLE, "italy-travel-archive.zip");
        if(documentsOnly)intent.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/pdf","image/png","image/jpeg"});
        try { activity.startActivityForResult(intent, code); }
        catch (SecurityException e) { failRequest(selected, Code.PERMISSION_DENIED, false); }
        catch (RuntimeException e) { failRequest(selected, Code.PROVIDER_UNAVAILABLE, false); }
    }

    /** Returns true for this instance's outstanding picker, including a quarantined late result. */
    public boolean onActivityResult(int code, int result, Intent data) {
        Request selected = request;
        if (selected == null || selected.code != code) return false;
        main.removeCallbacks(selected.timer);
        request = null;
        if (selected.ended || closed) return true;
        if (result == Activity.RESULT_CANCELED) { failRequest(selected, Code.CANCELLED, false); return true; }
        if (result != Activity.RESULT_OK || data == null || data.getData() == null ||
                data.getClipData() != null) {
            failRequest(selected, Code.INVALID_REQUEST, false); return true;
        }
        Uri uri = data.getData();
        if (!"content".equals(uri.getScheme())) { failRequest(selected, Code.INVALID_REQUEST, false); return true; }
        opening = selected;
        selected.timer = () -> expire(selected, Code.NO_PROGRESS);
        main.postDelayed(selected.timer, NO_PROGRESS_MS);
        try {
            OPENER.execute(() -> open(selected, uri));
        } catch (RejectedExecutionException e) { failRequest(selected, Code.BUSY, false); }
        return true;
    }

    private void open(Request selected, Uri uri) {
        ParcelFileDescriptor descriptor = null;
        try {
            // "wt" explicitly truncates an existing provider document before export.
            descriptor = activity.getContentResolver().openFileDescriptor(uri,
                    selected.export ? "wt" : "r", selected.signal);
            if (descriptor == null) throw new IOException("Null provider descriptor");
            ParcelFileDescriptor owned = descriptor;
            main.post(() -> {
                if (closed || selected.ended || selected.signal.isCanceled()) {
                    if (opening == selected) opening = null;
                    dispose(owned, null); return;
                }
                main.removeCallbacks(selected.timer);
                if (opening == selected) opening = null;
                HandleMarker opened = selected.export ? new ExportHandle(selected.context, owned, selected.signal) :
                        new ImportHandle(selected.context, owned, selected.signal);
                handle = opened;
                opened.arm();
                selected.ended = true;
                if (selected.export) completeExport(selected, (OutputStream) opened);
                else completeImport(selected, (PickerTicket) opened);
            });
        } catch (SecurityException e) { postFailure(selected, Code.PERMISSION_DENIED); }
        catch (OperationCanceledException e) { postFailure(selected, Code.CANCELLED); }
        catch (IOException | RuntimeException e) { postFailure(selected, Code.IO_FAILURE); }
    }

    private void postFailure(Request selected, Code reason) {
        main.post(() -> { if (!selected.ended) failRequest(selected, reason, false); });
    }

    private void expire(Request selected, Code reason) {
        if (selected.ended) return;
        // A timed-out picker remains correlated until its actual result arrives.
        failRequest(selected, reason, request == selected);
    }

    private void failRequest(Request selected, Code reason, boolean keepPicker) {
        selected.ended = true;
        main.removeCallbacks(selected.timer);
        selected.signal.cancel();
        if (!keepPicker && request == selected) request = null;
        if (opening == selected) opening = null;
        failAnswer(selected.answer, reason);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void failAnswer(CompletableFuture answer, Code reason) {
        answer.complete(Result.failed(reason));
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void uncertainAnswer(CompletableFuture answer) {
        answer.complete(Result.uncertain(null));
    }
    @SuppressWarnings("unchecked")
    private static void completeImport(Request request, PickerTicket ticket) {
        ((CompletableFuture<Result<PickerTicket>>) request.answer).complete(Result.ok(ticket));
    }
    @SuppressWarnings("unchecked")
    private static void completeExport(Request request, OutputStream stream) {
        ((CompletableFuture<Result<OutputStream>>) request.answer).complete(Result.ok(stream));
    }

    private void dispose(ParcelFileDescriptor fd, HandleMarker owner) {
        try { CLEANUP.execute(() -> {
            try {
                RETIREMENT.retire(fd, fd::close, () -> {
                    if (owner != null) postRelease(owner);
                });
            } catch (IOException | RuntimeException failedRetirement) {
                // Keep the owner pinned; no retry can prove this descriptor retired.
            }
        }); }
        catch (RejectedExecutionException failedRetirement) { RETIREMENT.rejected(fd); }
    }

    private void postRelease(HandleMarker owner) {
        if (!main.post(() -> { if (handle == owner) handle = null; }))
            throw new IllegalStateException("Archive SAF owner release unavailable");
    }

    private abstract class Handle implements HandleMarker {
        final Context selectedContext;
        final ParcelFileDescriptor descriptor;
        final CancellationSignal signal;
        volatile boolean stopped;
        volatile long progress = SystemClock.elapsedRealtime();
        final Runnable watchdog = this::watch;
        Handle(Context context, ParcelFileDescriptor descriptor, CancellationSignal signal) {
            selectedContext = context; this.descriptor = descriptor; this.signal = signal;
        }
        public void arm() { main.postDelayed(watchdog, NO_PROGRESS_MS); }
        void watch() {
            if (stopped) return;
            if (SystemClock.elapsedRealtime() - progress >= NO_PROGRESS_MS) stop();
            else main.postDelayed(watchdog, NO_PROGRESS_MS);
        }
        void live() throws WalletFailure {
            if (stopped || signal.isCanceled()) throw new WalletFailure(Code.CANCELLED);
            if (Looper.myLooper() == Looper.getMainLooper()) throw new WalletFailure(Code.INVALID_REQUEST);
        }
        public void stop() {
            if (stopped) return;
            stopped = true;
            main.removeCallbacks(watchdog);
            signal.cancel();
            dispose(descriptor, this);
        }
    }

    private final class ImportHandle extends Handle implements PickerTicket {
        private final java.io.InputStream input;
        ImportHandle(Context context, ParcelFileDescriptor fd, CancellationSignal signal) {
            super(context, fd, signal); input = new ParcelFileDescriptor.AutoCloseInputStream(fd);
        }
        @Override public Context context() { return selectedContext; }
        @Override public int read(byte[] target, int offset, int count) throws WalletFailure {
            live();
            if (target == null || offset < 0 || count < 1 || count > CHUNK_BYTES ||
                    offset > target.length - count) throw new WalletFailure(Code.INVALID_REQUEST);
            try {
                int n = input.read(target, offset, count);
                live();
                if (n > 0) progress = SystemClock.elapsedRealtime();
                return n;
            } catch (IOException e) { throw new WalletFailure(stopped ? Code.CANCELLED : Code.IO_FAILURE); }
        }
        @Override public void close() throws WalletFailure {
            if (stopped) throw new WalletFailure(Code.CANCELLED);
            if (Looper.myLooper() == Looper.getMainLooper()) {
                stop(); throw new WalletFailure(Code.INVALID_REQUEST);
            }
            try {
                RETIREMENT.retire(descriptor, input::close, () -> {
                    if (!signal.isCanceled()) {
                        stopped = true; main.removeCallbacks(watchdog); postRelease(this);
                    }
                });
            }
            catch (IOException | RuntimeException e) {
                stopped = true; signal.cancel();
                main.removeCallbacks(watchdog);
                throw new WalletFailure(Code.IO_FAILURE);
            }
            if (signal.isCanceled()) throw new WalletFailure(Code.CANCELLED);
        }
    }

    private final class ExportHandle extends OutputStream implements HandleMarker {
        private final Context selectedContext;
        private final ParcelFileDescriptor descriptor;
        private final CancellationSignal signal;
        private final OutputStream output;
        private volatile boolean stopped;
        private volatile long progress = SystemClock.elapsedRealtime();
        private long total;
        private final Runnable watchdog = this::watch;
        ExportHandle(Context context, ParcelFileDescriptor fd, CancellationSignal signal) {
            selectedContext = context; descriptor = fd; this.signal = signal;
            output = new ParcelFileDescriptor.AutoCloseOutputStream(fd);
        }
        public void arm() { main.postDelayed(watchdog, NO_PROGRESS_MS); }
        private void watch() {
            if (stopped) return;
            if (SystemClock.elapsedRealtime() - progress >= NO_PROGRESS_MS) stop();
            else main.postDelayed(watchdog, NO_PROGRESS_MS);
        }
        private void live() throws IOException {
            if (stopped || signal.isCanceled()) throw new IOException("Archive export cancelled");
            if (Looper.myLooper() == Looper.getMainLooper()) throw new IOException("UI thread I/O refused");
        }
        @Override public void write(int b) throws IOException { write(new byte[]{(byte)b}, 0, 1); }
        @Override public void write(byte[] bytes, int offset, int count) throws IOException {
            live();
            if (bytes == null || offset < 0 || count < 0 || offset > bytes.length - count ||
                    total > ARCHIVE_BYTES - count) throw new IOException("Archive output limit");
            output.write(bytes, offset, count);
            live(); total += count; if (count > 0) progress = SystemClock.elapsedRealtime();
        }
        @Override public void flush() throws IOException { live(); output.flush(); live(); }
        public void stop() {
            if (stopped) return;
            stopped = true; signal.cancel(); main.removeCallbacks(watchdog); dispose(descriptor, this);
        }
        @Override public void close() throws IOException {
            if (stopped) throw new IOException("Archive export cancelled");
            if (Looper.myLooper() == Looper.getMainLooper()) {
                stop(); throw new IOException("UI thread close refused");
            }
            try {
                RETIREMENT.retire(descriptor, output::close, () -> {
                    if (!signal.isCanceled()) {
                        stopped = true; main.removeCallbacks(watchdog); postRelease(this);
                    }
                });
            }
            catch (IOException | RuntimeException e) {
                stopped = true; signal.cancel();
                main.removeCallbacks(watchdog);
                throw e;
            }
            if (signal.isCanceled()) throw new IOException("Archive export cancelled");
        }
    }

    private interface HandleMarker { void arm(); void stop(); }

    /** Host calls this when the selected document/session becomes obsolete. */
    public void newDocument() { main.post(this::invalidate); }

    private void invalidate() {
        Request selected = request;
        if (selected != null && !selected.ended) failRequest(selected, Code.STALE_SESSION, true);
        selected = opening;
        if (selected != null && !selected.ended) failRequest(selected, Code.STALE_SESSION, false);
        HandleMarker active = handle;
        if (active != null) active.stop();
    }

    @Override public void close() {
        main.post(() -> {
            closed = true;
            invalidate();
        });
    }

    private static final class Request {
        final int code; final Context context; final boolean export;
        final CompletableFuture<?> answer; final CancellationSignal signal = new CancellationSignal();
        boolean ended; Runnable timer;
        Request(int code, Context context, boolean export, CompletableFuture<?> answer) {
            this.code = code; this.context = context; this.export = export; this.answer = answer;
        }
    }
}
