package com.koalaman64.italytravelpocketguide;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Installs verified Vosk models under an app-private directory. Never handles recorded audio. */
public final class OfflineModelStore {
    private static final long MAX_ARCHIVE_BYTES = 128L * 1024 * 1024;
    private static final long MAX_EXTRACTED_BYTES = 768L * 1024 * 1024;
    private static final long MAX_FILE_BYTES = 256L * 1024 * 1024;
    private static final long MIN_FREE_BYTES = 256L * 1024 * 1024;
    private static final long MIB = 1024L * 1024;
    private static final int MAX_FILES = 5000;
    private static final int MAX_ENTRIES = 10000;
    private static final String MANIFEST = ".model-integrity";
    private static final String MODEL_HOST = "alphacephei.com";
    private static final String[] REQUIRED = {"am/final.mdl", "conf/model.conf", "graph/HCLr.fst", "graph/Gr.fst"};
    private static final Object PROCESS_MODEL_LOCK = new Object();
    private static final Pattern STAGING_NAME = Pattern.compile(
            "\\.(en|it)-staging-[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");

    // SHA-256 of the exact ZIP bytes from the official Vosk model catalog.
    private static final Map<String, ModelSpec> PRODUCTION = productionSpecs();

    private static Map<String, ModelSpec> productionSpecs() {
        Map<String, ModelSpec> models = new HashMap<>();
        models.put("en", new ModelSpec("en", "vosk-model-small-en-us-0.15",
                "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
                "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"));
        models.put("it", new ModelSpec("it", "vosk-model-small-it-0.22",
                "https://alphacephei.com/vosk/models/vosk-model-small-it-0.22.zip",
                "9ec65e75861d1c6c2e457cccd932705340dcdf233f5b239f00733b4de0bf3267"));
        return Collections.unmodifiableMap(models);
    }

    private final File root;
    private final Map<String, ModelSpec> specs;
    private final DownloadSource source;

    public interface Progress {
        void onDownload(long receivedBytes, long totalBytes);
    }

    public interface Cancellation {
        boolean isCancelled();
    }

    /** Advisory free-space failure; storage can still fill after this check. */
    public static final class InsufficientStorageException extends IOException {
        public final long availableMiB;
        public final long requiredMiB;

        InsufficientStorageException(long availableBytes) {
            super("Free storage is " + (availableBytes / MIB) + " MiB; at least "
                    + (MIN_FREE_BYTES / MIB) + " MiB is needed to prepare an offline speech model. "
                    + "Free space and retry. Existing verified models are preserved.");
            availableMiB = availableBytes / MIB;
            requiredMiB = MIN_FREE_BYTES / MIB;
        }
    }

    /** Must try loading the staged directory with Vosk and throw if the model is unusable. */
    public interface Validator {
        void validate(File stagedModelDirectory) throws IOException;
    }

    /** Opens every HTTPS request, including redirects, on the caller-selected network. */
    public interface ConnectionFactory {
        URLConnection open(URL url) throws IOException;
    }

    interface DownloadSource {
        Download open(URL url) throws IOException;
    }

    static final class Download implements AutoCloseable {
        final InputStream stream;
        final long length;
        final HttpURLConnection connection;

        Download(InputStream stream, long length, HttpURLConnection connection) {
            this.stream = stream;
            this.length = length;
            this.connection = connection;
        }

        @Override public void close() throws IOException {
            try { stream.close(); } finally {
                if (connection != null) connection.disconnect();
            }
        }
    }

    static final class ModelSpec {
        final String language;
        final String directoryName;
        final String url;
        final String sha256;

        ModelSpec(String language, String directoryName, String url, String sha256) {
            this.language = language;
            this.directoryName = directoryName;
            this.url = url;
            this.sha256 = sha256;
        }
    }

    public OfflineModelStore(File appPrivateRoot) {
        this(appPrivateRoot, URL::openConnection);
    }

    public OfflineModelStore(File appPrivateRoot, ConnectionFactory connections) {
        this(appPrivateRoot, PRODUCTION, url -> openHttps(url, connections));
    }

    OfflineModelStore(File appPrivateRoot, Map<String, ModelSpec> specs, DownloadSource source) {
        if (appPrivateRoot == null || specs == null || source == null) throw new IllegalArgumentException("null model store argument");
        this.root = appPrivateRoot;
        this.specs = Collections.unmodifiableMap(new HashMap<>(specs));
        this.source = source;
    }

    public File modelDirectory(String language) {
        spec(language);
        return new File(root, language);
    }

    /** Performs a full integrity scan; call from a worker thread, not the UI thread. */
    public boolean isReady(String language) {
        synchronized (PROCESS_MODEL_LOCK) { return isReadyLocked(language); }
    }

