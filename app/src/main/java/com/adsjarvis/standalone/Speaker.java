package com.adsjarvis.standalone;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Text-to-speech using the phone's installed engine. Android does not expose
 * voice gender, so "female preference" means: use the voice the owner picked,
 * otherwise a voice whose name says female, otherwise the engine default with
 * a slightly higher pitch. Which voices exist depends on the phone's engine.
 */
final class Speaker {
    interface Listener { void onSpeaking(boolean speaking); }

    private TextToSpeech tts; // not final: the init callback may run before the constructor returns
    private boolean ready;
    private String voiceName = "";
    private boolean preferFemale = true;
    private String gender = "female";
    private float pitch = 1f, rate = 1f;
    private Listener listener;

    Speaker(Context ctx, Runnable onReady) {
        tts = new TextToSpeech(ctx, status -> {
            ready = status == TextToSpeech.SUCCESS;
            if (ready && tts != null) {
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) { if (listener != null) listener.onSpeaking(true); }
                    @Override public void onDone(String id) { if (listener != null) listener.onSpeaking(false); }
                    @Override public void onError(String id) { if (listener != null) listener.onSpeaking(false); }
                });
            }
            if (onReady != null) onReady.run();
        });
    }

    boolean isReady() { return ready; }
    void setListener(Listener l) { listener = l; }
    void setVoiceName(String name) { voiceName = name == null ? "" : name; }
    void setPreferFemale(boolean f) { preferFemale = f; gender = f ? "female" : "any"; }
    void setGender(String g) { gender = g == null ? "any" : g; preferFemale = "female".equals(gender); }
    void setPitch(float p) { pitch = p; }
    void setRate(float r) { rate = r; }
    String engineName() { return ready ? String.valueOf(tts.getDefaultEngine()) : "none"; }
    boolean isSpeaking() { return ready && tts.isSpeaking(); }
    /** Installed voice whose name declares the requested gender, if any. */
    String genderVoice(String langTag) { Voice v = pickVoice(Locale.forLanguageTag(langTag)); return v == null ? "" : v.getName(); }

    /** Installed voices for a language tag, e.g. "ta-IN" / "en-IN". */
    List<String> voicesFor(String langTag) {
        List<String> out = new ArrayList<>();
        if (!ready) return out;
        Set<Voice> vs = tts.getVoices();
        if (vs == null) return out;
        String lang = Locale.forLanguageTag(langTag).getLanguage();
        for (Voice v : vs) if (v.getLocale().getLanguage().equals(lang)) out.add(v.getName());
        java.util.Collections.sort(out);
        return out;
    }

    /** Returns false when TTS is unavailable so the caller can show text only. */
    boolean speak(String text, String langTag) {
        if (!ready || text == null || text.isEmpty()) return false;
        Locale loc = Locale.forLanguageTag(langTag);
        int r = tts.setLanguage(loc);
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            // Fall back to Indian English so the reply is still heard.
            tts.setLanguage(new Locale("en", "IN"));
        }
        Voice chosen = pickVoice(loc);
        if (chosen != null) tts.setVoice(chosen);
        float genderPitch = chosen != null ? 1f : ("female".equals(gender) ? 1.12f : "male".equals(gender) ? .9f : 1f);
        tts.setPitch(pitch * genderPitch);
        tts.setSpeechRate(rate);
        return tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis-" + System.nanoTime()) == TextToSpeech.SUCCESS;
    }

    private Voice pickVoice(Locale loc) {
        Set<Voice> vs = tts.getVoices();
        if (vs == null) return null;
        Voice femaleNamed = null;
        for (Voice v : vs) {
            if (!voiceName.isEmpty() && v.getName().equals(voiceName)) return v;
            String n = v.getName().toLowerCase(Locale.ROOT);
            boolean wantF = "female".equals(gender), wantM = "male".equals(gender);
            boolean match = (wantF && n.contains("female")) || (wantM && n.contains("male") && !n.contains("female"));
            if (match && femaleNamed == null && v.getLocale().getLanguage().equals(loc.getLanguage()) && !v.isNetworkConnectionRequired()) femaleNamed = v;
        }
        return femaleNamed;
    }

    void stop() { if (ready) tts.stop(); }
    void shutdown() { tts.stop(); tts.shutdown(); }
}
