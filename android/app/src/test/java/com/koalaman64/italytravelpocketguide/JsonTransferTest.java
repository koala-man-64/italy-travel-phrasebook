package com.koalaman64.italytravelpocketguide;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public class JsonTransferTest {
    static final String SESSION = "ses_" + "1".repeat(32);
    static String id(int n) { return "req_" + String.format("%032x", n); }
    static String payload() {
        return "{\"format\":\"itguide-user-data\",\"schemaVersion\":1,\"revision\":\"0\",\"generationId\":\"gen_" + "a".repeat(32) +
                "\",\"updatedAtUtc\":\"2026-09-27T12:00:00Z\",\"preferences\":{\"slow\":false,\"tab\":\"phrases\"}," +
                "\"legacyBuilderRaw\":null,\"saved\":[],\"progress\":[],\"wallet\":null,\"attachments\":[]}";
    }
    static String saved(String text) {
        return JsonTransferJson.encode(JsonTransferJson.object("id", "sav_" + "a".repeat(32), "sourcePhraseId", null,
                "sourceContentVersion", null, "snapshot", JsonTransferJson.object("it", text, "en", "Hello"), "createdAtUtc", "2024-02-29T00:00:00Z"));
    }
    static void reject(String code, String input) {
        assertEquals(code, assertThrows(JsonTransferJson.Failure.class, () -> JsonTransferProtocol.payload(input)).code);
    }
    @Test public void validatesFrozenDataAndExactRevisionAndDates() throws Exception {
        assertArrayEquals(payload().getBytes(StandardCharsets.UTF_8), JsonTransferProtocol.payload(payload()));
        JsonTransferProtocol.payload(payload().replace("\"revision\":\"0\"", "\"revision\":\"9223372036854775807\""));
        reject("INVALID_DATA", payload().replace("\"revision\":\"0\"", "\"revision\":\"9223372036854775808\""));
        for (String date : new String[]{"2026-02-29T12:00:00Z", "2026-09-27T24:00:00Z", "2026-09-31T12:00:00Z", "2026-09-27T12:00:60Z"})
            reject("INVALID_DATA", payload().replace("2026-09-27T12:00:00Z", date));
        reject("UNSUPPORTED_SCHEMA", payload().replace("schemaVersion\":1", "schemaVersion\":2"));
        reject("INVALID_DATA", payload().replace("\"saved\":[]", "\"extra\":true,\"saved\":[]"));
        reject("INVALID_DATA", payload().replace("\"wallet\":null", "\"wallet\":{}"));
    }
    @Test public void rejectsDuplicateEscapedKeysTrailingInputAndUnboundedDepth() throws Exception {
        reject("MALFORMED", payload().replace("\"saved\":[]", "\"saved\":[],\"sav\\u0065d\":[]"));
        reject("MALFORMED", payload() + " null");
        reject("MALFORMED", "\ufeff" + payload());
        reject("MALFORMED", payload().replace("\"slow\":false", "\"slow\":NaN"));
        reject("MALFORMED", payload().replace("\"slow\":false", "\"slow\":1e999"));
        assertThrows(JsonTransferJson.Failure.class, () -> JsonTransferJson.parse("[".repeat(25) + "]".repeat(25), 1000));
        JsonTransferJson.parse("[".repeat(24) + "]".repeat(24), 1000);
    }
    @Test public void rejectsInvalidUtf8AndLoneSurrogatesAndActualByteOverflow() throws Exception {
        for (byte[] bytes : new byte[][]{{(byte)0xc0,(byte)0x80}, {(byte)0xed,(byte)0xa0,(byte)0x80}, {(byte)0xf0,(byte)0x9f}})
            assertEquals("INVALID_UTF8", assertThrows(JsonTransferJson.Failure.class, () -> JsonTransferJson.decode(bytes)).code);
        reject("INVALID_UTF8", payload().replace("phrases", "\\ud800"));
        reject("INVALID_UTF8", payload().replace("phrases", "\udc00"));
        assertEquals("FILE_LIMIT", assertThrows(JsonTransferJson.Failure.class, () -> JsonTransferJson.utf8("é".repeat(262145), 524288)).code);
        assertEquals(524288, JsonTransferJson.utf8("é".repeat(262144), 524288).length);
    }
    @Test public void validatesSnapshotScalarsCollectionsLegacyAndDuplicateIds() throws Exception {
        JsonTransferProtocol.payload(payload().replace("\"saved\":[]", "\"saved\":[" + saved("😀".repeat(2000)) + "]"));
        reject("INVALID_DATA", payload().replace("\"saved\":[]", "\"saved\":[" + saved("😀".repeat(2001)) + "]"));
        reject("INVALID_DATA", payload().replace("\"saved\":[]", "\"saved\":[" + saved("ciao") + "," + saved("ciao") + "]"));
        reject("INVALID_DATA", payload().replace("\"saved\":[]", "\"saved\":[" + saved("ciao").replace("\"it\":", "\"italian\":") + "]"));
        reject("INVALID_DATA", payload().replace("\"legacyBuilderRaw\":null", "\"legacyBuilderRaw\":\"[]\""));
        reject("MALFORMED", payload().replace("\"legacyBuilderRaw\":null", "\"legacyBuilderRaw\":\"{\\\"x\\\":1,\\\"x\\\":2}\""));
    }
    @Test public void collectionLimitsNestedFieldsAndLegacyByteLimitFailClosed() throws Exception {
        StringBuilder saved = new StringBuilder();
        for (int i = 0; i < 201; i++) {
            if (i > 0) saved.append(',');
            saved.append(saved("ciao").replace("sav_" + "a".repeat(32), "sav_" + String.format("%032x", i)));
        }
        reject("INVALID_DATA", payload().replace("\"saved\":[]", "\"saved\":[" + saved + "]"));
        String progress = "{\"phraseId\":\"phrase_x\",\"reviewCount\":0,\"lastReviewedAtUtc\":\"2026-09-27T00:00:00Z\"}";
        reject("INVALID_DATA", payload().replace("\"progress\":[]", "\"progress\":[" + progress + "," + progress + "]"));
        reject("INVALID_DATA", payload().replace("\"progress\":[]", "\"progress\":[" + progress.replace("reviewCount\":0", "reviewCount\":1000001") + "]"));
        reject("INVALID_DATA", payload().replace("\"progress\":[]", "\"progress\":[" + progress.replace("reviewCount\":0", "reviewCount\":0.5") + "]"));
        String legacy = "{\"x\":\"" + "é".repeat(4096) + "\"}";
        reject("FILE_LIMIT", payload().replace("\"legacyBuilderRaw\":null", "\"legacyBuilderRaw\":" + JsonTransferJson.encode(legacy)));
        reject("INVALID_DATA", payload().replace("\"slow\":false", "\"slow\":false,\"unknown\":0"));
    }
    static final class Fixture implements JsonTransferController.Platform, JsonTransferController.Timer {
        final List<Map<String, Object>> results = new ArrayList<>();
        final List<Scheduled> scheduled = new ArrayList<>();
        int sessions, starts, cancels;
        long now;
        public long now() { return now; }
        final JsonTransferController controller = new JsonTransferController(this, this, () -> "ses_" + String.format("%032x", ++sessions));
        JsonTransferController.Completion callback;
        static class Scheduled { long delay; Runnable action; boolean cancelled; }
        public Runnable after(long delay, Runnable action) {
            Scheduled s = new Scheduled(); s.delay = delay; s.action = action; scheduled.add(s); return () -> s.cancelled = true;
        }
        public void start(Object token, String op, byte[] payload, JsonTransferController.Completion completion) { starts++; callback = completion; }
        public void cancel(Object token) { cancels++; }
        void receive(String raw) { try { results.add(JsonTransferProtocol.frame(raw)); } catch (Exception e) { throw new AssertionError(e); } }
        void hello() { controller.command("{\"v\":1,\"op\":\"hello\"}", this::receive); }
        String session() { return "ses_" + String.format("%032x", sessions); }
        void request(int n, String op, Object... extra) {
            Map<String, Object> m = JsonTransferJson.object("v", 1, "sessionId", session(), "requestId", id(n), "op", op);
            for (int i = 0; i < extra.length; i += 2) m.put((String)extra[i], extra[i+1]);
            controller.command(JsonTransferJson.encode(m), this::receive);
        }
        Map<String, Object> last() { return results.get(results.size() - 1); }
        long terminals(int n) { return results.stream().filter(r -> id(n).equals(r.get("requestId"))).count(); }
    }
    @Test public void helloCannotResetLiveSessionOrAuthenticateClientChosenId() {
        Fixture f = new Fixture(); f.request(1, "import"); assertEquals(0, f.starts);
        f.controller.command("{\"v\":1,\"op\":\"hello\",\"sessionId\":\"" + SESSION + "\"}", f::receive);
        assertEquals(0, f.results.size()); f.hello(); f.request(1, "import"); f.hello();
        assertEquals(1, f.sessions); assertEquals(1, f.starts);
        f.request(1, "import"); assertEquals(1, f.starts);
        f.callback.imported(payload()); assertEquals(1, f.terminals(1));
        f.request(1, "import"); assertEquals(1, f.terminals(1));
    }
    @Test public void validatesBeforePickerAndRejectsUnknownOperationalFields() {
        Fixture f = new Fixture(); f.hello(); f.request(1, "export", "payload", "{}");
        assertEquals(0, f.starts); assertEquals("UNSUPPORTED_SCHEMA", f.last().get("code"));
        f.request(2, "import", "path", "/tmp/file"); assertEquals("INVALID_REQUEST", f.last().get("code"));
        f.request(3, "import"); f.request(4, "import"); assertEquals("BUSY", f.last().get("code")); assertEquals(1, f.starts);
    }
    @Test public void cancelAndAllLateCallbackPermutationsAreExactOnce() {
        Fixture f = new Fixture(); f.hello(); f.request(1, "export", "payload", payload());
        JsonTransferController.Completion late = f.callback;
        f.request(2, "cancel", "targetRequestId", id(1));
        assertEquals("cancelled", f.last().get("status")); assertEquals("unknown", f.last().get("externalEffect"));
        late.picked(); late.exported(payload().length()); late.failed("IO_ERROR"); late.cancelled();
        f.scheduled.get(0).action.run(); assertEquals(1, f.terminals(1)); assertEquals(1, f.terminals(2));
        f.request(3, "cancel", "targetRequestId", id(1)); assertEquals("already-terminal", f.last().get("outcome"));
    }
    @Test public void pickerAndIoDeadlinesAndShortWriteCannotReportSuccess() {
        Fixture f = new Fixture(); f.hello(); f.request(1, "import");
        assertEquals(120000, f.scheduled.get(0).delay); f.scheduled.get(0).action.run();
        assertEquals("TIMEOUT", f.last().get("code")); f.callback.imported(payload()); assertEquals(1, f.terminals(1));
        f.request(2, "export", "payload", payload()); f.callback.picked();
        assertTrue(f.scheduled.get(1).cancelled); assertEquals(30000, f.scheduled.get(2).delay);
        f.scheduled.get(2).action.run(); f.callback.exported(payload().length()); assertEquals(1, f.terminals(2));
        f.request(3, "export", "payload", payload()); f.callback.exported(1); assertEquals("IO_ERROR", f.last().get("code"));
    }
    @Test public void documentAndDestroyQuarantineCallbacks() {
        Fixture f = new Fixture(); f.hello(); f.request(1, "import"); JsonTransferController.Completion old = f.callback;
        f.controller.newDocument(); f.hello(); f.request(2, "import");
        old.imported(payload()); old.failed("IO_ERROR"); assertEquals(0, f.terminals(1));
        f.callback.imported(payload()); assertEquals(1, f.terminals(2));
        f.controller.close(); int count = f.results.size(); f.hello(); assertEquals(count, f.results.size());
    }
    @Test public void staleReplyProxyFailureInvalidatesSessionWithoutCrashingHost() {
        Fixture f = new Fixture();
        f.controller.command("{\"v\":1,\"op\":\"hello\"}", raw -> { throw new IllegalStateException("gone"); });
        f.request(1, "import"); assertEquals(0, f.starts);
        f.hello(); f.request(2, "import"); assertEquals(1, f.starts);
    }
    @Test public void stalePickerTimerCannotExpireIoButElapsedDeadlineAlwaysWins() {
        Fixture f = new Fixture(); f.hello(); f.request(1, "import");
        f.callback.picked(); f.scheduled.get(0).action.run(); assertEquals(0, f.terminals(1));
        f.now = 30001; f.callback.imported(payload()); assertEquals("TIMEOUT", f.last().get("code"));
        f.request(2, "import"); f.now += 120001; f.controller.resume(); assertEquals("TIMEOUT", f.last().get("code"));
        f.callback.picked(); assertEquals(1, f.terminals(2));
    }
    @Test public void rotatesOnlyAfterThousandIdsAndDrain() {
        Fixture f = new Fixture(); f.hello();
        for (int i = 1; i < 1000; i++) f.request(i, "cancel", "targetRequestId", id(0));
        f.hello(); assertEquals(1, f.sessions);
        f.request(1000, "import"); f.hello(); assertEquals(1, f.sessions);
        f.callback.cancelled(); f.hello(); assertEquals(2, f.sessions);
        f.request(1, "import"); assertEquals(2, f.starts);
    }
    @Test public void providerReadsActualBytesAndStopsAtSentinel() throws Exception {
        byte[] exact = (payload() + " ".repeat(524288 - payload().length())).getBytes(StandardCharsets.UTF_8);
        assertEquals(524288, JsonTransferIo.read(new ByteArrayInputStream(exact), () -> {}).length());
        ByteArrayInputStream overflow = new ByteArrayInputStream(new byte[600000]);
        assertEquals("FILE_LIMIT", assertThrows(JsonTransferJson.Failure.class, () -> JsonTransferIo.read(overflow, () -> {})).code);
        assertEquals(600000 - 524289, overflow.available());
        assertThrows(IOException.class, () -> JsonTransferIo.read(new ByteArrayInputStream(exact), () -> { throw new IOException(); }));
        ByteArrayOutputStream out = new ByteArrayOutputStream(); JsonTransferIo.write(out, exact, () -> {}); assertEquals(exact.length, out.size());
        assertThrows(IOException.class, () -> JsonTransferIo.write(new OutputStream() {
            public void write(int b) throws IOException { throw new IOException(); }
        }, exact, () -> {}));
    }
    @Test public void readAndWriteCloseFailuresCannotBecomeSuccess() {
        byte[] data = payload().getBytes(StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> JsonTransferIo.read(new ByteArrayInputStream(data) {
            public void close() throws IOException { throw new IOException("close failed"); }
        }, () -> {}));
        assertThrows(IOException.class, () -> JsonTransferIo.write(new ByteArrayOutputStream() {
            public void close() throws IOException { throw new IOException("close failed"); }
        }, data, () -> {}));
        assertThrows(IOException.class, () -> JsonTransferIo.write(new ByteArrayOutputStream() {
            public void flush() throws IOException { throw new IOException("flush failed"); }
        }, data, () -> {}));
    }
}
