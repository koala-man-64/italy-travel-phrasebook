package com.koalaman64.italytravelpocketguide;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.OperationCanceledException;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Native-only SAF URIs. Call public entry points on the Activity main thread. */
public final class JsonTransferSaf implements AutoCloseable, JsonTransferController.Platform {
    // Process-wide bounds survive Activity recreation, even if a provider ignores cancellation.
    private static final ThreadPoolExecutor IO = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), r -> { Thread t = new Thread(r, "json-transfer-io"); t.setDaemon(true); return t; });
    private static final ThreadPoolExecutor CLEANUP = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(2), r -> { Thread t = new Thread(r, "json-transfer-cancel"); t.setDaemon(true); return t; });
    private static final AtomicInteger NEXT_CODE = new AtomicInteger(0x4000);
    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final JsonTransferController controller;
    private Job picker;
    private Job job;
    private boolean closed;
    private static final class Job {
        final Object token;
        final String op;
        final byte[] payload;
        final JsonTransferController.Completion completion;
        final CancellationSignal signal = new CancellationSignal();
        final int requestCode;
        volatile boolean cancelled;
        volatile ParcelFileDescriptor descriptor;
        Job(Object token, String op, byte[] payload, JsonTransferController.Completion completion, int code) {
            this.token = token; this.op = op; this.payload = payload; this.completion = completion; requestCode = code;
        }
    }
    public JsonTransferSaf(Activity activity) {
        this.activity = activity;
        controller = new JsonTransferController(this, new JsonTransferController.Timer() {
            public Runnable after(long delay, Runnable action) {
                main.postDelayed(action, delay); return () -> main.removeCallbacks(action);
            }
            public long now() { return SystemClock.elapsedRealtime(); }
        });
    }
    public void command(String message, Consumer<String> reply) { controller.command(message, reply); }
    public void newDocument() { controller.newDocument(); }
    public void resume() { controller.resume(); }
    @Override public void start(Object token, String op, byte[] payload, JsonTransferController.Completion completion) {
        if (closed) { completion.failed("INTERRUPTED"); return; }
        if (picker != null || IO.getActiveCount() != 0) { completion.failed("BUSY"); return; }
        int code = NEXT_CODE.getAndIncrement();
        // Never recycle request codes: an old Activity/provider result must not match a successor.
        if (code > 0x7fff) { completion.failed("UNSUPPORTED"); return; }
        Job next = new Job(token, op, payload, completion, code);
        picker = next; job = next;
        Intent intent = new Intent(op.equals("import") ? Intent.ACTION_OPEN_DOCUMENT : Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("application/json");
        if (op.equals("export")) intent.putExtra(Intent.EXTRA_TITLE, "italy-personal-data.json");
        try { activity.startActivityForResult(intent, code); }
        catch (ActivityNotFoundException e) { picker = null; completion.failed("PROVIDER_UNAVAILABLE"); }
        catch (SecurityException e) { picker = null; completion.failed("PERMISSION_DENIED"); }
        catch (RuntimeException e) { picker = null; completion.failed("PROVIDER_UNAVAILABLE"); }
    }
    /** Return true only for this instance's outstanding picker result. */
    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        Job current = picker;
        if (current == null || current.requestCode != requestCode) return false;
        picker = null;
        if (closed || current.cancelled) return true;
        if (resultCode == Activity.RESULT_CANCELED) { current.completion.cancelled(); return true; }
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            current.completion.failed("PROVIDER_UNAVAILABLE"); return true;
        }
        Uri uri = data.getData();
        if (!"content".equals(uri.getScheme())) { current.completion.failed("INVALID_DATA"); return true; }
        current.completion.picked();
        if (current.cancelled) return true;
        try { IO.execute(() -> transfer(current, uri)); }
        catch (RejectedExecutionException busy) { current.completion.failed("BUSY"); }
        return true;
    }
    private void transfer(Job current, Uri uri) {
        try {
            String imported = null;
            ensureLive(current);
            ParcelFileDescriptor fd = activity.getContentResolver().openFileDescriptor(uri,
                    current.op.equals("import") ? "r" : "wt", current.signal);
            if (fd == null) throw new IOException();
            current.descriptor = fd;
            try (ParcelFileDescriptor owned = fd) {
                ensureLive(current);
                if (current.op.equals("import")) {
                    imported = JsonTransferIo.read(new ParcelFileDescriptor.AutoCloseInputStream(owned), () -> ensureLive(current));
                    ensureLive(current);
                } else {
                    JsonTransferIo.write(new ParcelFileDescriptor.AutoCloseOutputStream(owned), current.payload, () -> ensureLive(current));
                    ensureLive(current);
                    // Delivery occurs after both stream AND owning descriptor have closed below.
                }
            }
            final String result = imported;
            if (current.op.equals("import")) main.post(() -> {
                if (!closed && !current.cancelled) current.completion.imported(result);
            });
            if (current.op.equals("export")) main.post(() -> {
                if (!closed && !current.cancelled) current.completion.exported(current.payload.length);
            });
        } catch (JsonTransferJson.Failure invalid) { fail(current, invalid.code); }
        catch (SecurityException denied) { fail(current, "PERMISSION_DENIED"); }
        catch (OperationCanceledException cancelled) { fail(current, "INTERRUPTED"); }
        catch (IOException | RuntimeException failure) { fail(current, "IO_ERROR"); }
        finally { current.descriptor = null; }
    }
    private void fail(Job current, String code) {
        main.post(() -> { if (!closed && !current.cancelled) current.completion.failed(code); });
    }
    private static void ensureLive(Job current) throws IOException {
        if (current.cancelled || Thread.currentThread().isInterrupted()) throw new IOException("INTERRUPTED");
    }
    @Override public void cancel(Object token) {
        Job current = job;
        if (current == null || current.token != token) return;
        job = null; current.cancelled = true;
        cleanup(current.signal::cancel);
        ParcelFileDescriptor fd = current.descriptor;
        if (fd != null) cleanup(() -> { try { fd.close(); } catch (IOException ignored) { /* best effort only */ } });
        // Keep picker correlation until the actual result arrives. No second picker on timeout/cancel.
    }
    private static void cleanup(Runnable action) {
        try { CLEANUP.execute(action); } catch (RejectedExecutionException ignored) { /* bounded best effort */ }
    }
    @Override public void close() { closed = true; controller.close(); }
}
