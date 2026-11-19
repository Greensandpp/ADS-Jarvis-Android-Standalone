package com.adsjarvis.standalone;

import android.content.SharedPreferences;
import java.util.Locale;

/** Validation + persistence for preferences that voice/chat/AI may change. */
public final class SettingsModel {
    private SettingsModel() {}

    public static String validate(String name, String v) {
        if (v == null) return "Value required.";
        switch (name) {
            case "visual_style": return v.toUpperCase(Locale.ROOT).matches("ARC|AIRA|APEX|MIRA|OFFICE") ? null : "Visual must be ARC, AIRA, APEX, MIRA or OFFICE.";
            case "reply_language": return v.toLowerCase(Locale.ROOT).matches("auto|tamil|english|tanglish") ? null : "Language must be auto, tamil, english or tanglish.";
            case "voice_gender": return v.toLowerCase(Locale.ROOT).matches("female|male|any") ? null : "Voice gender must be female, male or any.";
            case "tts_pitch": case "tts_speed":
                try { float f = Float.parseFloat(v); return f >= .5f && f <= 2f ? null : "Must be between 0.5 and 2.0."; }
                catch (NumberFormatException e) { return "Must be a number."; }
            case "response_length": return v.matches("short|detailed") ? null : "short or detailed.";
            case "input_language": return v.matches("auto|ta-IN|en-IN") ? null : "auto, ta-IN or en-IN.";
            case "assistant_name": return v.trim().length() >= 1 && v.trim().length() <= 24 ? null : "1–24 characters.";
            case "wake_word": return v.trim().matches("[\\p{L} ]{2,20}") ? null : "2–20 letters.";
            default: return "Unknown setting " + name;
        }
    }

    public static void apply(SharedPreferences p, String name, String v) {
        SharedPreferences.Editor e = p.edit();
        switch (name) {
            case "visual_style": e.putString(Store.STYLE, v.toUpperCase(Locale.ROOT)); break;
            case "reply_language": e.putString(Store.REPLY_LANG, v.toLowerCase(Locale.ROOT)); break;
            case "voice_gender":
                e.putString(Store.VOICE_GENDER, v.toLowerCase(Locale.ROOT));
                e.putBoolean(Store.FEMALE, v.equalsIgnoreCase("female"));
                e.putString(Store.VOICE, "");
                break;
            case "tts_pitch": e.putFloat(Store.PITCH, Float.parseFloat(v)); break;
            case "tts_speed": e.putFloat(Store.SPEED, Float.parseFloat(v)); break;
            case "response_length": e.putString(Store.RESP_LEN, v); break;
            case "input_language": e.putString(Store.INPUT_LANG, "auto".equals(v) ? "auto" : v); break;
            case "assistant_name": e.putString(Store.NAME, v.trim()); break;
            case "wake_word": e.putString(Store.WAKE_WORD, v.trim().toLowerCase(Locale.ROOT)); break;
            default: break;
        }
        e.apply();
    }
}
