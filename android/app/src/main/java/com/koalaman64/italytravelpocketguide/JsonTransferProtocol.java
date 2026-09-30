package com.koalaman64.italytravelpocketguide;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Frozen Wave 0 operational grammar and wallet-free UserData validation. */
final class JsonTransferProtocol {
    static final class Request {
        final String sessionId, requestId, op, payload, targetRequestId;
        Request(Map<String, Object> m) {
            sessionId = (String) m.get("sessionId"); requestId = (String) m.get("requestId");
            op = (String) m.get("op"); payload = (String) m.get("payload");
            targetRequestId = (String) m.get("targetRequestId");
        }
    }
    static Map<String, Object> frame(String raw) throws JsonTransferJson.Failure {
        return map(JsonTransferJson.parse(raw, JsonTransferJson.FRAME_LIMIT));
    }
    static boolean hello(Map<String, Object> m) throws JsonTransferJson.Failure {
        if (!"hello".equals(m.get("op"))) return false;
        keys(m, "v", "op"); integer(m.get("v"), 1, 1); return true;
    }
    static Request request(Map<String, Object> m) throws JsonTransferJson.Failure {
        String op = text(m.get("op"), 1, 6);
        switch (op) {
            case "import": keys(m, "v", "sessionId", "requestId", "op"); break;
            case "export": keys(m, "v", "sessionId", "requestId", "op", "payload"); text(m.get("payload"), 1, 524288); break;
            case "cancel": keys(m, "v", "sessionId", "requestId", "op", "targetRequestId"); id(m.get("targetRequestId"), "req"); break;
            default: fail();
        }
        integer(m.get("v"), 1, 1); id(m.get("sessionId"), "ses"); id(m.get("requestId"), "req");
        return new Request(m);
    }
    static byte[] payload(String raw) throws JsonTransferJson.Failure {
        byte[] bytes = JsonTransferJson.utf8(raw, JsonTransferJson.PAYLOAD_LIMIT);
        Map<String, Object> m = map(JsonTransferJson.parse(raw, JsonTransferJson.PAYLOAD_LIMIT));
        if (!(m.get("schemaVersion") instanceof BigDecimal) || ((BigDecimal) m.get("schemaVersion")).compareTo(BigDecimal.ONE) != 0)
            throw new JsonTransferJson.Failure("UNSUPPORTED_SCHEMA");
        keys(m, "format", "schemaVersion", "revision", "generationId", "updatedAtUtc", "preferences", "legacyBuilderRaw", "saved", "progress", "wallet", "attachments");
        if (!"itguide-user-data".equals(m.get("format"))) fail();
        revision(m.get("revision")); id(m.get("generationId"), "gen"); utc(m.get("updatedAtUtc"));
        Map<String, Object> preferences = map(m.get("preferences")); keys(preferences, "slow", "tab");
        if (!(preferences.get("slow") instanceof Boolean) || !Arrays.asList("phrases", "builder", "vocab", "itinerary").contains(preferences.get("tab"))) fail();
        if (m.get("legacyBuilderRaw") != null) map(JsonTransferJson.parse(text(m.get("legacyBuilderRaw"), 1, 8192), 8192));
        Set<String> ids = new HashSet<>();
        for (Object item : list(m.get("saved"), 200)) {
            Map<String, Object> s = map(item); keys(s, "id", "sourcePhraseId", "sourceContentVersion", "snapshot", "createdAtUtc");
            id(s.get("id"), "sav"); if (!ids.add((String) s.get("id"))) fail();
            if (s.get("sourcePhraseId") != null) phrase(s.get("sourcePhraseId"));
            if (s.get("sourceContentVersion") != null) text(s.get("sourceContentVersion"), 1, 64);
            Map<String, Object> snapshot = map(s.get("snapshot")); keys(snapshot, "it", "en");
            text(snapshot.get("it"), 1, 2000); text(snapshot.get("en"), 1, 2000); utc(s.get("createdAtUtc"));
        }
        ids.clear();
        for (Object item : list(m.get("progress"), 500)) {
            Map<String, Object> p = map(item); keys(p, "phraseId", "reviewCount", "lastReviewedAtUtc");
            phrase(p.get("phraseId")); if (!ids.add((String) p.get("phraseId"))) fail();
            integer(p.get("reviewCount"), 0, 1000000); utc(p.get("lastReviewedAtUtc"));
        }
        // JSON-only recovery cannot establish native wallet context. Web replacement reports ARCHIVE_REQUIRED.
        if (m.get("wallet") != null || !list(m.get("attachments"), 1000).isEmpty()) fail();
        return bytes;
    }
    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) throws JsonTransferJson.Failure {
        if (!(value instanceof Map)) fail(); return (Map<String, Object>) value;
    }
    private static List<?> list(Object value, int max) throws JsonTransferJson.Failure {
        if (!(value instanceof List) || ((List<?>) value).size() > max) fail(); return (List<?>) value;
    }
    private static void keys(Map<String, Object> m, String... fields) throws JsonTransferJson.Failure {
        if (!m.keySet().equals(new HashSet<>(Arrays.asList(fields)))) fail();
    }
    private static String text(Object value, int min, int max) throws JsonTransferJson.Failure {
        if (!(value instanceof String)) fail();
        String s = (String) value; int n = s.codePointCount(0, s.length());
        if (n < min || n > max) fail(); return s;
    }
    private static void id(Object value, String prefix) throws JsonTransferJson.Failure {
        if (!text(value, 36, 36).matches(prefix + "_[0-9a-f]{32}")) fail();
    }
    private static void phrase(Object value) throws JsonTransferJson.Failure {
        if (!text(value, 1, 70).matches("phrase_[a-z0-9][a-z0-9_-]{0,63}")) fail();
    }
    private static void revision(Object value) throws JsonTransferJson.Failure {
        String r = text(value, 1, 19);
        if (!r.matches("0|[1-9][0-9]{0,18}") || (r.length() == 19 && r.compareTo("9223372036854775807") > 0)) fail();
    }
    private static void integer(Object value, int min, int max) throws JsonTransferJson.Failure {
        if (!(value instanceof BigDecimal)) fail();
        BigDecimal n = (BigDecimal) value;
        if (n.stripTrailingZeros().scale() > 0 || n.compareTo(BigDecimal.valueOf(min)) < 0 || n.compareTo(BigDecimal.valueOf(max)) > 0) fail();
    }
    private static void utc(Object value) throws JsonTransferJson.Failure {
        String s = text(value, 20, 20);
        if (!s.matches("20[0-9]{2}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z")) fail();
        try { LocalDateTime.parse(s, DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'").withResolverStyle(ResolverStyle.STRICT)); }
        catch (DateTimeParseException e) { fail(); }
    }
    private static void fail() throws JsonTransferJson.Failure { throw new JsonTransferJson.Failure("INVALID_DATA"); }
}
