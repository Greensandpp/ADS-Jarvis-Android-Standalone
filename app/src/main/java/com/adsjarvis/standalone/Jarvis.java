package com.adsjarvis.standalone;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import com.adsjarvis.standalone.core.AssistantState;
import com.adsjarvis.standalone.core.Brain;
import com.adsjarvis.standalone.core.Diagnostics;
import com.adsjarvis.standalone.core.GeminiClient;
import com.adsjarvis.standalone.core.MemoryEngine;
import com.adsjarvis.standalone.core.Tool;
import com.adsjarvis.standalone.core.ToolRegistry;
import com.adsjarvis.standalone.core.ToolResult;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * App-wide controller: AI provider, brain, tools, memory, state machine.
 * One instance per process; activities attach a {@link Ui}.
 */
public final class Jarvis implements AndroidTools.Host {
    public interface Ui {
        void onSettingChanged(String name);
        void openSettings(String section);
        void onAgent(Tool tool, boolean running, String detail);
        /** Shown on the main thread; return value is the owner's decision. */
        void askApproval(Tool tool, JSONObject args, String preview, java.util.function.Consumer<Boolean> decision);
    }

    public static final class Reply {
        public final String text; public final boolean ok; public final List<String> tools; public final String model;
        public final GeminiClient.Kind error;
        Reply(String text, boolean ok, List<String> tools, String model, GeminiClient.Kind error) {
            this.text = text; this.ok = ok; this.tools = tools; this.model = model; this.error = error;
        }
    }

    private static Jarvis instance;
    public static synchronized Jarvis get(Context c) {
        if (instance == null) instance = new Jarvis(c.getApplicationContext());
        return instance;
    }

    public final Context app;
    public final SharedPreferences prefs;
    public final KeyVault vault;
    public final MemoryEngine memory;
    public final ToolRegistry tools;
    public final AssistantState state = new AssistantState();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile GeminiClient.CancelToken current;
    private volatile Ui ui;

    private Jarvis(Context c) {
        app = c;
        prefs = Store.prefs(c);
        vault = new KeyVault(prefs);
        memory = new MemoryEngine(JarvisDb.get(c).memories);
        memory.setEnabled(prefs.getBoolean(Store.MEM_ON, true));
        // Semantic memory: Gemini embeddings when a model was discovered and the phone is online.
        java.util.concurrent.ExecutorService embedPool = Executors.newSingleThreadExecutor();
        memory.setEmbedder(text -> {
            GeminiClient g = gemini();
            String model = prefs.getString(Store.EMBED_MODEL, "");
            if (g == null || model.isEmpty() || !online()) return null;
            return g.embed(model, text, null);
        }, embedPool);
        tools = AndroidTools.build(c, memory, this);
        for (String d : prefs.getString("tools_disabled", "").split(",")) if (!d.isEmpty() && tools.get(d) != null) tools.setEnabled(d, false);
        state.to(online() ? AssistantState.S.READY : AssistantState.S.OFFLINE, "start");
        // Watchdog: a request can never leave the assistant stuck in THINKING.
        main.postDelayed(new Runnable() {
            @Override public void run() {
                if (state.enforceDeadline()) { GeminiClient.CancelToken t = current; if (t != null) t.cancel(); }
                main.postDelayed(this, 5000);
            }
        }, 5000);
    }

    public void attach(Ui u) { ui = u; }
    public void detach(Ui u) { if (ui == u) ui = null; }

    // ------------------------------------------------------------------ Host

    @Override public void applySetting(String name, String value) {
        SettingsModel.apply(prefs, name, value);
        main.post(() -> { Ui u = ui; if (u != null) u.onSettingChanged(name); });
    }

    @Override public void openJarvisSettings(String section) {
        main.post(() -> { Ui u = ui; if (u != null) u.openSettings(section); });
    }

    public boolean isGemini() { return !"OPENAI_COMPATIBLE".equals(prefs.getString(Store.PROVIDER, "GEMINI")); }

    @Override public GeminiClient gemini() {
        if (!isGemini()) return null;
        String key = vault.load();
        if (key.isEmpty()) return null;
        return new GeminiClient(prefs.getString(Store.BASE, GeminiClient.DEFAULT_BASE), key);
    }

