package com.adsjarvis.standalone;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import com.adsjarvis.standalone.core.Backup;
import com.adsjarvis.standalone.core.Brain;
import com.adsjarvis.standalone.core.EvalSuite;
import com.adsjarvis.standalone.core.GeminiClient;
import com.adsjarvis.standalone.core.JournalAnalyzer;
import com.adsjarvis.standalone.core.Memory;
import com.adsjarvis.standalone.core.MemoryEngine;
import com.adsjarvis.standalone.core.RiskEngine;
import com.adsjarvis.standalone.core.Tool;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Complete, scrollable settings. Every control changes real behaviour or runs a real action. */
public final class SettingsActivity extends Activity {
    private static final int REQ_EXPORT = 61, REQ_IMPORT = 62, REQ_JOURNAL = 63, REQ_MIC = 64, REQ_NOTIF = 65;
    private static final String[][] SECTIONS = {
        {"general", "General"}, {"ai", "AI Brain"}, {"voice", "Voice"}, {"memory", "Memory & Learning"},
        {"trading", "Trading AI"}, {"agents", "Agents & Automation"}, {"device", "Device & PC"},
        {"visual", "Visual Customization"}, {"security", "Security"}, {"backup", "Backup & Recovery"}, {"diagnostics", "Diagnostics"}};

