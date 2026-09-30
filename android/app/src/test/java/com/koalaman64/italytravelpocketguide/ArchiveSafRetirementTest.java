package com.koalaman64.italytravelpocketguide;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ArchiveSafRetirementTest {
    @Test public void successfulCloseReleasesOwnerWithoutQuarantine() throws Exception {
        ArchiveSafRetirement retirement = new ArchiveSafRetirement();
        Object descriptor = new Object();
        AtomicBoolean closed = new AtomicBoolean(), released = new AtomicBoolean();
        retirement.retire(descriptor, () -> closed.set(true), () -> {
            assertTrue(closed.get()); released.set(true);
        });
        assertTrue(released.get());
        assertFalse(retirement.quarantined());
        assertEquals(0, retirement.retainedCount());
    }

    @Test public void closeFailurePinsDescriptorAndQuarantinesFutureOwner() {
        ArchiveSafRetirement retirement = new ArchiveSafRetirement();
        Object descriptor = new Object();
        AtomicBoolean released = new AtomicBoolean();
        try {
            retirement.retire(descriptor, () -> { throw new IOException("provider close failed"); },
                    () -> released.set(true));
            fail("Expected provider close failure");
        } catch (IOException expected) {
            assertEquals("provider close failed", expected.getMessage());
        }
        assertFalse(released.get());
        assertTrue(retirement.quarantined());
        assertEquals(1, retirement.retainedCount());
        // A recreated Activity shares the same process retirement state.
        ArchiveSafRetirement anotherOwner = retirement;
        assertTrue(anotherOwner.quarantined());
    }

    @Test public void rejectedCleanupRetainsLateDescriptorWithoutReleasingOwner() {
        ArchiveSafRetirement retirement = new ArchiveSafRetirement();
        Object lateDescriptor = new Object();
        retirement.rejected(lateDescriptor);
        assertTrue(retirement.quarantined());
        assertEquals(1, retirement.retainedCount());
    }

    @Test public void releaseFailureIsNotReportedAsVerifiedRetirement() {
        ArchiveSafRetirement retirement = new ArchiveSafRetirement();
        Object descriptor = new Object();
        try {
            retirement.retire(descriptor, () -> {}, () -> { throw new IllegalStateException("owner dispatch failed"); });
            fail("Expected owner dispatch failure");
        } catch (IllegalStateException expected) {
            assertEquals("owner dispatch failed", expected.getMessage());
        } catch (IOException impossible) { fail(impossible.getMessage()); }
        assertTrue(retirement.quarantined());
        assertEquals(1, retirement.retainedCount());
    }
}
