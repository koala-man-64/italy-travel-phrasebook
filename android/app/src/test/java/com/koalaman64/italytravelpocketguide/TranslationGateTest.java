package com.koalaman64.italytravelpocketguide;

import org.junit.Test;
import static org.junit.Assert.*;

public class TranslationGateTest {
    @Test public void staleTurnCannotStartAnotherNativeTranslationUntilTaskCompletes() {
        TranslationGate gate = new TranslationGate();
        assertTrue(gate.tryAcquire());
        assertFalse(gate.tryAcquire());
        // Clearing the UI turn does not complete the SDK task or release this gate.
        assertFalse(gate.tryAcquire());
        gate.release();
        assertTrue(gate.tryAcquire());
        gate.release();
    }
}
