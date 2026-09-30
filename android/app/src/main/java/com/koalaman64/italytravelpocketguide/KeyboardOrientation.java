package com.koalaman64.italytravelpocketguide;

import android.content.pm.ActivityInfo;
import android.view.Surface;

/** Chooses the opposite physical edge on portrait- and landscape-natural devices. */
final class KeyboardOrientation {
    private KeyboardOrientation() {}

    static int opposite(boolean portrait, int rotation) {
        boolean normal = rotation == Surface.ROTATION_0
                || rotation == (portrait ? Surface.ROTATION_270 : Surface.ROTATION_90);
        if (portrait) return normal ? ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        return normal ? ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
    }
}