    private boolean isReadyLocked(String language) {
        ModelSpec model = spec(language);
        try {
            recover(model);
            cleanupOrphanStaging();
            return verifyInstalled(model, modelDirectory(language));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    public void install(String language, Progress progress, Cancellation cancellation,
                        Validator validator) throws IOException {
        synchronized (PROCESS_MODEL_LOCK) { installLocked(language, progress, cancellation, validator); }
    }

    private void installLocked(String language, Progress progress, Cancellation cancellation,
                               Validator validator) throws IOException {
        ModelSpec model = spec(language);
        if (validator == null) throw new IllegalArgumentException("model validator required");
        if (model.sha256 == null || !model.sha256.matches("[a-fA-F0-9]{64}")) {
            throw new IOException("No verified SHA-256 is configured for " + language);
        }
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Cannot create private model directory");
        recover(model);
        cleanupOrphanStaging();
        long freeBytes = root.getUsableSpace();
        if (freeBytes < MIN_FREE_BYTES) throw new InsufficientStorageException(freeBytes);
        checkCancelled(cancellation);

        File staging = new File(root, "." + language + "-staging-" + UUID.randomUUID());
        File archive = new File(staging, "model.zip");
        File extracted = new File(staging, "extracted");
        try {
            if (!staging.mkdir() || !extracted.mkdir()) throw new IOException("Cannot stage model");
            download(model, archive, progress, cancellation);
            rejectArchiveSymlinks(archive);
            extract(model, archive, extracted, cancellation);
            File stagedModel = new File(extracted, model.directoryName);
            for (String relative : REQUIRED) {
                if (!Files.isRegularFile(new File(stagedModel, relative).toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Incomplete Vosk model: " + relative);
                }
            }
            checkCancelled(cancellation);
            validator.validate(stagedModel);
            checkCancelled(cancellation);
            writeManifest(model, stagedModel);
            if (!verifyInstalled(model, stagedModel)) throw new IOException("Staged model integrity failed");
            activate(model, stagedModel);
        } finally {
            deleteTree(staging.toPath());
        }
    }

    private ModelSpec spec(String language) {
        ModelSpec model = specs.get(language);
        if (model == null || !("en".equals(language) || "it".equals(language))) {
            throw new IllegalArgumentException("Unsupported model language");
        }
        return model;
    }

    private void download(ModelSpec model, File archive, Progress progress, Cancellation cancellation) throws IOException {
        URL url = new URL(model.url);
        validateUrl(url);
        MessageDigest digest = sha256();
        try (Download download = source.open(url);
             OutputStream out = new BufferedOutputStream(new FileOutputStream(archive))) {
            if (download.length > MAX_ARCHIVE_BYTES) throw new IOException("Model archive too large");
            byte[] buffer = new byte[64 * 1024];
            long received = 0;
            int count;
            while (true) {
                checkCancelled(cancellation);
                count = download.stream.read(buffer);
                if (count == -1) break;
                checkCancelled(cancellation);
                received += count;
                if (received > MAX_ARCHIVE_BYTES) throw new IOException("Model archive too large");
                digest.update(buffer, 0, count);
                out.write(buffer, 0, count);
                if (progress != null) progress.onDownload(received, download.length);
            }
            if (download.length >= 0 && received != download.length) throw new IOException("Incomplete model archive");
        }
        if (!hex(digest.digest()).equalsIgnoreCase(model.sha256)) throw new IOException("Model archive SHA-256 mismatch");
    }

    static Download openHttps(URL initial, ConnectionFactory connections) throws IOException {
        if (connections == null) throw new IllegalArgumentException("connection factory required");
        URL url = initial;
        for (int redirects = 0; redirects <= 3; redirects++) {
            validateUrl(url);
            URLConnection opened = connections.open(url);
            if (!(opened instanceof HttpURLConnection)) throw new IOException("Model connection is not HTTP(S)");
            HttpURLConnection connection = (HttpURLConnection) opened;
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null || redirects == 3) throw new IOException("Invalid model redirect");
                url = new URL(url, location);
                continue;
            }
            if (status != 200) {
                connection.disconnect();
                throw new IOException("Model download HTTP " + status);
            }
            long length = connection.getContentLengthLong();
            if (length > MAX_ARCHIVE_BYTES) {
                connection.disconnect();
                throw new IOException("Model archive too large");
            }
            try { return new Download(new BufferedInputStream(connection.getInputStream()), length, connection); }
            catch (IOException e) { connection.disconnect(); throw e; }
        }
        throw new IOException("Too many model redirects");
    }

    private static void validateUrl(URL url) throws IOException {
        if (!"https".equalsIgnoreCase(url.getProtocol()) || !MODEL_HOST.equalsIgnoreCase(url.getHost())
                || (url.getPort() != -1 && url.getPort() != 443) || url.getUserInfo() != null) {
            throw new IOException("Unapproved model download URL");
        }
    }

    private static void extract(ModelSpec model, File archive, File extracted, Cancellation cancellation) throws IOException {
        Path rootPath = extracted.toPath().toRealPath();
        Set<String> names = new HashSet<>();
        long total = 0;
        int files = 0;
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(archive)))) {
            ZipEntry entry;
            byte[] buffer = new byte[64 * 1024];
            while ((entry = zip.getNextEntry()) != null) {
                checkCancelled(cancellation);
                String name = entry.getName();
                if (++entries > MAX_ENTRIES || name.length() > 512 || name.indexOf('\\') >= 0
                        || name.startsWith("/") || name.indexOf('\0') >= 0
                        || name.contains(":") || name.split("/", -1).length > 16) {
                    throw new IOException("Unsafe model archive path");
                }
                Path output = rootPath.resolve(name).normalize();
                if (!output.startsWith(rootPath.resolve(model.directoryName)) || output.equals(rootPath.resolve(model.directoryName))) {
                    if (!entry.isDirectory() || !output.equals(rootPath.resolve(model.directoryName))) {
                        throw new IOException("Unexpected model archive path");
                    }
                }
                String normalized = output.toString().toLowerCase(Locale.ROOT);
                if (!names.add(normalized)) throw new IOException("Duplicate model archive path");
                if (entry.isDirectory()) {
                    Files.createDirectories(output);
                } else {
                    if (++files > MAX_FILES) throw new IOException("Too many model files");
                    Files.createDirectories(output.getParent());
                    if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Duplicate model file");
                    long fileBytes = 0;
                    try (OutputStream out = new BufferedOutputStream(new FileOutputStream(output.toFile()))) {
                        int count;
                        while ((count = zip.read(buffer)) != -1) {
                            checkCancelled(cancellation);
                            fileBytes += count;
                            total += count;
                            if (fileBytes > MAX_FILE_BYTES || total > MAX_EXTRACTED_BYTES) {
                                throw new IOException("Expanded model exceeds limit");
                            }
                            out.write(buffer, 0, count);
                        }
                    }
                }
                zip.closeEntry();
            }
        }
        if (files == 0) throw new IOException("Empty model archive");
    }

    /** ZipInputStream does not expose Unix link attributes; reject links from the central directory. */
    private static void rejectArchiveSymlinks(File archive) throws IOException {
        try (RandomAccessFile zip = new RandomAccessFile(archive, "r")) {
            long length = zip.length();
            long lower = Math.max(0, length - 65557);
            long end = -1;
            for (long pos = length - 22; pos >= lower; pos--) {
                zip.seek(pos);
                if (readIntLittleEndian(zip) == 0x06054b50) { end = pos; break; }
            }
            if (end < 0) throw new IOException("Missing ZIP central directory");
            zip.seek(end + 10);
            int count = readShortLittleEndian(zip);
            long centralSize = Integer.toUnsignedLong(readIntLittleEndian(zip));
            long centralOffset = Integer.toUnsignedLong(readIntLittleEndian(zip));
            if (count == 0xffff || count > MAX_ENTRIES || centralSize == 0xffffffffL
                    || centralOffset == 0xffffffffL || centralOffset + centralSize > end) {
                throw new IOException("Unsupported ZIP central directory");
            }
            zip.seek(centralOffset);
            for (int i = 0; i < count; i++) {
                if (readIntLittleEndian(zip) != 0x02014b50) throw new IOException("Invalid ZIP central entry");
                int madeBy = readShortLittleEndian(zip);
                zip.skipBytes(22);
                int nameLength = readShortLittleEndian(zip);
                int extraLength = readShortLittleEndian(zip);
                int commentLength = readShortLittleEndian(zip);
                zip.skipBytes(4);
                long attributes = Integer.toUnsignedLong(readIntLittleEndian(zip));
                zip.skipBytes(4);
                if ((madeBy >>> 8) == 3 && (((attributes >>> 16) & 0170000) == 0120000)) {
                    throw new IOException("Model ZIP contains a symbolic link");
                }
                long next = zip.getFilePointer() + nameLength + extraLength + commentLength;
                if (next > centralOffset + centralSize) throw new IOException("Invalid ZIP central entry length");
                zip.seek(next);
            }
        }
    }

    private static int readShortLittleEndian(RandomAccessFile file) throws IOException {
        return file.readUnsignedByte() | (file.readUnsignedByte() << 8);
    }

    private static int readIntLittleEndian(RandomAccessFile file) throws IOException {
        return readShortLittleEndian(file) | (readShortLittleEndian(file) << 16);
    }

    private static void writeManifest(ModelSpec model, File directory) throws IOException {
        List<Path> files = new ArrayList<>();
        try (var walk = Files.walk(directory.toPath())) {
            walk.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !path.getFileName().toString().equals(MANIFEST))
                    .forEach(files::add);
        }
        files.sort(Path::compareTo);
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(new File(directory, MANIFEST)), StandardCharsets.UTF_8))) {
            writer.write(model.directoryName + "\t" + model.sha256.toLowerCase(Locale.ROOT));
            writer.newLine();
            for (Path path : files) {
                String relative = directory.toPath().relativize(path).toString().replace(File.separatorChar, '/');
                writer.write(relative + "\t" + Files.size(path) + "\t" + fileSha256(path));
                writer.newLine();
            }
        }
    }

    private static boolean verifyInstalled(ModelSpec model, File directory) throws IOException {
        File manifest = new File(directory, MANIFEST);
        if (!Files.isRegularFile(manifest.toPath(), LinkOption.NOFOLLOW_LINKS)) return false;
        if (model.sha256 == null) return false;
        Set<String> listed = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(manifest), StandardCharsets.UTF_8))) {
            if (!(model.directoryName + "\t" + model.sha256.toLowerCase(Locale.ROOT)).equals(reader.readLine())) return false;
            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = line.split("\t", -1);
                if (fields.length != 3 || !listed.add(fields[0]) || !fields[2].matches("[a-f0-9]{64}")) return false;
                Path path = directory.toPath().resolve(fields[0]).normalize();
                if (!path.startsWith(directory.toPath()) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        || Files.size(path) != Long.parseLong(fields[1]) || !fileSha256(path).equals(fields[2])) return false;
            }
        }
        if (!listed.containsAll(Arrays.asList(REQUIRED))) return false;
        try (var walk = Files.walk(directory.toPath())) {
            long actual = walk.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !path.getFileName().toString().equals(MANIFEST)).count();
            return actual == listed.size();
        }
    }

    private void activate(ModelSpec model, File stagedModel) throws IOException {
        File current = modelDirectory(model.language);
        File backup = new File(root, "." + model.language + "-previous");
        if (backup.exists()) throw new IOException("Previous model recovery is incomplete");
        boolean hadCurrent = current.exists();
        if (hadCurrent) move(current.toPath(), backup.toPath());
        try {
            move(stagedModel.toPath(), current.toPath());
        } catch (IOException e) {
            if (hadCurrent) move(backup.toPath(), current.toPath());
            throw e;
        }
        if (hadCurrent) deleteTree(backup.toPath());
    }

    private void recover(ModelSpec model) throws IOException {
        File current = modelDirectory(model.language);
        File backup = new File(root, "." + model.language + "-previous");
        if (!backup.exists()) return;
        if (!current.exists()) move(backup.toPath(), current.toPath());
        else if (verifyInstalled(model, current)) deleteTree(backup.toPath());
        else if (verifyInstalled(model, backup)) {
            deleteTree(current.toPath());
            move(backup.toPath(), current.toPath());
        } else throw new IOException("Incomplete model activation");
    }

    /** Only names generated by this installer are eligible; Files.walk never follows links. */
    private void cleanupOrphanStaging() throws IOException {
        if (!root.isDirectory()) return;
        File[] entries = root.listFiles();
        if (entries == null) throw new IOException("Cannot inspect private model directory");
        for (File entry : entries) {
            if (STAGING_NAME.matcher(entry.getName()).matches()) deleteTree(entry.toPath());
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try { Files.move(from, to, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(from, to); }
    }

    private static void deleteTree(Path tree) throws IOException {
        if (!Files.exists(tree, LinkOption.NOFOLLOW_LINKS)) return;
        try (var walk = Files.walk(tree)) {
            for (Path path : walk.sorted((a, b) -> b.compareTo(a)).collect(Collectors.toList())) Files.deleteIfExists(path);
        }
    }

    private static void checkCancelled(Cancellation cancellation) throws IOException {
        if (cancellation != null && cancellation.isCancelled()) throw new IOException("Model installation cancelled");
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static String fileSha256(Path path) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream in = new BufferedInputStream(Files.newInputStream(path))) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] result = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            result[i * 2] = digits[(bytes[i] >> 4) & 0xf];
            result[i * 2 + 1] = digits[bytes[i] & 0xf];
        }
        return new String(result);
    }
}
