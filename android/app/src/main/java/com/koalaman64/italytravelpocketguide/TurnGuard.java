package com.koalaman64.italytravelpocketguide;

/** Generation checks prevent a cancelled SDK callback from overwriting the current turn. */
final class TurnGuard {
    static final int MAX_TEXT = 2000;
    static final int MAX_MESSAGE = 32768;
    private long generation;
    private boolean closed;
    long next() { return ++generation; }
    boolean current(long value) { return !closed && generation == value; }
    void close() { closed = true; next(); }
    static boolean language(String value) { return "en".equals(value) || "it".equals(value); }
    static String target(String source) {
        if (!language(source)) throw new IllegalArgumentException("Unsupported language");
        return "en".equals(source) ? "it" : "en";
    }
    static String text(String value) {
        if (value == null || value.length() > MAX_TEXT || value.trim().isEmpty())
            throw new IllegalArgumentException("Enter 1–2000 characters");
        return value.trim();
    }
    static boolean tracksRequest(String op, String state, boolean busy,
                                 boolean hasClip, boolean hasTranslation) {
        return switch (op) {
            case "status" -> false;
            case "start", "setup", "installVoice", "speakText" -> !busy;
            case "translate" -> !busy;
            case "stop" -> "recording".equals(state);
            case "stopPlayback" -> "playing".equals(state);
            case "replay" -> !busy && hasClip;
            case "speak" -> !busy && hasTranslation;
            default -> true;
        };
    }
}
