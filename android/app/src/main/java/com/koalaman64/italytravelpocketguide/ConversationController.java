package com.koalaman64.italytravelpocketguide;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import com.google.android.gms.tasks.Task;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.TranslateRemoteModel;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import org.json.JSONException;
import org.json.JSONObject;
import org.vosk.Model;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Native owner of conversation state. UI-thread transitions, worker-thread models/audio. */
final class ConversationController implements AutoCloseable {
    private static final TranslationGate TRANSLATION_GATE = new TranslationGate();
    static final int MICROPHONE_REQUEST = 41;
    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService modelsWorker = Executors.newSingleThreadExecutor();
    private final TurnGuard guard = new TurnGuard();
    private final AudioTurn audio = new AudioTurn();
    private final OfflineModelStore models;
    private final Translator englishToItalian;
    private final Translator italianToEnglish;
    private final Map<String, Voice> voices = new HashMap<>();
    private final AutoTranslation autoTranslation = new AutoTranslation();
    private final Runnable phraseEnded;
    private Consumer<String> events = ignored -> { };
    private TextToSpeech speech;
    private volatile boolean italianVoiceReady;
    private boolean speechInitialized;
    private boolean closed;
    private boolean foreground = true;
    private boolean modelEnglish;
    private boolean modelItalian;
    private boolean translationReady;
    private long readinessEpoch;
    private long lastRequest;
    private long requestId;
    private long sessionEpoch;
    private long eventSequence;
    private long recordingMs;
    private String state = "setup";
    private String source = "en";
    private String text = "";
    private String translation = "";
    private boolean machineTranslated;
    private String error = "";
    private String progress = "";
    private String utteranceId;
    private boolean utteranceIsPhrase;
    private long utteranceCounter;
    private AtomicBoolean setupCancelled = new AtomicBoolean(true);
    private boolean sdkDownloadStarted;

