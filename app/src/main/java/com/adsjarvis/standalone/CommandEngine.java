package com.adsjarvis.standalone;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure Java command parser: no OS, network or device access, so it is fully
 * unit-testable on the JVM. It only decides WHAT was asked; MainActivity /
 * DeviceActions decide whether and how Android can do it.
 *
 * Understands English, Tamil script and Tanglish (Tamil in Latin letters),
 * e.g. "open youtube", "youtube open pannu", "youtube thira", "யூடியூப் திற".
 * Anything not recognised is handed to the configured AI provider.
 */
public final class CommandEngine {
    public enum Action {
        NONE, SAVE_MEMORY, CLEAR_MEMORY, OPEN_SETTINGS,
        OPEN_APP, OPEN_WIFI, OPEN_BLUETOOTH, SET_ALARM, SET_TIMER, DIAL, WEB_SEARCH,
        TORCH_ON, TORCH_OFF, VOLUME_UP, VOLUME_DOWN, BATTERY, TIME, DATE
    }

    public enum Lang { ENGLISH, TAMIL, TANGLISH }

    public static final class Result {
        public final Action action;
        public final String response;
        public final String memoryValue;
        /** Target for OPEN_APP / DIAL / WEB_SEARCH. */
        public final String argument;
        /** Hours/minutes for SET_ALARM, seconds for SET_TIMER (in hour). */
        public final int hour, minute;
        /** True when no local command matched and the AI provider should answer. */
        public final boolean askAi;
        public final Lang lang;

        Result(Action action, String response, String memoryValue) {
            this(action, response, memoryValue, "", 0, 0, false, Lang.ENGLISH);
        }

        Result(Action action, String response, String memoryValue, String argument,
               int hour, int minute, boolean askAi, Lang lang) {
            this.action = action; this.response = response; this.memoryValue = memoryValue;
            this.argument = argument; this.hour = hour; this.minute = minute;
            this.askAi = askAi; this.lang = lang;
        }
    }

    private CommandEngine() {}

    private static final Pattern TAMIL_SCRIPT = Pattern.compile("[\\u0B80-\\u0BFF]");
    private static final String[] TANGLISH_MARKERS = {
        " pannu", " panna", " pannunga", " thira", " thirai", " podu", " vai", " vaiyi",
        " sollu", " enna", " epdi", " eppadi", " iruku", " irukku", " da", " machan", " konjam",
        " venum", " mani", "vanakkam", " aakku", " niruthu", " koodu", " kammi", " ethu",
    };

    public static Lang detectLanguage(String raw) {
        if (raw == null) return Lang.ENGLISH;
        if (TAMIL_SCRIPT.matcher(raw).find()) return Lang.TAMIL;
        String q = " " + raw.toLowerCase(Locale.ROOT) + " ";
        for (String m : TANGLISH_MARKERS) if (q.contains(m + " ") || q.contains(m)) return Lang.TANGLISH;
        return Lang.ENGLISH;
    }

    /** Safe app/target names: letters, digits, spaces, '.', '-', 1–3 words. */
    private static final Pattern SAFE_TARGET =
        Pattern.compile("^[\\p{L}\\p{M}0-9.\\-]+(?: [\\p{L}\\p{M}0-9.\\-]+){0,2}$");
    private static final String[] UNSAFE_WORDS = {
        "delete", "remove", "uninstall", "format", "wipe", "pay", "send", "transfer", "and ", " and",
    };

    private static boolean safeTarget(String t) {
        if (t == null || t.isEmpty() || t.length() > 40) return false;
        String l = t.toLowerCase(Locale.ROOT);
        for (String u : UNSAFE_WORDS) if (l.contains(u)) return false;
        return SAFE_TARGET.matcher(t).matches();
    }

