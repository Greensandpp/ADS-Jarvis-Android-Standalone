package com.adsjarvis.standalone;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.StatFs;
import android.provider.AlarmClock;
import android.provider.CalendarContract;
import android.util.Base64;
import com.adsjarvis.standalone.core.GeminiClient;
import com.adsjarvis.standalone.core.JournalAnalyzer;
import com.adsjarvis.standalone.core.Memory;
import com.adsjarvis.standalone.core.MemoryEngine;
import com.adsjarvis.standalone.core.RiskEngine;
import com.adsjarvis.standalone.core.Schema;
import com.adsjarvis.standalone.core.Tool;
import com.adsjarvis.standalone.core.ToolRegistry;
import com.adsjarvis.standalone.core.ToolResult;
import com.adsjarvis.standalone.core.TradingRules;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The registered tool catalog. Every tool declares its agent, approval tier and
 * whether it needs the network. Outward-facing actions (calls, SMS, WhatsApp,
 * calendar, alarms) only open the target app pre-filled; the owner confirms there.
 */
public final class AndroidTools {
    /** UI hooks the controller provides. */
    public interface Host {
        void applySetting(String name, String value);
        void openJarvisSettings(String section);
        GeminiClient gemini();          // null when AI not configured
        List<String> models();
        boolean online();
    }

    private AndroidTools() {}

    static abstract class T implements Tool {
        final String name, desc; final Agent agent; final Tier tier; final boolean net; final JSONObject params;
        T(String name, Agent agent, Tier tier, boolean net, String desc, Schema schema) {
            this.name = name; this.agent = agent; this.tier = tier; this.net = net; this.desc = desc; this.params = schema.build();
        }
        public String name() { return name; }
        public String description() { return desc; }
        public JSONObject parameters() { return params; }
        public Agent agent() { return agent; }
        public Tier tier() { return tier; }
        public boolean needsNetwork() { return net; }
    }

    static TradingRules rules(SharedPreferences p) {
        TradingRules r = new TradingRules();
        r.minConfidence = Math.max(70, p.getFloat(Store.T_CONF, 70));
        r.maxRiskPerEntry = Math.min(1200, p.getFloat(Store.T_RISK, 1200));
        r.dailyHardLoss = Math.min(3000, p.getFloat(Store.T_DAILY, 3000));
        r.niftyLot = p.getInt(Store.T_NLOT, 75);
        r.sensexLot = p.getInt(Store.T_SLOT, 20);
        r.slippagePointsPerUnit = p.getFloat(Store.T_SLIP, .5f);
        r.brokeragePerOrder = p.getFloat(Store.T_BROK, 20);
        r.statutoryPct = p.getFloat(Store.T_STAT, .12f);
        return r;
    }

    static boolean installed(Context c, String pkg) {
        try { c.getPackageManager().getPackageInfo(pkg, 0); return true; }
        catch (PackageManager.NameNotFoundException e) { return false; }
    }

