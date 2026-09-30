package com.koalaman64.italytravelpocketguide;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.Assert.*;

public class ArchiveFormatTest {
    static final String DOC = "doc_" + "1".repeat(32), GEN = "gen_" + "a".repeat(32), WALLET = "gen_" + "b".repeat(32);
    static final String TIME = "2030-06-10T12:00:00Z";
    static final ArchiveFormat.Check CHECK = () -> {};
    static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    static String hash(byte[] b) throws Exception {
        StringBuilder s = new StringBuilder();
        for (byte n : MessageDigest.getInstance("SHA-256").digest(b)) s.append(String.format("%02x", n & 255));
        return s.toString();
    }
    static ArchiveFormat.Source source(byte[] data) {
        return new ArchiveFormat.Source() {
            public long size() { return data.length; }
            public int read(long offset, byte[] buffer, int start, int count) {
                if (offset >= data.length) return -1;
                int n = (int) Math.min(count, data.length - offset); System.arraycopy(data, (int) offset, buffer, start, n); return n;
            }
        };
    }
    static Map<String, Object> user(boolean wallet) {
        return JsonTransferJson.object("format", "itguide-user-data", "schemaVersion", 1, "revision", "7", "generationId", GEN,
                "updatedAtUtc", TIME, "preferences", JsonTransferJson.object("slow", false, "tab", "phrases"),
                "legacyBuilderRaw", null, "saved", List.of(), "progress", List.of(),
                "wallet", wallet ? JsonTransferJson.object("generationId", WALLET, "revision", "2") : null,
                "attachments", wallet ? List.of(JsonTransferJson.object("tripId", "trip_orphan", "eventId", "event_orphan", "documentId", DOC)) : List.of());
    }
    static Map<String, Object> manifest(byte[] user, byte[] doc, String label) throws Exception {
        return JsonTransferJson.object("format", "itguide-full-archive", "schemaVersion", 1, "exportedAtUtc", TIME,
                "user", JsonTransferJson.object("entry", "user-data.json", "byteLength", user.length, "sha256", hash(user), "generationId", GEN, "revision", "7", "schemaVersion", 1),
                "wallet", doc == null ? null : JsonTransferJson.object("generationId", WALLET, "revision", "2", "documents", List.of(
                        JsonTransferJson.object("id", DOC, "kind", "png", "byteLength", doc.length, "sha256", hash(doc), "displayName", label, "importedAtUtc", TIME))));
    }
    static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                CRC32 crc = new CRC32(); crc.update(e.getValue()); ZipEntry entry = new ZipEntry(e.getKey());
                entry.setMethod(ZipEntry.STORED); entry.setSize(e.getValue().length); entry.setCompressedSize(e.getValue().length); entry.setCrc(crc.getValue());
                entry.setTime(315619200000L); zip.putNextEntry(entry); zip.write(e.getValue()); zip.closeEntry();
            }
        }
        return output.toByteArray();
    }
    static LinkedHashMap<String, byte[]> entries(boolean withDoc) throws Exception {
        byte[] user = bytes(JsonTransferJson.encode(user(withDoc))), doc = withDoc ? bytes("synthetic-not-a-decoder-fixture") : null;
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("manifest.json", bytes(JsonTransferJson.encode(manifest(user, doc, "Synthetic")))); entries.put("user-data.json", user);
        if (withDoc) entries.put("documents/" + DOC + ".png", doc); return entries;
    }
    static ArchiveFormat.Archive read(byte[] zip) throws IOException { return ArchiveFormat.read(source(zip), CHECK); }
    static void rejected(byte[] zip) { assertThrows(ArchiveFormat.Failure.class, () -> read(zip)); }
    static int signature(byte[] data, int value) {
        for (int i = 0; i <= data.length - 4; i++) if ((data[i] & 255) == (value & 255) && (data[i + 1] & 255) == ((value >>> 8) & 255)
                && (data[i + 2] & 255) == ((value >>> 16) & 255) && (data[i + 3] & 255) == ((value >>> 24) & 255)) return i;
        throw new AssertionError("signature absent");
    }
    @Test public void acceptsEmptyAndPreservesWalletOrphansAndExactBytes() throws Exception {
        for (boolean doc : new boolean[]{false, true}) {
            var entries = entries(doc); byte[] original = zip(entries); var archive = read(original);
            assertArrayEquals(entries.get("user-data.json"), archive.userBytes()); assertEquals(doc ? 1 : 0, archive.documents.size());
            ByteArrayOutputStream out = new ByteArrayOutputStream(); ArchiveFormat.write(archive, source(original), out, CHECK);
            assertArrayEquals(archive.userBytes(), read(out.toByteArray()).userBytes());
        }
    }
    @Test public void createsNewCanonicalArchiveFromLeasedHandles() throws Exception {
        var entries = entries(true); ByteArrayOutputStream out = new ByteArrayOutputStream();
        ArchiveFormat.write(entries.get("manifest.json"), entries.get("user-data.json"), Map.of("documents/" + DOC + ".png", source(entries.get("documents/" + DOC + ".png"))), out, CHECK);
        assertEquals(1, read(out.toByteArray()).documents.size());
    }
    @Test public void boundsEveryReadAndSupportsShortReads() throws Exception {
        byte[] data = zip(entries(true)); ArchiveFormat.Source base = source(data);
        ArchiveFormat.Source shortReads = new ArchiveFormat.Source() {
            public long size() { return data.length; }
            public int read(long p, byte[] b, int off, int count) throws IOException { assertTrue(count <= 65536); return base.read(p, b, off, Math.min(count, 3)); }
        };
        assertEquals(3, ArchiveFormat.read(shortReads, CHECK).entries.size());
    }
    @Test public void rejectsTraversalUnexpectedNamesAndReorderedMetadata() throws Exception {
        for (String name : List.of("../manifest.json", "/manifest.json", "manifest.json\0", "%2e%2e/x", "documents\\x", "extra.txt")) {
            var e = entries(false); e.put(name, bytes("x")); rejected(zip(e));
        }
        var e = entries(false); LinkedHashMap<String, byte[]> reversed = new LinkedHashMap<>();
        reversed.put("user-data.json", e.get("user-data.json")); reversed.put("manifest.json", e.get("manifest.json")); rejected(zip(reversed));
    }
    @Test public void rejectsLocalCentralMismatchOverlapCrcAndTrailingBytes() throws Exception {
        byte[] valid = zip(entries(true)); int central = signature(valid, 0x02014b50);
        for (int at : new int[]{6, 8, 14, 18, 26, 28, 30, central + 8, central + 10, central + 16, central + 38, central + 42}) {
            byte[] bad = valid.clone(); bad[at] ^= 1; rejected(bad);
        }
        rejected(Arrays.copyOf(valid, valid.length + 1)); rejected(Arrays.copyOf(valid, valid.length - 1));
    }
    @Test public void rejectsWrongHashEvenWithValidZipCrc() throws Exception {
        var e = entries(true); e.put("documents/" + DOC + ".png", bytes("same-CRC-not-needed"));
        rejected(zip(e));
        e = entries(false); e.put("user-data.json", bytes(new String(e.get("user-data.json"), StandardCharsets.UTF_8).replace("phrases", "builder")));
        rejected(zip(e));
    }
    @Test public void rejectsMalformedMetadataAndMissingRequiredUserField() throws Exception {
        for (String bad : List.of("{}", "{\"x\":1,\"x\":2}", "\ufeff{}", "[".repeat(25) + "]".repeat(25))) {
            var e = entries(false); e.put("manifest.json", bytes(bad)); rejected(zip(e));
        }
        var e = entries(false); e.put("manifest.json", new byte[]{(byte) 0xc0, (byte) 0xaf}); rejected(zip(e));
        var u = user(false); u.remove("wallet"); byte[] raw = bytes(JsonTransferJson.encode(u));
        e = entries(false); e.put("user-data.json", raw); e.put("manifest.json", bytes(JsonTransferJson.encode(manifest(raw, null, "x")))); rejected(zip(e));
    }
    @Test public void rejectsDanglingReferencesAndDuplicateRelationsWithFreshHashes() throws Exception {
        for (boolean duplicate : new boolean[]{false, true}) {
            var u = user(true); Object ref = ((List<?>) u.get("attachments")).get(0);
            u.put("attachments", duplicate ? List.of(ref, ref) : List.of(JsonTransferJson.object("tripId", "trip_x", "eventId", "event_x", "documentId", "doc_" + "f".repeat(32))));
            var e = entries(true); byte[] raw = bytes(JsonTransferJson.encode(u)), doc = e.get("documents/" + DOC + ".png");
            e.put("user-data.json", raw); e.put("manifest.json", bytes(JsonTransferJson.encode(manifest(raw, doc, "x")))); rejected(zip(e));
        }
    }
    @Test public void labelsUse120ScalarsAndRejectControlAndBidiMarks() throws Exception {
        var e = entries(true); byte[] raw = e.get("user-data.json"), doc = e.get("documents/" + DOC + ".png");
        e.put("manifest.json", bytes(JsonTransferJson.encode(manifest(raw, doc, "😀".repeat(120))))); read(zip(e));
        for (String label : List.of("x".repeat(121), "a\n", "a\u202e", "")) {
            e.put("manifest.json", bytes(JsonTransferJson.encode(manifest(raw, doc, label)))); rejected(zip(e));
        }
    }
    @Test public void rejectsOversizedMetadataInputAndNoProgress() throws Exception {
        var e = entries(false); e.put("manifest.json", new byte[ArchiveFormat.METADATA_LIMIT + 1]); rejected(zip(e));
        ArchiveFormat.Source giant = new ArchiveFormat.Source() {
            public long size() { return ArchiveFormat.ARCHIVE_LIMIT + 1; }
            public int read(long p, byte[] b, int o, int n) { throw new AssertionError("must reject before allocation/read"); }
        };
        assertThrows(ArchiveFormat.Failure.class, () -> ArchiveFormat.read(giant, CHECK));
        ArchiveFormat.Source stuck = new ArchiveFormat.Source() {
            public long size() { return 100; }
            public int read(long p, byte[] b, int o, int n) { return 0; }
        };
        assertThrows(ArchiveFormat.Failure.class, () -> ArchiveFormat.read(stuck, CHECK));
    }
    @Test public void cancellationAndOutputFailurePropagateWithoutSuccess() throws Exception {
        byte[] data = zip(entries(true)); var archive = read(data);
        assertThrows(IOException.class, () -> ArchiveFormat.read(source(data), () -> { throw new IOException("CANCELLED"); }));
        OutputStream fail = new OutputStream() { public void write(int b) throws IOException { throw new IOException("synthetic output fault"); } };
        assertThrows(IOException.class, () -> ArchiveFormat.write(archive, source(data), fail, CHECK));
        byte[] changed = data.clone(); changed[(int) archive.entries.get(2).offset] ^= 1;
        assertThrows(ArchiveFormat.Failure.class, () -> ArchiveFormat.write(archive, source(changed), new ByteArrayOutputStream(), CHECK));
    }
}
