package com.koalaman64.italytravelpocketguide;

/** One recognition result can initiate at most one automatic translation. */
final class AutoTranslation {
    private boolean pending;

    void arm(boolean enabled) { pending = enabled; }
    boolean take() {
        boolean shouldTranslate = pending;
        pending = false;
        return shouldTranslate;
    }
    void clear() { pending = false; }
}
