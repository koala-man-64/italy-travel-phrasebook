package com.koalaman64.italytravelpocketguide;

import java.util.concurrent.atomic.AtomicBoolean;

/** Prevents overlapping ML Kit native translation work, including after a turn is cleared. */
final class TranslationGate {
    private final AtomicBoolean inFlight = new AtomicBoolean();

    boolean tryAcquire() { return inFlight.compareAndSet(false, true); }
    void release() { inFlight.set(false); }
}
