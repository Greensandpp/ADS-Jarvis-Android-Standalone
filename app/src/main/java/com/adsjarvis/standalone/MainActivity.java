package com.adsjarvis.standalone;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * One unified JARVIS home: animated reactor HUD, transcript, and a bottom dock
 * with text input + microphone. Long-press the reactor to customize visual
 * style, language, voice and AI provider. There are no top-level mode buttons.
 */
public final class MainActivity extends Activity {
    private static final int MIC_PERMISSION = 44;
    private static final int CYAN = 0xff68e3ff, MUTED = 0xff8fa6bd, PANEL = 0xcc0b1726;

    // Preference keys (stored in the activity's private prefs, same file as v0.4 so "memory" is preserved).
    static final String K_STYLE = "visual_style", K_LANG = "voice_lang", K_FEMALE = "prefer_female",
        K_VOICE = "tts_voice", K_BRIGHT = "brightness", K_REDUCE = "reduce_motion", K_PROVIDER = "ai_provider",
        K_BASE = "ai_base_url", K_MODEL = "ai_model", K_HISTORY = "history_v1", K_HINTED = "longpress_hint_shown";

    private SharedPreferences prefs;
    private KeyVault vault;
    private HudView hud;
    private TextView status;
    private LinearLayout transcript;
    private ScrollView transcriptScroll;
    private EditText input;
    private ImageButton mic;
    private SpeechRecognizer speech;
    private boolean listening;
    private Speaker speaker;
    private DeviceActions actions;
    private final AiClient ai = new AiClient();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<AiClient.Turn> history = new ArrayList<>();

    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density + .5f); }

    private GradientDrawable bg(int fill, int stroke, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radius));
        if (stroke != 0) d.setStroke(dp(1), stroke);
        return d;
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getPreferences(MODE_PRIVATE);
        vault = new KeyVault(prefs);
        actions = new DeviceActions(this);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.BLACK);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        hud = new HudView(this);
        root.addView(hud, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, new FrameLayout.LayoutParams(-1, -1));

        TextView title = new TextView(this);
        title.setText("J A R V I S");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(10), 0, 0);
        column.addView(title, new LinearLayout.LayoutParams(-1, -2));

        status = new TextView(this);
        status.setTextColor(CYAN);
        status.setTextSize(11);
        status.setLetterSpacing(.08f);
        status.setGravity(Gravity.CENTER);
        status.setContentDescription("status");
        column.addView(status, new LinearLayout.LayoutParams(-1, -2));

        // Spacer: the reactor shows through here; tap = talk, long-press = customize.
        View reactorHit = new View(this);
        reactorHit.setContentDescription("reactor");
        reactorHit.setOnClickListener(v -> toggleListening());
        reactorHit.setOnLongClickListener(v -> { openCustomization(); return true; });
        column.addView(reactorHit, new LinearLayout.LayoutParams(-1, 0, 1f));

        transcriptScroll = new ScrollView(this);
        transcript = new LinearLayout(this);
        transcript.setOrientation(LinearLayout.VERTICAL);
        transcript.setPadding(dp(14), dp(6), dp(14), dp(6));
        transcriptScroll.addView(transcript);
        transcriptScroll.setContentDescription("conversation");
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, dp(190));
        column.addView(transcriptScroll, tp);

        LinearLayout dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER_VERTICAL);
        dock.setPadding(dp(10), dp(8), dp(10), dp(10));
        dock.setBackground(bg(0xee050b14, 0xff163049, 0));
        column.addView(dock, new LinearLayout.LayoutParams(-1, -2));

        mic = new ImageButton(this);
        mic.setImageResource(android.R.drawable.ic_btn_speak_now);
        mic.setBackground(bg(0xff037bb0, 0, 26));
        mic.setContentDescription("microphone");
        mic.setOnClickListener(v -> toggleListening());
        dock.addView(mic, new LinearLayout.LayoutParams(dp(52), dp(52)));

        input = new EditText(this);
        input.setHint("Ask JARVIS · தமிழ் · Tanglish");
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(MUTED);
        input.setTextSize(15);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setPadding(dp(14), dp(10), dp(14), dp(10));
        input.setBackground(bg(PANEL, 0xff21445d, 22));
        input.setContentDescription("message input");
        input.setOnEditorActionListener((v, id, ev) -> {
            if (id == EditorInfo.IME_ACTION_SEND || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER)) { send(); return true; }
            return false;
        });
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0, dp(52), 1f);
        ip.leftMargin = dp(8); ip.rightMargin = dp(8);
        dock.addView(input, ip);

        Button sendBtn = new Button(this);
        sendBtn.setText("➤");
        sendBtn.setTextSize(18);
        sendBtn.setTextColor(Color.BLACK);
        sendBtn.setBackground(bg(CYAN, 0, 26));
        sendBtn.setContentDescription("send");
        sendBtn.setOnClickListener(v -> send());
        dock.addView(sendBtn, new LinearLayout.LayoutParams(dp(52), dp(52)));

        // Android 15/16 edge-to-edge: pad for status/nav bars, cutouts and the keyboard.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom, left, right;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets s = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                top = s.top; left = s.left; right = s.right; bottom = Math.max(s.bottom, ime.bottom);
            } else {
                top = insets.getSystemWindowInsetTop(); bottom = insets.getSystemWindowInsetBottom();
                left = insets.getSystemWindowInsetLeft(); right = insets.getSystemWindowInsetRight();
            }
            column.setPadding(left, top, right, bottom);
            return insets;
        });
        setContentView(root);

        speaker = new Speaker(this, () -> main.post(this::refreshStatus));
        speaker.setListener(speaking -> main.post(() -> {
            if (!listening) hud.setState(speaking ? HudView.State.SPEAKING : HudView.State.IDLE);
        }));
        applyPrefs();
        loadHistory();
        if (history.isEmpty()) bubble(false, "Vanakkam! Tap the reactor or the mic to talk, or type below. "
            + "Long-press the reactor to customize.");
        if (!prefs.getBoolean(K_HINTED, false)) {
            Toast.makeText(this, "Long-press the reactor to customize JARVIS", Toast.LENGTH_LONG).show();
            prefs.edit().putBoolean(K_HINTED, true).apply();
        }
        refreshStatus();
    }

    // ------------------------------------------------------------------ prefs

    private void applyPrefs() {
        hud.setStyle(styleFromPrefs());
        hud.setBrightness(prefs.getFloat(K_BRIGHT, 1f));
        hud.setReduceMotion(prefs.getBoolean(K_REDUCE, false));
        speaker.setPreferFemale(prefs.getBoolean(K_FEMALE, true));
        speaker.setVoiceName(prefs.getString(K_VOICE, ""));
    }

    private HudView.Style styleFromPrefs() {
        try { return HudView.Style.valueOf(prefs.getString(K_STYLE, "ARC")); }
        catch (IllegalArgumentException e) { return HudView.Style.ARC; }
    }

    /** "auto" (English + Tanglish), "ta-IN" or "en-IN". */
    private String langPref() { return prefs.getString(K_LANG, "auto"); }

    private AiClient.Config aiConfig() {
        AiClient.Provider p = "OPENAI_COMPATIBLE".equals(prefs.getString(K_PROVIDER, "GEMINI"))
            ? AiClient.Provider.OPENAI_COMPATIBLE : AiClient.Provider.GEMINI;
        return new AiClient.Config(p, prefs.getString(K_BASE, AiClient.Config.defaultBaseUrl(p)),
            prefs.getString(K_MODEL, p == AiClient.Provider.GEMINI ? "gemini-2.5-flash" : ""), vault.load());
    }

    private void refreshStatus() {
        AiClient.Config c = aiConfig();
        String aiState = c.isComplete() ? (online() ? "AI ONLINE" : "AI OFFLINE · NO NETWORK") : "AI NOT CONNECTED";
        String voice = speaker != null && speaker.isReady() ? "VOICE READY" : "VOICE UNAVAILABLE";
        String l = langPref();
        status.setText(hud.getStyle().name() + "  ·  " + aiState + "  ·  " + voice + "  ·  "
            + ("ta-IN".equals(l) ? "தமிழ்" : "auto".equals(l) ? "EN/TANGLISH" : "ENGLISH"));
    }

    private boolean online() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
        return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    // ---------------------------------------------------------------- history

    private void loadHistory() {
        try {
            JSONArray a = new JSONArray(prefs.getString(K_HISTORY, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                AiClient.Turn t = new AiClient.Turn(o.getBoolean("u"), o.getString("t"));
                history.add(t);
                addBubbleView(t.fromUser, t.text);
            }
        } catch (Exception ignored) { history.clear(); }
    }

    private void saveHistory() {
        JSONArray a = new JSONArray();
        int from = Math.max(0, history.size() - 40);
        try {
            for (AiClient.Turn t : history.subList(from, history.size())) a.put(new JSONObject().put("u", t.fromUser).put("t", t.text));
        } catch (Exception ignored) {}
        prefs.edit().putString(K_HISTORY, a.toString()).apply();
    }

    private void bubble(boolean fromUser, String text) {
        history.add(new AiClient.Turn(fromUser, text));
        saveHistory();
        addBubbleView(fromUser, text);
    }

    private void addBubbleView(boolean fromUser, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setTextColor(fromUser ? Color.WHITE : 0xffdff8ff);
        tv.setPadding(dp(12), dp(8), dp(12), dp(8));
        tv.setBackground(bg(fromUser ? 0xcc0f3550 : PANEL, fromUser ? 0 : 0xff1d435e, 14));
        tv.setTextIsSelectable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.gravity = fromUser ? Gravity.END : Gravity.START;
        lp.topMargin = dp(6);
        transcript.addView(tv, lp);
        while (transcript.getChildCount() > 40) transcript.removeViewAt(0);
        transcriptScroll.post(() -> transcriptScroll.fullScroll(View.FOCUS_DOWN));
    }

    // ---------------------------------------------------------------- commands

    private void send() {
        String q = input.getText().toString().trim();
        if (q.isEmpty()) return;
        input.setText("");
        handle(q);
    }

    void handle(String command) {
        bubble(true, command);
        CommandEngine.Result r = CommandEngine.run(command, prefs.getString("memory", ""));
        if (r.action == CommandEngine.Action.SAVE_MEMORY) prefs.edit().putString("memory", r.memoryValue).apply();
        if (r.action == CommandEngine.Action.CLEAR_MEMORY) prefs.edit().remove("memory").apply();
        String replyLang = replyLang(r.lang);
        if (!r.askAi) {
            String answer = actions.run(r);
            reply(answer, replyLang);
            return;
        }
        AiClient.Config cfg = aiConfig();
        if (!cfg.isComplete()) {
            reply("AI provider is not connected yet. Long-press the reactor → AI provider to add your own key. "
                + "Local commands like \"open YouTube\", \"battery\" or \"time enna\" still work.", replyLang);
            return;
        }
        if (!online()) {
            reply("No internet connection, so I can't reach the AI right now. Local commands still work.", replyLang);
            return;
        }
        hud.setState(HudView.State.THINKING);
        status.setText("THINKING…");
        List<AiClient.Turn> context = new ArrayList<>(history.subList(0, history.size() - 1));
        worker.execute(() -> {
            AiClient.Reply res = ai.chat(cfg, context, command);
            main.post(() -> {
                if (isFinishing()) return;
                if (!res.ok) hud.setState(HudView.State.ERROR);
                reply(res.text, replyLang);
                refreshStatus();
            });
        });
    }

    private String replyLang(CommandEngine.Lang detected) {
        if (detected == CommandEngine.Lang.TAMIL || "ta-IN".equals(langPref())) return "ta-IN";
        return "en-IN";
    }

    private void reply(String text, String lang) {
        bubble(false, text);
        boolean spoke = speaker.speak(text, lang);
        if (!spoke && hud.getState() != HudView.State.ERROR) hud.setState(HudView.State.IDLE);
        if (!spoke && !speaker.isReady()) status.setText("TEXT ONLY · NO TEXT-TO-SPEECH ENGINE");
    }

    // ------------------------------------------------------------------ voice

    private void toggleListening() {
        if (listening) { stopListening(); return; }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_PERMISSION);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status.setText("NO SPEECH RECOGNITION SERVICE ON THIS PHONE");
            reply("Speech recognition isn't available on this phone. You can still type.", "en-IN");
            return;
        }
        speaker.stop();
        if (speech != null) speech.destroy();
        speech = SpeechRecognizer.createSpeechRecognizer(this);
        speech.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle b) { setListening(true); status.setText("LISTENING…"); }
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float db) { hud.setLevel((db + 2f) / 12f); }
            @Override public void onBufferReceived(byte[] b) {}
            @Override public void onEndOfSpeech() { status.setText("PROCESSING VOICE…"); }
            @Override public void onError(int e) { setListening(false); status.setText(speechError(e)); }
            @Override public void onResults(Bundle b) {
                setListening(false);
                ArrayList<String> v = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (v != null && !v.isEmpty()) handle(v.get(0)); else status.setText("DIDN'T CATCH THAT · TRY AGAIN");
            }
            @Override public void onPartialResults(Bundle b) {
                ArrayList<String> v = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (v != null && !v.isEmpty()) input.setHint(v.get(0));
            }
            @Override public void onEvent(int type, Bundle b) {}
        });
        String l = langPref();
        String tag = "ta-IN".equals(l) ? "ta-IN" : "en-IN";
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        speech.startListening(i);
        setListening(true);
    }

    private void setListening(boolean on) {
        listening = on;
        hud.setState(on ? HudView.State.LISTENING : HudView.State.IDLE);
        mic.setBackground(bg(on ? 0xffff3b8a : 0xff037bb0, 0, 26));
        if (!on) { hud.setLevel(0); input.setHint("Ask JARVIS · தமிழ் · Tanglish"); }
    }

    private void stopListening() {
        if (speech != null) speech.stopListening();
        setListening(false);
        refreshStatus();
    }

    static String speechError(int e) {
        switch (e) {
            case SpeechRecognizer.ERROR_NO_MATCH: return "DIDN'T CATCH THAT · TRY AGAIN";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "NO SPEECH HEARD";
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "SPEECH NEEDS NETWORK OR OFFLINE LANGUAGE PACK";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "MICROPHONE PERMISSION REQUIRED";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "RECOGNIZER BUSY · TRY AGAIN";
            case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE:
            case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED: return "LANGUAGE NOT INSTALLED FOR SPEECH";
            default: return "VOICE ERROR " + e + " · TRY AGAIN";
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != MIC_PERMISSION) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) toggleListening();
        else status.setText("MICROPHONE PERMISSION DENIED · TYPE INSTEAD");
    }

    // ---------------------------------------------------------- customization

    private void openCustomization() {
        ScrollView sv = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));
        sv.addView(box);

        box.addView(label("Visual style"));
        RadioGroup styles = new RadioGroup(this);
        String[] names = {"JARVIS ARC reactor", "AIRA neural", "Agent Office", "APEX", "MIRA"};
        HudView.Style[] values = HudView.Style.values();
        for (int k = 0; k < values.length; k++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(names[k]);
            rb.setId(1000 + k);
            styles.addView(rb);
        }
        styles.check(1000 + hud.getStyle().ordinal());
        styles.setOnCheckedChangeListener((g, id) -> hud.setStyle(values[id - 1000])); // live preview
        box.addView(styles);

        box.addView(label("Voice & language"));
        Spinner lang = new Spinner(this);
        String[] langCodes = {"auto", "ta-IN", "en-IN"};
        lang.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
            new String[]{"English + Tanglish (auto)", "தமிழ் Tamil", "English (India)"}));
        lang.setSelection(Math.max(0, java.util.Arrays.asList(langCodes).indexOf(langPref())));
        box.addView(lang);
        Switch female = sw("Prefer female voice", prefs.getBoolean(K_FEMALE, true));
        box.addView(female);
        Spinner voice = new Spinner(this);
        List<String> voices = new ArrayList<>();
        voices.add("Automatic");
        voices.addAll(speaker.voicesFor("ta-IN"));
        voices.addAll(speaker.voicesFor("en-IN"));
        voice.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, voices));
        int vi = voices.indexOf(prefs.getString(K_VOICE, ""));
        voice.setSelection(Math.max(0, vi));
        box.addView(voice);

        box.addView(label("Display"));
        SeekBar bright = new SeekBar(this);
        bright.setMax(100);
        bright.setProgress((int) ((prefs.getFloat(K_BRIGHT, 1f) - .5f) / 1.1f * 100));
        box.addView(bright);
        Switch reduce = sw("Reduce motion", prefs.getBoolean(K_REDUCE, false));
        box.addView(reduce);

        box.addView(label("AI provider (your own key, stored encrypted on this phone)"));
        Spinner provider = new Spinner(this);
        provider.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
            new String[]{"Google Gemini", "OpenAI-compatible"}));
        AiClient.Config cfg = aiConfig();
        provider.setSelection(cfg.provider == AiClient.Provider.GEMINI ? 0 : 1);
        box.addView(provider);
        EditText base = field("Base URL", cfg.baseUrl, false);
        EditText model = field("Model (e.g. gemini-2.5-flash)", cfg.model, false);
        EditText key = field(vault.has() ? "API key saved ✓ (leave blank to keep)" : "API key", "", true);
        box.addView(base); box.addView(model); box.addView(key);
        provider.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                AiClient.Provider pr = pos == 0 ? AiClient.Provider.GEMINI : AiClient.Provider.OPENAI_COMPATIBLE;
                if (base.getText().toString().trim().isEmpty() || base.getText().toString().equals(AiClient.Config.defaultBaseUrl(
                        pr == AiClient.Provider.GEMINI ? AiClient.Provider.OPENAI_COMPATIBLE : AiClient.Provider.GEMINI)))
                    base.setText(AiClient.Config.defaultBaseUrl(pr));
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });
        Button clear = new Button(this);
        clear.setText("Clear conversation history");
        clear.setOnClickListener(v -> {
            history.clear(); transcript.removeAllViews(); prefs.edit().remove(K_HISTORY).apply();
            Toast.makeText(this, "Conversation history deleted", Toast.LENGTH_SHORT).show();
        });
        box.addView(clear);
        Button removeKey = new Button(this);
        removeKey.setText("Remove saved API key");
        removeKey.setOnClickListener(v -> { vault.save(""); key.setHint("API key"); refreshStatus(); });
        box.addView(removeKey);

        new AlertDialog.Builder(this)
            .setTitle("Customize JARVIS")
            .setView(sv)
            .setNegativeButton("Cancel", (d, w) -> hud.setStyle(styleFromPrefs()))
            .setOnCancelListener(d -> hud.setStyle(styleFromPrefs()))
            .setPositiveButton("Save", (d, w) -> {
                String url = base.getText().toString().trim();
                String err = url.isEmpty() ? null : AiClient.validUrl(url);
                if (err != null) { Toast.makeText(this, err, Toast.LENGTH_LONG).show(); return; }
                SharedPreferences.Editor e = prefs.edit()
                    .putString(K_STYLE, values[styles.getCheckedRadioButtonId() - 1000].name())
                    .putString(K_LANG, langCodes[lang.getSelectedItemPosition()])
                    .putBoolean(K_FEMALE, female.isChecked())
                    .putString(K_VOICE, voice.getSelectedItemPosition() == 0 ? "" : (String) voice.getSelectedItem())
                    .putFloat(K_BRIGHT, .5f + bright.getProgress() / 100f * 1.1f)
                    .putBoolean(K_REDUCE, reduce.isChecked())
                    .putString(K_PROVIDER, provider.getSelectedItemPosition() == 0 ? "GEMINI" : "OPENAI_COMPATIBLE")
                    .putString(K_BASE, url)
                    .putString(K_MODEL, model.getText().toString().trim());
                e.apply();
                String k = key.getText().toString().trim();
                if (!k.isEmpty() && !vault.save(k)) Toast.makeText(this, "Could not store key securely", Toast.LENGTH_LONG).show();
                applyPrefs();
                refreshStatus();
            })
            .show();
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(14), 0, dp(4));
        return t;
    }

    private Switch sw(String s, boolean on) {
        Switch w = new Switch(this);
        w.setText(s);
        w.setChecked(on);
        w.setMinHeight(dp(48));
        return w;
    }

    private EditText field(String hint, String value, boolean secret) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setSingleLine(true);
        e.setInputType(secret ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
            : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        return e;
    }

    // -------------------------------------------------------------- lifecycle

    @Override protected void onPause() {
        if (listening) stopListening();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (speech != null) speech.destroy();
        speaker.shutdown();
        worker.shutdownNow();
        super.onDestroy();
    }
}
