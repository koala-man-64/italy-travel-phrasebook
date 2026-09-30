package com.koalaman64.italytravelpocketguide;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Process-owned proof of provider descriptor retirement; failed closes stay quarantined. */
final class ArchiveSafRetirement {
    interface Closer { void close() throws IOException; }

    private final List<Object> uncertainDescriptors = new ArrayList<>();
    private boolean quarantined;

    synchronized boolean quarantined() { return quarantined; }
    synchronized int retainedCount() { return uncertainDescriptors.size(); }

    void retire(Object descriptor, Closer closer, Runnable release) throws IOException {
        try {
            closer.close();
            release.run();
        } catch (IOException | RuntimeException failure) {
            rejected(descriptor);
            throw failure;
        }
    }

    synchronized void rejected(Object descriptor) {
        quarantined = true;
        uncertainDescriptors.add(descriptor);
    }
}
