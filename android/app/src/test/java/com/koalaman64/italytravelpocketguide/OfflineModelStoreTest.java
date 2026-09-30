package com.koalaman64.italytravelpocketguide;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class OfflineModelStoreTest {
    private static final String MODEL = "vosk-model-small-en-us-0.15";
    private static final String URL = "https://alphacephei.com/vosk/models/" + MODEL + ".zip";

    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void installsVerifiedModelAndDetectsSameSizeCorruption() throws Exception {
        byte[] zip = modelZip("original");
        OfflineModelStore store = store(zip, digest(zip));
        store.install("en", null, null, directory -> assertTrue(new File(directory, "am/final.mdl").isFile()));

        assertTrue(store.isReady("en"));
        File modelFile = new File(store.modelDirectory("en"), "am/final.mdl");
        byte[] original = Files.readAllBytes(modelFile.toPath());
        Files.write(modelFile.toPath(), "changed!".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertFalse(store.isReady("en"));
        Files.write(modelFile.toPath(), original);
        assertTrue(store.isReady("en"));
    }

    @Test public void wrongDigestCannotReplaceInstalledModel() throws Exception {
        byte[] first = modelZip("original");
        OfflineModelStore originalStore = store(first, digest(first));
        originalStore.install("en", null, null, directory -> {});
        File modelFile = new File(originalStore.modelDirectory("en"), "am/final.mdl");
        byte[] original = Files.readAllBytes(modelFile.toPath());

        OfflineModelStore rejected = store(modelZip("different"), digest(first));
        expectFailure(() -> rejected.install("en", null, null, directory -> {}));
        assertArrayEquals(original, Files.readAllBytes(modelFile.toPath()));
        assertTrue(originalStore.isReady("en"));
    }

    @Test public void rejectsTraversalAndLeavesNoReadyModel() throws Exception {
        Map<String, byte[]> entries = requiredEntries("model");
        entries.put(MODEL + "/../../escape", new byte[] {1});
        byte[] zip = zip(entries);
        OfflineModelStore store = store(zip, digest(zip));

        expectFailure(() -> store.install("en", null, null, directory -> {}));
        assertFalse(store.isReady("en"));
        assertFalse(new File(temporary.getRoot(), "escape").exists());
    }

    @Test public void validatorFailurePreservesOldModel() throws Exception {
        byte[] first = modelZip("original");
        OfflineModelStore originalStore = store(first, digest(first));
        originalStore.install("en", null, null, directory -> {});
        File modelFile = new File(originalStore.modelDirectory("en"), "am/final.mdl");
        byte[] original = Files.readAllBytes(modelFile.toPath());

        byte[] second = modelZip("updated!");
        OfflineModelStore rejected = store(second, digest(second));
        expectFailure(() -> rejected.install("en", null, null, directory -> { throw new IOException("Vosk load failed"); }));
        assertArrayEquals(original, Files.readAllBytes(modelFile.toPath()));
        assertTrue(originalStore.isReady("en"));
    }

    @Test public void cancellationStopsBeforeActivation() throws Exception {
        byte[] zip = modelZip("model");
        OfflineModelStore store = store(zip, digest(zip));
        expectFailure(() -> store.install("en", null, () -> true, directory -> {}));
        assertFalse(store.isReady("en"));
    }

    @Test public void cancellationPreservesInstalledModel() throws Exception {
        byte[] first = modelZip("original");
        OfflineModelStore originalStore = store(first, digest(first));
        originalStore.install("en", null, null, directory -> {});
        byte[] second = modelZip("updated!");
        OfflineModelStore cancelled = store(second, digest(second));
        expectFailure(() -> cancelled.install("en", null, () -> true, directory -> {}));
        assertTrue(originalStore.isReady("en"));
        assertArrayEquals("original".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                Files.readAllBytes(new File(originalStore.modelDirectory("en"), "am/final.mdl").toPath()));
    }

    @Test public void rejectsNormalizedDuplicateEntry() throws Exception {
        Map<String, byte[]> entries = requiredEntries("model");
        entries.put(MODEL + "/am/./final.mdl", new byte[] {4});
        byte[] zip = zip(entries);
        expectFailure(() -> store(zip, digest(zip)).install("en", null, null, directory -> {}));
    }

    @Test public void rejectsUnixSymlinkEntry() throws Exception {
        Map<String, byte[]> entries = requiredEntries("model");
        entries.put(MODEL + "/link", "../escape".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] zip = markSymlink(zip(entries), MODEL + "/link");
        expectFailure(() -> store(zip, digest(zip)).install("en", null, null, directory -> {}));
    }

    @Test public void recoversPreviousModelAfterInterruptedActivation() throws Exception {
        byte[] zip = modelZip("original");
        OfflineModelStore store = store(zip, digest(zip));
        store.install("en", null, null, directory -> {});
        Files.move(store.modelDirectory("en").toPath(),
                new File(temporary.getRoot(), ".en-previous").toPath());
        assertTrue(store.isReady("en"));
    }

    @Test public void redirectsUseInjectedConnectionFactoryForEveryHop() throws Exception {
        List<String> opened = new ArrayList<>();
        OfflineModelStore.ConnectionFactory factory = url -> {
            opened.add(url.toString());
            return opened.size() == 1
                    ? new FakeConnection(url, 302, "/vosk/models/second.zip", new byte[0])
                    : new FakeConnection(url, 200, null, new byte[] {7, 8});
        };
        try (OfflineModelStore.Download result = OfflineModelStore.openHttps(new URL(URL), factory)) {
            assertArrayEquals(new byte[] {7, 8}, result.stream.readAllBytes());
        }
        assertTrue(opened.get(0).equals(URL));
        assertTrue(opened.get(1).equals("https://alphacephei.com/vosk/models/second.zip"));
        assertTrue(opened.size() == 2);
    }

    @Test public void externalRedirectIsRejectedBeforeConnectionFactorySeesIt() throws Exception {
        List<String> opened = new ArrayList<>();
        OfflineModelStore.ConnectionFactory factory = url -> {
            opened.add(url.toString());
            return new FakeConnection(url, 302, "https://example.com/redirect.zip", new byte[0]);
        };
        expectFailure(() -> OfflineModelStore.openHttps(new URL(URL), factory));
        assertTrue(opened.size() == 1);
    }

    @Test public void connectionFailureCannotFallBackToDefaultNetwork() throws Exception {
        List<String> opened = new ArrayList<>();
        OfflineModelStore.ConnectionFactory disconnected = url -> {
            opened.add(url.toString());
            throw new IOException("Wi-Fi was lost");
        };
        expectFailure(() -> OfflineModelStore.openHttps(new URL(URL), disconnected));
        assertTrue(opened.size() == 1);
    }

    @Test public void lowStorageFailsBeforeOpeningDownloadOrCreatingStaging() throws Exception {
        byte[] zip = modelZip("model");
        AtomicBoolean downloadOpened = new AtomicBoolean();
        OfflineModelStore store = lowSpaceStore(zip, downloadOpened);

        try {
            store.install("en", null, null, directory -> {});
            fail("Expected InsufficientStorageException");
        } catch (OfflineModelStore.InsufficientStorageException expected) {
            assertEquals(42, expected.availableMiB);
            assertEquals(256, expected.requiredMiB);
        }
        assertFalse(downloadOpened.get());
        assertEquals(0, temporary.getRoot().list().length);
    }

    @Test public void lowStoragePreservesExistingVerifiedModel() throws Exception {
        byte[] first = modelZip("original");
        OfflineModelStore originalStore = store(first, digest(first));
        originalStore.install("en", null, null, directory -> {});
        byte[] original = Files.readAllBytes(new File(originalStore.modelDirectory("en"), "am/final.mdl").toPath());
        long entriesBefore;
        try (var walk = Files.walk(temporary.getRoot().toPath())) { entriesBefore = walk.count(); }

        AtomicBoolean downloadOpened = new AtomicBoolean();
        OfflineModelStore lowSpace = lowSpaceStore(modelZip("updated!"), downloadOpened);
        expectFailure(() -> lowSpace.install("en", null, null, directory -> {}));
        long entriesAfter;
        try (var walk = Files.walk(temporary.getRoot().toPath())) { entriesAfter = walk.count(); }
        assertFalse(downloadOpened.get());
        assertEquals(entriesBefore, entriesAfter);
        assertArrayEquals(original, Files.readAllBytes(new File(originalStore.modelDirectory("en"), "am/final.mdl").toPath()));
        assertTrue(originalStore.isReady("en"));
    }

    @Test public void readinessCleansOnlyInstallerOwnedOrphanStaging() throws Exception {
        byte[] zip = modelZip("original");
        OfflineModelStore store = store(zip, digest(zip));
        store.install("en", null, null, directory -> {});
        File englishOrphan = new File(temporary.getRoot(), ".en-staging-" + UUID.randomUUID());
        File italianOrphan = new File(temporary.getRoot(), ".it-staging-" + UUID.randomUUID());
        File unrelated = new File(temporary.getRoot(), ".en-staging-not-a-uuid");
        assertTrue(englishOrphan.mkdir());
        assertTrue(italianOrphan.mkdir());
        assertTrue(unrelated.mkdir());
        Files.write(new File(englishOrphan, "partial.zip").toPath(), new byte[] {1});
        Files.write(new File(italianOrphan, "partial.zip").toPath(), new byte[] {2});
        Files.write(new File(unrelated, "keep.txt").toPath(), new byte[] {3});

        assertTrue(store.isReady("en"));
        assertFalse(englishOrphan.exists());
        assertFalse(italianOrphan.exists());
        assertTrue(new File(unrelated, "keep.txt").exists());
        assertTrue(store.modelDirectory("en").isDirectory());
    }

    @Test public void secondInstanceCannotCleanActiveInstallationStaging() throws Exception {
        byte[] zip = modelZip("model");
        OfflineModelStore.ModelSpec spec = new OfflineModelStore.ModelSpec("en", MODEL, URL, digest(zip));
        Map<String, OfflineModelStore.ModelSpec> specs = java.util.Collections.singletonMap("en", spec);
        CountDownLatch downloadOpened = new CountDownLatch(1);
        CountDownLatch releaseDownload = new CountDownLatch(1);
        OfflineModelStore installing = new OfflineModelStore(temporary.getRoot(), specs, url -> {
            downloadOpened.countDown();
            try {
                if (!releaseDownload.await(5, TimeUnit.SECONDS)) throw new IOException("Test download timed out");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Test download interrupted", interrupted);
            }
            return new OfflineModelStore.Download(new ByteArrayInputStream(zip), zip.length, null);
        });
        OfflineModelStore checking = new OfflineModelStore(temporary.getRoot(), specs, url -> {
            throw new AssertionError("Readiness must not download");
        });
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> install = workers.submit(() -> {
                try { installing.install("en", null, null, directory -> {}); }
                catch (IOException failure) { throw new RuntimeException(failure); }
            });
            assertTrue(downloadOpened.await(5, TimeUnit.SECONDS));
            File[] active = temporary.getRoot().listFiles(file -> file.getName().startsWith(".en-staging-"));
            assertEquals(1, active.length);
            CountDownLatch checkStarted = new CountDownLatch(1);
            Future<Boolean> readiness = workers.submit(() -> {
                checkStarted.countDown();
                return checking.isReady("en");
            });
            assertTrue(checkStarted.await(5, TimeUnit.SECONDS));
            try { readiness.get(200, TimeUnit.MILLISECONDS); fail("Readiness ran during active install"); }
            catch (TimeoutException expected) { /* process-wide lock keeps staging live */ }
            assertTrue(active[0].exists());
            releaseDownload.countDown();
            install.get(5, TimeUnit.SECONDS);
            assertTrue(readiness.get(5, TimeUnit.SECONDS));
            assertFalse(active[0].exists());
        } finally {
            releaseDownload.countDown();
            workers.shutdownNow();
        }
    }

    private OfflineModelStore store(byte[] zip, String digest) {
        OfflineModelStore.ModelSpec spec = new OfflineModelStore.ModelSpec("en", MODEL, URL, digest);
        return new OfflineModelStore(temporary.getRoot(), java.util.Collections.singletonMap("en", spec),
                url -> new OfflineModelStore.Download(new ByteArrayInputStream(zip), zip.length, null));
    }

    private OfflineModelStore lowSpaceStore(byte[] zip, AtomicBoolean opened) throws Exception {
        OfflineModelStore.ModelSpec spec = new OfflineModelStore.ModelSpec("en", MODEL, URL, digest(zip));
        return new OfflineModelStore(new LowSpaceFile(temporary.getRoot(), 42L * 1024 * 1024),
                java.util.Collections.singletonMap("en", spec), url -> {
                    opened.set(true);
                    return new OfflineModelStore.Download(new ByteArrayInputStream(zip), zip.length, null);
                });
    }

    private static byte[] modelZip(String modelContents) throws IOException {
        return zip(requiredEntries(modelContents));
    }

    private static Map<String, byte[]> requiredEntries(String modelContents) {
        Map<String, byte[]> entries = new HashMap<>();
        entries.put(MODEL + "/am/final.mdl", modelContents.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        entries.put(MODEL + "/conf/model.conf", new byte[] {1});
        entries.put(MODEL + "/graph/HCLr.fst", new byte[] {2});
        entries.put(MODEL + "/graph/Gr.fst", new byte[] {3});
        return entries;
    }

    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static byte[] markSymlink(byte[] zip, String name) {
        byte[] needle = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (int i = 0; i < zip.length - 46 - needle.length; i++) {
            if (zip[i] != 0x50 || zip[i + 1] != 0x4b || zip[i + 2] != 1 || zip[i + 3] != 2) continue;
            boolean matches = true;
            for (int j = 0; j < needle.length; j++) {
                if (zip[i + 46 + j] != needle[j]) { matches = false; break; }
            }
            if (!matches) continue;
            zip[i + 5] = 3; // Unix origin in version-made-by.
            int mode = 0120777;
            zip[i + 40] = (byte) mode;
            zip[i + 41] = (byte) (mode >>> 8);
            return zip;
        }
        throw new AssertionError("No central entry for " + name);
    }

    private static String digest(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder hex = new StringBuilder();
        for (byte value : digest) hex.append(String.format("%02x", value & 0xff));
        return hex.toString();
    }

    private static void expectFailure(CheckedAction action) throws Exception {
        try { action.run(); fail("Expected IOException"); }
        catch (IOException expected) { /* installer fails closed */ }
    }

    private interface CheckedAction { void run() throws Exception; }

    private static final class FakeConnection extends HttpURLConnection {
        private final int status;
        private final String location;
        private final byte[] bytes;

        FakeConnection(URL url, int status, String location, byte[] bytes) {
            super(url);
            this.status = status;
            this.location = location;
            this.bytes = bytes;
        }

        @Override public void disconnect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
        @Override public int getResponseCode() { return status; }
        @Override public String getHeaderField(String name) { return "Location".equals(name) ? location : null; }
        @Override public long getContentLengthLong() { return bytes.length; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
    }

    private static final class LowSpaceFile extends File {
        private final long available;

        LowSpaceFile(File directory, long available) {
            super(directory.getAbsolutePath());
            this.available = available;
        }

        @Override public long getUsableSpace() { return available; }
    }
}