    ConversationController(Activity activity, Runnable phraseEnded) {
        this.activity = activity;
        this.phraseEnded = phraseEnded;
        models = new OfflineModelStore(new File(activity.getFilesDir(), "speech-models"));
        englishToItalian = translator("en", "it");
        italianToEnglish = translator("it", "en");
        speech = new TextToSpeech(activity.getApplicationContext(), status -> main.post(() -> {
            if (closed) return;
            speechInitialized = status == TextToSpeech.SUCCESS;
            if (speechInitialized) {
                speech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) { }
                    @Override public void onDone(String id) { main.post(() -> speechFinished(id, false)); }
                    @Override public void onError(String id) { main.post(() -> speechFinished(id, true)); }
                });
            }
            refreshVoices();
            emit();
        }));
        refreshReadiness();
    }

    private static Translator translator(String from, String to) {
        return Translation.getClient(new TranslatorOptions.Builder()
                .setSourceLanguage(from).setTargetLanguage(to).build());
    }

    void setEvents(Consumer<String> events) { this.events = events; }

    void newDocument() {
        events = ignored -> { };
        sessionEpoch++;
        cancel(true);
        lastRequest = 0;
        requestId = 0;
        eventSequence = 0;
    }

    /** All messages arrive on the UI thread after host origin/frame validation. */
    void command(String json) {
        if (closed || !foreground || json == null || json.length() > TurnGuard.MAX_MESSAGE) return;
        try {
            JSONObject request = new JSONObject(json);
            if (request.optInt("v", -1) != 1 || !request.has("id")
                    || !(request.get("id") instanceof Number)) return;
            long id = request.getLong("id");
            if (id <= 0 || id > Integer.MAX_VALUE || id <= lastRequest
                    || request.getDouble("id") != id) return;
            String op = request.getString("op");
            Set<String> allowed = switch (op) {
                case "start" -> keys("v", "id", "op", "source", "autoTranslate");
                case "translate" -> keys("v", "id", "op", "text", "source");
                case "present" -> keys("v", "id", "op", "en", "it");
                case "speak" -> keys("v", "id", "op", "slow");
                case "speakText" -> keys("v", "id", "op", "text", "language", "slow");
                case "status", "setup", "cancelSetup", "stop", "cancel", "clear",
                        "replay", "stopPlayback", "installVoice" -> keys("v", "id", "op");
                default -> throw new IllegalArgumentException("Unknown command");
            };
            Iterator<String> keys = request.keys();
            while (keys.hasNext()) if (!allowed.contains(keys.next())) return;
            if ("start".equals(op) && request.has("autoTranslate")
                    && !(request.get("autoTranslate") instanceof Boolean)) return;
            if (("speak".equals(op) || "speakText".equals(op)) && request.has("slow")
                    && !(request.get("slow") instanceof Boolean)) return;
            String validatedText = null;
            String validatedLanguage = null;
            String preparedItalian = null;
            if ("translate".equals(op) || "speakText".equals(op)) {
                if (!(request.get("text") instanceof String)) return;
                validatedText = TurnGuard.text(request.getString("text"));
            }
            if ("present".equals(op)) {
                if (!(request.get("en") instanceof String) || !(request.get("it") instanceof String)) return;
                validatedText = TurnGuard.text(request.getString("en"));
                preparedItalian = TurnGuard.text(request.getString("it"));
            }
            if ("translate".equals(op) || "start".equals(op)) validatedLanguage = language(request);
            if ("speakText".equals(op)) {
                validatedLanguage = request.getString("language");
                if (!TurnGuard.language(validatedLanguage)) return;
            }
            lastRequest = id;
            if (TurnGuard.tracksRequest(op, state, busy(), audio.hasClip(),
                    !translation.trim().isEmpty())) requestId = id;
            switch (op) {
                case "status" -> emit();
                case "setup" -> prepareModels();
                case "cancelSetup" -> cancelSetup();
                case "start" -> start(validatedLanguage, request.optBoolean("autoTranslate", false));
                case "stop" -> {
                    if ("recording".equals(state)) { state = "finalizing"; audio.finish(); emit(); }
                }
                case "cancel" -> cancel(false);
                case "clear" -> { sessionEpoch++; cancel(true); }
                case "translate" -> {
                    translate(validatedLanguage, validatedText);
                }
                case "present" -> present(validatedText, preparedItalian);
                case "replay" -> replay();
                case "speak" -> {
                    speakTranslation(request.optBoolean("slow", false));
                }
                case "speakText" -> speakText(validatedText, validatedLanguage,
                        request.optBoolean("slow", false));
                case "stopPlayback" -> stopPlayback();
                case "installVoice" -> installVoice();
                default -> { }
            }
        } catch (JSONException | IllegalArgumentException ignored) {
            // Malformed messages never reach microphone, filesystem or SDK operations.
        }
    }

    private String language(JSONObject request) throws JSONException {
        String value = request.getString("source");
        if (!TurnGuard.language(value)) throw new IllegalArgumentException("Unsupported language");
        return value;
    }

    private boolean busy() {
        return Arrays.asList("preparing", "permission", "recording", "finalizing", "translating").contains(state);
    }

    private static Set<String> keys(String... values) { return new HashSet<>(Arrays.asList(values)); }

    private void start(String language, boolean translateAfterRecording) {
        if (busy()) return;
        cancel(true);
        source = language;
        autoTranslation.arm(translateAfterRecording);
        if (!("en".equals(source) ? modelEnglish : modelItalian)) {
            fail("Prepare the " + languageName(source) + " speech model first."); return;
        }
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            state = "permission";
            emit();
            activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MICROPHONE_REQUEST);
            return;
        }
        long turn = guard.next();
        state = "preparing";
        progress = "Loading " + languageName(source) + " speech recognition…";
        emit();
        main.postDelayed(() -> {
            if (guard.current(turn) && "preparing".equals(state)) {
                audio.cancel(); fail("Speech recognition took too long to load. Try again.");
            }
        }, 30000);
        audio.start(models.modelDirectory(source), new AudioTurn.Listener() {
            @Override public void listening() { main.post(() -> {
                if (!guard.current(turn) || !foreground) return;
                state = "recording"; progress = ""; recordingMs = 0; emit();
            }); }
            @Override public void elapsed(long ms) { main.post(() -> {
                if (guard.current(turn)) { recordingMs = ms; emit(); }
            }); }
            @Override public void result(String recognized) { main.post(() -> {
                if (!guard.current(turn) || !foreground) return;
                progress = "";
                if (recognized.trim().isEmpty()) { fail("No speech detected. Try again or type your sentence."); return; }
                text = recognized.substring(0, Math.min(recognized.length(), TurnGuard.MAX_TEXT));
                state = "review";
                emit();
                if (autoTranslation.take()) translate(source, text);
            }); }
            @Override public void failed(String message) { main.post(() -> {
                if (!guard.current(turn)) return;
                fail(message);
            }); }
        });
    }

    void permissionResult(boolean granted) {
        if (closed) return;
        // Granting permission never restarts a recorder after a lifecycle interruption.
        state = restingState();
        error = granted ? "Microphone enabled. Tap Record to begin."
                : "Microphone permission was not granted. You can still type a sentence. Enable microphone access in Android app settings to record.";
        emit();
    }

    private void translate(String language, String entered) {
        if (busy()) return;
        if (!translationReady) { fail("Prepare offline translation on Wi-Fi first."); return; }
        if (!TRANSLATION_GATE.tryAcquire()) {
            source = language; text = entered; translation = ""; machineTranslated = true;
            fail("A previous translation is still finishing. Your text is kept; try again shortly.");
            return;
        }
        stopAudio(!language.equals(source));
        long turn = guard.next();
        source = language; text = entered; translation = ""; error = ""; progress = "";
        machineTranslated = true;
        state = "translating"; emit();
        Translator translator = "en".equals(source) ? englishToItalian : italianToEnglish;
        Task<String> task;
        try { task = translator.translate(text); }
        catch (RuntimeException | LinkageError failure) {
            TRANSLATION_GATE.release();
            fail("Could not start offline translation. Your text is kept; try again.");
            return;
        }
        task.addOnCompleteListener(completed -> TRANSLATION_GATE.release());
        task.addOnSuccessListener(result -> {
            if (!guard.current(turn) || !foreground) return;
            if (result == null || result.trim().isEmpty()) { fail("No translation was returned. Edit your text and retry."); return; }
            if (result.length() > TurnGuard.MAX_TEXT) {
                fail("Translation is too long. Shorten your sentence and retry."); return;
            }
            translation = result;
            state = "result"; emit();
        }).addOnFailureListener(failure -> {
            if (guard.current(turn)) {
                fail("Could not translate on this phone. Your text is kept; try again. If it keeps failing, prepare the translation models again on Wi-Fi.");
            }
        });
        main.postDelayed(() -> {
            if (guard.current(turn) && "translating".equals(state))
                fail("Translation took too long. Your text is kept; try again.");
        }, 20000);
    }

    private void present(String english, String italian) {
        cancel(true);
        source = "en"; text = english; translation = italian;
        machineTranslated = false;
        state = "result"; emit();
    }

    private void speakText(String value, String language, boolean slow) {
        if (busy()) return;
        speak(value, language, slow, false);
    }

    private void replay() {
        if (busy() || !audio.hasClip()) return;
        stopAudio(false);
        long turn = guard.next();
        state = "playing"; error = ""; emit();
        audio.replay(() -> main.post(() -> {
            if (guard.current(turn)) { state = restingState(); emit(); }
        }), () -> main.post(() -> {
            if (guard.current(turn)) fail("Could not replay this recording. Record it again.");
        }));
    }

    private void speakTranslation(boolean slow) {
        if (busy() || translation.trim().isEmpty()) return;
        speak(translation, TurnGuard.target(source), slow, false);
    }

    boolean canSpeakItalian() { return italianVoiceReady; }

    void speakPhrase(String phrase, boolean slow) {
        if (closed || !foreground || phrase == null || phrase.trim().isEmpty()
                || phrase.length() > TextToSpeech.getMaxSpeechInputLength()) return;
        // A phrasebook tap interrupts a conversation safely instead of competing for audio.
        cancel(false);
        speak(phrase, "it", slow, true);
    }

    private void speak(String value, String language, boolean slow, boolean phrase) {
        stopAudio(false);
        guard.next();
        refreshVoices();
        Voice voice = voices.get(language);
        if (voice == null || speech == null || value.length() > TextToSpeech.getMaxSpeechInputLength()
                || speech.setVoice(voice) != TextToSpeech.SUCCESS) {
            if (phrase) phraseEnded.run();
            fail("Install an offline " + languageName(language) + " voice in Android settings first.");
            return;
        }
        utteranceId = "offline-" + (++utteranceCounter);
        utteranceIsPhrase = phrase;
        speech.setSpeechRate(slow ? 0.65f : 0.9f);
        state = "playing"; error = ""; emit();
        if (speech.speak(value, TextToSpeech.QUEUE_FLUSH, null, utteranceId) == TextToSpeech.ERROR)
            speechFinished(utteranceId, true);
    }

    private void speechFinished(String id, boolean failed) {
        if (closed || id == null || !id.equals(utteranceId)) return;
        utteranceId = null;
        if (utteranceIsPhrase) phraseEnded.run();
        utteranceIsPhrase = false;
        if (failed) fail("The offline voice could not play this text.");
        else { state = restingState(); emit(); }
    }

    void stopPlayback() {
        if (closed || !"playing".equals(state)) return;
        stopAudio(false);
        if ("playing".equals(state)) { guard.next(); state = restingState(); emit(); }
    }

    private void stopAudio(boolean discardClip) {
        if (discardClip) audio.cancel(); else audio.stopPlayback();
        if (speech != null) speech.stop();
        if (utteranceIsPhrase) phraseEnded.run();
        utteranceId = null; utteranceIsPhrase = false;
    }

    private void cancel(boolean clearText) {
        guard.next();
        setupCancelled.set(true);
        autoTranslation.clear();
        stopAudio(true);
        if (clearText) { text = ""; translation = ""; machineTranslated = false; }
        progress = ""; error = ""; recordingMs = 0;
        state = restingState();
        emit();
    }

    private void cancelSetup() {
        boolean mayFinish = sdkDownloadStarted;
        cancel(false);
        if (mayFinish) {
            progress = "Setup stopped. A translation download already started may finish in the background.";
            emit();
        }
        refreshReadiness();
    }

    private void fail(String message) {
        guard.next();
        stopAudio(true);
        progress = ""; error = message; state = "error"; emit();
    }

    private String restingState() {
        if (!translation.isEmpty()) return "result";
        if (!text.isEmpty()) return "review";
        return translationReady || modelEnglish || modelItalian ? "ready" : "setup";
    }

    private void refreshVoices() {
        voices.clear();
        if (speechInitialized && speech != null) {
            Set<Voice> installed = speech.getVoices();
            if (installed != null) for (Voice voice : installed) {
                String language = voice.getLocale().getLanguage();
                if (TurnGuard.language(language) && !voice.isNetworkConnectionRequired()
                        && (voice.getFeatures() == null
                        || !voice.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)))
                    voices.putIfAbsent(language, voice);
            }
        }
        italianVoiceReady = voices.containsKey("it");
    }

    private void refreshReadiness() {
        if (closed) return;
        long epoch = ++readinessEpoch;
        modelsWorker.execute(() -> {
            boolean en = models.isReady("en");
            boolean it = models.isReady("it");
            main.post(() -> {
                if (closed || epoch != readinessEpoch) return;
                modelEnglish = en; modelItalian = it;
                if (!busy() && !"playing".equals(state)) state = restingState();
                emit();
            });
        });
        RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel.class)
                .addOnSuccessListener(installed -> {
                    if (closed || epoch != readinessEpoch) return;
                    boolean italian = installed.stream().anyMatch(model ->
                            TranslateLanguage.ITALIAN.equals(model.getLanguage()));
                    // The model catalog is enough for readiness; concurrent probe translations
                    // can initialize both native translators at the same time on cold launch.
                    translationReady = italian;
                    if (!busy() && !"playing".equals(state)) state = restingState();
                    emit();
                }).addOnFailureListener(failure -> {
                    if (!closed && epoch == readinessEpoch) { translationReady = false; emit(); }
                });
    }

    private void prepareModels() {
        if (busy()) return;
        ConnectivityManager connectivity = activity.getSystemService(ConnectivityManager.class);
        Network wifiNetwork = connectivity == null ? null : connectivity.getActiveNetwork();
        if (!isWifiNetwork(connectivity, wifiNetwork)) {
            fail("Connect to Wi-Fi, then tap Prepare offline models."); return;
        }
        // Every Vosk request, including redirects, stays on this Wi-Fi Network.
        OfflineModelStore wifiModels = new OfflineModelStore(
                new File(activity.getFilesDir(), "speech-models"), url -> {
                    if (!isWifiNetwork(connectivity, wifiNetwork)) throw new IOException("Wi-Fi connection was lost");
                    return wifiNetwork.openConnection(url);
                });
        cancel(false);
        readinessEpoch++;
        long turn = guard.next();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        setupCancelled = cancelled;
        sdkDownloadStarted = false;
        Future<?> unloaded = audio.unload();
        state = "preparing"; progress = "Checking offline speech models…"; emit();
        modelsWorker.execute(() -> {
            try {
                unloaded.get(30, TimeUnit.SECONDS);
                for (String language : new String[]{"en", "it"}) {
                    if (cancelled.get()) return;
                    if (!models.isReady(language)) {
                        AtomicLong lastProgress = new AtomicLong();
                        wifiModels.install(language, (received, total) -> {
                            long now = System.nanoTime();
                            if (received != total && now - lastProgress.get() < TimeUnit.MILLISECONDS.toNanos(250)) return;
                            lastProgress.set(now);
                            String detail = total > 0 ? (received * 100 / total) + "%"
                                    : (received / (1024 * 1024)) + " MB";
                            main.post(() -> {
                                if (guard.current(turn)) {
                                    progress = "Downloading " + languageName(language) + " speech: " + detail;
                                    emit();
                                }
                            });
                        }, () -> cancelled.get() || !isWifiNetwork(connectivity, wifiNetwork), folder -> {
                            try (Model staged = new Model(folder.getAbsolutePath())) {
                                if (cancelled.get()) throw new IOException("Setup cancelled");
                            } catch (LinkageError failure) { throw new IOException("Speech library unavailable", failure); }
                        });
                    }
                    main.post(() -> {
                        if (guard.current(turn)) {
                            if ("en".equals(language)) modelEnglish = true; else modelItalian = true;
                            emit();
                        }
                    });
                }
                if (cancelled.get()) return;
                main.post(() -> {
                    if (guard.current(turn)) { sdkDownloadStarted = true; progress = "Preparing offline translation…"; emit(); }
                });
                DownloadConditions wifi = new DownloadConditions.Builder().requireWifi().build();
                awaitDownload(englishToItalian.downloadModelIfNeeded(wifi), cancelled);
                awaitDownload(italianToEnglish.downloadModelIfNeeded(wifi), cancelled);
                if (cancelled.get()) return;
                main.post(() -> {
                    if (!guard.current(turn)) return;
                    sdkDownloadStarted = false; translationReady = true; progress = "Offline models are ready.";
                    state = restingState(); refreshVoices(); emit();
                });
            } catch (Exception | LinkageError failure) {
                String storageMessage = failure instanceof OfflineModelStore.InsufficientStorageException
                        ? failure.getMessage() : null;
                main.post(() -> {
                    if (!guard.current(turn)) return;
                    sdkDownloadStarted = false;
                    if (storageMessage != null) fail(storageMessage);
                    else if (!isWifiNetwork(connectivity, wifiNetwork))
                        fail("Wi-Fi was lost during setup. Reconnect and retry. Existing verified models are preserved.");
                    else fail("Setup could not finish. Check Wi-Fi and free storage, then retry. Existing verified models are preserved.");
                    refreshReadiness();
                });
            }
        });
    }

    private static boolean isWifiNetwork(ConnectivityManager connectivity, Network network) {
        NetworkCapabilities capabilities = connectivity == null || network == null ? null
                : connectivity.getNetworkCapabilities(network);
        return capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }

    private static void awaitDownload(Task<Void> task, AtomicBoolean cancelled) throws Exception {
        long end = System.nanoTime() + TimeUnit.MINUTES.toNanos(10);
        while (!task.isComplete()) {
            if (cancelled.get()) throw new IOException("Setup cancelled");
            if (System.nanoTime() > end) throw new IOException("Setup timed out");
            Thread.sleep(100);
        }
        if (!task.isSuccessful()) throw new IOException("Translation model unavailable");
    }

    private void installVoice() {
        if (busy()) return;
        cancel(false);
        try { activity.startActivity(new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)); }
        catch (ActivityNotFoundException missing) {
            activity.startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    void resume() {
        if (closed) return;
        foreground = true; refreshVoices(); refreshReadiness(); emit();
    }

    void pause() {
        foreground = false;
        sessionEpoch++;
        cancel(true);
        audio.unload();
    }

    private void emit() {
        if (closed) return;
        try {
            JSONObject snapshot = new JSONObject().put("v", 1).put("event", "state")
                    .put("seq", ++eventSequence).put("ack", lastRequest)
                    .put("requestId", requestId).put("sessionEpoch", sessionEpoch)
                    .put("machineTranslated", machineTranslated)
                    .put("state", state).put("source", source).put("text", text)
                    .put("translation", translation).put("canReplay", audio.hasClip())
                    .put("models", new JSONObject().put("en", modelEnglish).put("it", modelItalian))
                    .put("translationReady", translationReady)
                    .put("voices", new JSONObject().put("en", voices.containsKey("en")).put("it", voices.containsKey("it")))
                    .put("progress", progress).put("error", error).put("recordingMs", recordingMs);
            events.accept(snapshot.toString());
        } catch (JSONException impossible) { throw new IllegalStateException("Invalid native state", impossible); }
    }

    private static String languageName(String language) { return "en".equals(language) ? "English" : "Italian"; }

    @Override public void close() {
        if (closed) return;
        pause(); closed = true; guard.close(); readinessEpoch++;
        audio.close(); modelsWorker.shutdown();
        englishToItalian.close(); italianToEnglish.close();
        if (speech != null) { speech.shutdown(); speech = null; }
        italianVoiceReady = false; voices.clear(); events = ignored -> { };
        main.removeCallbacksAndMessages(null);
    }
}