    static ToolResult start(Context c, Intent i, String ok) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { c.startActivity(i); return ToolResult.ok(ok); }
        catch (Exception e) { return ToolResult.fail("No app on this phone can handle that."); }
    }

    static String httpGet(String url, String bearer, int max) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(10000); h.setReadTimeout(15000);
        h.setRequestProperty("Accept", "application/json, text/plain, */*");
        h.setRequestProperty("User-Agent", "GreenSoil-JARVIS-Android");
        if (bearer != null && !bearer.isEmpty()) h.setRequestProperty("Authorization", "Bearer " + bearer);
        int code = h.getResponseCode();
        InputStream in = code >= 400 ? h.getErrorStream() : h.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        if (in != null) try (InputStream is = in) {
            byte[] b = new byte[8192]; int n;
            while ((n = is.read(b)) > 0 && bo.size() < max) bo.write(b, 0, n);
        }
        h.disconnect();
        if (code >= 400) throw new java.io.IOException("HTTP " + code);
        return bo.toString("UTF-8");
    }

    public static ToolRegistry build(Context ctx, MemoryEngine mem, Host host) {
        Context c = ctx.getApplicationContext();
        SharedPreferences p = Store.prefs(c);
        DeviceActions da = new DeviceActions(c);
        ToolRegistry reg = new ToolRegistry();

        // ------------------------------------------------ Android/PC Automation
        reg.register(new T("get_time", Tool.Agent.AUTOMATION, Tool.Tier.GREEN, false,
            "Current local date, time and time zone from the phone clock; also IST.", Schema.obj()) {
            public ToolResult run(JSONObject a) throws Exception {
                Date now = new Date();
                SimpleDateFormat f = new SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.ENGLISH);
                SimpleDateFormat ist = new SimpleDateFormat("HH:mm", Locale.ENGLISH);
                ist.setTimeZone(TimeZone.getTimeZone("Asia/Kolkata"));
                return ToolResult.ok(f.format(now), new JSONObject().put("local", f.format(now))
                    .put("timezone", TimeZone.getDefault().getID()).put("ist_time", ist.format(now)));
            }
        });
        reg.register(new T("device_status", Tool.Agent.AUTOMATION, Tool.Tier.GREEN, false,
            "Battery level/charging, network type, free storage, Android version and model.", Schema.obj()) {
            public ToolResult run(JSONObject a) throws Exception {
                BatteryManager bm = (BatteryManager) c.getSystemService(Context.BATTERY_SERVICE);
                ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
                NetworkCapabilities nc = cm == null ? null : cm.getNetworkCapabilities(cm.getActiveNetwork());
                String net = nc == null ? "offline" : nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "Wi-Fi"
                    : nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "mobile data" : "other";
                StatFs fs = new StatFs(c.getFilesDir().getPath());
                JSONObject d = new JSONObject()
                    .put("battery_pct", bm == null ? -1 : bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
                    .put("charging", bm != null && bm.isCharging()).put("network", net)
                    .put("internet_validated", nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                    .put("free_storage_gb", Math.round(fs.getAvailableBytes() / 1e8) / 10.0)
                    .put("android", Build.VERSION.RELEASE).put("model", Build.MANUFACTURER + " " + Build.MODEL);
                return ToolResult.ok("device status read", d);
            }
        });
        reg.register(new T("open_app", Tool.Agent.AUTOMATION, Tool.Tier.GREEN, false,
            "Open an installed app by its name (e.g. YouTube, Clock, TradingView).",
            Schema.obj().str("name", "app name as shown on the home screen", true)) {
            public ToolResult run(JSONObject a) {
                String r = da.openApp(a.optString("name"), "Opened " + a.optString("name"));
                return r.startsWith("Opened") ? ToolResult.ok(r) : ToolResult.fail(r);
            }
        });
        reg.register(new T("open_url", Tool.Agent.AUTOMATION, Tool.Tier.GREEN, false,
            "Open an https web page in the browser.", Schema.obj().str("url", "https URL", true)) {
            public ToolResult run(JSONObject a) {
                String u = a.optString("url");
                if (!u.startsWith("https://")) return ToolResult.fail("Only https links are opened.");
                return start(c, new Intent(Intent.ACTION_VIEW, Uri.parse(u)), "Opened " + u);
            }
        });
        reg.register(new T("open_tradingview", Tool.Agent.TRADING, Tool.Tier.GREEN, false,
            "Open TradingView on a chart. JARVIS cannot read the TradingView screen; ask the owner for a screenshot to analyse.",
            Schema.obj().oneOf("symbol", "index", false, "NIFTY", "SENSEX").oneOf("interval", "minutes", false, "5", "15")) {
            public ToolResult run(JSONObject a) {
                String sym = "SENSEX".equals(a.optString("symbol")) ? "BSE%3ASENSEX" : "NSE%3ANIFTY";
                String iv = a.optString("interval", "5");
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.tradingview.com/chart/?symbol=" + sym + "&interval=" + iv));
                if (installed(c, "com.tradingview.tradingviewapp")) i.setPackage("com.tradingview.tradingviewapp");
                ToolResult r = start(c, i, "Opened TradingView " + sym.replace("%3A", ":") + " " + iv + "m");
                if (!r.ok && i.getPackage() != null) { i.setPackage(null); r = start(c, i, "Opened TradingView in the browser"); }
                return r;
            }
        });
        reg.register(new T("set_alarm", Tool.Agent.AUTOMATION, Tool.Tier.YELLOW, false,
            "Open the Clock app with an alarm pre-filled (owner confirms in Clock).",
            Schema.obj().num("hour", "0-23", true).num("minute", "0-59", true).str("label", "label", false)) {
            public ToolResult run(JSONObject a) {
                int h = a.optInt("hour", -1), m = a.optInt("minute", -1);
                if (h < 0 || h > 23 || m < 0 || m > 59) return ToolResult.fail("Invalid time.");
                return start(c, new Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, m)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", "JARVIS")).putExtra(AlarmClock.EXTRA_SKIP_UI, false),
                    String.format(Locale.ROOT, "Clock opened with alarm %02d:%02d — confirm it there.", h, m));
            }
        });
        reg.register(new T("set_timer", Tool.Agent.AUTOMATION, Tool.Tier.YELLOW, false,
            "Start a countdown timer in the Clock app.", Schema.obj().num("seconds", "1-86400", true)) {
            public ToolResult run(JSONObject a) {
                int s = a.optInt("seconds", 0);
                if (s < 1 || s > 86400) return ToolResult.fail("Timer must be 1 s – 24 h.");
                return start(c, new Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, s)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, false), "Timer opened in Clock for " + s + " s.");
            }
        });
        reg.register(new T("create_reminder", Tool.Agent.MEMORY, Tool.Tier.YELLOW, false,
            "Schedule a JARVIS reminder notification on this phone (works offline).",
            Schema.obj().str("text", "what to remind", true).num("minutes_from_now", "delay in minutes", false)
                .str("at_time", "HH:MM 24h local time (today, or tomorrow if passed)", false)) {
            public ToolResult run(JSONObject a) throws Exception {
                String text = a.optString("text").trim();
                if (text.isEmpty()) return ToolResult.fail("Reminder text is required.");
                long due;
                if (a.has("minutes_from_now")) due = System.currentTimeMillis() + (long) (a.getDouble("minutes_from_now") * 60000);
                else {
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,2}):(\\d{2})").matcher(a.optString("at_time"));
                    if (!m.find()) return ToolResult.fail("Give minutes_from_now or at_time HH:MM.");
                    Calendar cal = Calendar.getInstance();
                    cal.set(Calendar.HOUR_OF_DAY, Integer.parseInt(m.group(1))); cal.set(Calendar.MINUTE, Integer.parseInt(m.group(2)));
                    cal.set(Calendar.SECOND, 0);
                    if (cal.getTimeInMillis() <= System.currentTimeMillis()) cal.add(Calendar.DATE, 1);
                    due = cal.getTimeInMillis();
                }
                if (due < System.currentTimeMillis() + 5000) due = System.currentTimeMillis() + 5000;
                long id = Reminders.schedule(c, due, text);
                return ToolResult.ok("Reminder #" + id + " set for " + new SimpleDateFormat("EEE HH:mm", Locale.ENGLISH).format(new Date(due)),
                    new JSONObject().put("id", id).put("due", due)
                        .put("notifications_allowed", Reminders.notificationsAllowed(c)));
            }
        });
        reg.register(new T("add_calendar_event", Tool.Agent.MEMORY, Tool.Tier.YELLOW, false,
            "Open the calendar app with an event pre-filled (owner saves it).",
            Schema.obj().str("title", "title", true).str("start", "yyyy-MM-dd HH:mm local", true).num("duration_minutes", "length", false)) {
            public ToolResult run(JSONObject a) throws Exception {
                Date d = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).parse(a.getString("start"));
                long len = (long) (a.optDouble("duration_minutes", 30) * 60000);
                return start(c, new Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                    .putExtra(CalendarContract.Events.TITLE, a.getString("title"))
                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, d.getTime())
                    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, d.getTime() + len), "Calendar opened — save the event there.");
            }
        });
        reg.register(new T("dial_number", Tool.Agent.AUTOMATION, Tool.Tier.YELLOW, false,
            "Open the dialer with a number. The owner presses call; JARVIS cannot place calls.",
            Schema.obj().str("number", "phone number", true)) {
            public ToolResult run(JSONObject a) {
                String n = a.optString("number").replaceAll("[^0-9+]", "");
                if (n.length() < 3) return ToolResult.fail("Invalid number.");
                return start(c, new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(n))), "Dialer opened with " + n + ". Press call to confirm.");
            }
        });
        reg.register(new T("compose_sms", Tool.Agent.AUTOMATION, Tool.Tier.YELLOW, false,
            "Open the SMS app with a drafted message. The owner reviews and presses send.",
            Schema.obj().str("number", "recipient", true).str("message", "draft text", true)) {
            public ToolResult run(JSONObject a) {
                Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(a.optString("number")))).putExtra("sms_body", a.optString("message"));
                return start(c, i, "SMS draft opened — review and press send yourself.");
            }
        });
        reg.register(new T("draft_whatsapp", Tool.Agent.AUTOMATION, Tool.Tier.YELLOW, false,
            "Open WhatsApp share sheet with drafted text. The owner chooses the chat and sends.",
            Schema.obj().str("message", "draft text", true)) {
            public ToolResult run(JSONObject a) {
                Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, a.optString("message"));
                if (installed(c, "com.whatsapp")) i.setPackage("com.whatsapp");
                else return start(c, Intent.createChooser(i, "Share"), "WhatsApp not installed; share sheet opened.");
                return start(c, i, "WhatsApp opened with the draft — pick the chat and send yourself.");
            }
        });
        reg.register(new T("web_search", Tool.Agent.RESEARCH, Tool.Tier.GREEN, false,
            "Open a Google search in the browser for the owner to read.", Schema.obj().str("query", "search text", true)) {
            public ToolResult run(JSONObject a) {
                return start(c, new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(a.optString("query")))),
                    "Browser opened with the search.");
            }
        });
        reg.register(new T("change_setting", Tool.Agent.AUTOMATION, Tool.Tier.GREEN, false,
            "Change a JARVIS preference. visual_style: ARC|AIRA|APEX|MIRA|OFFICE; reply_language: auto|tamil|english|tanglish; "
                + "voice_gender: female|male|any; tts_pitch/tts_speed: 0.5-2.0; response_length: short|detailed; "
                + "input_language: auto|ta-IN|en-IN; assistant_name; wake_word.",
            Schema.obj().oneOf("name", "setting", true, "visual_style", "reply_language", "voice_gender", "tts_pitch", "tts_speed",
                "response_length", "input_language", "assistant_name", "wake_word").str("value", "new value", true)) {
            public ToolResult run(JSONObject a) {
                String n = a.optString("name"), v = a.optString("value").trim();
                String err = SettingsModel.validate(n, v);
                if (err != null) return ToolResult.fail(err);
                host.applySetting(n, v);
                return ToolResult.ok(n + " set to " + v);
            }
        });
        reg.register(new T("open_jarvis_settings", Tool.Agent.AUTOMATION, Tool.Tier.GREEN, false,
            "Open the JARVIS settings screen, optionally at a section.",
            Schema.obj().oneOf("section", "section", false, "general", "ai", "voice", "memory", "trading", "agents", "device",
                "visual", "security", "backup", "diagnostics")) {
            public ToolResult run(JSONObject a) { host.openJarvisSettings(a.optString("section", "")); return ToolResult.ok("Settings opened"); }
        });
        reg.register(new T("list_capabilities", Tool.Agent.MEMORY, Tool.Tier.GREEN, false,
            "List the tools JARVIS can use right now, their agents, approval tiers and the Android permissions involved. Use when planning.",
            Schema.obj()) {
            public ToolResult run(JSONObject a) throws Exception {
                return ToolResult.ok("capabilities listed", new JSONObject().put("tools", reg.capabilitySummary(host.online()))
                    .put("android_permissions", new JSONObject()
                        .put("microphone", c.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                        .put("notifications", Reminders.notificationsAllowed(c)))
                    .put("not_available", "Windows PC agent, background/lock-screen wake word, reading other apps' screens, "
                        + "sending messages or calls without owner tap, payments, live trading."));
            }
        });

        // ------------------------------------------------ Memory & Planning
        reg.register(new T("remember", Tool.Agent.MEMORY, Tool.Tier.YELLOW, false,
            "Save a long-term memory (owner approves). Use a stable key for facts that may change, e.g. preferred_language.",
            Schema.obj().oneOf("category", "type", true, "PREFERENCE", "KNOWLEDGE", "WORKFLOW", "TASK", "TRADING", "CORRECTION", "SUMMARY", "DEVICE")
                .str("key", "stable topic key", false).str("text", "the fact to remember", true)) {
            public ToolResult run(JSONObject a) {
                if (!mem.isEnabled()) return ToolResult.fail("Memory is turned off in Settings.");
                Memory.Category cat;
                try { cat = Memory.Category.valueOf(a.optString("category", "KNOWLEDGE")); } catch (Exception e) { cat = Memory.Category.KNOWLEDGE; }
                MemoryEngine.Proposal pr = mem.propose(cat, a.optString("key"), a.optString("text"), "assistant");
                Memory m = mem.commit(pr);
                return ToolResult.ok(pr.outcome == MemoryEngine.Outcome.DUPLICATE ? "already remembered" : "saved memory #" + m.id);
            }
        });
        reg.register(new T("recall_memory", Tool.Agent.MEMORY, Tool.Tier.GREEN, false,
            "Search long-term memory.", Schema.obj().str("query", "what to look for", true)) {
            public ToolResult run(JSONObject a) throws Exception {
                JSONArray out = new JSONArray();
                for (MemoryEngine.Hit h : mem.search(a.optString("query"), null, 8))
                    out.put(new JSONObject().put("id", h.memory.id).put("category", h.memory.category.name()).put("key", h.memory.key)
                        .put("text", h.memory.text).put("updated", h.memory.updatedAt));
                return ToolResult.ok(out.length() + " memories found", new JSONObject().put("memories", out));
            }
        });
        reg.register(new T("forget_memory", Tool.Agent.MEMORY, Tool.Tier.RED, false,
            "Delete a memory by id (owner approves).", Schema.obj().num("id", "memory id", true)) {
            public ToolResult run(JSONObject a) {
                return mem.delete(a.optLong("id")) ? ToolResult.ok("Memory deleted") : ToolResult.fail("No memory with that id.");
            }
        });
        reg.register(new T("task_history", Tool.Agent.MEMORY, Tool.Tier.GREEN, false,
            "Recent JARVIS tasks with tools used and outcomes; use to compare sessions.", Schema.obj().num("limit", "max rows", false)) {
            public ToolResult run(JSONObject a) throws Exception {
                return ToolResult.ok("task history", new JSONObject().put("tasks",
                    new JSONArray(JarvisDb.get(c).lines("task", null, Math.min(30, a.optInt("limit", 10))))));
            }
        });

        // ------------------------------------------------ Trading Analyst
        reg.register(new T("trading_rules", Tool.Agent.TRADING, Tool.Tier.GREEN, false,
            "The owner's GreenSoil PAPER-ONLY risk rules and lot/fee settings.", Schema.obj()) {
            public ToolResult run(JSONObject a) throws Exception {
                TradingRules r = rules(p);
                return ToolResult.ok("rules", new JSONObject().put("earliest_entry", "09:30 IST").put("candle", "5-minute confirmed close")
                    .put("htf", "15-minute confirmation").put("min_confidence", r.minConfidence).put("max_risk_per_entry", r.maxRiskPerEntry)
                    .put("daily_hard_loss", r.dailyHardLoss).put("max_open_positions", r.maxOpenPositions)
                    .put("nifty_lot", r.niftyLot).put("sensex_lot", r.sensexLot).put("paper_only", true)
                    .put("indicators", p.getString(Store.T_IND, "EMA9,MA9,VWAP,Supertrend")));
            }
        });
        reg.register(new T("trading_risk_check", Tool.Agent.TRADING, Tool.Tier.GREEN, false,
            "Deterministic GreenSoil risk gate. MUST be called before presenting any trade plan. Returns TRADE (PAPER) or NO TRADE "
                + "with reasons, lot size within ₹1,200 risk, TP1-TP8 ladder and trailing plan.",
            Schema.obj().oneOf("instrument", "index", true, "NIFTY", "SENSEX").oneOf("side", "premium side", false, "LONG", "SHORT")
                .num("entry", "option premium entry", true).num("stop_loss", "structural stop premium", true)
                .num("confidence", "0-100", true).str("signal_time_ist", "HH:MM of the 5-minute candle close", true)
                .bool("candle_closed", "5-minute candle closed", true).bool("htf_confirmed", "15-minute confirms", false)
                .num("quote_age_seconds", "age of the price data; -1 if unknown (e.g. screenshot)", true)
                .num("open_positions", "currently open", false).num("realized_loss_today", "₹ lost today", false)
                .str("evidence", "what the analysis is based on", false)) {
            public ToolResult run(JSONObject a) {
                RiskEngine.Setup s = new RiskEngine.Setup();
                s.instrument = a.optString("instrument"); s.side = a.optString("side", "LONG");
                s.entry = a.optDouble("entry", 0); s.stopLoss = a.optDouble("stop_loss", 0); s.confidence = a.optDouble("confidence", 0);
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,2}):(\\d{2})").matcher(a.optString("signal_time_ist"));
                s.entryMinutesIst = m.find() ? Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2)) : -1;
                s.candleClosed = a.optBoolean("candle_closed"); s.htfConfirmed = a.optBoolean("htf_confirmed", !p.getBoolean(Store.T_HTF, true));
                s.quoteAgeSeconds = a.optInt("quote_age_seconds", -1); s.openPositions = a.optInt("open_positions", 0);
                s.realizedLossToday = a.optDouble("realized_loss_today", 0); s.evidence = a.optString("evidence");
                RiskEngine.Decision d = RiskEngine.evaluate(s, rules(p));
                return ToolResult.ok(d.trade ? "TRADE (PAPER)" : "NO TRADE: " + String.join("; ", d.reasons), d.toJson());
            }
        });
        reg.register(new T("analyze_trade_journal", Tool.Agent.TRADING, Tool.Tier.GREEN, false,
            "Analyse the owner's imported trade journal: performance, data errors, rule violations, giveback and improvement hypotheses.",
            Schema.obj()) {
            public ToolResult run(JSONObject a) {
                String csv = p.getString(Store.T_JOURNAL, "");
                if (csv.isEmpty()) return ToolResult.fail("No trade journal imported. Share a CSV to JARVIS or use Settings → Trading AI → Import journal.");
                return ToolResult.ok("journal analysed", JournalAnalyzer.analyze(csv, rules(p)));
            }
        });
        reg.register(new T("pb7_status", Tool.Agent.TRADING, Tool.Tier.GREEN, true,
            "Read-only status from the owner's GreenSoil PB7 PAPER bot endpoint (signals, rejections, P&L). Cannot change the bot.",
            Schema.obj().str("question", "what to look up", false)) {
            public ToolResult run(JSONObject a) throws Exception {
                String url = p.getString(Store.PB7_URL, "");
                if (url.isEmpty()) return ToolResult.fail("PB7 PAPER bot is NOT CONNECTED. Add its read-only status URL in Settings → Trading AI.");
                if (!url.startsWith("https://")) return ToolResult.fail("PB7 URL must be https.");
                String body = httpGet(url, new KeyVault(p, Store.PB7_TOKEN).load(), 60000);
                return ToolResult.ok("PB7 status read (read-only)", new JSONObject().put("status", body.length() > 12000 ? body.substring(0, 12000) : body));
            }
        });

        // ------------------------------------------------ Research Intelligence
        reg.register(new T("web_research", Tool.Agent.RESEARCH, Tool.Tier.GREEN, true,
            "Research a question on the web with Google Search grounding; returns a summary with source links.",
            Schema.obj().str("question", "research question", true)) {
            public ToolResult run(JSONObject a) throws Exception {
                GeminiClient g = host.gemini();
                if (g == null) return ToolResult.fail("AI provider not configured.");
                JSONObject body = new JSONObject()
                    .put("contents", new JSONArray().put(new JSONObject().put("role", "user").put("parts",
                        new JSONArray().put(new JSONObject().put("text", "Research and answer with facts and dates: " + a.optString("question"))))))
                    .put("tools", new JSONArray().put(new JSONObject().put("google_search", new JSONObject())));
                JSONObject r = g.generate(host.models(), body, null);
                JSONObject cand = r.getJSONArray("candidates").getJSONObject(0);
                StringBuilder t = new StringBuilder();
                JSONArray parts = cand.getJSONObject("content").optJSONArray("parts");
                if (parts != null) for (int i = 0; i < parts.length(); i++) t.append(parts.getJSONObject(i).optString("text"));
                JSONArray sources = new JSONArray();
                JSONObject gm = cand.optJSONObject("groundingMetadata");
                JSONArray chunks = gm == null ? null : gm.optJSONArray("groundingChunks");
                if (chunks != null) for (int i = 0; i < chunks.length() && i < 8; i++) {
                    JSONObject w = chunks.getJSONObject(i).optJSONObject("web");
                    if (w != null) sources.put(new JSONObject().put("title", w.optString("title")).put("url", w.optString("uri")));
                }
                return ToolResult.ok(sources.length() + " sources", new JSONObject().put("answer", t.toString()).put("sources", sources)
                    .put("verified_by_search", sources.length() > 0));
            }
        });

        // ------------------------------------------------ Software Engineering (read-only)
        reg.register(new T("read_github", Tool.Agent.ENGINEERING, Tool.Tier.GREEN, true,
            "Read-only: list a directory or read a file from a PUBLIC GitHub repository (owner/repo). Proposes fixes only; never commits.",
            Schema.obj().str("repo", "owner/repo", true).str("path", "file or directory path ('' for root)", false).str("ref", "branch or commit", false)) {
            public ToolResult run(JSONObject a) throws Exception {
                String repo = a.optString("repo");
                if (!repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) return ToolResult.fail("repo must be owner/repo");
                String path = a.optString("path", "").replaceAll("^/+", "");
                String url = "https://api.github.com/repos/" + repo + "/contents/" + path + (a.optString("ref").isEmpty() ? "" : "?ref=" + Uri.encode(a.optString("ref")));
                String body = httpGet(url, null, 400000);
                if (body.trim().startsWith("[")) {
                    JSONArray arr = new JSONArray(body), out = new JSONArray();
                    for (int i = 0; i < arr.length(); i++) out.put(arr.getJSONObject(i).optString("type") + " " + arr.getJSONObject(i).optString("path"));
                    return ToolResult.ok(out.length() + " entries", new JSONObject().put("entries", out));
                }
                JSONObject f = new JSONObject(body);
                String content = new String(Base64.decode(f.optString("content").replace("\n", ""), Base64.DEFAULT), StandardCharsets.UTF_8);
                return ToolResult.ok("file read", new JSONObject().put("path", f.optString("path")).put("sha", f.optString("sha"))
                    .put("content", content.length() > 20000 ? content.substring(0, 20000) + "\n…[truncated]" : content));
            }
        });
        return reg;
    }

    /** Reminder scheduling via AlarmManager (inexact, allowed while idle; no exact-alarm permission needed). */
    public static final class Reminders {
        static long schedule(Context c, long due, String text) {
            long id = JarvisDb.get(c).addReminder(due, text);
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            Intent i = new Intent(c, ReminderReceiver.class).putExtra("id", id).putExtra("text", text);
            PendingIntent pi = PendingIntent.getBroadcast(c, (int) id, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, pi);
            return id;
        }

        static void cancel(Context c, long id) {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            Intent i = new Intent(c, ReminderReceiver.class);
            am.cancel(PendingIntent.getBroadcast(c, (int) id, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            JarvisDb.get(c).deleteReminder(id);
        }

        static boolean notificationsAllowed(Context c) {
            android.app.NotificationManager nm = (android.app.NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            return nm != null && nm.areNotificationsEnabled();
        }
    }
}
