package com.koalaman64.italytravelpocketguide;

import org.junit.Test;
import static org.junit.Assert.*;

public class TurnGuardTest {
    @Test public void cancelAndNewTurnRejectOldCallbacks() {
        TurnGuard guard = new TurnGuard();
        long first = guard.next();
        assertTrue(guard.current(first));
        long second = guard.next();
        assertFalse(guard.current(first));
        assertTrue(guard.current(second));
        guard.close();
        assertFalse(guard.current(second));
        assertFalse(guard.current(guard.next()));
    }
    @Test public void directionIsAlwaysExplicitAndOpposite() {
        assertEquals("it", TurnGuard.target("en"));
        assertEquals("en", TurnGuard.target("it"));
        assertFalse(TurnGuard.language("en-US"));
        assertThrows(IllegalArgumentException.class, () -> TurnGuard.target("fr"));
    }
    @Test public void textRejectsEmptyAndOversizedButPreservesUntrustedTextAsText() {
        assertThrows(IllegalArgumentException.class, () -> TurnGuard.text("   "));
        assertThrows(IllegalArgumentException.class, () -> TurnGuard.text("a".repeat(2001)));
        assertEquals("<img src=x>", TurnGuard.text(" <img src=x> "));
        assertEquals(2000, TurnGuard.text("a".repeat(2000)).length());
    }
    @Test public void requestCorrelationIgnoresStatusAndRejectedBusyCommands() {
        assertFalse(TurnGuard.tracksRequest("status", "translating", true, false, false));
        assertFalse(TurnGuard.tracksRequest("start", "translating", true, false, false));
        assertFalse(TurnGuard.tracksRequest("stop", "finalizing", true, true, false));
        assertTrue(TurnGuard.tracksRequest("stop", "recording", true, false, false));
        assertFalse(TurnGuard.tracksRequest("translate", "translating", true, true, false));
        assertTrue(TurnGuard.tracksRequest("translate", "result", false, true, true));
        assertTrue(TurnGuard.tracksRequest("present", "translating", true, true, false));
    }
}