    private static final Pattern OPEN_EN = Pattern.compile("^(?:open|launch|start)\\s+(?:the\\s+)?(.+?)(?:\\s+app)?$");
    private static final Pattern OPEN_TANGLISH =
        Pattern.compile("^(.+?)\\s+(?:app\\s+)?(?:open|thira|thirai|thiranthu|launch)(?:\\s+(?:pannu|panna|pannunga|da|please))*$");
    private static final Pattern OPEN_TAMIL = Pattern.compile("^(.+?)\\s*(?:ஐ|யை)?\\s*(?:திற|திறக்கவும்|திறந்து விடு|ஓப்பன் பண்ணு)$");
    private static final Pattern ALARM = Pattern.compile(
        "(?:set\\s+(?:an\\s+)?alarm|alarm\\s+(?:set|vai|vaiyi|podu|pannu)?|wake me)\\D*(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm|mani)?");
    private static final Pattern ALARM_TAMIL = Pattern.compile("(\\d{1,2})(?:[:.](\\d{2}))?\\s*(?:மணிக்கு|மணி)?\\s*அலாரம்");
    private static final Pattern TIMER = Pattern.compile(
        "(?:set\\s+(?:a\\s+)?timer|timer\\s*(?:podu|vai|set)?)\\D*(\\d{1,3})\\s*(sec|second|seconds|min|minute|minutes|nimisham)?");
    private static final Pattern DIAL = Pattern.compile("^(?:call|dial|phone)\\s+([+0-9][0-9 \\-]{2,18})$");
    private static final Pattern SEARCH = Pattern.compile("^(?:search(?:\\s+for)?|google|look up)\\s+(.{1,120})$");

