package com.koalaman64.italytravelpocketguide;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import android.content.pm.ActivityInfo;
import android.view.Surface;

public class KeyboardOrientationTest {
    @Test public void portraitNaturalPhoneFacesOppositeEdge() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT, KeyboardOrientation.opposite(true, Surface.ROTATION_0));
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, KeyboardOrientation.opposite(true, Surface.ROTATION_180));
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE, KeyboardOrientation.opposite(false, Surface.ROTATION_90));
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, KeyboardOrientation.opposite(false, Surface.ROTATION_270));
    }
    @Test public void landscapeNaturalDeviceFacesOppositeEdge() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE, KeyboardOrientation.opposite(false, Surface.ROTATION_0));
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, KeyboardOrientation.opposite(false, Surface.ROTATION_180));
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, KeyboardOrientation.opposite(true, Surface.ROTATION_90));
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT, KeyboardOrientation.opposite(true, Surface.ROTATION_270));
    }
}
