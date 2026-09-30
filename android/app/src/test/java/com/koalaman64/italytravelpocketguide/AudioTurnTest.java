package com.koalaman64.italytravelpocketguide;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;

public class AudioTurnTest {
    private static long token(AudioTurn turn) throws Exception {
        Field field = AudioTurn.class.getDeclaredField("generation");
        field.setAccessible(true);
        return ((AtomicLong) field.get(turn)).get();
    }

    @Test public void backgroundCancelRejectsAWorkerThatFinishedRecognitionBeforeCancel() throws Exception {
        try (AudioTurn turn = new AudioTurn()) {
            long inFlight = token(turn);
            assertTrue(turn.retainClip(inFlight, new byte[]{1, 2}, 2));
            turn.cancel();
            assertFalse(turn.hasClip());
            // Worker had finished recognition, but publication raced with background/cancel.
            assertFalse(turn.retainClip(inFlight, new byte[]{3, 4}, 2));
            assertFalse(turn.hasClip());
        }
    }

    @Test public void staleWorkerCannotReplaceAudioFromANewerTurn() throws Exception {
        try (AudioTurn turn = new AudioTurn()) {
            long old = token(turn);
            turn.cancel();
            assertTrue(turn.retainClip(token(turn), new byte[]{7, 8}, 2));
            assertFalse(turn.retainClip(old, new byte[]{1, 2}, 2));
            assertTrue(turn.hasClip());
        }
    }
}