    public static Result run(String text, String savedMemory) {
        String raw = text == null ? "" : text.trim();
        String q = raw.toLowerCase(Locale.ROOT).replaceAll("[?!]+$", "").trim();
        Lang lang = detectLanguage(raw);

        // ---- Existing v0.4 behaviour (preserved exactly) ----
        if (q.startsWith("remember ")) {
            String memo = raw.substring(9).trim();
            if (memo.isEmpty() || memo.length() > 250)
                return new Result(Action.NONE, "Memory must be between 1 and 250 characters.", "");
            return new Result(Action.SAVE_MEMORY, "Saved on this device: " + memo, memo);
        }
        if (q.equals("what do you remember") || q.equals("show memory")) {
            String memo = savedMemory == null ? "" : savedMemory;
            return new Result(Action.NONE, memo.isEmpty() ? "No memory saved yet." : "I remember: " + memo, "");
        }
        if (q.equals("forget memory") || q.equals("clear memory"))
            return new Result(Action.CLEAR_MEMORY, "Local memory cleared.", "");
        if (q.equals("open settings") || q.equals("settings open pannu") || raw.equals("செட்டிங்ஸ் திற"))
            return new Result(Action.OPEN_SETTINGS, say(lang, "Opening Android Settings.", "Settings திறக்கிறேன்."), "");
        if (q.equals("hello") || q.equals("hi") || q.equals("vanakkam") || raw.equals("வணக்கம்"))
            return new Result(Action.NONE, lang == Lang.TAMIL ? "வணக்கம்! JARVIS தயார்." :
                "Vanakkam! JARVIS is ready.", "", "", 0, 0, false, lang);

        // ---- Device commands (each is permission-safe; see DeviceActions) ----
        if (q.matches("^(open )?(wi-?fi|wifi settings)( open pannu)?$"))
            return act(Action.OPEN_WIFI, say(lang, "Opening Wi-Fi panel.", "Wi-Fi panel திறக்கிறேன்."), lang);
        if (q.matches("^(open )?bluetooth( settings)?( open pannu)?$"))
            return act(Action.OPEN_BLUETOOTH, say(lang, "Opening Bluetooth settings.", "Bluetooth settings திறக்கிறேன்."), lang);
        if (q.matches(".*\\b(torch|flash ?light)\\b.*\\b(on|podu|aakku|aan)\\b.*") || q.matches("^(turn on|switch on) (the )?(torch|flashlight)$")
                || raw.contains("டார்ச் ஆன்"))
            return act(Action.TORCH_ON, say(lang, "Torch on.", "டார்ச் ஆன் செய்தேன்."), lang);
        if (q.matches(".*\\b(torch|flash ?light)\\b.*\\b(off|niruthu|anai)\\b.*") || q.matches("^(turn off|switch off) (the )?(torch|flashlight)$")
                || raw.contains("டார்ச் ஆஃப்"))
            return act(Action.TORCH_OFF, say(lang, "Torch off.", "டார்ச் ஆஃப் செய்தேன்."), lang);
        if (q.matches(".*\\bvolume\\b.*\\b(up|increase|koodu|kooduthal|athigam)\\b.*") || q.equals("louder"))
            return act(Action.VOLUME_UP, say(lang, "Volume up.", "ஒலி அதிகரித்தேன்."), lang);
        if (q.matches(".*\\bvolume\\b.*\\b(down|decrease|kammi|korai|kurai)\\b.*") || q.equals("quieter"))
            return act(Action.VOLUME_DOWN, say(lang, "Volume down.", "ஒலி குறைத்தேன்."), lang);
        if (q.matches(".*\\bbattery\\b.*") || raw.contains("பேட்டரி") || raw.contains("சார்ஜ்"))
            return act(Action.BATTERY, "", lang);
        if (q.matches("^(what('?s| is) the )?time( now)?$") || q.matches("^what time is it( now)?$") || q.matches(".*\\b(time enna|mani enna|enna time)\\b.*")
                || raw.contains("நேரம் என்ன") || raw.contains("மணி என்ன"))
            return act(Action.TIME, "", lang);
        if (q.matches("^(what('?s| is) )?(the )?(today'?s )?date( today)?$") || q.matches(".*\\b(date enna|innaiku date)\\b.*")
                || raw.contains("தேதி"))
            return act(Action.DATE, "", lang);

        Matcher m = ALARM.matcher(q);
        if (!m.find()) m = ALARM_TAMIL.matcher(raw);
        else m.reset();
        if (m.find()) {
            int h = Integer.parseInt(m.group(1));
            int min = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
            String ampm = m.groupCount() >= 3 ? m.group(3) : null;
            if ("pm".equals(ampm) && h < 12) h += 12;
            if ("am".equals(ampm) && h == 12) h = 0;
            if (h > 23 || min > 59)
                return new Result(Action.NONE, "That is not a valid time.", "", "", 0, 0, false, lang);
            return new Result(Action.SET_ALARM,
                say(lang, String.format(Locale.ROOT, "Opening your clock to set an alarm for %02d:%02d. Confirm it there.", h, min),
                    String.format(Locale.ROOT, "%02d:%02d அலாரம் — Clock app-இல் உறுதி செய்யவும்.", h, min)),
                "", "", h, min, false, lang);
        }
        m = TIMER.matcher(q);
        if (m.find()) {
            int n = Integer.parseInt(m.group(1));
            String unit = m.group(2);
            int seconds = (unit != null && unit.startsWith("sec")) ? n : n * 60;
            if (seconds < 1 || seconds > 24 * 3600)
                return new Result(Action.NONE, "Timer must be between 1 second and 24 hours.", "", "", 0, 0, false, lang);
            return new Result(Action.SET_TIMER, say(lang, "Starting a timer in your clock app.", "Timer தொடங்குகிறேன்."),
                "", "", seconds, 0, false, lang);
        }
        m = DIAL.matcher(q);
        if (m.find()) {
            String num = m.group(1).replaceAll("[ \\-]", "");
            return new Result(Action.DIAL, "Opening the dialer with " + num + ". Press call yourself to confirm.",
                "", num, 0, 0, false, lang);
        }
        m = SEARCH.matcher(q);
        if (m.find())
            return new Result(Action.WEB_SEARCH, "Searching the web.", "", m.group(1).trim(), 0, 0, false, lang);

        // App launching (English / Tanglish / Tamil word order).
        String target = null;
        m = OPEN_EN.matcher(q);
        if (m.matches()) target = m.group(1);
        if (target == null) { m = OPEN_TANGLISH.matcher(q); if (m.matches()) target = m.group(1); }
        if (target == null) { m = OPEN_TAMIL.matcher(raw); if (m.matches()) target = m.group(1).trim(); }
        if (target != null) {
            target = target.trim();
            if (safeTarget(target))
                return new Result(Action.OPEN_APP, say(lang, "Opening " + target + ".", target + " திறக்கிறேன்."),
                    "", target, 0, 0, false, lang);
            // Unsafe or compound request ("open settings and delete files"): do nothing locally.
            return new Result(Action.NONE, "I can only open one app at a time, and I don't run destructive commands.",
                "", "", 0, 0, false, lang);
        }

        // Fallback: let the AI provider answer. Action stays NONE (v0.4 contract).
        return new Result(Action.NONE,
            "I heard: " + raw + ". AI provider is not connected yet — long-press the reactor to add one.",
            "", raw, 0, 0, true, lang);
    }

    private static Result act(Action a, String response, Lang lang) {
        return new Result(a, response, "", "", 0, 0, false, lang);
    }

    private static String say(Lang lang, String english, String tamil) {
        return lang == Lang.TAMIL ? tamil : english;
    }
}
