package com.adsjarvis.standalone;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Speech input. Two modes:
 *  - push-to-talk: one utterance, then stop;
 *  - in-app hands-free: while JARVIS is open on screen, keeps listening and
 *    acts only on utterances that start with the wake word. It never runs in
 *    the background or on the lock screen (no foreground service is used).
 */
public final class VoiceInput {
    public interface Listener {
        void onListening(boolean on, boolean handsFree);
        void onLevel(float level);
        void onPartial(String text);
        void onCommand(String text);
        void onError(String message, int code);
    }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private SpeechRecognizer rec;
    private Listener listener;
    private boolean handsFree, active, paused;
    private String wakeWord = "jarvis";
    private int sensitivity = 1; // max edit distance accepted for the wake word
    private String lang = "en-IN";
    public float peakLevel;

    public VoiceInput(Context c) { ctx = c; }
    public void setListener(Listener l) { listener = l; }
    public void setLanguage(String tag) { lang = tag; }
    public void setWake(String word, int sens) { wakeWord = word == null || word.trim().isEmpty() ? "jarvis" : word.trim().toLowerCase(Locale.ROOT); sensitivity = sens; }
    public boolean isActive() { return active; }
    public boolean isHandsFree() { return handsFree; }
    public static boolean available(Context c) { return SpeechRecognizer.isRecognitionAvailable(c); }

    public void startPushToTalk() { handsFree = false; begin(); }
    public void startHandsFree() { handsFree = true; paused = false; begin(); }
    public void pauseHandsFree(boolean p) { paused = p; if (p) stopRecognizer(); else if (handsFree) begin(); }

    public void stop() {
        handsFree = false;
        stopRecognizer();
    }

    private void stopRecognizer() {
        active = false;
        if (rec != null) { try { rec.cancel(); rec.destroy(); } catch (Exception ignored) {} rec = null; }
        if (listener != null) listener.onListening(false, handsFree);
    }

    public void release() { stop(); }

    private void begin() {
        if (!available(ctx)) { if (listener != null) listener.onError("No speech recognition service on this phone", -1); return; }
        stopRecognizer();
        rec = SpeechRecognizer.createSpeechRecognizer(ctx);
        rec.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle b) { active = true; if (listener != null) listener.onListening(true, handsFree); }
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float db) {
                float l = Math.max(0, Math.min(1, (db + 2f) / 12f));
                peakLevel = Math.max(peakLevel, l);
                if (listener != null) listener.onLevel(l);
            }
            @Override public void onBufferReceived(byte[] b) {}
            @Override public void onEndOfSpeech() {}
            @Override public void onError(int e) {
                active = false;
                if (handsFree && !paused && (e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) { restartSoon(); return; }
                if (listener != null) { listener.onListening(false, handsFree); listener.onError(describe(e), e); }
                if (handsFree && !paused && e != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) restartSoon();
            }
            @Override public void onResults(Bundle b) {
                active = false;
                ArrayList<String> v = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                String best = v == null || v.isEmpty() ? "" : v.get(0);
                if (!handsFree) {
                    if (listener != null) listener.onListening(false, false);
                    if (!best.isEmpty() && listener != null) listener.onCommand(best);
                    else if (listener != null) listener.onError("Didn't catch that — try again", SpeechRecognizer.ERROR_NO_MATCH);
                    return;
                }
                String cmd = afterWakeWord(v);
                if (cmd != null && listener != null) listener.onCommand(cmd);
                if (!paused) restartSoon();
            }
            @Override public void onPartialResults(Bundle b) {
                ArrayList<String> v = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (v != null && !v.isEmpty() && listener != null) listener.onPartial(v.get(0));
            }
            @Override public void onEvent(int t, Bundle b) {}
        });
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        rec.startListening(i);
    }

    private void restartSoon() { main.postDelayed(() -> { if (handsFree && !paused) begin(); }, 400); }

    /** Returns the command after the wake word, "" if only the wake word, or null if not addressed. */
    String afterWakeWord(ArrayList<String> candidates) {
        if (candidates == null) return null;
        for (String c : candidates) {
            String[] words = c.trim().toLowerCase(Locale.ROOT).split("\\s+");
            String[] wake = wakeWord.split("\\s+");
            if (words.length < wake.length) continue;
            for (int start = 0; start <= Math.min(2, words.length - wake.length); start++) {
                StringBuilder head = new StringBuilder();
                for (int k = 0; k < wake.length; k++) head.append(k == 0 ? "" : " ").append(words[start + k].replaceAll("[^\\p{L}]", ""));
                if (distance(head.toString(), wakeWord) <= sensitivity) {
                    String[] orig = c.trim().split("\\s+");
                    StringBuilder rest = new StringBuilder();
                    for (int k = start + wake.length; k < orig.length; k++) rest.append(rest.length() == 0 ? "" : " ").append(orig[k]);
                    return rest.toString().replaceAll("^[,.!?]+\\s*", "");
                }
            }
        }
        return null;
    }

    static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++)
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }

    static String describe(int e) {
        switch (e) {
            case SpeechRecognizer.ERROR_NO_MATCH: return "Didn't catch that — try again";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "No speech heard";
            case SpeechRecognizer.ERROR_AUDIO: return "Microphone audio error";
            case SpeechRecognizer.ERROR_NETWORK: case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "Speech service needs network or an offline language pack";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "Microphone permission required";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "Speech recognizer busy";
            case SpeechRecognizer.ERROR_SERVER: case SpeechRecognizer.ERROR_SERVER_DISCONNECTED: return "Speech service error";
            case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED: case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE: return "Speech language not installed";
            case SpeechRecognizer.ERROR_TOO_MANY_REQUESTS: return "Speech service rate-limited";
            default: return "Speech error " + e;
        }
    }
}
