package com.adsjarvis.standalone;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.adsjarvis.standalone.core.AssistantState;
import com.adsjarvis.standalone.core.Brain;
import com.adsjarvis.standalone.core.GeminiClient;
import com.adsjarvis.standalone.core.JournalAnalyzer;
import com.adsjarvis.standalone.core.LocalIntents;
import com.adsjarvis.standalone.core.Memory;
import com.adsjarvis.standalone.core.MemoryEngine;
import com.adsjarvis.standalone.core.Tool;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Unified JARVIS home: top bar (menu · title/status · settings), GPU visual,
 * agent activity strip, expandable conversation, and the bottom dock
 * (attach · mic · text · send). Text and voice reach the same brain.
 */
public final class MainActivity extends Activity implements Jarvis.Ui {
    private static final int REQ_MIC = 44, REQ_IMAGE = 45, REQ_UNLOCK = 46, REQ_NOTIF = 47;
    static final int CYAN = 0xff68e3ff, MUTED = 0xff8fa6bd, PANEL = 0xcc0b1726;
    /** Last measured visual FPS / renderer, read by Diagnostics. */
    public static volatile float lastFps;
    public static volatile String renderer = "not started";

    private Jarvis jarvis;
    private SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private VisualEngine gl;
    private HudView canvasHud;
    private FrameLayout visualFrame;
    private TextView title, status, chips, activity, officePanel, attachChip;
    private Button stopBtn, expandBtn;
    private LinearLayout transcript, drawer, column;
    private ScrollView transcriptScroll;
    private EditText input;
    private Button mic;
    private Speaker speaker;
    private VoiceInput voice;
    private DeviceActions actions;
    private final List<Brain.Turn> history = new ArrayList<>();
    private String pendingImage;
    private boolean expanded, unlocked;

    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density + .5f); }

    static GradientDrawable bg(Activity a, int fill, int stroke, int radius) {
        float d = a.getResources().getDisplayMetrics().density;
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radius * d);
        if (stroke != 0) g.setStroke((int) d, stroke);
        return g;
    }

    private Button smallButton(String text, String desc) {
        Button b = new Button(this);
        b.setText(text); b.setAllCaps(false); b.setTextSize(18); b.setTextColor(CYAN);
        b.setBackground(bg(this, 0x00000000, 0, 18));
        b.setContentDescription(desc);
        b.setMinWidth(dp(48)); b.setMinHeight(dp(48)); b.setPadding(0, 0, 0, 0);
        return b;
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        installCrashLog();
        jarvis = Jarvis.get(this);
        prefs = jarvis.prefs;
        actions = new DeviceActions(this);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.BLACK);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, new FrameLayout.LayoutParams(-1, -1));

        // --- top bar
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(4), dp(4), dp(4), 0);
        Button menu = smallButton("☰", "menu");
        menu.setOnClickListener(v -> toggleDrawer(true));
        top.addView(menu, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout tcol = new LinearLayout(this);
        tcol.setOrientation(LinearLayout.VERTICAL);
        title = new TextView(this);
        title.setTextColor(Color.WHITE); title.setTextSize(20); title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER); title.setLetterSpacing(.12f);
        status = new TextView(this);
        status.setTextColor(CYAN); status.setTextSize(11); status.setGravity(Gravity.CENTER);
        status.setContentDescription("status");
        tcol.addView(title); tcol.addView(status);
        top.addView(tcol, new LinearLayout.LayoutParams(0, -2, 1f));
        Button gear = smallButton("⚙", "settings");
        gear.setOnClickListener(v -> openSettings(""));
        top.addView(gear, new LinearLayout.LayoutParams(dp(48), dp(48)));
        column.addView(top, new LinearLayout.LayoutParams(-1, -2));
        chips = new TextView(this);
        chips.setTextColor(MUTED); chips.setTextSize(10); chips.setGravity(Gravity.CENTER); chips.setLetterSpacing(.06f);
        chips.setContentDescription("indicators");
        column.addView(chips, new LinearLayout.LayoutParams(-1, -2));

        // --- visual
        visualFrame = new FrameLayout(this);
        column.addView(visualFrame, new LinearLayout.LayoutParams(-1, 0, 1f));
        createVisual();
        officePanel = new TextView(this);
        officePanel.setTextColor(0xddffffff); officePanel.setTextSize(11);
        officePanel.setPadding(dp(10), dp(6), dp(10), dp(6));
        officePanel.setBackground(bg(this, 0x99000000, 0xff1d435e, 10));
        FrameLayout.LayoutParams op = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        op.setMargins(dp(8), 0, 0, dp(6));
        visualFrame.addView(officePanel, op);
        View hit = new View(this);
        hit.setContentDescription("reactor");
        hit.setOnClickListener(v -> micTapped());
        hit.setOnLongClickListener(v -> { visualMenu(v); return true; });
        visualFrame.addView(hit, new FrameLayout.LayoutParams(-1, -1));

        // --- activity strip
        LinearLayout strip = new LinearLayout(this);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setPadding(dp(12), 0, dp(6), 0);
        activity = new TextView(this);
        activity.setTextColor(0xffbff8ff); activity.setTextSize(12);
        activity.setContentDescription("agent activity");
        strip.addView(activity, new LinearLayout.LayoutParams(0, -2, 1f));
        stopBtn = new Button(this);
        stopBtn.setText("Stop"); stopBtn.setAllCaps(false); stopBtn.setTextColor(Color.WHITE);
        stopBtn.setBackground(bg(this, 0xffb0304a, 0, 16)); stopBtn.setContentDescription("stop");
        stopBtn.setVisibility(View.GONE);
        stopBtn.setOnClickListener(v -> { jarvis.cancel(); speaker.stop(); });
        strip.addView(stopBtn, new LinearLayout.LayoutParams(-2, dp(40)));
        column.addView(strip, new LinearLayout.LayoutParams(-1, -2));

        // --- conversation
        LinearLayout ch = new LinearLayout(this);
        ch.setGravity(Gravity.CENTER_VERTICAL);
        ch.setPadding(dp(14), 0, dp(4), 0);
        TextView ct = new TextView(this);
        ct.setText("CONVERSATION"); ct.setTextColor(MUTED); ct.setTextSize(10); ct.setLetterSpacing(.1f);
        ch.addView(ct, new LinearLayout.LayoutParams(0, -2, 1f));
        expandBtn = smallButton("⌃", "expand history");
        expandBtn.setTextSize(16);
        expandBtn.setOnClickListener(v -> setExpanded(!expanded));
        ch.addView(expandBtn, new LinearLayout.LayoutParams(dp(48), dp(36)));
        column.addView(ch, new LinearLayout.LayoutParams(-1, -2));
        transcriptScroll = new ScrollView(this);
        transcript = new LinearLayout(this);
        transcript.setOrientation(LinearLayout.VERTICAL);
        transcript.setPadding(dp(12), 0, dp(12), dp(4));
        transcriptScroll.addView(transcript);
        transcriptScroll.setContentDescription("conversation");
        column.addView(transcriptScroll, new LinearLayout.LayoutParams(-1, dp(170)));

        attachChip = new TextView(this);
        attachChip.setTextColor(Color.WHITE); attachChip.setTextSize(12);
        attachChip.setPadding(dp(12), dp(4), dp(12), dp(4));
        attachChip.setBackground(bg(this, 0xff123a52, 0, 12));
        attachChip.setVisibility(View.GONE);
        attachChip.setOnClickListener(v -> { pendingImage = null; attachChip.setVisibility(View.GONE); });
        LinearLayout.LayoutParams acp = new LinearLayout.LayoutParams(-2, -2);
        acp.leftMargin = dp(12);
        column.addView(attachChip, acp);

        // --- dock
        LinearLayout dock = new LinearLayout(this);
        dock.setGravity(Gravity.CENTER_VERTICAL);
        dock.setPadding(dp(8), dp(6), dp(8), dp(8));
        dock.setBackground(bg(this, 0xee050b14, 0xff163049, 0));
        Button attach = smallButton("＋", "attach");
        attach.setOnClickListener(v -> pickImage());
        dock.addView(attach, new LinearLayout.LayoutParams(dp(44), dp(48)));
        mic = new Button(this);
        mic.setText("🎙"); mic.setTextSize(18);
        mic.setBackground(bg(this, 0xff037bb0, 0, 26));
        mic.setContentDescription("microphone");
        mic.setOnClickListener(v -> micTapped());
        dock.addView(mic, new LinearLayout.LayoutParams(dp(52), dp(52)));
        input = new EditText(this);
        input.setHint("Ask JARVIS · தமிழ் · Tanglish");
        input.setTextColor(Color.WHITE); input.setHintTextColor(MUTED); input.setTextSize(15);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setPadding(dp(14), dp(10), dp(14), dp(10));
        input.setBackground(bg(this, PANEL, 0xff21445d, 22));
        input.setContentDescription("message input");
        input.setOnEditorActionListener((v, id, ev) -> {
            if (id == EditorInfo.IME_ACTION_SEND || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER)) { send(); return true; }
            return false;
        });
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0, dp(52), 1f);
        ip.leftMargin = dp(8); ip.rightMargin = dp(8);
        dock.addView(input, ip);
        Button sendBtn = new Button(this);
        sendBtn.setText("➤"); sendBtn.setTextSize(18); sendBtn.setTextColor(Color.BLACK);
        sendBtn.setBackground(bg(this, CYAN, 0, 26)); sendBtn.setContentDescription("send");
        sendBtn.setOnClickListener(v -> send());
        dock.addView(sendBtn, new LinearLayout.LayoutParams(dp(52), dp(52)));
        column.addView(dock, new LinearLayout.LayoutParams(-1, -2));

        buildDrawer(root);

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int t, b, l, r;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets s = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                t = s.top; l = s.left; r = s.right; b = Math.max(s.bottom, ime.bottom);
            } else {
                t = insets.getSystemWindowInsetTop(); b = insets.getSystemWindowInsetBottom();
                l = insets.getSystemWindowInsetLeft(); r = insets.getSystemWindowInsetRight();
            }
            column.setPadding(l, t, r, b);
            drawer.setPadding(dp(16) + l, t + dp(12), dp(16), b);
            return insets;
        });
        setContentView(root);

        speaker = new Speaker(this, () -> main.post(this::refresh));
        speaker.setListener(speaking -> main.post(() -> onSpeaking(speaking)));
        voice = new VoiceInput(this);
        voice.setListener(voiceListener);
        jarvis.state.setListener((from, to, d) -> main.post(this::refresh));
        applyPrefs();
        loadHistory();
        if (history.isEmpty()) bubble(false, "Vanakkam! I'm " + name() + ". Tap the visual or 🎙 to talk, type below, "
            + "or long-press the visual to change style. ⚙ opens settings.");
        handleShare(getIntent());
        main.postDelayed(fpsTicker, 2000);
        refresh();
        if (prefs.getBoolean(Store.APP_LOCK, false) && state == null) requestUnlock(); else unlocked = true;
        if (state == null) {
            if (prefs.getBoolean(Store.STARTUP_GREET, false))
                main.postDelayed(() -> speaker.speak("Hello, I'm " + name() + ". " + jarvis.aiStatus().toLowerCase(Locale.ROOT) + ".", "en-IN"), 1200);
            if (prefs.getBoolean(Store.STARTUP_LISTEN, false))
                main.postDelayed(() -> { if (hasMic() && unlocked) voice.startPushToTalk(); }, 2500);
        }
    }

    // ------------------------------------------------------------------ visual

    private void createVisual() {
        String style = prefs.getString(Store.STYLE, "ARC");
        if (gl == null && canvasHud == null) {
            try {
                gl = new VisualEngine(this);
                gl.setFailureListener(reason -> fallbackToCanvas(reason));
                visualFrame.addView(gl, 0, new FrameLayout.LayoutParams(-1, -1));
                renderer = "OpenGL ES 2.0";
            } catch (RuntimeException e) {
                fallbackToCanvas(e.getClass().getSimpleName());
            }
        }
        applyVisualPrefs(style);
    }

    private void fallbackToCanvas(String reason) {
        Log.w("JARVIS", "GL visual unavailable, using canvas fallback: " + reason);
        if (gl != null) { visualFrame.removeView(gl); gl = null; }
        if (canvasHud == null) {
            canvasHud = new HudView(this);
            visualFrame.addView(canvasHud, 0, new FrameLayout.LayoutParams(-1, -1));
        }
        renderer = "Canvas fallback (" + reason + ")";
        applyVisualPrefs(prefs.getString(Store.STYLE, "ARC"));
    }

    private void applyVisualPrefs(String style) {
        float glow = prefs.getFloat(Store.GLOW, 1f), dens = prefs.getFloat(Store.DENSITY, 1f), spd = prefs.getFloat(Store.SPEED_V, 1f);
        boolean reduce = prefs.getBoolean(Store.REDUCE, false);
        if (gl != null) {
            gl.setStyle(style); gl.setGlow(glow * prefs.getFloat(Store.BRIGHT, 1f)); gl.setDensity(dens); gl.setSpeed(spd);
            gl.setReduceMotion(reduce); gl.setFpsCap(prefs.getInt(Store.FPS, 60));
            gl.setQuality(prefs.getFloat(Store.QUALITY, .75f)); gl.setBatterySaverAware(prefs.getBoolean(Store.SAVER, true));
            gl.setAccent(prefs.getInt(Store.ACCENT, 0x1AE6FF));
        }
        if (canvasHud != null) {
            try { canvasHud.setStyle(HudView.Style.valueOf(style)); } catch (Exception e) { canvasHud.setStyle(HudView.Style.ARC); }
            canvasHud.setBrightness(glow); canvasHud.setReduceMotion(reduce);
        }
        if (officePanel != null) {
            officePanel.setVisibility("OFFICE".equals(style) ? View.VISIBLE : View.GONE);
            updateOffice(null, false, "");
        }
    }

    private final String[] agentStatus = {"idle", "idle", "idle", "idle", "idle"};

    private void updateOffice(Tool.Agent a, boolean running, String what) {
        if (a != null) {
            agentStatus[a.ordinal()] = running ? "working: " + what : "done: " + what;
            if (gl != null) gl.setAgentActivity(a.ordinal(), running ? 1f : .35f);
        }
        StringBuilder sb = new StringBuilder("AGENT OFFICE · internal agents (real activity)\n");
        for (Tool.Agent ag : Tool.Agent.values()) sb.append("● ").append(ag.label).append(" — ").append(agentStatus[ag.ordinal()]).append('\n');
        officePanel.setText(sb.toString().trim());
    }

    private final Runnable fpsTicker = new Runnable() {
        @Override public void run() {
            if (gl != null) {
                lastFps = gl.getMeasuredFps();
                Log.i("JARVIS_FPS", "style=" + gl.getStyle() + " fps=" + String.format(Locale.ROOT, "%.1f", lastFps));
            }
            main.postDelayed(this, 5000);
        }
    };

    private void visualMenu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor, Gravity.CENTER);
        String[] styles = {"ARC", "AIRA", "APEX", "MIRA", "OFFICE"};
        String[] names = {"JARVIS ARC reactor", "AIRA neural plasma", "APEX cinematic", "MIRA holographic", "Agent Office"};
        for (int i = 0; i < styles.length; i++) pm.getMenu().add(0, i, i, names[i]);
        pm.getMenu().add(0, 99, 99, "Visual settings…");
        pm.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 99) { openSettings("visual"); return true; }
            jarvis.applySetting("visual_style", styles[item.getItemId()]);
            return true;
        });
        pm.show();
    }

    // ------------------------------------------------------------------ drawer

    private void buildDrawer(FrameLayout root) {
        drawer = new LinearLayout(this);
        drawer.setOrientation(LinearLayout.VERTICAL);
        drawer.setBackground(bg(this, 0xf2050b14, 0xff163049, 0));
        drawer.setVisibility(View.GONE);
        drawer.setClickable(true);
        TextView h = new TextView(this);
        h.setText("J.A.R.V.I.S."); h.setTextColor(Color.WHITE); h.setTextSize(18); h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setPadding(0, 0, 0, dp(12));
        drawer.addView(h);
        String[][] items = {{"New conversation", "new"}, {"Conversation history", "history"}, {"Memory", "memory"},
            {"Tasks & audit log", "agents"}, {"Trading journal & risk", "trading"}, {"Diagnostics", "diagnostics"},
            {"Settings", ""}, {"Close menu", "close"}};
        for (String[] it : items) {
            Button b = new Button(this);
            b.setText(it[0]); b.setAllCaps(false); b.setTextColor(0xffdff8ff); b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            b.setBackground(bg(this, 0x00000000, 0, 0));
            b.setOnClickListener(v -> {
                toggleDrawer(false);
                switch (it[1]) {
                    case "new":
                        history.clear(); transcript.removeAllViews(); prefs.edit().remove(Store.HISTORY).apply();
                        bubble(false, "New conversation started. Long-term memories are kept.");
                        break;
                    case "history": setExpanded(true); break;
                    case "close": break;
                    default: openSettings(it[1]);
                }
            });
            drawer.addView(b, new LinearLayout.LayoutParams(-1, dp(52)));
        }
        root.addView(drawer, new FrameLayout.LayoutParams(dp(290), -1, Gravity.START));
    }

    private void toggleDrawer(boolean show) { drawer.setVisibility(show ? View.VISIBLE : View.GONE); }

    private void setExpanded(boolean on) {
        expanded = on;
        LinearLayout.LayoutParams vp = (LinearLayout.LayoutParams) visualFrame.getLayoutParams();
        LinearLayout.LayoutParams tp = (LinearLayout.LayoutParams) transcriptScroll.getLayoutParams();
        if (on) { vp.weight = 0; vp.height = dp(110); tp.height = 0; tp.weight = 1; }
        else { vp.weight = 1; vp.height = 0; tp.weight = 0; tp.height = dp(170); }
        visualFrame.setLayoutParams(vp);
        transcriptScroll.setLayoutParams(tp);
        expandBtn.setText(on ? "⌄" : "⌃");
        transcriptScroll.post(() -> transcriptScroll.fullScroll(View.FOCUS_DOWN));
    }

    @Override public void onBackPressed() {
        if (drawer.getVisibility() == View.VISIBLE) { toggleDrawer(false); return; }
        if (expanded) { setExpanded(false); return; }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------ prefs & status

    String name() { return prefs.getString(Store.NAME, "JARVIS"); }

    private void applyPrefs() {
        title.setText(spaced(name()));
        speaker.setGender(prefs.getString(Store.VOICE_GENDER, prefs.getBoolean(Store.FEMALE, true) ? "female" : "any"));
        speaker.setVoiceName(prefs.getString(Store.VOICE, ""));
        speaker.setPitch(prefs.getFloat(Store.PITCH, 1f));
        speaker.setRate(prefs.getFloat(Store.SPEED, 1f));
        voice.setLanguage(inputLang());
        voice.setWake(prefs.getString(Store.WAKE_WORD, "jarvis"), prefs.getInt(Store.WAKE_SENS, 1));
        jarvis.memory.setEnabled(prefs.getBoolean(Store.MEM_ON, true));
        createVisual();
    }

    static String spaced(String n) {
        StringBuilder sb = new StringBuilder();
        for (char c : n.toUpperCase(Locale.ROOT).toCharArray()) { if (sb.length() > 0) sb.append('.'); sb.append(c); }
        return n.length() <= 8 ? sb.append('.').toString() : n.toUpperCase(Locale.ROOT);
    }

    private String inputLang() {
        String l = prefs.getString(Store.INPUT_LANG, "auto");
        if ("ta-IN".equals(l) || "tamil".equals(prefs.getString(Store.REPLY_LANG, "auto")) && "auto".equals(l)) return "ta-IN";
        return "en-IN";
    }

    private void refresh() {
        AssistantState.S s = jarvis.state.get();
        String voiceState = speaker != null && speaker.isReady() ? "VOICE READY" : "VOICE UNAVAILABLE";
        status.setText(s.name() + "  ·  " + jarvis.aiStatus());
        String wake = prefs.getBoolean(Store.WAKE, false)
            ? (voice != null && voice.isHandsFree() ? "WAKE \"" + prefs.getString(Store.WAKE_WORD, "jarvis").toUpperCase(Locale.ROOT) + "\" (IN-APP)" : "WAKE PAUSED")
            : "PUSH-TO-TALK";
        boolean micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        String style = prefs.getString(Store.STYLE, "ARC");
        chips.setText((jarvis.online() ? "NET ONLINE" : "NET OFFLINE") + "  ·  " + (micOk ? "MIC OK" : "MIC NOT GRANTED") + "  ·  "
            + wake + "  ·  " + voiceState + "  ·  " + style + "  ·  " + langLabel());
        int code;
        switch (s) {
            case LISTENING: code = 1; break;
            case THINKING: case RETRYING: code = 2; break;
            case SPEAKING: code = 3; break;
            case ERROR: code = 4; break;
            default: code = 0;
        }
        if (gl != null) gl.setStateCode(code);
        if (canvasHud != null) canvasHud.setState(code == 1 ? HudView.State.LISTENING : code == 2 ? HudView.State.THINKING
            : code == 3 ? HudView.State.SPEAKING : code == 4 ? HudView.State.ERROR : HudView.State.IDLE);
        stopBtn.setVisibility(jarvis.busy() || (speaker != null && speaker.isSpeaking()) ? View.VISIBLE : View.GONE);
    }

    private String langLabel() {
        switch (prefs.getString(Store.REPLY_LANG, "auto")) {
            case "tamil": return "தமிழ்";
            case "tanglish": return "TANGLISH";
            case "english": return "ENGLISH";
            default: return "AUTO LANG";
        }
    }

    // ------------------------------------------------------------------ history

    private void loadHistory() {
        try {
            JSONArray a = new JSONArray(prefs.getString(Store.HISTORY, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                history.add(new Brain.Turn(o.getBoolean("u"), o.getString("t")));
                addBubbleView(o.getBoolean("u"), o.getString("t"));
            }
        } catch (Exception ignored) { history.clear(); }
    }

    private void saveHistory() {
        JSONArray a = new JSONArray();
        int from = Math.max(0, history.size() - 60);
        try { for (Brain.Turn t : history.subList(from, history.size())) a.put(new JSONObject().put("u", t.user).put("t", t.text)); }
        catch (Exception ignored) {}
        prefs.edit().putString(Store.HISTORY, a.toString()).apply();
    }

    private void bubble(boolean user, String text) {
        history.add(new Brain.Turn(user, text));
        saveHistory();
        addBubbleView(user, text);
    }

    private void addBubbleView(boolean user, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setTextColor(user ? Color.WHITE : 0xffdff8ff);
        tv.setPadding(dp(12), dp(8), dp(12), dp(8));
        tv.setBackground(bg(this, user ? 0xcc0f3550 : PANEL, user ? 0 : 0xff1d435e, 14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.gravity = user ? Gravity.END : Gravity.START;
        lp.topMargin = dp(6);
        if (!user) tv.setOnLongClickListener(v -> { feedbackMenu(v, text); return true; });
        transcript.addView(tv, lp);
        while (transcript.getChildCount() > 60) transcript.removeViewAt(0);
        transcriptScroll.post(() -> transcriptScroll.fullScroll(View.FOCUS_DOWN));
    }

    /** Owner feedback becomes memory the brain retrieves later (measurable learning, no self-modification). */
    private void feedbackMenu(View anchor, String reply) {
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add(0, 1, 1, "👍 Helpful");
        pm.getMenu().add(0, 2, 2, "👎 Not helpful");
        pm.getMenu().add(0, 3, 3, "✏️ Correct this answer…");
        pm.setOnMenuItemClickListener(item -> {
            String snippet = reply.length() > 160 ? reply.substring(0, 160) + "…" : reply;
            if (item.getItemId() == 3) {
                EditText e = new EditText(this);
                e.setHint("What is the correct answer or preference?");
                new AlertDialog.Builder(this).setTitle("Correct JARVIS").setView(e)
                    .setPositiveButton("Save correction", (d, w) -> {
                        String c = e.getText().toString().trim();
                        if (c.isEmpty()) return;
                        jarvis.memory.commit(jarvis.memory.propose(Memory.Category.CORRECTION, "", "Owner corrected \"" + snippet + "\": " + c, "user"));
                        Toast.makeText(this, "Correction saved to memory", Toast.LENGTH_SHORT).show();
                    }).setNegativeButton("Cancel", null).show();
            } else {
                boolean good = item.getItemId() == 1;
                jarvis.memory.commit(jarvis.memory.propose(Memory.Category.FEEDBACK, "",
                    (good ? "Owner found helpful: " : "Owner found NOT helpful: ") + snippet, "user"));
                Toast.makeText(this, "Feedback saved", Toast.LENGTH_SHORT).show();
            }
            return true;
        });
        pm.show();
    }

    // ------------------------------------------------------------------ input

    private void send() {
        String q = input.getText().toString().trim();
        if (q.isEmpty() && pendingImage == null) return;
        input.setText("");
        handle(q.isEmpty() ? "Analyse this image." : q);
    }

    private String replyLang(CommandEngine.Lang detected) {
        String pref = prefs.getString(Store.REPLY_LANG, "auto");
        if ("tamil".equals(pref) || ("auto".equals(pref) && detected == CommandEngine.Lang.TAMIL)) return "ta-IN";
        return "en-IN";
    }

    void handle(String text) {
        bubble(true, pendingImage != null ? text + "  📷" : text);
        CommandEngine.Lang lang = CommandEngine.detectLanguage(text);
        String rl = replyLang(lang);

        // 1. Instant on-device intents.
        LocalIntents.Match li = LocalIntents.match(text);
        if (li != null && pendingImage == null) { localIntent(li, rl); return; }

        // 2. Device commands (offline-capable).
        CommandEngine.Result r = CommandEngine.run(text, prefs.getString("memory", ""));
        if (!r.askAi && pendingImage == null) {
            if (r.action == CommandEngine.Action.SAVE_MEMORY) {
                prefs.edit().putString("memory", r.memoryValue).apply();
                jarvis.memory.commit(jarvis.memory.propose(Memory.Category.KNOWLEDGE, "", r.memoryValue, "user"));
            }
            if (r.action == CommandEngine.Action.CLEAR_MEMORY) prefs.edit().remove("memory").apply();
            String answer = actions.run(r);
            if (r.action == CommandEngine.Action.NONE && r.response.startsWith("I remember")) answer = r.response + recentMemories();
            reply(answer, rl);
            return;
        }

        // 3. AI brain (text, voice and images all arrive here).
        if (jarvis.isGemini() && jarvis.gemini() != null && jarvis.online()) {
            String img = pendingImage;
            pendingImage = null;
            attachChip.setVisibility(View.GONE);
            activity.setText("🧠 Thinking…");
            refresh();
            List<Brain.Turn> ctx = new ArrayList<>(history.subList(0, Math.max(0, history.size() - 1)));
            jarvis.ask(text, img, ctx, res -> {
                activity.setText(res.ok ? (res.tools.isEmpty() ? "" : "Tools used: " + String.join(", ", res.tools) + " · " + res.model) : "⚠ " + res.error);
                reply(res.text, rl);
            });
            return;
        }
        if (!jarvis.isGemini() && !jarvis.vault.load().isEmpty() && jarvis.online()) { legacyChat(text, rl); return; }

        // 4. Honest offline / not-connected fallback with useful local work.
        reply(offlineAnswer(text), rl);
    }

    private String recentMemories() {
        List<Memory> all = jarvis.memory.repo().all();
        if (all.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\nLong-term memories:");
        for (int i = 0; i < Math.min(5, all.size()); i++) sb.append("\n• ").append(all.get(i).text);
        return sb.toString();
    }

    private String offlineAnswer(String text) {
        String q = text.toLowerCase(Locale.ROOT);
        String why = !jarvis.online() ? "You're offline, so cloud AI is unavailable." : "Cloud AI is not connected (" + jarvis.aiStatus() + ").";
        if (q.contains("journal")) {
            String csv = prefs.getString(Store.T_JOURNAL, "");
            if (csv.isEmpty()) return why + " No trade journal imported yet — share a CSV to JARVIS or use Settings → Trading AI → Import journal.";
            JSONObject a = JournalAnalyzer.analyze(csv, AndroidTools.rules(prefs));
            return "Journal check (on-device, no AI needed):\n" + summarizeJournal(a);
        }
        if (q.contains("pb7")) return "PB7 PAPER bot is NOT CONNECTED. Add its read-only status URL in Settings → Trading AI. " + why;
        if (q.contains("chart") || pendingImage != null)
            return why + " Chart analysis needs Gemini vision: attach a TradingView screenshot (＋) and connect your key in Settings → AI Brain. "
                + "Any plan will still pass the on-device risk gate (≥70% confidence, ₹1,200 risk, after 09:30 IST).";
        if (q.contains("plan") || q.contains("permission"))
            return why + " Planning needs AI. What I can use right now:\n" + jarvis.tools.capabilitySummary(jarvis.online());
        return why + " Offline I can still: tell time/date, set alarms, timers and reminders, open apps, control torch/volume, "
            + "check battery, remember notes, and audit your trade journal.";
    }

    static String summarizeJournal(JSONObject a) {
        StringBuilder sb = new StringBuilder();
        sb.append(a.optInt("trades")).append(" trades · win rate ").append(a.optDouble("win_rate_pct")).append("% · net ₹")
            .append(a.optLong("net_pnl")).append(" · max drawdown ₹").append(a.optLong("max_drawdown")).append('\n');
        JSONArray e = a.optJSONArray("data_errors"), v = a.optJSONArray("rule_violations"), h = a.optJSONArray("improvement_hypotheses");
        if (e != null && e.length() > 0) { sb.append("Data errors:\n"); for (int i = 0; i < e.length(); i++) sb.append("• ").append(e.optString(i)).append('\n'); }
        if (v != null && v.length() > 0) { sb.append("Rule violations:\n"); for (int i = 0; i < v.length(); i++) sb.append("• ").append(v.optString(i)).append('\n'); }
        if (h != null && h.length() > 0) { sb.append("Hypotheses to test (paper/walk-forward):\n"); for (int i = 0; i < h.length(); i++) sb.append("• ").append(h.optString(i)).append('\n'); }
        return sb.toString().trim();
    }

    private void legacyChat(String text, String rl) {
        AiClient.Config cfg = new AiClient.Config(AiClient.Provider.OPENAI_COMPATIBLE, prefs.getString(Store.BASE, ""),
            prefs.getString(Store.MODEL, ""), jarvis.vault.load());
        List<AiClient.Turn> ctx = new ArrayList<>();
        for (Brain.Turn t : history) ctx.add(new AiClient.Turn(t.user, t.text));
        jarvis.state.to(AssistantState.S.THINKING, "");
        refresh();
        new Thread(() -> {
            AiClient.Reply res = new AiClient().chat(cfg, ctx.subList(0, Math.max(0, ctx.size() - 1)), text);
            main.post(() -> { jarvis.state.to(res.ok ? AssistantState.S.READY : AssistantState.S.ERROR, ""); reply(res.text, rl); });
        }).start();
    }

    private void localIntent(LocalIntents.Match li, String rl) {
        boolean ta = "ta-IN".equals(rl);
        switch (li.kind) {
            case NAME:
                reply(ta ? "நான் " + name() + ", உங்கள் தனிப்பட்ட AI உதவியாளர்." : "I'm " + name() + ", your personal AI assistant.", rl);
                break;
            case STOP: speaker.stop(); jarvis.cancel(); reply("Stopped.", rl); break;
            case OPEN_JARVIS_SETTINGS: reply(ta ? "Settings திறக்கிறேன்." : "Opening settings.", rl); openSettings(""); break;
            case OPEN_PHONE_SETTINGS: reply(actions.run(CommandEngine.run("open settings", "")), rl); break;
            case SET_VISUAL: jarvis.applySetting("visual_style", li.value); reply("Visual changed to " + li.value + ".", rl); break;
            case SET_VOICE_GENDER: {
                jarvis.applySetting("voice_gender", li.value);
                speaker.setGender(li.value);
                String v = speaker.genderVoice(rl);
                reply(v.isEmpty()
                    ? "Voice preference set to " + li.value + ". No installed voice is labelled " + li.value + " for this language, so I'm "
                        + "adjusting pitch on the default voice. Pick a specific voice in Settings → Voice."
                    : "Voice changed to " + li.value + " (" + v + ").", rl);
                break;
            }
            case SET_REPLY_LANG:
                jarvis.applySetting("reply_language", li.value);
                reply("tamil".equals(li.value) ? "சரி, இனி தமிழில் பேசுகிறேன்." : "tanglish".equals(li.value)
                    ? "Sari, ini naan Tanglish-la pesuren!" : "Okay, I'll reply in English.", "tamil".equals(li.value) ? "ta-IN" : "en-IN");
                break;
            case REMEMBER_PREF_LANG: {
                String lang = prefs.getString(Store.REPLY_LANG, "auto");
                String label = "auto".equals(lang) ? "the same language I use (auto)" : lang;
                MemoryEngine.Proposal p = jarvis.memory.propose(Memory.Category.PREFERENCE, "preferred_language",
                    "Owner's preferred reply language: " + label, "user");
                approveMemory(p, () -> reply("Saved: your preferred language is " + label + ". I'll remember this after restarts.", rl));
                break;
            }
            case CAPABILITIES:
                reply("Status: " + jarvis.aiStatus() + "\nI can use:\n" + jarvis.tools.capabilitySummary(jarvis.online())
                    + "\nSensitive actions always ask you first; payments, OTPs and live trading are never automated.", rl);
                break;
        }
        refresh();
    }

    private void approveMemory(MemoryEngine.Proposal p, Runnable after) {
        if (prefs.getBoolean(Store.MEM_AUTO, false) || p.outcome == MemoryEngine.Outcome.DUPLICATE) { jarvis.memory.commit(p); after.run(); return; }
        new AlertDialog.Builder(this).setTitle("Save to memory?").setMessage(p.describe())
            .setPositiveButton("Save", (d, w) -> { jarvis.memory.commit(p); after.run(); })
            .setNegativeButton("Don't save", (d, w) -> reply("Okay, not saved.", "en-IN"))
            .setCancelable(false).show();
    }

    private void reply(String text, String lang) {
        bubble(false, text);
        boolean spoke = prefs.getBoolean(Store.SPEAK_REPLIES, true) && speaker.speak(text, lang);
        if (!spoke && jarvis.state.get() != AssistantState.S.ERROR) jarvis.state.to(jarvis.online() ? AssistantState.S.READY : AssistantState.S.OFFLINE, "");
        refresh();
    }

    private void onSpeaking(boolean speaking) {
        if (speaking) { jarvis.state.to(AssistantState.S.SPEAKING, ""); voice.pauseHandsFree(true); }
        else {
            jarvis.state.to(jarvis.online() ? AssistantState.S.READY : AssistantState.S.OFFLINE, "");
            if (prefs.getBoolean(Store.CONVO, false) && hasMic()) voice.startPushToTalk();
            else if (prefs.getBoolean(Store.WAKE, false) && hasMic()) voice.pauseHandsFree(false);
        }
        refresh();
    }

    // ------------------------------------------------------------------ voice

    private boolean hasMic() { return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED; }

    private void micTapped() {
        if (speaker.isSpeaking()) speaker.stop();           // barge-in
        if (voice.isActive() && !voice.isHandsFree()) { voice.stop(); refresh(); return; }
        if (!hasMic()) { requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC); return; }
        if (!VoiceInput.available(this)) { reply("Speech recognition isn't available on this phone. You can still type.", "en-IN"); return; }
        voice.setLanguage(inputLang());
        voice.startPushToTalk();
    }

    private final VoiceInput.Listener voiceListener = new VoiceInput.Listener() {
        @Override public void onListening(boolean on, boolean handsFree) {
            if (on && !handsFree) jarvis.state.to(AssistantState.S.LISTENING, "");
            else if (!on && jarvis.state.get() == AssistantState.S.LISTENING) jarvis.state.to(jarvis.online() ? AssistantState.S.READY : AssistantState.S.OFFLINE, "");
            mic.setBackground(bg(MainActivity.this, on && !handsFree ? 0xffff3b8a : 0xff037bb0, 0, 26));
            if (on && !handsFree) status.setText("LISTENING…");
            refresh();
        }
        @Override public void onLevel(float l) { if (gl != null) gl.setLevel(l); if (canvasHud != null) canvasHud.setLevel(l); }
        @Override public void onPartial(String t) { input.setHint(t); }
        @Override public void onCommand(String t) {
            input.setHint("Ask JARVIS · தமிழ் · Tanglish");
            if (t.trim().isEmpty()) { speaker.speak("Yes?", "en-IN"); voice.startPushToTalk(); return; }
            handle(t);
        }
        @Override public void onError(String m, int code) { activity.setText("🎙 " + m); refresh(); }
    };

    // ------------------------------------------------------------------ attachments & sharing

    private void pickImage() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
        try { startActivityForResult(Intent.createChooser(i, "Attach chart screenshot"), REQ_IMAGE); }
        catch (Exception e) { Toast.makeText(this, "No image picker available", Toast.LENGTH_SHORT).show(); }
    }

    private void attachImage(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            Bitmap b = BitmapFactory.decodeStream(in);
            if (b == null) throw new IllegalStateException("not an image");
            float s = Math.min(1f, 1280f / Math.max(b.getWidth(), b.getHeight()));
            if (s < 1f) b = Bitmap.createScaledBitmap(b, (int) (b.getWidth() * s), (int) (b.getHeight() * s), true);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.JPEG, 85, bo);
            pendingImage = Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
            attachChip.setText("📷 Image attached (" + (bo.size() / 1024) + " KB) · tap to remove");
            attachChip.setVisibility(View.VISIBLE);
        } catch (Exception e) {
            Toast.makeText(this, "Could not read that image", Toast.LENGTH_SHORT).show();
        }
    }

    private void handleShare(Intent i) {
        if (i == null || !Intent.ACTION_SEND.equals(i.getAction())) return;
        String type = i.getType() == null ? "" : i.getType();
        if (type.startsWith("image/")) {
            Uri u = i.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) { attachImage(u); bubble(false, "Image received. Ask me about it (e.g. \"Analyze this NIFTY chart\")."); }
            return;
        }
        String text = i.getStringExtra(Intent.EXTRA_TEXT);
        Uri u = i.getParcelableExtra(Intent.EXTRA_STREAM);
        if (text == null && u != null) {
            try (InputStream in = getContentResolver().openInputStream(u)) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] b = new byte[8192]; int n;
                while ((n = in.read(b)) > 0 && bo.size() < 2_000_000) bo.write(b, 0, n);
                text = bo.toString("UTF-8");
            } catch (Exception e) { text = null; }
        }
        if (text == null) return;
        String head = text.split("\n", 2)[0].toLowerCase(Locale.ROOT);
        if (head.contains("entry") && head.contains("exit")) {
            prefs.edit().putString(Store.T_JOURNAL, text).apply();
            int rows = text.split("\n").length - 1;
            bubble(false, "Trade journal imported (" + rows + " rows, stored only on this phone). Ask: \"Find errors in my previous trading journal\".");
        } else {
            input.setText(text.length() > 2000 ? text.substring(0, 2000) : text);
        }
    }

    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); handleShare(intent); }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_IMAGE && res == RESULT_OK && data != null && data.getData() != null) attachImage(data.getData());
        if (req == REQ_UNLOCK) { if (res == RESULT_OK) unlocked = true; else finish(); }
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_MIC) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) micTapped();
            else activity.setText("🎙 Microphone permission denied — you can still type.");
        }
        refresh();
    }

    // ------------------------------------------------------------------ Jarvis.Ui

    @Override public void onSettingChanged(String name) {
        applyPrefs();
        if ("visual_style".equals(name)) applyVisualPrefs(prefs.getString(Store.STYLE, "ARC"));
        refresh();
    }

    @Override public void openSettings(String section) {
        startActivity(new Intent(this, SettingsActivity.class).putExtra("section", section == null ? "" : section));
    }

    @Override public void onAgent(Tool tool, boolean running, String detail) {
        activity.setText((running ? "⚙ " : "✓ ") + tool.agent().label + " → " + tool.name() + (running ? "…" : ": " + detail));
        updateOffice(tool.agent(), running, tool.name());
    }

    @Override public void askApproval(Tool tool, JSONObject args, String preview, java.util.function.Consumer<Boolean> decision) {
        String tier = tool.tier() == Tool.Tier.RED ? "RED — sensitive / external" : "YELLOW — changes data or opens another app";
        new AlertDialog.Builder(this)
            .setTitle("Approve: " + tool.name() + "?")
            .setMessage(tool.agent().label + " wants to run this.\nTier: " + tier + "\n\n" + preview)
            .setPositiveButton("Approve", (d, w) -> decision.accept(true))
            .setNegativeButton("Deny", (d, w) -> decision.accept(false))
            .setCancelable(false).show();
    }

    // ------------------------------------------------------------------ lifecycle

    private void requestUnlock() {
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        @SuppressWarnings("deprecation")
        Intent i = km == null ? null : km.createConfirmDeviceCredentialIntent("Unlock JARVIS", "Confirm it's you");
        if (i == null) { unlocked = true; Toast.makeText(this, "Set a screen lock to use App lock", Toast.LENGTH_LONG).show(); return; }
        startActivityForResult(i, REQ_UNLOCK);
    }

    private void installCrashLog() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        if (prev instanceof CrashHandler) return;
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(getFilesDir(), prev));
    }

    static final class CrashHandler implements Thread.UncaughtExceptionHandler {
        private final File dir; private final Thread.UncaughtExceptionHandler prev;
        CrashHandler(File dir, Thread.UncaughtExceptionHandler prev) { this.dir = dir; this.prev = prev; }
        @Override public void uncaughtException(Thread t, Throwable e) {
            try (PrintWriter w = new PrintWriter(new FileOutputStream(new File(dir, "crash.txt"), true))) {
                w.println("=== " + new java.util.Date() + " thread=" + t.getName());
                e.printStackTrace(w);
            } catch (Exception ignored) {}
            if (prev != null) prev.uncaughtException(t, e);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        jarvis.attach(this);
        if (gl != null) gl.onResume();
        applyPrefs();
        if (prefs.getBoolean(Store.WAKE, false) && hasMic() && unlocked) voice.startHandsFree();
        refresh();
    }

    @Override protected void onPause() {
        voice.stop();                    // never listen while not visible
        if (gl != null) gl.onPause();
        jarvis.detach(this);
        super.onPause();
    }

    @Override protected void onDestroy() {
        main.removeCallbacks(fpsTicker);
        voice.release();
        speaker.shutdown();
        super.onDestroy();
    }
}
