package com.koalaman64.italytravelpocketguide;

import android.annotation.SuppressLint;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.SystemClock;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.File;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/** A single native microphone stream. Audio never leaves this process or reaches disk. */
final class AudioTurn implements AutoCloseable {
    static final int SAMPLE_RATE = 16000;
    static final int MAX_SECONDS = 30;
    static final int MAX_BYTES = SAMPLE_RATE * 2 * MAX_SECONDS;
    interface Listener {
        void listening();
        void elapsed(long milliseconds);
        void result(String text);
        void failed(String message);
    }
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicLong generation = new AtomicLong();
    private final Object devices = new Object();
    private volatile boolean finishRequested;
    private volatile boolean closed;
    private volatile byte[] clip = new byte[0];
    private AudioRecord recorder;
    private AudioTrack player;
    private Model model;
    private String modelPath;

    void start(File directory, Listener listener) {
        cancel();
        long id = generation.get();
        finishRequested = false;
        worker.execute(() -> capture(id, directory, listener));
    }

    @SuppressLint("MissingPermission") // Host checks permission immediately before each start.
    private void capture(long id, File directory, Listener listener) {
        byte[] recording = new byte[MAX_BYTES];
        AudioRecord input = null;
        String recognized = null;
        String failureMessage = null;
        try {
            if (!current(id)) return;
            String path = directory.getAbsolutePath();
            if (!path.equals(modelPath)) {
                releaseModel();
                model = new Model(path);
                modelPath = path;
            }
            if (!current(id)) return;
            int minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new IllegalStateException("Unsupported recording format");
            input = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minimum * 2, 8192));
            if (input.getState() != AudioRecord.STATE_INITIALIZED || input.getSampleRate() != SAMPLE_RATE)
                throw new IllegalStateException("Microphone initialization failed");
            synchronized (devices) {
                if (!current(id)) return;
                recorder = input;
                input.startRecording();
            }
            if (input.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
                throw new IllegalStateException("Microphone did not start");
            listener.listening();
            int used = 0;
            long began = SystemClock.elapsedRealtime();
            long lastTick = 0;
            StringBuilder words = new StringBuilder();
            try (Recognizer recognizer = new Recognizer(model, SAMPLE_RATE)) {
                byte[] chunk = new byte[2048];
                while (current(id) && !finishRequested && used < MAX_BYTES
                        && SystemClock.elapsedRealtime() - began < MAX_SECONDS * 1000L) {
                    int count = input.read(chunk, 0, Math.min(chunk.length, MAX_BYTES - used));
                    if (count < 0) {
                        if (!current(id) || finishRequested) break;
                        throw new IllegalStateException("Microphone read failed");
                    }
                    if (count == 0) continue;
                    System.arraycopy(chunk, 0, recording, used, count);
                    used += count;
                    if (recognizer.acceptWaveForm(chunk, count)) append(words, recognizer.getResult());
                    long elapsed = SystemClock.elapsedRealtime() - began;
                    if (elapsed - lastTick >= 250) {
                        lastTick = elapsed;
                        listener.elapsed(Math.min(elapsed, MAX_SECONDS * 1000L));
                    }
                }
                append(words, recognizer.getFinalResult());
            }
            if (retainClip(id, recording, used)) {
                recognized = words.toString().trim();
            }
        } catch (Exception | LinkageError failure) {
            if (current(id)) {
                clearClip();
                failureMessage = "Could not record or recognize speech. Retry after checking microphone permission and closing other recording apps. If it keeps failing, prepare the language models again on Wi-Fi.";
            }
            releaseModel();
        } finally {
            Arrays.fill(recording, (byte) 0);
            synchronized (devices) {
                if (recorder == input) recorder = null;
                if (input != null) {
                    try { input.stop(); } catch (IllegalStateException ignored) { }
                    input.release();
                }
            }
        }
        // Do not announce a terminal state until the device handle is actually released.
        if (current(id)) {
            if (failureMessage != null) listener.failed(failureMessage);
            else if (recognized != null) listener.result(recognized);
        }
    }

    private static void append(StringBuilder words, String json) throws Exception {
        String part = new JSONObject(json).optString("text", "").trim();
        if (!part.isEmpty()) {
            if (words.length() > 0) words.append(' ');
            words.append(part);
        }
    }

    boolean hasClip() { return clip.length > 0; }

    boolean retainClip(long id, byte[] pcm, int length) {
        synchronized (devices) {
            if (!current(id)) return false;
            if (length < 0 || length > MAX_BYTES || length > pcm.length)
                throw new IllegalArgumentException("Invalid clip length");
            clip = Arrays.copyOf(pcm, length);
            return true;
        }
    }

    Future<?> unload() {
        cancel();
        return worker.submit(this::releaseModel);
    }

    void finish() {
        finishRequested = true;
        synchronized (devices) {
            if (recorder != null) {
                try { recorder.stop(); } catch (IllegalStateException ignored) { }
            }
        }
    }

    void replay(Runnable done, Runnable failed) {
        long id = generation.incrementAndGet();
        stopDevices();
        byte[] original = clip;
        if (original.length == 0) { failed.run(); return; }
        // No copies outside the native process; clear/cancel stops playback before discarding audio.
        worker.execute(() -> {
            AudioTrack track = null;
            boolean playbackFailed = false;
            try {
                if (!current(id)) return;
                track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                        .setBufferSizeInBytes(original.length)
                        .setTransferMode(AudioTrack.MODE_STATIC).build();
                synchronized (devices) {
                    if (!current(id)) return;
                    player = track;
                    if (track.write(original, 0, original.length) != original.length)
                        throw new IllegalStateException("Audio playback write failed");
                    track.play();
                }
                int frames = original.length / 2;
                long deadline = SystemClock.elapsedRealtime() + MAX_SECONDS * 1000L + 2000;
                while (current(id) && track.getPlaybackHeadPosition() < frames) {
                    if (SystemClock.elapsedRealtime() > deadline)
                        throw new IllegalStateException("Audio playback timed out");
                    Thread.sleep(25);
                }
            } catch (Exception failure) {
                playbackFailed = true;
            } finally {
                synchronized (devices) {
                    if (player == track) player = null;
                    if (track != null) {
                        try { track.stop(); } catch (IllegalStateException ignored) { }
                        track.release();
                    }
                }
            }
            if (current(id)) {
                if (playbackFailed) failed.run(); else done.run();
            }
        });
    }

    void stopPlayback() {
        generation.incrementAndGet();
        stopDevices();
    }

    void cancel() {
        synchronized (devices) {
            generation.incrementAndGet();
            finishRequested = true;
            stopDevices();
            clearClip();
        }
    }

    private void clearClip() {
        byte[] previous = clip;
        clip = new byte[0];
        Arrays.fill(previous, (byte) 0);
    }

    private void stopDevices() {
        synchronized (devices) {
            if (recorder != null) {
                try { recorder.stop(); } catch (IllegalStateException ignored) { }
            }
            if (player != null) {
                try { player.stop(); } catch (IllegalStateException ignored) { }
            }
        }
    }

    private boolean current(long id) { return !closed && generation.get() == id; }
    private void releaseModel() {
        if (model != null) model.close();
        model = null;
        modelPath = null;
    }

    @Override public void close() {
        closed = true;
        cancel();
        worker.execute(this::releaseModel);
        worker.shutdown();
    }
}