    public String preferredModel() { return prefs.getString(Store.MODEL, "gemini-2.5-flash"); }

    public List<GeminiClient.ModelInfo> discovered() {
        List<GeminiClient.ModelInfo> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString(Store.DISCOVERED, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(newModel(o.getString("id"), o.optBoolean("gen"), o.optBoolean("emb")));
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static GeminiClient.ModelInfo newModel(String id, boolean gen, boolean emb) {
        return new GeminiClient.ModelInfo(id, id, gen, emb);
    }

    @Override public List<String> models() {
        if (!prefs.getBoolean(Store.FALLBACK, true)) return Arrays.asList(preferredModel());
        List<String> order = GeminiClient.fallbackOrder(prefs.getBoolean(Store.MODEL_AUTO, false) ? "" : preferredModel(), discovered());
        return order.isEmpty() ? Arrays.asList(preferredModel()) : order;
    }

    @Override public boolean online() {
        ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
        return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    /** ONLINE is shown only after a verified request; otherwise the honest state. */
    public String aiStatus() {
        if (isGemini() && vault.load().isEmpty()) return "AI NOT CONNECTED";
        if (!isGemini() && vault.load().isEmpty()) return "AI NOT CONNECTED";
        if (!online()) return "AI OFFLINE · NO NETWORK";
        String err = prefs.getString("ai_last_error_kind", "");
        if (!err.isEmpty()) return "AI ERROR · " + err;
        if (prefs.getBoolean(Store.VERIFIED, false)) return "AI ONLINE · " + prefs.getString(Store.LAST_OK_MODEL, preferredModel());
        return "AI NOT VERIFIED · run test";
    }

    public boolean busy() { return current != null; }

    public void cancel() {
        GeminiClient.CancelToken t = current;
        if (t != null) t.cancel();
    }

    // ------------------------------------------------------------------ requests

    private void recordError(GeminiClient.GeminiException e) {
        try {
            JSONArray a = new JSONArray(prefs.getString(Store.LAST_ERR, "[]"));
            a.put(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(new Date()) + " · " + e.kind
                + (e.httpStatus > 0 ? " HTTP " + e.httpStatus : "") + " · " + GeminiClient.redact(e.getMessage()));
            while (a.length() > 30) a.remove(0);
            prefs.edit().putString(Store.LAST_ERR, a.toString())
                .putString("ai_last_error_kind", e.kind == GeminiClient.Kind.CANCELLED ? "" : e.kind.name()).apply();
        } catch (Exception ignored) {}
    }

    private void recordOk(String model) {
        prefs.edit().putLong(Store.LAST_OK, System.currentTimeMillis()).putString(Store.LAST_OK_MODEL, model)
            .putBoolean(Store.VERIFIED, true).putString("ai_last_error_kind", "").apply();
    }

    /** Runs the AI brain off the main thread; callback on the main thread. */
    public void ask(String text, String imageB64, List<Brain.Turn> history, java.util.function.Consumer<Reply> done) {
        GeminiClient g = gemini();
        if (g == null) { done.accept(new Reply("", false, new ArrayList<>(), "", GeminiClient.Kind.NO_KEY)); return; }
        GeminiClient.CancelToken tok = new GeminiClient.CancelToken();
        current = tok;
        state.to(AssistantState.S.THINKING, "ai");
        long t0 = System.currentTimeMillis();
        worker.execute(() -> {
            Reply reply;
            try {
                Brain.Request r = new Brain.Request();
                r.userText = text;
                r.history = history;
                r.imageBase64 = imageB64;
                r.assistantName = prefs.getString(Store.NAME, "JARVIS");
                r.replyLanguage = prefs.getString(Store.REPLY_LANG, "auto");
                r.responseLength = prefs.getString(Store.RESP_LEN, "short");
                r.online = online();
                r.approveYellow = prefs.getBoolean(Store.APPROVE_YELLOW, true);
                r.deviceTime = DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.SHORT).format(new Date());
                r.memories = memory.contextFor(text, memory.embedQuery(text), 6);
                Brain brain = new Brain(g, tools, models())
                    .approver(this::blockingApprove)
                    .events(new Brain.Events() {
                        public void onToolStart(Tool t, JSONObject a) { main.post(() -> { Ui u = ui; if (u != null) u.onAgent(t, true, a.toString()); }); }
                        public void onToolEnd(Tool t, ToolResult res) { main.post(() -> { Ui u = ui; if (u != null) u.onAgent(t, false, res.summary); }); }
                    })
                    .audit((tool, agent, tier, args, approved, ok, summary) -> JarvisDb.get(app).audit(tool, agent, tier, args, approved, ok, summary));
                Brain.Answer a = brain.run(r, tok);
                recordOk(a.model);
                reply = new Reply(a.text, true, a.toolsUsed, a.model, null);
            } catch (GeminiClient.GeminiException e) {
                recordError(e);
                reply = new Reply(e.userMessage(), false, new ArrayList<>(), "", e.kind);
            } catch (RuntimeException e) {
                reply = new Reply("Internal error: " + e.getClass().getSimpleName(), false, new ArrayList<>(), "", GeminiClient.Kind.PARSE);
            }
            Reply fr = reply;
            JarvisDb.get(app).task(text, String.join(", ", fr.tools), fr.text, fr.ok, fr.model, System.currentTimeMillis() - t0);
            main.post(() -> {
                current = null;
                if (fr.error == GeminiClient.Kind.CANCELLED) state.to(AssistantState.S.CANCELLED, "");
                else state.to(fr.ok ? AssistantState.S.READY : AssistantState.S.ERROR, fr.ok ? "" : String.valueOf(fr.error));
                done.accept(fr);
            });
        });
    }

    /** Called on the brain worker thread; waits for the owner's tap (2 min timeout = deny). */
    private boolean blockingApprove(Tool t, JSONObject args) {
        if ("remember".equals(t.name()) && prefs.getBoolean(Store.MEM_AUTO, false)) return true;
        Ui u = ui;
        if (u == null) return false;
        String preview = previewFor(t, args);
        AtomicBoolean ok = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);
        main.post(() -> u.askApproval(t, args, preview, v -> { ok.set(v); latch.countDown(); }));
        try { if (!latch.await(120, TimeUnit.SECONDS)) return false; } catch (InterruptedException e) { return false; }
        return ok.get();
    }

    String previewFor(Tool t, JSONObject a) {
        if ("remember".equals(t.name())) {
            try {
                com.adsjarvis.standalone.core.Memory.Category cat = com.adsjarvis.standalone.core.Memory.Category.valueOf(a.optString("category", "KNOWLEDGE"));
                return memory.propose(cat, a.optString("key"), a.optString("text"), "assistant").describe();
            } catch (Exception e) { return "Remember: " + a.optString("text"); }
        }
        StringBuilder sb = new StringBuilder();
        java.util.Iterator<String> it = a.keys();
        while (it.hasNext()) { String k = it.next(); sb.append(k).append(": ").append(a.opt(k)).append('\n'); }
        return sb.toString().trim();
    }

    public void diagnostics(java.util.function.Consumer<Diagnostics.Report> done) {
        GeminiClient g = gemini();
        worker.execute(() -> {
            Diagnostics.Report rep;
            if (g == null) {
                rep = new Diagnostics.Report();
            } else {
                rep = new Diagnostics().run(g, prefs.getString(Store.BASE, GeminiClient.DEFAULT_BASE), preferredModel());
                if (!rep.models.isEmpty()) {
                    JSONArray a = new JSONArray();
                    String embed = "";
                    for (GeminiClient.ModelInfo m : rep.models) {
                        try { a.put(new JSONObject().put("id", m.id).put("gen", m.generate).put("emb", m.embed)); } catch (Exception ignored) {}
                        if (m.embed && embed.isEmpty()) embed = m.id;
                    }
                    prefs.edit().putString(Store.DISCOVERED, a.toString()).putString(Store.EMBED_MODEL, embed).apply();
                }
                if (rep.online) { recordOk(rep.workingModel); memory.backfillEmbeddings(200); }
                else prefs.edit().putBoolean(Store.VERIFIED, false).apply();
            }
            Diagnostics.Report fr = rep;
            main.post(() -> done.accept(fr));
        });
    }

    public void saveDisabledTools() {
        prefs.edit().putString("tools_disabled", String.join(",", tools.disabledNames())).apply();
    }
}
