package com.koalaman64.italytravelpocketguide;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Serialized session/terminal ownership, independent of Android for deterministic race tests. */
public final class JsonTransferController implements AutoCloseable {
    public static final long PICKER_TIMEOUT_MS = 120000, IO_TIMEOUT_MS = 30000;
    interface Timer {
        Runnable after(long millis, Runnable action);
        default long now() { return System.nanoTime() / 1000000; }
    }
    interface Platform {
        void start(Object token, String op, byte[] payload, Completion completion);
        void cancel(Object token);
    }
    interface Completion {
        void picked();
        void imported(String payload);
        void exported(int bytes);
        void cancelled();
        void failed(String code);
    }
    private final Platform platform;
    private final Timer timer;
    private final Supplier<String> ids;
    private final Set<String> seen = new HashSet<>();
    private String session;
    private Consumer<String> events;
    private Operation active;
    private boolean closed;
    private static final class Operation {
        final JsonTransferProtocol.Request request;
        Runnable stopTimer = () -> {};
        boolean io;
        int exportBytes;
        long deadline;
        Operation(JsonTransferProtocol.Request request) { this.request = request; }
    }
    JsonTransferController(Platform platform, Timer timer) {
        this(platform, timer, () -> "ses_" + UUID.randomUUID().toString().replace("-", ""));
    }
    JsonTransferController(Platform platform, Timer timer, Supplier<String> ids) {
        this.platform = platform; this.timer = timer; this.ids = ids;
    }
    /** Called for EVERY top-level document start, including untrusted replacements. */
    public synchronized void newDocument() {
        events = null; session = null; seen.clear();
        if (active != null) { Operation old = active; active = null; old.stopTimer.run(); platform.cancel(old); }
    }
    /** Host must first authenticate main frame, source origin AND current exact document URL. */
    public synchronized void command(String raw, Consumer<String> reply) {
        if (closed) return;
        Map<String, Object> m;
        try {
            m = JsonTransferProtocol.frame(raw);
            if (JsonTransferProtocol.hello(m)) {
                if (session == null || (seen.size() >= 1000 && active == null)) {
                    session = ids.get(); seen.clear(); events = reply;
                }
                deliver(reply, JsonTransferJson.encode(JsonTransferJson.object("v", 1, "op", "ready", "sessionId", session)));
                return;
            }
        } catch (JsonTransferJson.Failure malformed) { return; }
        // Only well-formed identities in the current native-issued session receive results.
        if (session == null || !session.equals(m.get("sessionId")) || !(m.get("requestId") instanceof String)
                || !((String) m.get("requestId")).matches("req_[0-9a-f]{32}")
                || !java.util.Arrays.asList("import", "export", "cancel").contains(m.get("op"))) return;
        String requestId = (String) m.get("requestId");
        if (seen.size() >= 1000 || !seen.add(requestId)) return;
        JsonTransferProtocol.Request request;
        try { request = JsonTransferProtocol.request(m); }
        catch (JsonTransferJson.Failure invalid) {
            emit(error((String) m.get("op"), requestId, "INVALID_REQUEST", "none")); return;
        }
        if (request.op.equals("cancel")) {
            Operation target = active;
            boolean matches = target != null && target.request.requestId.equals(request.targetRequestId);
            Map<String, Object> result = base(request.op, requestId, "ok");
            result.put("outcome", matches ? "requested" : "already-terminal");
            emit(result);
            if (matches) terminate(target, "cancelled", null, null);
            return;
        }
        if (active != null) { emit(error(request.op, requestId, "BUSY", "none")); return; }
        byte[] payload = null;
        if (request.op.equals("export")) {
            try { payload = JsonTransferProtocol.payload(request.payload); }
            catch (JsonTransferJson.Failure invalid) { emit(error(request.op, requestId, invalid.code, "none")); return; }
        }
        Operation operation = new Operation(request);
        operation.exportBytes = payload == null ? 0 : payload.length;
        active = operation;
        operation.deadline = timer.now() + PICKER_TIMEOUT_MS;
        operation.stopTimer = timer.after(PICKER_TIMEOUT_MS, () -> timeout(operation, false));
        try {
            platform.start(operation, request.op, payload, new Completion() {
                public void picked() { beginIo(operation); }
                public void imported(String data) { importDone(operation, data); }
                public void exported(int bytes) { exportDone(operation, bytes); }
                public void cancelled() { terminate(operation, "cancelled", null, null); }
                public void failed(String code) { terminate(operation, "error", code, null); }
            });
        } catch (RuntimeException e) { terminate(operation, "error", "PROVIDER_UNAVAILABLE", null); }
    }
    private synchronized void beginIo(Operation operation) {
        if (active != operation || operation.io) return;
        if (expired(operation)) return;
        operation.io = true; operation.stopTimer.run();
        operation.deadline = timer.now() + IO_TIMEOUT_MS;
        operation.stopTimer = timer.after(IO_TIMEOUT_MS, () -> timeout(operation, true));
    }
    private synchronized void timeout(Operation operation, boolean io) {
        if (active == operation && operation.io == io) terminate(operation, "error", "TIMEOUT", null);
    }
    private boolean expired(Operation operation) {
        if (timer.now() < operation.deadline) return false;
        terminate(operation, "error", "TIMEOUT", null); return true;
    }
    /** Re-check elapsed time after device sleep; Android Handler deadlines alone use uptime. */
    public synchronized void resume() { if (active != null) expired(active); }
    private synchronized void importDone(Operation operation, String payload) {
        if (active != operation) return;
        if (expired(operation)) return;
        try {
            JsonTransferProtocol.payload(payload);
            Map<String, Object> result = base("import", operation.request.requestId, "ok");
            result.put("payload", payload); result.put("externalEffect", "none");
            JsonTransferJson.utf8(JsonTransferJson.encode(result), JsonTransferJson.FRAME_LIMIT);
            terminate(operation, "ok", null, result);
        } catch (JsonTransferJson.Failure e) { terminate(operation, "error", e.code, null); }
    }
    private synchronized void exportDone(Operation operation, int bytes) {
        if (active != operation) return;
        if (expired(operation)) return;
        if (bytes != operation.exportBytes || bytes == 0) { terminate(operation, "error", "IO_ERROR", null); return; }
        Map<String, Object> result = base("export", operation.request.requestId, "ok");
        result.put("bytesWritten", bytes); result.put("externalEffect", "complete");
        terminate(operation, "ok", null, result);
    }
    private synchronized void terminate(Operation operation, String status, String code, Map<String, Object> result) {
        if (active != operation) return;
        active = null; operation.stopTimer.run();
        platform.cancel(operation);
        if (result == null) {
            result = base(operation.request.op, operation.request.requestId, status);
            if (code != null) result.put("code", code);
            // Creating the document itself may have external effects before I/O begins.
            result.put("externalEffect", operation.request.op.equals("export") ? "unknown" : "none");
        }
        emit(result);
    }
    private Map<String, Object> base(String op, String request, String status) {
        return JsonTransferJson.object("v", 1, "sessionId", session, "requestId", request, "op", op, "status", status);
    }
    private Map<String, Object> error(String op, String request, String code, String effect) {
        Map<String, Object> result = base(op, request, "error"); result.put("code", code); result.put("externalEffect", effect); return result;
    }
    private void emit(Map<String, Object> result) { if (events != null) deliver(events, JsonTransferJson.encode(result)); }
    private void deliver(Consumer<String> sink, String result) {
        try { sink.accept(result); }
        catch (RuntimeException unavailableDocument) { newDocument(); }
    }
    @Override public synchronized void close() { newDocument(); closed = true; }
}