    private Jarvis jarvis;
    private SharedPreferences p;
    private LinearLayout box;
    private ScrollView scroll;
    private String section = "";
    private boolean launchedWithSection;
    private char[] pendingPassword;
    private Speaker speaker;
    private VoiceInput micTest;

    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density + .5f); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        jarvis = Jarvis.get(this);
        p = jarvis.prefs;
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xff03070d);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(12), dp(18), dp(40));
        scroll.addView(box);
        scroll.setOnApplyWindowInsetsListener((v, in) -> {
            int t = Build.VERSION.SDK_INT >= 30 ? in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime()).top : in.getSystemWindowInsetTop();
            int bo = Build.VERSION.SDK_INT >= 30 ? in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime()).bottom : in.getSystemWindowInsetBottom();
            box.setPadding(dp(18), t + dp(8), dp(18), bo + dp(40));
            return in;
        });
        setContentView(scroll);
        speaker = new Speaker(this, null);
        section = getIntent().getStringExtra("section");
        if (section == null) section = "";
        launchedWithSection = !section.isEmpty();
        show(section);
    }

    @Override protected void onDestroy() {
        if (micTest != null) micTest.release();
        speaker.shutdown();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (!section.isEmpty() && !launchedWithSection) show(""); else super.onBackPressed();
    }

    // ------------------------------------------------------------------ builders

    private TextView text(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(size); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private void title(String s) { TextView t = text(s, 22, Color.WHITE, true); t.setContentDescription("settings title"); box.addView(t); }
    private void header(String s) { TextView t = text(s.toUpperCase(Locale.ROOT), 12, MainActivity.CYAN, true); t.setPadding(0, dp(18), 0, dp(4)); t.setLetterSpacing(.08f); box.addView(t); }
    private TextView note(String s) { TextView t = text(s, 13, 0xff9fb3c8, false); box.addView(t); return t; }
    private TextView unavailable(String what, String why) { return note("⛔ " + what + " — not available: " + why); }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label); b.setAllCaps(false); b.setTextColor(0xffdff8ff);
        b.setBackground(MainActivity.bg(this, 0xff0b1d2c, 0xff1d435e, 12));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(50));
        lp.topMargin = dp(6);
        box.addView(b, lp);
        return b;
    }

    private Switch toggle(String label, String key, boolean def, Runnable after) {
        Switch s = new Switch(this);
        s.setText(label); s.setTextColor(Color.WHITE); s.setTextSize(15); s.setMinHeight(dp(48));
        s.setChecked(p.getBoolean(key, def));
        s.setOnCheckedChangeListener((v, on) -> { p.edit().putBoolean(key, on).apply(); if (after != null) after.run(); changed(); });
        box.addView(s);
        return s;
    }

    private void slider(String label, String key, float min, float max, float def, String fmt) {
        TextView t = text("", 15, Color.WHITE, false);
        box.addView(t);
        SeekBar sb = new SeekBar(this);
        sb.setMax(100);
        float cur = p.getFloat(key, def);
        sb.setProgress((int) ((cur - min) / (max - min) * 100));
        t.setText(label + ": " + String.format(Locale.ROOT, fmt, cur));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int prog, boolean u) {
                float v = min + (max - min) * prog / 100f;
                t.setText(label + ": " + String.format(Locale.ROOT, fmt, v));
                if (u) p.edit().putFloat(key, v).apply();
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) { changed(); }
        });
        box.addView(sb, new LinearLayout.LayoutParams(-1, dp(40)));
    }

    /** Choice shown as a button that opens a single-choice list. */
    private void choice(String label, String key, String[] labels, String[] values, String def, Runnable after) {
        String cur = p.getString(key, def);
        int idx = java.util.Arrays.asList(values).indexOf(cur);
        Button b = button(label + ": " + (idx >= 0 ? labels[idx] : cur), null);
        b.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle(label)
            .setSingleChoiceItems(labels, java.util.Arrays.asList(values).indexOf(p.getString(key, def)), (d, w) -> {
                p.edit().putString(key, values[w]).apply();
                b.setText(label + ": " + labels[w]);
                d.dismiss();
                if (after != null) after.run();
                changed();
            }).show());
    }

    private void textPref(String label, String key, String def, String settingName) {
        EditText e = new EditText(this);
        e.setHint(label); e.setText(p.getString(key, def)); e.setTextColor(Color.WHITE); e.setHintTextColor(0xff6b7f93);
        e.setSingleLine(true);
        box.addView(text(label, 13, 0xff9fb3c8, false));
        box.addView(e);
        button("Save " + label.toLowerCase(Locale.ROOT), v -> {
            String val = e.getText().toString().trim();
            String err = settingName == null ? null : SettingsModel.validate(settingName, val);
            if (err != null) { toast(err); return; }
            if (settingName != null) SettingsModel.apply(p, settingName, val); else p.edit().putString(key, val).apply();
            toast(label + " saved");
            changed();
        });
    }

    private EditText field(String hint, String value, boolean secret, boolean number) {
        EditText e = new EditText(this);
        e.setHint(hint); e.setText(value); e.setTextColor(Color.WHITE); e.setHintTextColor(0xff6b7f93); e.setSingleLine(true);
        e.setInputType(secret ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
            : number ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        box.addView(e);
        return e;
    }

    private void lines(List<String> rows, String empty) {
        if (rows.isEmpty()) { note(empty); return; }
        for (String r : rows) {
            TextView t = text(r, 12, 0xffd0dde8, false);
            t.setBackground(MainActivity.bg(this, 0xff07121c, 0, 8));
            t.setPadding(dp(10), dp(6), dp(10), dp(6));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(4);
            box.addView(t, lp);
        }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private void changed() { jarvis.applySetting("__refresh", ""); }

    // ------------------------------------------------------------------ pages

    private void show(String s) {
        section = s;
        box.removeAllViews();
        scroll.scrollTo(0, 0);
        switch (s) {
            case "general": general(); break;
            case "ai": ai(); break;
            case "voice": voice(); break;
            case "memory": memory(""); break;
            case "trading": trading(); break;
            case "agents": agents(); break;
            case "device": device(); break;
            case "visual": visual(); break;
            case "security": security(); break;
            case "backup": backup(); break;
            case "diagnostics": diagnostics(); break;
            default: index();
        }
    }

    private void index() {
        title("Settings");
        note("JARVIS " + version() + " · " + jarvis.aiStatus());
        for (String[] sct : SECTIONS) button(sct[1] + "  ›", v -> show(sct[0]));
    }

    private void back(String t) {
        if (!launchedWithSection) button("‹ All settings", v -> show(""));
        title(t);
    }

    private void general() {
        back("General");
        textPref("Assistant name", Store.NAME, "JARVIS", "assistant_name");
        choice("Accent colour", "accent_name", new String[]{"Arc cyan", "Gold", "Violet", "Emerald"}, new String[]{"cyan", "gold", "violet", "emerald"}, "cyan", () -> {
            String a = p.getString("accent_name", "cyan");
            p.edit().putInt(Store.ACCENT, "gold".equals(a) ? 0xFFC14D : "violet".equals(a) ? 0xB45CFF : "emerald".equals(a) ? 0x3DFFA8 : 0x1AE6FF).apply();
        });
        choice("Default visual", Store.STYLE, new String[]{"JARVIS ARC", "AIRA", "APEX", "MIRA", "Agent Office"}, new String[]{"ARC", "AIRA", "APEX", "MIRA", "OFFICE"}, "ARC", null);
        choice("Reply language", Store.REPLY_LANG, new String[]{"Auto (match me)", "தமிழ் Tamil", "English", "Tanglish"}, new String[]{"auto", "tamil", "english", "tanglish"}, "auto", null);
        header("Startup");
        toggle("Greet me when JARVIS opens", Store.STARTUP_GREET, false, null);
        toggle("Start listening when JARVIS opens", Store.STARTUP_LISTEN, false, null);
        header("Notifications");
        note("Reminder notifications: " + (AndroidTools.Reminders.notificationsAllowed(this) ? "allowed" : "NOT allowed"));
        if (Build.VERSION.SDK_INT >= 33) button("Allow notifications", v -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF));
        button("Send a test reminder in 10 seconds", v -> {
            ReminderReceiver.ensureChannel(this);
            long id = AndroidTools.Reminders.schedule(this, System.currentTimeMillis() + 10000, "Test reminder from JARVIS settings");
            toast("Reminder #" + id + " scheduled");
        });
    }

    private void ai() {
        back("AI Brain");
        note("Status: " + jarvis.aiStatus() + ". ONLINE is shown only after a successful real request.");
        choice("Provider", Store.PROVIDER, new String[]{"Google Gemini (tools, memory, vision)", "OpenAI-compatible (basic chat only)"},
            new String[]{"GEMINI", "OPENAI_COMPATIBLE"}, "GEMINI", () -> p.edit().putBoolean(Store.VERIFIED, false).apply());
        header("Credentials");
        note(jarvis.vault.has() ? "API key: saved (encrypted with Android Keystore)" : "API key: not set");
        EditText key = field("Paste API key (never shared, never in backups)", "", true, false);
        button("Save API key", v -> {
            String k = key.getText().toString().trim();
            if (k.isEmpty()) { toast("Paste a key first"); return; }
            if (jarvis.vault.save(k)) { p.edit().putBoolean(Store.VERIFIED, false).putString("ai_last_error_kind", "").apply(); toast("Key saved — run the connection test"); show("ai"); }
            else toast("Could not store the key securely");
        });
        button("Remove API key", v -> { jarvis.vault.save(""); p.edit().putBoolean(Store.VERIFIED, false).apply(); show("ai"); });
        header("Endpoint & model");
        EditText base = field("Base URL", p.getString(Store.BASE, GeminiClient.DEFAULT_BASE), false, false);
        EditText model = field("Model id", jarvis.preferredModel(), false, false);
        button("Save endpoint & model", v -> {
            String u = base.getText().toString().trim();
            if (!u.startsWith("https://") && !u.startsWith("http://127.0.0.1") && !u.startsWith("http://localhost")) { toast("Use an https:// URL"); return; }
            p.edit().putString(Store.BASE, u).putString(Store.MODEL, model.getText().toString().trim()).putBoolean(Store.VERIFIED, false).apply();
            toast("Saved"); changed();
        });
        toggle("Automatic model selection (best discovered)", Store.MODEL_AUTO, false, null);
        toggle("Fallback to other models on 404/429/5xx", Store.FALLBACK, true, null);
        TextView out = note("");
        button("Test connection + discover models", v -> {
            out.setText("Testing DNS → TLS → key → model…");
            jarvis.diagnostics(rep -> {
                out.setText(rep.steps.isEmpty() ? "No Gemini key configured." : rep.summary());
                if (!rep.models.isEmpty()) chooseModel(rep.models);
            });
        });
        List<GeminiClient.ModelInfo> disc = jarvis.discovered();
        if (!disc.isEmpty()) button("Choose from " + disc.size() + " discovered models", v -> chooseModel(disc));
        header("Reasoning & tools");
        toggle("Ask before YELLOW actions (memory writes, reminders, drafts)", Store.APPROVE_YELLOW, true, null);
        note("RED actions (deleting memories, external/irreversible) ALWAYS ask. Payments, OTPs and live trading are never automated.");
        choice("Response length", Store.RESP_LEN, new String[]{"Short (spoken)", "Detailed"}, new String[]{"short", "detailed"}, "short", null);
        header("Diagnostic log (redacted)");
        try {
            JSONArray a = new JSONArray(p.getString(Store.LAST_ERR, "[]"));
            List<String> l = new ArrayList<>();
            for (int i = a.length() - 1; i >= 0; i--) l.add(a.getString(i));
            lines(l, "No AI errors recorded.");
        } catch (Exception e) { note("log unreadable"); }
        button("Clear diagnostic log", v -> { p.edit().putString(Store.LAST_ERR, "[]").putString("ai_last_error_kind", "").apply(); show("ai"); });
    }

    private void chooseModel(List<GeminiClient.ModelInfo> ms) {
        List<String> ids = new ArrayList<>();
        for (GeminiClient.ModelInfo m : ms) if (m.generate) ids.add(m.id);
        if (ids.isEmpty()) { toast("No chat models available for this key"); return; }
        String[] arr = ids.toArray(new String[0]);
        new AlertDialog.Builder(this).setTitle("Choose model")
            .setSingleChoiceItems(arr, ids.indexOf(jarvis.preferredModel()), (d, w) -> {
                p.edit().putString(Store.MODEL, arr[w]).putBoolean(Store.MODEL_AUTO, false).apply();
                d.dismiss(); show("ai");
            }).show();
    }

    private void voice() {
        back("Voice");
        boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        note("Microphone permission: " + (mic ? "granted" : "NOT granted") + " · Speech service: "
            + (VoiceInput.available(this) ? "available" : "NOT available on this phone"));
        if (!mic) button("Grant microphone access", v -> requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC));
        choice("Input language", Store.INPUT_LANG, new String[]{"Auto (English/Tanglish)", "தமிழ் Tamil (ta-IN)", "English India (en-IN)"},
            new String[]{"auto", "ta-IN", "en-IN"}, "auto", null);
        choice("Reply language", Store.REPLY_LANG, new String[]{"Auto", "Tamil", "English", "Tanglish"}, new String[]{"auto", "tamil", "english", "tanglish"}, "auto", null);
        choice("Voice gender preference", Store.VOICE_GENDER, new String[]{"Female", "Male", "Any"}, new String[]{"female", "male", "any"}, "female",
            () -> p.edit().putBoolean(Store.FEMALE, "female".equals(p.getString(Store.VOICE_GENDER, "female"))).apply());
        note("Android does not report voice gender; JARVIS uses a voice whose name says female/male when installed, else adjusts pitch. "
            + "For a guaranteed voice, pick one below.");
        Button vb = button("TTS voice: " + (p.getString(Store.VOICE, "").isEmpty() ? "Automatic" : p.getString(Store.VOICE, "")), null);
        vb.setOnClickListener(v -> {
            List<String> vs = new ArrayList<>();
            vs.add("Automatic");
            vs.addAll(speaker.voicesFor("ta-IN"));
            vs.addAll(speaker.voicesFor("en-IN"));
            String[] arr = vs.toArray(new String[0]);
            new AlertDialog.Builder(this).setTitle(vs.size() == 1 ? "No voices reported by the TTS engine" : "Installed voices")
                .setSingleChoiceItems(arr, Math.max(0, vs.indexOf(p.getString(Store.VOICE, ""))), (d, w) -> {
                    p.edit().putString(Store.VOICE, w == 0 ? "" : arr[w]).apply(); d.dismiss(); show("voice"); changed();
                }).show();
        });
        slider("Pitch", Store.PITCH, .5f, 2f, 1f, "%.2f");
        slider("Speaking speed", Store.SPEED, .5f, 2f, 1f, "%.2f");
        toggle("Speak replies aloud", Store.SPEAK_REPLIES, true, null);
        button("Test voice (Tamil + English)", v -> {
            speaker.setGender(p.getString(Store.VOICE_GENDER, "female"));
            speaker.setVoiceName(p.getString(Store.VOICE, ""));
            speaker.setPitch(p.getFloat(Store.PITCH, 1f)); speaker.setRate(p.getFloat(Store.SPEED, 1f));
            boolean ok = speaker.speak("வணக்கம்! Hello, this is " + p.getString(Store.NAME, "JARVIS") + ".", "ta-IN");
            toast(ok ? "Speaking via " + speaker.engineName() : "Text-to-speech unavailable");
        });
        TextView micOut = note("");
        button("Microphone test (speak for a few seconds)", v -> {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { toast("Grant microphone first"); return; }
            if (micTest == null) micTest = new VoiceInput(this);
            micTest.peakLevel = 0;
            micTest.setLanguage("ta-IN".equals(p.getString(Store.INPUT_LANG, "auto")) ? "ta-IN" : "en-IN");
            micTest.setListener(new VoiceInput.Listener() {
                public void onListening(boolean on, boolean hf) { if (on) micOut.setText("Listening… speak now"); }
                public void onLevel(float l) {}
                public void onPartial(String t) { micOut.setText("Hearing: " + t); }
                public void onCommand(String t) { micOut.setText("Heard: \"" + t + "\" · peak level " + Math.round(micTest.peakLevel * 100) + "%"); }
                public void onError(String m, int c) { micOut.setText("Mic test: " + m + " (code " + c + ") · peak level " + Math.round(micTest.peakLevel * 100) + "%"); }
            });
            micTest.startPushToTalk();
        });
        header("Hands-free (wake word)");
        toggle("Wake word while JARVIS is open on screen", Store.WAKE, false, null);
        textPref("Wake word", Store.WAKE_WORD, "jarvis", "wake_word");
        choice("Wake-word sensitivity", "wake_sens_name", new String[]{"Strict (exact)", "Normal", "Loose"}, new String[]{"0", "1", "2"}, "1",
            () -> p.edit().putInt(Store.WAKE_SENS, Integer.parseInt(p.getString("wake_sens_name", "1"))).apply());
        toggle("Conversation mode (listen again after each reply)", Store.CONVO, false, null);
        unavailable("Background and lock-screen listening",
            "it needs an always-on microphone foreground service, which Android 14+ restricts and which has not been tested on your iQOO. Push-to-talk always works.");
    }

    private void memory(String query) {
        back("Memory & Learning");
        toggle("Long-term memory enabled", Store.MEM_ON, true, () -> jarvis.memory.setEnabled(p.getBoolean(Store.MEM_ON, true)));
        toggle("Save AI-proposed memories without asking", Store.MEM_AUTO, false, null);
        note(jarvis.memory.repo().all().size() + " memories · stored only on this phone (excluded from Android backup).");
        EditText q = field("Search memories", query, false, false);
        button("Search", v -> show2memory(q.getText().toString()));
        List<Memory> list;
        if (query.isEmpty()) list = jarvis.memory.repo().all();
        else { list = new ArrayList<>(); for (MemoryEngine.Hit h : jarvis.memory.search(query, null, 30)) list.add(h.memory); }
        header(query.isEmpty() ? "All memories" : "Results");
        if (list.isEmpty()) note("No memories.");
        for (Memory m : list.subList(0, Math.min(60, list.size()))) memoryRow(m, query);
        header("Correction history");
        List<String> corr = new ArrayList<>();
        for (Memory m : jarvis.memory.corrections()) {
            StringBuilder sb = new StringBuilder(m.text);
            for (int i = 0; i < m.history.length(); i++) sb.append("\n   was: ").append(m.history.optJSONObject(i).optString("text"));
            corr.add(sb.toString());
        }
        lines(corr, "No corrections yet. Long-press a JARVIS reply → Correct this answer.");
        header("Supervised evaluation");
        note("Runs a fixed regression set (trading safety, payments, identity) against the current model + memory and records the score. "
            + "Nothing about JARVIS changes automatically.");
        TextView ev = note(p.getString(Store.EVAL_HISTORY, "No evaluations yet."));
        button("Run evaluation now (uses AI)", v -> runEval(ev));
        header("Data");
        button("Backup / restore memories", v -> show("backup"));
        button("Delete ALL memories", v -> confirm("Delete all long-term memories? This cannot be undone.", () -> { jarvis.memory.repo().clear(); show("memory"); }));
    }

    private void show2memory(String q) { box.removeAllViews(); memory(q); }

    private void memoryRow(Memory m, String query) {
        TextView t = text("[" + m.category.name().toLowerCase(Locale.ROOT) + (m.key.isEmpty() ? "" : ":" + m.key) + "] " + m.text
            + "\n" + m.source + " · " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(m.updatedAt))
            + " · used " + m.uses + (m.pinned ? " · 📌 pinned" : ""), 13, 0xffd0dde8, false);
        t.setBackground(MainActivity.bg(this, 0xff07121c, 0, 8));
        t.setPadding(dp(10), dp(6), dp(10), dp(6));
        t.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Memory #" + m.id)
            .setItems(new String[]{m.pinned ? "Unpin" : "Pin (always include)", "Edit", "Delete"}, (d, w) -> {
                if (w == 0) { m.pinned = !m.pinned; jarvis.memory.repo().update(m); show2memory(query); }
                else if (w == 1) {
                    EditText e = new EditText(this); e.setText(m.text);
                    new AlertDialog.Builder(this).setTitle("Edit memory").setView(e).setPositiveButton("Save", (d2, w2) -> {
                        String nt = e.getText().toString().trim();
                        if (nt.isEmpty()) return;
                        try { m.history.put(new JSONObject().put("text", m.text).put("at", m.updatedAt)); } catch (Exception ignored) {}
                        m.text = nt; m.updatedAt = System.currentTimeMillis(); m.source = "user";
                        jarvis.memory.repo().update(m); show2memory(query);
                    }).setNegativeButton("Cancel", null).show();
                } else confirm("Delete this memory?", () -> { jarvis.memory.delete(m.id); show2memory(query); });
            }).show());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(4);
        box.addView(t, lp);
    }

    private void runEval(TextView out) {
        if (jarvis.gemini() == null) { toast("Connect Gemini first (AI Brain)"); return; }
        out.setText("Running " + "evaluation…");
        List<EvalSuite.Case> cases;
        try { cases = EvalSuite.parse(EvalSuite.DEFAULT); } catch (Exception e) { return; }
        final List<String> answers = new ArrayList<>();
        final int[] i = {0};
        Runnable[] next = new Runnable[1];
        next[0] = () -> {
            if (i[0] >= cases.size()) {
                EvalSuite.Result r = EvalSuite.run(cases, prompt -> answers.get(cases.indexOf(findCase(cases, prompt))));
                String line = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date()) + " · score "
                    + r.passed + "/" + r.total + " · model " + p.getString(Store.LAST_OK_MODEL, "?") + "\n" + String.join("\n", r.lines);
                p.edit().putString(Store.EVAL_HISTORY, line + "\n\n" + p.getString(Store.EVAL_HISTORY, "")).apply();
                out.setText(line);
                return;
            }
            jarvis.ask(cases.get(i[0]).prompt, null, new ArrayList<Brain.Turn>(), rep -> { answers.add(rep.text); i[0]++; next[0].run(); });
        };
        next[0].run();
    }

    private static EvalSuite.Case findCase(List<EvalSuite.Case> cs, String prompt) {
        for (EvalSuite.Case c : cs) if (c.prompt.equals(prompt)) return c;
        return cs.get(0);
    }

    private void trading() {
        back("Trading AI");
        note("🛡 PAPER ONLY. This app never places orders, edits Pine scripts, changes the PB7 bot, deploys, or writes to production. "
            + "Live trading cannot be enabled here.");
        choice("Default index", Store.T_INSTR, new String[]{"NIFTY", "SENSEX"}, new String[]{"NIFTY", "SENSEX"}, "NIFTY", null);
        header("Confirmation");
        note("Primary timeframe: 5-minute (confirmed candle close). Entries only after 09:30 IST.");
        toggle("Require 15-minute higher-timeframe confirmation", Store.T_HTF, true, null);
        header("Indicators the AI must check");
        String ind = p.getString(Store.T_IND, "EMA9,MA9,VWAP,Supertrend");
        for (String name : new String[]{"EMA9", "MA9", "VWAP", "Supertrend", "Pivots", "Volume", "Open interest", "Bid/ask spread"}) {
            Switch s = new Switch(this);
            s.setText(name); s.setTextColor(Color.WHITE); s.setMinHeight(dp(44));
            s.setChecked(ind.contains(name));
            s.setOnCheckedChangeListener((v, on) -> {
                List<String> cur = new ArrayList<>(java.util.Arrays.asList(p.getString(Store.T_IND, "EMA9,MA9,VWAP,Supertrend").split(",")));
                cur.remove(name); cur.remove("");
                if (on) cur.add(name);
                p.edit().putString(Store.T_IND, String.join(",", cur)).apply();
            });
            box.addView(s);
        }
        header("Risk limits (can only be made stricter)");
        slider("Minimum confidence %", Store.T_CONF, 70, 95, 70, "%.0f");
        slider("Max risk per entry ₹ (incl. fees/slippage)", Store.T_RISK, 300, 1200, 1200, "%.0f");
        slider("Daily hard-loss limit ₹", Store.T_DAILY, 500, 3000, 3000, "%.0f");
        note("Single open position across NIFTY/SENSEX · stale quotes rejected · oversized SL rejected.");
        header("Lots & costs (verify with your broker/exchange)");
        EditText nl = field("NIFTY lot size", "" + p.getInt(Store.T_NLOT, 75), false, true);
        EditText sl = field("SENSEX lot size", "" + p.getInt(Store.T_SLOT, 20), false, true);
        EditText slip = field("Slippage (premium points, entry+exit)", "" + p.getFloat(Store.T_SLIP, .5f), false, true);
        EditText brok = field("Brokerage per order ₹", "" + p.getFloat(Store.T_BROK, 20), false, true);
        EditText stat = field("Statutory charges % of turnover", "" + p.getFloat(Store.T_STAT, .12f), false, true);
        button("Save lots & costs", v -> {
            try {
                p.edit().putInt(Store.T_NLOT, Integer.parseInt(nl.getText().toString().trim())).putInt(Store.T_SLOT, Integer.parseInt(sl.getText().toString().trim()))
                    .putFloat(Store.T_SLIP, Float.parseFloat(slip.getText().toString().trim())).putFloat(Store.T_BROK, Float.parseFloat(brok.getText().toString().trim()))
                    .putFloat(Store.T_STAT, Float.parseFloat(stat.getText().toString().trim())).apply();
                toast("Saved");
            } catch (NumberFormatException e) { toast("Enter numbers only"); }
        });
        header("Risk gate calculator (on-device)");
        EditText en = field("Entry premium", "", false, true), stop = field("Stop-loss premium", "", false, true),
            conf = field("Confidence %", "", false, true), tm = field("Signal candle close time HH:MM (IST)", "", false, false);
        TextView res = note("");
        button("Check against GreenSoil rules", v -> {
            try {
                RiskEngine.Setup s = new RiskEngine.Setup();
                s.instrument = p.getString(Store.T_INSTR, "NIFTY");
                s.entry = Double.parseDouble(en.getText().toString()); s.stopLoss = Double.parseDouble(stop.getText().toString());
                s.confidence = Double.parseDouble(conf.getText().toString());
                String[] hm = tm.getText().toString().split(":");
                s.entryMinutesIst = Integer.parseInt(hm[0].trim()) * 60 + Integer.parseInt(hm[1].trim());
                s.candleClosed = true; s.htfConfirmed = true; s.quoteAgeSeconds = 0;
                RiskEngine.Decision d = RiskEngine.evaluate(s, AndroidTools.rules(p));
                res.setText(d.toJson().toString(2));
            } catch (Exception e) { res.setText("Fill all four fields (time as HH:MM)."); }
        });
        header("Trade journal");
        String csv = p.getString(Store.T_JOURNAL, "");
        note(csv.isEmpty() ? "No journal imported." : "Journal: " + (csv.split("\n").length - 1) + " rows (on this phone only).");
        button("Import journal CSV", v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/csv", "text/comma-separated-values", "text/plain", "application/vnd.ms-excel"});
            startActivityForResult(i, REQ_JOURNAL);
        });
        TextView jr = note("");
        if (!csv.isEmpty()) {
            button("Analyse journal now", v -> jr.setText(MainActivity.summarizeJournal(JournalAnalyzer.analyze(p.getString(Store.T_JOURNAL, ""), AndroidTools.rules(p)))));
            button("Remove journal", v -> confirm("Remove the imported journal?", () -> { p.edit().remove(Store.T_JOURNAL).apply(); show("trading"); }));
        }
        header("Connections");
        button("Open TradingView (NIFTY 5m)", v -> { try { toast(jarvis.tools.get("open_tradingview").run(new JSONObject()).summary); } catch (Exception e) { toast("Could not open TradingView"); } });
        note("PB7 PAPER bot (read-only): " + (p.getString(Store.PB7_URL, "").isEmpty() ? "NOT CONNECTED" : p.getString(Store.PB7_URL, "")));
        EditText pu = field("PB7 read-only status URL (https)", p.getString(Store.PB7_URL, ""), false, false);
        EditText pt = field("PB7 read-only token (optional, encrypted)", "", true, false);
        TextView pbOut = note("");
        button("Save PB7 connection", v -> {
            String u = pu.getText().toString().trim();
            if (!u.isEmpty() && !u.startsWith("https://")) { toast("URL must be https"); return; }
            p.edit().putString(Store.PB7_URL, u).apply();
            if (!pt.getText().toString().trim().isEmpty()) new KeyVault(p, Store.PB7_TOKEN).save(pt.getText().toString().trim());
            toast("Saved");
        });
        button("Test PB7 read", v -> new Thread(() -> {
            String r;
            try { r = jarvis.tools.get("pb7_status").run(new JSONObject()).summary; } catch (Exception e) { r = "Failed: " + e.getClass().getSimpleName(); }
            String fr = r;
            runOnUiThread(() -> pbOut.setText(fr));
        }).start());
        unavailable("Telegram reports", "no Telegram integration is configured in this app; PB7's own Telegram reporting is untouched.");
        unavailable("Live market data feed", "no broker/market-data API is connected. Chart analysis uses screenshots you attach, and quote freshness is treated as unknown (→ NO TRADE).");
    }

    private void agents() {
        back("Agents & Automation");
        note("JARVIS supervises five internal agents. Disable any tool to stop the AI from using it.");
        toggle("Ask before YELLOW actions", Store.APPROVE_YELLOW, true, null);
        for (Tool.Agent a : Tool.Agent.values()) {
            header(a.label);
            for (Tool t : jarvis.tools.all()) {
                if (t.agent() != a) continue;
                Switch s = new Switch(this);
                s.setText(t.name() + "  [" + t.tier() + (t.needsNetwork() ? ", online" : ", offline") + "]");
                s.setTextColor(Color.WHITE); s.setTextSize(13); s.setMinHeight(dp(44));
                s.setChecked(jarvis.tools.isEnabled(t.name()));
                s.setOnCheckedChangeListener((v, on) -> { jarvis.tools.setEnabled(t.name(), on); jarvis.saveDisabledTools(); });
                box.addView(s);
            }
        }
        header("Scheduled reminders");
        List<String> rem = JarvisDb.get(this).lines("reminder", "done=0", 50);
        lines(rem, "No pending reminders.");
        if (!rem.isEmpty()) button("Cancel a reminder…", v -> {
            String[] arr = rem.toArray(new String[0]);
            new AlertDialog.Builder(this).setTitle("Cancel reminder").setItems(arr, (d, w) -> {
                long id = Long.parseLong(arr[w].substring(1, arr[w].indexOf(' ')));
                AndroidTools.Reminders.cancel(this, id); show("agents");
            }).show();
        });
        header("Task history");
        lines(JarvisDb.get(this).lines("task", null, 30), "No AI tasks yet.");
        header("Failed tasks");
        lines(JarvisDb.get(this).lines("task", "ok=0", 20), "No failed tasks.");
        header("Audit log (tool calls & approvals)");
        lines(JarvisDb.get(this).lines("audit", null, 100), "No tool calls yet.");
    }

    private void device() {
        back("Device & PC");
        header("Android permissions");
        boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        note("Microphone: " + (mic ? "granted" : "not granted") + " · Notifications: " + (AndroidTools.Reminders.notificationsAllowed(this) ? "allowed" : "not allowed"));
        if (!mic) button("Grant microphone", v -> requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC));
        if (Build.VERSION.SDK_INT >= 33) button("Allow notifications", v -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF));
        button("Open Android app settings (review / revoke permissions)", v -> startActivity(
            new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));
        note("JARVIS does NOT request: accessibility, overlay, contacts, SMS, call, location, or package-listing permissions.");
        header("App integrations");
        String[][] apps = {{"TradingView", "com.tradingview.tradingviewapp"}, {"WhatsApp", "com.whatsapp"}};
        for (String[] a : apps) note(a[0] + ": " + (AndroidTools.installed(this, a[1]) ? "installed" : "not installed"));
        note("Clock, Calendar, Dialer, SMS and Browser are opened through standard Android intents; you confirm inside those apps.");
        header("Audio output / Bluetooth");
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        List<String> outs = new ArrayList<>();
        if (am != null) for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int t = d.getType();
            String kind = t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ? "Bluetooth audio" : t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ? "Bluetooth headset"
                : t == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ? "Phone speaker" : t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || t == AudioDeviceInfo.TYPE_WIRED_HEADSET ? "Wired headset"
                : t == AudioDeviceInfo.TYPE_USB_HEADSET ? "USB headset" : null;
            if (kind != null) outs.add(kind + " · " + d.getProductName());
        }
        lines(outs, "No audio outputs reported.");
        note("Speech output follows Android's current audio route (Bluetooth when connected).");
        header("Windows PC");
        unavailable("Windows pairing, file transfer, remote app launch", "the Windows companion agent has not been built yet (backlog). Mobile JARVIS never depends on a PC.");
        header("Works offline");
        note("Time/date · alarms, timers, reminders · open apps · torch, volume, battery · notes & memory · trade-journal audit · risk gate calculator · settings.");
    }

    private void visual() {
        back("Visual Customization");
        choice("Visual model", Store.STYLE, new String[]{"JARVIS ARC reactor", "AIRA neural plasma", "APEX cinematic", "MIRA holographic", "Agent Office"},
            new String[]{"ARC", "AIRA", "APEX", "MIRA", "OFFICE"}, "ARC", null);
        choice("Colour accent", "accent_name", new String[]{"Arc cyan", "Gold", "Violet", "Emerald"}, new String[]{"cyan", "gold", "violet", "emerald"}, "cyan", () -> {
            String a = p.getString("accent_name", "cyan");
            p.edit().putInt(Store.ACCENT, "gold".equals(a) ? 0xFFC14D : "violet".equals(a) ? 0xB45CFF : "emerald".equals(a) ? 0x3DFFA8 : 0x1AE6FF).apply();
        });
        slider("Particle density", Store.DENSITY, .3f, 1f, 1f, "%.2f");
        slider("Glow intensity", Store.GLOW, .5f, 1.6f, 1f, "%.2f");
        slider("Animation speed", Store.SPEED_V, .3f, 2f, 1f, "%.2f");
        Button fps = button("FPS cap: " + p.getInt(Store.FPS, 60), null);
        fps.setOnClickListener(v -> {
            String[] o = {"30", "45", "60"};
            new AlertDialog.Builder(this).setTitle("FPS cap").setItems(o, (d, w) -> { p.edit().putInt(Store.FPS, Integer.parseInt(o[w])).apply(); fps.setText("FPS cap: " + o[w]); changed(); }).show();
        });
        Button q = button("Quality preset: " + qualityName(p.getFloat(Store.QUALITY, .75f)), null);
        q.setOnClickListener(v -> {
            String[] o = {"Battery (50% resolution)", "Balanced (75%)", "High (100%)"};
            float[] val = {.5f, .75f, 1f};
            new AlertDialog.Builder(this).setTitle("Quality").setItems(o, (d, w) -> { p.edit().putFloat(Store.QUALITY, val[w]).apply(); q.setText("Quality preset: " + qualityName(val[w])); changed(); }).show();
        });
        toggle("Battery saver: lower quality when Android power saving or thermal throttling is on", Store.SAVER, true, null);
        toggle("Reduce motion", Store.REDUCE, false, null);
        note("Renderer: " + MainActivity.renderer + " · measured FPS: " + String.format(Locale.ROOT, "%.1f", MainActivity.lastFps));
    }

    private static String qualityName(float q) { return q <= .5f ? "Battery" : q <= .75f ? "Balanced" : "High"; }

    private void security() {
        back("Security");
        note("Credentials: the AI key and PB7 token are encrypted with a non-exportable AES-256-GCM Android Keystore key and are never included in backups or logs.");
        toggle("App lock (screen-lock/biometric when JARVIS opens)", Store.APP_LOCK, false, null);
        toggle("Approval for sensitive (YELLOW) actions", Store.APPROVE_YELLOW, true, null);
        note("RED actions always require approval. Payments: JARVIS never enters OTPs or UPI PINs.");
        button("Review / revoke Android permissions", v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));
        button("Audit log", v -> show("agents"));
        header("Revoke access");
        button("Remove AI API key", v -> confirm("Remove the AI key?", () -> { jarvis.vault.save(""); p.edit().putBoolean(Store.VERIFIED, false).apply(); toast("Removed"); }));
        button("Remove PB7 connection", v -> confirm("Remove PB7 URL and token?", () -> { p.edit().remove(Store.PB7_URL).apply(); new KeyVault(p, Store.PB7_TOKEN).save(""); toast("Removed"); }));
        header("Delete data");
        button("Delete conversation history", v -> confirm("Delete conversation history?", () -> { p.edit().remove(Store.HISTORY).apply(); toast("Deleted — reopen JARVIS"); }));
        button("Delete ALL JARVIS data (memories, logs, settings, keys)", v -> confirm("Erase everything? This cannot be undone.", () -> {
            JarvisDb.get(this).wipeAll();
            jarvis.vault.save(""); new KeyVault(p, Store.PB7_TOKEN).save("");
            p.edit().clear().apply();
            new File(getFilesDir(), "crash.txt").delete();
            toast("All JARVIS data erased");
            finishAffinity();
        }));
    }

    private void backup() {
        back("Backup & Recovery");
        note("Encrypted with your password (PBKDF2 + AES-256-GCM). Includes memories, settings, conversation history and trade journal. "
            + "Excludes API keys and tokens.");
        button("Export encrypted backup…", v -> askPassword("Backup password (min 8 characters)", pw -> {
            pendingPassword = pw;
            startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, "jarvis-backup-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(new Date()) + ".json"), REQ_EXPORT);
        }));
        button("Restore from backup…", v -> askPassword("Backup password", pw -> {
            pendingPassword = pw;
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_IMPORT);
        }));
        note("Restore validates the password and integrity (GCM tag + SHA-256) before changing anything; memories are merged, settings replaced.");
        unavailable("Cloud backup", "no cloud account is connected; save the encrypted file to Google Drive yourself from the file picker if you want an off-device copy.");
    }

    private void diagnostics() {
        back("Diagnostics");
        note("App version: " + version() + "\nBuild: " + BuildConfig.GIT_SHA + "\nAndroid " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ") · "
            + Build.MANUFACTURER + " " + Build.MODEL);
        long last = p.getLong(Store.LAST_OK, 0);
        note("AI: " + jarvis.aiStatus() + "\nLast successful AI request: " + (last == 0 ? "never" : DateFormat.getDateTimeInstance().format(new Date(last))
            + " via " + p.getString(Store.LAST_OK_MODEL, "?")));
        TextView out = note("");
        button("Run Gemini connection diagnostics (DNS → TLS → key → model)", v -> {
            out.setText("Running…");
            jarvis.diagnostics(rep -> out.setText(rep.steps.isEmpty() ? "No Gemini key configured (Settings → AI Brain)." : rep.summary()));
        });
        button("Microphone & TTS tests", v -> show("voice"));
        JarvisDb db = JarvisDb.get(this);
        note("Memory database: " + db.count("memory") + " memories · " + db.count("task") + " tasks · " + db.count("audit") + " audit entries · "
            + db.count("reminder") + " reminders · " + (getDatabasePath("jarvis.db").length() / 1024) + " KB");
        note("Rendering: " + MainActivity.renderer + " · " + String.format(Locale.ROOT, "%.1f", MainActivity.lastFps) + " FPS (last 5 s)");
        header("Crash log");
        File crash = new File(getFilesDir(), "crash.txt");
        if (!crash.exists()) note("No crashes recorded.");
        else {
            try (InputStream in = new FileInputStream(crash)) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] b = new byte[4096]; int n;
                while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                String s = bo.toString("UTF-8");
                lines(java.util.Collections.singletonList(s.length() > 6000 ? s.substring(s.length() - 6000) : s), "");
            } catch (Exception e) { note("Crash log unreadable"); }
            button("Clear crash log", v -> { crash.delete(); show("diagnostics"); });
        }
    }

    private String version() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.versionName + " (" + pi.getLongVersionCode() + ")";
        } catch (Exception e) { return "?"; }
    }

    // ------------------------------------------------------------------ dialogs & results

    private void confirm(String msg, Runnable yes) {
        new AlertDialog.Builder(this).setMessage(msg).setPositiveButton("Yes", (d, w) -> yes.run()).setNegativeButton("Cancel", null).show();
    }

    private void askPassword(String title, java.util.function.Consumer<char[]> ok) {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new AlertDialog.Builder(this).setTitle(title).setView(e).setPositiveButton("OK", (d, w) -> {
            char[] pw = e.getText().toString().toCharArray();
            if (pw.length < 8) { toast("At least 8 characters"); return; }
            ok.accept(pw);
        }).setNegativeButton("Cancel", null).show();
    }

    private JSONObject exportPayload() throws Exception {
        JSONArray mem = new JSONArray();
        for (Memory m : jarvis.memory.repo().all()) mem.put(m.toJson());
        JSONObject settings = new JSONObject();
        for (Map.Entry<String, ?> e : p.getAll().entrySet()) {
            String k = e.getKey();
            if (k.endsWith("_enc") || k.contains("token") || k.equals(Store.LAST_ERR)) continue;    // never export secrets
            Object v = e.getValue();
            settings.put(k, new JSONObject().put("type", v instanceof Boolean ? "b" : v instanceof Integer ? "i" : v instanceof Long ? "l"
                : v instanceof Float ? "f" : "s").put("v", String.valueOf(v)));
        }
        return new JSONObject().put("memories", mem).put("settings", settings).put("exported", System.currentTimeMillis()).put("app", version());
    }

    private void restore(JSONObject o) throws Exception {
        JSONArray mem = o.optJSONArray("memories");
        int added = 0;
        if (mem != null) for (int i = 0; i < mem.length(); i++) {
            Memory m = Memory.fromJson(mem.getJSONObject(i));
            MemoryEngine.Proposal pr = jarvis.memory.propose(m.category, m.key, m.text, "import");
            if (pr.outcome != MemoryEngine.Outcome.DUPLICATE) { jarvis.memory.commit(pr); added++; }
        }
        JSONObject s = o.optJSONObject("settings");
        if (s != null) {
            SharedPreferences.Editor e = p.edit();
            java.util.Iterator<String> it = s.keys();
            while (it.hasNext()) {
                String k = it.next();
                JSONObject v = s.getJSONObject(k);
                String val = v.getString("v");
                switch (v.getString("type")) {
                    case "b": e.putBoolean(k, Boolean.parseBoolean(val)); break;
                    case "i": e.putInt(k, Integer.parseInt(val)); break;
                    case "l": e.putLong(k, Long.parseLong(val)); break;
                    case "f": e.putFloat(k, Float.parseFloat(val)); break;
                    default: e.putString(k, val);
                }
            }
            e.apply();
        }
        toast("Backup verified and restored: " + added + " memories added");
        changed();
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri u = data.getData();
        try {
            if (req == REQ_EXPORT) {
                String file = Backup.encrypt(exportPayload(), pendingPassword);
                try (OutputStream os = getContentResolver().openOutputStream(u)) { os.write(file.getBytes(StandardCharsets.UTF_8)); }
                // Integrity validation: read back and decrypt.
                Backup.decrypt(readAll(u), pendingPassword);
                toast("Encrypted backup written and verified");
            } else if (req == REQ_IMPORT) {
                restore(Backup.decrypt(readAll(u), pendingPassword));
            } else if (req == REQ_JOURNAL) {
                String csv = readAll(u);
                String head = csv.split("\n", 2)[0].toLowerCase(Locale.ROOT);
                if (!(head.contains("entry") && head.contains("exit"))) { toast("CSV needs entry and exit columns"); return; }
                p.edit().putString(Store.T_JOURNAL, csv).apply();
                toast("Journal imported");
                show("trading");
            }
        } catch (Backup.BackupException e) {
            toast(e.getMessage());
        } catch (Exception e) {
            toast("Failed: " + e.getClass().getSimpleName());
        } finally {
            if (req != REQ_JOURNAL && pendingPassword != null) java.util.Arrays.fill(pendingPassword, '\0');
        }
    }

    private String readAll(Uri u) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(u)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) > 0 && bo.size() < 20_000_000) bo.write(b, 0, n);
            return bo.toString("UTF-8");
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        show(section);
    }
}
