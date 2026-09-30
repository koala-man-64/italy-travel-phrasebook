package com.koalaman64.italytravelpocketguide;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;

/** Strict ZIP32 STORED archive codec. Owns no files, provider handles or persistence. */
final class ArchiveFormat {
    static final long DOCUMENT_LIMIT = 20L * 1024 * 1024;
    static final long DOCUMENTS_LIMIT = 250L * 1024 * 1024;
    static final int METADATA_LIMIT = 512 * 1024;
    static final int OVERHEAD_LIMIT = 64 * 1024;
    static final long ARCHIVE_LIMIT = DOCUMENTS_LIMIT + 2L * METADATA_LIMIT + OVERHEAD_LIMIT;
    static final int BUFFER_SIZE = 64 * 1024;
    private static final int FLAG = 0x800;

    /** Positional reads of an immutable leased snapshot. Caller owns its lifetime. */
    interface Source {
        long size() throws IOException;
        int read(long offset, byte[] buffer, int start, int length) throws IOException;
    }
    interface Check { void check() throws IOException; }
    static final class Failure extends IOException {
        final String code;
        Failure(String code) { super(code); this.code = code; }
    }
    static final class Entry {
        final String name, sha256;
        final long offset, size, crc;
        Entry(String name, long offset, long size, long crc, String sha256) {
            this.name = name; this.offset = offset; this.size = size; this.crc = crc; this.sha256 = sha256;
        }
    }
    static final class Document {
        final String id, kind, displayName, importedAtUtc, sha256;
        final long byteLength;
        Document(String id, String kind, String displayName, String importedAtUtc, String sha256, long byteLength) {
            this.id = id; this.kind = kind; this.displayName = displayName;
            this.importedAtUtc = importedAtUtc; this.sha256 = sha256; this.byteLength = byteLength;
        }
    }
    static final class Archive {
        final List<Entry> entries;
        final List<Document> documents;
        private final byte[] manifest, user;
        private Archive(List<Entry> entries, List<Document> documents, byte[] manifest, byte[] user) {
            this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
            this.documents = Collections.unmodifiableList(new ArrayList<>(documents));
            this.manifest = manifest.clone(); this.user = user.clone();
        }
        byte[] manifestBytes() { return manifest.clone(); }
        byte[] userBytes() { return user.clone(); }
    }
    private static void need(boolean valid, String code) throws Failure { if (!valid) throw new Failure(code); }
    private static long u32(byte[] b, int n) {
        return (b[n] & 255L) | (b[n + 1] & 255L) << 8 | (b[n + 2] & 255L) << 16 | (b[n + 3] & 255L) << 24;
    }
    private static int u16(byte[] b, int n) { return (b[n] & 255) | (b[n + 1] & 255) << 8; }
    private static byte[] read(Source source, long offset, int count, Check check) throws IOException {
        byte[] bytes = new byte[count]; readInto(source, offset, bytes, count, check); return bytes;
    }
    private static void readInto(Source source, long offset, byte[] bytes, int count, Check check) throws IOException {
        int at = 0;
        while (at < count) {
            check.check(); int n = source.read(offset + at, bytes, at, count - at);
            need(n > 0 && n <= count - at, "TRUNCATED_OR_NO_PROGRESS"); at += n;
        }
    }
    private static String name(byte[] bytes) throws Failure {
        for (byte b : bytes) need(b > 0 && b < 127, "INVALID_ARCHIVE");
        String name = new String(bytes, StandardCharsets.US_ASCII);
        need(name.equals("manifest.json") || name.equals("user-data.json")
                || name.matches("documents/doc_[0-9a-f]{32}\\.(pdf|png|jpeg)"), "INVALID_ARCHIVE");
        return name;
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static String hex(byte[] bytes) {
        StringBuilder s = new StringBuilder(64);
        for (byte b : bytes) { s.append(Character.forDigit((b & 255) >>> 4, 16)); s.append(Character.forDigit(b & 15, 16)); }
        return s.toString();
    }
    private static String hash(byte[] bytes) { return hex(digest().digest(bytes)); }

    /** Builds a new archive from exact metadata and leased document sources, with a bounded preflight pass. */
    static void write(byte[] manifest, byte[] user, Map<String, Source> documents, OutputStream output, Check check) throws IOException {
        need(manifest.length <= METADATA_LIMIT && user.length <= METADATA_LIMIT && documents.size() <= 50, "FILE_LIMIT");
        manifest = manifest.clone(); user = user.clone();
        LinkedHashMap<String, Source> sources = new LinkedHashMap<>();
        sources.put("manifest.json", memory(manifest)); sources.put("user-data.json", memory(user));
        List<String> names = new ArrayList<>(documents.keySet()); names.sort(String::compareTo);
        for (String key : names) {
            name(key.getBytes(StandardCharsets.UTF_8)); need(key.startsWith("documents/"), "INVALID_ARCHIVE");
            sources.put(key, documents.get(key));
        }
        List<Entry> entries = new ArrayList<>(); long offset = 0, totalDocs = 0;
        for (Map.Entry<String, Source> source : sources.entrySet()) {
            check.check(); long size = source.getValue().size();
            need(size > 0 && size <= (entries.size() < 2 ? METADATA_LIMIT : DOCUMENT_LIMIT), "FILE_LIMIT");
            if (entries.size() >= 2) { totalDocs += size; need(totalDocs <= DOCUMENTS_LIMIT, "FILE_LIMIT"); }
            CRC32 crc = new CRC32(); MessageDigest sha = digest(); byte[] buffer = new byte[BUFFER_SIZE];
            for (long copied = 0; copied < size;) {
                int n = (int) Math.min(buffer.length, size - copied); readInto(source.getValue(), copied, buffer, n, check);
                crc.update(buffer, 0, n); sha.update(buffer, 0, n); copied += n;
            }
            need(source.getValue().size() == size, "INVALID_ARCHIVE");
            entries.add(new Entry(source.getKey(), offset, size, crc.getValue(), hex(sha.digest()))); offset += size;
        }
        Archive archive = new Archive(entries, validate(manifest, user, entries), manifest, user);
        final long total = offset;
        Source combined = new Source() {
            public long size() { return total; }
            public int read(long at, byte[] b, int start, int count) throws IOException {
                for (Entry e : entries) if (at >= e.offset && at < e.offset + e.size) {
                    need(count <= e.offset + e.size - at, "INVALID_ARCHIVE");
                    return sources.get(e.name).read(at - e.offset, b, start, count);
                }
                return -1;
            }
        };
        write(archive, combined, output, check);
    }
    private static Source memory(byte[] value) {
        byte[] data = value.clone();
        return new Source() {
            public long size() { return data.length; }
            public int read(long at, byte[] b, int start, int count) {
                if (at >= data.length) return -1;
                int n = (int) Math.min(count, data.length - at); System.arraycopy(data, (int) at, b, start, n); return n;
            }
        };
    }

    static Archive read(Source source, Check check) throws IOException {
        check.check(); long size = source.size();
        need(size >= 22 && size <= ARCHIVE_LIMIT, "FILE_LIMIT");
        byte[] end = read(source, size - 22, 22, check);
        need(u32(end, 0) == 0x06054b50L && u16(end, 4) == 0 && u16(end, 6) == 0
                && u16(end, 20) == 0, "UNSUPPORTED_ARCHIVE_PROFILE");
        int count = u16(end, 10);
        need(count >= 2 && count <= 52 && u16(end, 8) == count, "DOCUMENT_LIMIT");
        long centralSize = u32(end, 12), centralOffset = u32(end, 16);
        need(centralSize <= OVERHEAD_LIMIT && centralOffset + centralSize == size - 22, "INVALID_ARCHIVE");
        long at = centralOffset, local = 0, overhead = 22, totalDocs = 0;
        List<Entry> entries = new ArrayList<>(); Set<String> names = new HashSet<>();
        byte[] manifest = null, user = null;
        for (int index = 0; index < count; index++) {
            need(at + 46 <= size - 22, "INVALID_ARCHIVE");
            byte[] central = read(source, at, 46, check);
            need(u32(central, 0) == 0x02014b50L, "INVALID_ARCHIVE");
            // STORED needs ZIP 1.0; our writer emits 2.0. Neither permits extra features.
            need((u16(central, 4) == 10 || u16(central, 4) == 20)
                    && (u16(central, 6) == 10 || u16(central, 6) == 20) && u16(central, 8) == FLAG
                    && u16(central, 10) == 0 && u16(central, 30) == 0 && u16(central, 32) == 0
                    && u16(central, 34) == 0 && u16(central, 36) == 0 && u32(central, 38) == 0,
                    "UNSUPPORTED_ARCHIVE_PROFILE");
            int length = u16(central, 28); long memberSize = u32(central, 24), crc = u32(central, 16);
            need(length >= 1 && length <= 52 && at + 46 + length <= size - 22, "INVALID_ARCHIVE");
            String memberName = name(read(source, at + 46, length, check));
            need(names.add(memberName), "DUPLICATE_ENTRY");
            need(index == 0 ? memberName.equals("manifest.json") : index == 1 ? memberName.equals("user-data.json")
                    : memberName.startsWith("documents/") && (index == 2 || entries.get(index - 1).name.compareTo(memberName) < 0), "INVALID_ARCHIVE");
            need(u32(central, 20) == memberSize && u32(central, 42) == local, "INVALID_ARCHIVE");
            need(memberSize > 0 && memberSize <= (index < 2 ? METADATA_LIMIT : DOCUMENT_LIMIT), "FILE_LIMIT");
            if (index >= 2) { totalDocs += memberSize; need(totalDocs <= DOCUMENTS_LIMIT, "FILE_LIMIT"); }
            need(local + 30 + length + memberSize <= centralOffset, "INVALID_ARCHIVE");
            byte[] header = read(source, local, 30, check);
            need(u32(header, 0) == 0x04034b50L && u16(header, 4) == u16(central, 6) && u16(header, 6) == FLAG
                    && u16(header, 8) == 0 && u16(header, 26) == length && u16(header, 28) == 0,
                    "UNSUPPORTED_ARCHIVE_PROFILE");
            need(u16(header, 10) == u16(central, 12) && u16(header, 12) == u16(central, 14)
                    && u32(header, 14) == crc && u32(header, 18) == memberSize && u32(header, 22) == memberSize
                    && name(read(source, local + 30, length, check)).equals(memberName), "INVALID_ARCHIVE");
            long offset = local + 30 + length;
            MessageDigest sha = digest(); CRC32 actualCrc = new CRC32();
            byte[] buffer = new byte[BUFFER_SIZE];
            ByteArrayOutputStream metadata = index < 2 ? new ByteArrayOutputStream((int) memberSize) : null;
            for (long copied = 0; copied < memberSize;) {
                int n = (int) Math.min(buffer.length, memberSize - copied);
                readInto(source, offset + copied, buffer, n, check);
                sha.update(buffer, 0, n); actualCrc.update(buffer, 0, n);
                if (metadata != null) metadata.write(buffer, 0, n);
                copied += n;
            }
            need(actualCrc.getValue() == crc, "CRC_MISMATCH");
            entries.add(new Entry(memberName, offset, memberSize, crc, hex(sha.digest())));
            if (index == 0) manifest = metadata.toByteArray();
            if (index == 1) user = metadata.toByteArray();
            local = offset + memberSize; at += 46 + length;
            overhead += 76L + 2L * length; need(overhead <= OVERHEAD_LIMIT, "FILE_LIMIT");
        }
        need(local == centralOffset && at == size - 22 && source.size() == size, "INVALID_ARCHIVE");
        List<Document> docs = validate(manifest, user, entries);
        check.check(); return new Archive(entries, docs, manifest, user);
    }

    /** Revalidates bytes as copied; caller alone closes/commits the output and reports partial effects. */
    static void write(Archive archive, Source source, OutputStream output, Check check) throws IOException {
        long at = 0; List<Long> offsets = new ArrayList<>();
        for (Entry entry : archive.entries) {
            check.check(); offsets.add(at); byte[] name = entry.name.getBytes(StandardCharsets.US_ASCII);
            put(output, 0x04034b50L, 4); put(output, 20, 2); put(output, FLAG, 2); put(output, 0, 2);
            put(output, 0, 2); put(output, 33, 2); put(output, entry.crc, 4);
            put(output, entry.size, 4); put(output, entry.size, 4); put(output, name.length, 2); put(output, 0, 2); output.write(name);
            MessageDigest sha = digest(); CRC32 crc = new CRC32(); byte[] buffer = new byte[BUFFER_SIZE];
            for (long copied = 0; copied < entry.size;) {
                int n = (int) Math.min(buffer.length, entry.size - copied);
                readInto(source, entry.offset + copied, buffer, n, check); sha.update(buffer, 0, n); crc.update(buffer, 0, n);
                check.check(); output.write(buffer, 0, n); copied += n;
            }
            need(crc.getValue() == entry.crc && hex(sha.digest()).equals(entry.sha256), "HASH_MISMATCH");
            at += 30 + name.length + entry.size;
        }
        long centralOffset = at;
        for (int i = 0; i < archive.entries.size(); i++) {
            check.check(); Entry entry = archive.entries.get(i); byte[] name = entry.name.getBytes(StandardCharsets.US_ASCII);
            put(output, 0x02014b50L, 4); put(output, 20, 2); put(output, 20, 2); put(output, FLAG, 2); put(output, 0, 2);
            put(output, 0, 2); put(output, 33, 2); put(output, entry.crc, 4); put(output, entry.size, 4); put(output, entry.size, 4);
            put(output, name.length, 2); put(output, 0, 2); put(output, 0, 2); put(output, 0, 2); put(output, 0, 2);
            put(output, 0, 4); put(output, offsets.get(i), 4); output.write(name); at += 46 + name.length;
        }
        put(output, 0x06054b50L, 4); put(output, 0, 2); put(output, 0, 2);
        put(output, archive.entries.size(), 2); put(output, archive.entries.size(), 2);
        put(output, at - centralOffset, 4); put(output, centralOffset, 4); put(output, 0, 2); check.check();
    }
    private static void put(OutputStream out, long n, int count) throws IOException {
        for (int i = 0; i < count; i++) out.write((int) (n >>> (i * 8)) & 255);
    }

    private static List<Document> validate(byte[] manifest, byte[] userRaw, List<Entry> entries) throws Failure {
        try {
            Map<String, Object> m = map(JsonTransferJson.parse(JsonTransferJson.decode(manifest), METADATA_LIMIT));
            keys(m, "format", "schemaVersion", "exportedAtUtc", "user", "wallet");
            need("itguide-full-archive".equals(m.get("format")), "INVALID_ARCHIVE");
            need(number(m.get("schemaVersion"), 1, 1) == 1, "UNSUPPORTED_VERSION"); utc(m.get("exportedAtUtc"));
            Map<String, Object> descriptor = map(m.get("user"));
            keys(descriptor, "entry", "byteLength", "sha256", "generationId", "revision", "schemaVersion");
            identity(descriptor); number(descriptor.get("schemaVersion"), 1, 1);
            need("user-data.json".equals(descriptor.get("entry")), "REFERENCE_MISMATCH");
            need(number(descriptor.get("byteLength"), 1, METADATA_LIMIT) == userRaw.length
                    && sha(descriptor.get("sha256")).equals(hash(userRaw)), "HASH_MISMATCH");
            Map<String, Object> user = map(JsonTransferJson.parse(JsonTransferJson.decode(userRaw), METADATA_LIMIT));
            keys(user, "format", "schemaVersion", "revision", "generationId", "updatedAtUtc", "preferences", "legacyBuilderRaw", "saved", "progress", "wallet", "attachments");
            need(descriptor.get("generationId").equals(user.get("generationId"))
                    && descriptor.get("revision").equals(user.get("revision")), "REFERENCE_MISMATCH");
            // Reuse the existing strict personal-field validator without changing its wallet-free public guard.
            Map<String, Object> personal = new LinkedHashMap<>(user);
            personal.put("wallet", null); personal.put("attachments", Collections.emptyList());
            JsonTransferProtocol.payload(JsonTransferJson.encode(personal));
            List<Document> docs = new ArrayList<>(); Set<String> ids = new HashSet<>();
            if (m.get("wallet") == null) need(user.get("wallet") == null, "REFERENCE_MISMATCH");
            else {
                Map<String, Object> wallet = map(m.get("wallet")); keys(wallet, "generationId", "revision", "documents"); identity(wallet);
                Map<String, Object> binding = map(user.get("wallet")); keys(binding, "generationId", "revision"); identity(binding);
                need(binding.get("generationId").equals(wallet.get("generationId")) && binding.get("revision").equals(wallet.get("revision")), "REFERENCE_MISMATCH");
                for (Object raw : list(wallet.get("documents"), 50)) {
                    Map<String, Object> d = map(raw); keys(d, "id", "kind", "byteLength", "sha256", "displayName", "importedAtUtc");
                    String id = identifier(d.get("id"), "doc"), kind = string(d.get("kind"));
                    need(ids.add(id), "DUPLICATE_ID"); need(Arrays.asList("pdf", "png", "jpeg").contains(kind), "INVALID_DATA");
                    String label = string(d.get("displayName")); need(label.codePointCount(0, label.length()) >= 1 && label.codePointCount(0, label.length()) <= 120, "INVALID_DATA");
                    need(label.codePoints().noneMatch(c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT), "INVALID_DATA");
                    utc(d.get("importedAtUtc")); long bytes = number(d.get("byteLength"), 1, DOCUMENT_LIMIT);
                    docs.add(new Document(id, kind, label, string(d.get("importedAtUtc")), sha(d.get("sha256")), bytes));
                }
            }
            need(entries.size() == docs.size() + 2, "REFERENCE_MISMATCH");
            String previous = "";
            for (int i = 0; i < docs.size(); i++) {
                Document d = docs.get(i); Entry entry = entries.get(i + 2);
                need(previous.compareTo(d.id) < 0, "INVALID_DATA"); previous = d.id;
                need(entry.name.equals("documents/" + d.id + "." + d.kind) && entry.size == d.byteLength, "REFERENCE_MISMATCH");
                need(entry.sha256.equals(d.sha256), "HASH_MISMATCH");
            }
            Set<String> refs = new HashSet<>();
            for (Object raw : list(user.get("attachments"), 1000)) {
                Map<String, Object> ref = map(raw); keys(ref, "tripId", "eventId", "documentId");
                String trip = string(ref.get("tripId")), event = string(ref.get("eventId")), doc = identifier(ref.get("documentId"), "doc");
                need(trip.matches("trip_[a-z0-9][a-z0-9_-]{0,63}") && event.matches("event_[a-z0-9][a-z0-9_-]{0,63}"), "INVALID_DATA");
                need(refs.add(trip + "/" + event + "/" + doc), "DUPLICATE_ID"); need(ids.contains(doc), "REFERENCE_MISMATCH");
            }
            return docs;
        } catch (JsonTransferJson.Failure e) { throw new Failure(e.code); }
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) throws Failure {
        need(value instanceof Map, "INVALID_DATA"); return (Map<String, Object>) value;
    }
    private static List<?> list(Object value, int max) throws Failure {
        need(value instanceof List && ((List<?>) value).size() <= max, "INVALID_DATA"); return (List<?>) value;
    }
    private static void keys(Map<String, Object> m, String... keys) throws Failure {
        need(m.keySet().equals(new HashSet<>(Arrays.asList(keys))), "INVALID_DATA");
    }
    private static String string(Object value) throws Failure { need(value instanceof String, "INVALID_DATA"); return (String) value; }
    private static String identifier(Object value, String prefix) throws Failure {
        String s = string(value); need(s.matches(prefix + "_[0-9a-f]{32}"), "INVALID_DATA"); return s;
    }
    private static String sha(Object value) throws Failure { String s = string(value); need(s.matches("[0-9a-f]{64}"), "INVALID_DATA"); return s; }
    private static void identity(Map<String, Object> m) throws Failure {
        identifier(m.get("generationId"), "gen"); String r = string(m.get("revision"));
        need(r.matches("0|[1-9][0-9]{0,18}") && (r.length() < 19 || r.compareTo("9223372036854775807") <= 0), "INVALID_DATA");
    }
    private static long number(Object value, long min, long max) throws Failure {
        need(value instanceof BigDecimal, "INVALID_DATA");
        try { long n = ((BigDecimal) value).longValueExact(); need(n >= min && n <= max, "FILE_LIMIT"); return n; }
        catch (ArithmeticException e) { throw new Failure("INVALID_DATA"); }
    }
    private static void utc(Object value) throws Failure {
        String s = string(value); need(s.matches("20[0-9]{2}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z"), "INVALID_DATA");
        try { LocalDateTime.parse(s, DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'").withResolverStyle(ResolverStyle.STRICT)); }
        catch (RuntimeException e) { throw new Failure("INVALID_DATA"); }
    }
}
