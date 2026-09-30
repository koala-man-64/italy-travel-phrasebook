package com.koalaman64.italytravelpocketguide;

import org.junit.Test;
import static org.junit.Assert.*;

public class AutoTranslationTest {
    @Test public void explicitOptInIsConsumedExactlyOnce() {
        AutoTranslation automatic = new AutoTranslation();
        assertFalse(automatic.take());
        automatic.arm(true);
        assertTrue(automatic.take());
        assertFalse(automatic.take());
        automatic.arm(false);
        assertFalse(automatic.take());
    }

    @Test public void clearInvalidatesPendingReply() {
        AutoTranslation automatic = new AutoTranslation();
        automatic.arm(true);
        automatic.clear();
        assertFalse(automatic.take());
    }
}
