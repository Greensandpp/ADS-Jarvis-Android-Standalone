package com.adsjarvis.standalone.core;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic on-device intents that must work instantly and offline
 * (identity, opening JARVIS settings, switching visuals/voice/language,
 * remembering the language preference). Everything else goes to the AI brain
 * or the device command engine.
 */
public final class LocalIntents {
    public enum Kind { NAME, OPEN_JARVIS_SETTINGS, OPEN_PHONE_SETTINGS, SET_VISUAL, SET_VOICE_GENDER, SET_REPLY_LANG,
        REMEMBER_PREF_LANG, CAPABILITIES, STOP }

    public static final class Match {
        public final Kind kind; public final String value;
        Match(Kind k, String v) { kind = k; value = v; }
    }

    private static final Pattern VISUAL = Pattern.compile(
        "(?:change|switch|set)\\s+(?:the\\s+)?(?:visual|style|look|theme|model)?\\s*(?:to|as)\\s+(arc|jarvis|aira|apex|mira|agent office|office)(?:\\s+(?:mode|style|visual))?"
        + "|^(arc|aira|apex|mira|agent office)\\s+(?:mode|visual|style)(?:\\s+(?:podu|maathu|change pannu|pannu))?$"
        + "|^(?:visual|style)\\s+(arc|aira|apex|mira|office)\\s+(?:ku\\s+)?(?:maathu|maattru|change pannu)$");

    private LocalIntents() {}

    public static Match match(String raw) {
        if (raw == null) return null;
        String q = raw.trim().toLowerCase(Locale.ROOT).replaceAll("^(hey |ok |hi )?jarvis[,!.]?\\s*", "").replaceAll("[?!.]+$", "").trim();
        String t = raw.trim();
        if (q.matches("(what('?s| is) your name|who are you|your name|un(nu|oda)? peru enna|unga peru enna|peru enna)") || t.contains("உன் பெயர் என்ன")
            || t.contains("உங்கள் பெயர் என்ன"))
            return new Match(Kind.NAME, null);
        if (q.matches("(stop|cancel|stop talking|be quiet|niruthu|pothum)")) return new Match(Kind.STOP, null);
        if (q.matches("(open|show|go to) (jarvis |your |the app )?settings( page| screen)?|settings (open pannu|kaattu)|settings")
            || t.equals("செட்டிங்ஸ் திற") || t.contains("அமைப்புகளைத் திற"))
            return new Match(Kind.OPEN_JARVIS_SETTINGS, null);
        if (q.matches("(open )?(phone|android|system|device) settings( open pannu)?")) return new Match(Kind.OPEN_PHONE_SETTINGS, null);
        Matcher m = VISUAL.matcher(q);
        if (m.find()) {
            String v = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
            v = v.replace("agent office", "office").replace("jarvis", "arc");
            return new Match(Kind.SET_VISUAL, v.toUpperCase(Locale.ROOT));
        }
        if (q.matches("(change|switch|set) (the )?voice (to )?(female|woman|lady|girl)( voice)?|female voice( podu| venum)?|pen kural"))
            return new Match(Kind.SET_VOICE_GENDER, "female");
        if (q.matches("(change|switch|set) (the )?voice (to )?(male|man|boy)( voice)?|male voice( podu| venum)?|aan kural"))
            return new Match(Kind.SET_VOICE_GENDER, "male");
        if (t.contains("தமிழ்ல பேசு") || t.contains("தமிழில் பேசு") || q.matches("(speak|talk|reply|answer) in tamil|tamil(-| )?la (pesu|pesunga|answer pannu|reply pannu)"))
            return new Match(Kind.SET_REPLY_LANG, "tamil");
        if (q.matches("(speak|talk|reply|answer) in tanglish|tanglish(-| )?la (pesu|pesunga|answer pannu|reply pannu|sollu)"))
            return new Match(Kind.SET_REPLY_LANG, "tanglish");
        if (q.matches("(speak|talk|reply|answer) in english|english(-| )?la (pesu|pesunga|answer pannu|reply pannu)"))
            return new Match(Kind.SET_REPLY_LANG, "english");
        if (q.matches("remember my (preferred |favourite |favorite )?language|en (language|mozhi) (ah |a )?(nyabagam vechuko|remember pannu)"))
            return new Match(Kind.REMEMBER_PREF_LANG, null);
        if (q.matches("what can you do|(list )?(your )?capabilities|what are your (skills|tools)|help"))
            return new Match(Kind.CAPABILITIES, null);
        return null;
    }
}
