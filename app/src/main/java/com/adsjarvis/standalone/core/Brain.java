package com.adsjarvis.standalone.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * JARVIS supervisor: an LLM tool-calling loop. The model reads the registered
 * tool catalog, plans, calls tools, sees their real results and answers. JARVIS
 * enforces approvals for YELLOW/RED tools and never fabricates tool success.
 */
public final class Brain {
    public interface Approver { boolean approve(Tool tool, JSONObject args); }

    public interface Events {
        void onToolStart(Tool tool, JSONObject args);
        void onToolEnd(Tool tool, ToolResult result);
    }

    public interface Audit {
        void record(String tool, String agent, String tier, String argsSummary, boolean approved, boolean ok, String summary);
    }

    public static final class Turn {
        public final boolean user; public final String text;
        public Turn(boolean user, String text) { this.user = user; this.text = text; }
    }

    public static final class Request {
        public String userText = "";
        public List<Turn> history = Collections.emptyList();
        public List<String> memories = Collections.emptyList();
        public String imageBase64, imageMime = "image/jpeg";
        public String assistantName = "JARVIS";
        public String replyLanguage = "auto"; // auto | tamil | english | tanglish
        public String responseLength = "short"; // short | detailed
        public String deviceTime = "";
        public boolean online = true;
        public boolean approveYellow = true;
    }

    public static final class Answer {
        public final String text; public final List<String> toolsUsed; public final String model; public final int steps;
        Answer(String text, List<String> toolsUsed, String model, int steps) {
            this.text = text; this.toolsUsed = toolsUsed; this.model = model; this.steps = steps;
        }
    }

    public static final int MAX_STEPS = 8;

    private final GeminiClient client;
    private final ToolRegistry tools;
    private final List<String> models;
    private Approver approver = (t, a) -> false;
    private Events events;
    private Audit audit;

    public Brain(GeminiClient client, ToolRegistry tools, List<String> modelsInOrder) {
        this.client = client; this.tools = tools; this.models = modelsInOrder;
    }
    public Brain approver(Approver a) { approver = a; return this; }
    public Brain events(Events e) { events = e; return this; }
    public Brain audit(Audit a) { audit = a; return this; }

    static String systemPrompt(Request r, ToolRegistry tools) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are ").append(r.assistantName).append(", the owner's personal AI assistant running natively on their Android phone. ")
          .append("You supervise five internal specialists: Research Intelligence, Trading Analyst, Software Engineering, ")
          .append("Android/PC Automation and Memory & Planning. Use the provided tools to act; never claim an action happened ")
          .append("unless a tool result says ok=true. If a tool fails or is denied, say so plainly and suggest a next step. ")
          .append("For multi-step requests, briefly state your plan, execute permitted steps, verify results, and report. ")
          .append("Ask for missing essential information instead of guessing. Device-local facts (time, battery) must come from tools.\n");
        sb.append("Language: ");
        switch (r.replyLanguage) {
            case "tamil": sb.append("always reply in Tamil script."); break;
            case "english": sb.append("always reply in English."); break;
            case "tanglish": sb.append("always reply in natural Tanglish (Tamil written in Latin letters mixed with English)."); break;
            default: sb.append("reply in the language the user used (Tamil script, English or Tanglish).");
        }
        sb.append(r.responseLength.equals("detailed") ? " Give thorough answers when useful.\n" : " Keep spoken answers under 80 words unless asked for detail.\n");
        sb.append("Trading rules (GreenSoil, PAPER ONLY): never place or suggest placing live orders; entries only after 09:30 IST on a confirmed ")
          .append("5-minute close; below 70% confidence the answer is NO TRADE; max estimated risk Rs1,200 per entry incl. fees/slippage; ")
          .append("Rs3,000 daily hard loss; one open position across NIFTY/SENSEX; reject stale quotes and oversized SL. Always state data ")
          .append("freshness and evidence sources; use trading_risk_check before presenting any trade plan. Never promise profit.\n");
        sb.append("Payments, OTPs and UPI PINs: never handle them; the owner does that manually.\n");
        if (!r.online) sb.append("The device is OFFLINE: only local tools are available.\n");
        if (!r.deviceTime.isEmpty()) sb.append("Device local time: ").append(r.deviceTime).append('\n');
        if (!r.memories.isEmpty()) {
            sb.append("Relevant long-term memories (owner-approved; cite them when used):\n");
            for (String m : r.memories) sb.append("- ").append(m).append('\n');
        }
        sb.append("Available capabilities:\n").append(tools.capabilitySummary(r.online));
        return sb.toString();
    }

    static JSONObject textContent(String role, String text) throws JSONException {
        return new JSONObject().put("role", role).put("parts", new JSONArray().put(new JSONObject().put("text", text)));
    }

    public Answer run(Request r, GeminiClient.CancelToken cancel) throws GeminiClient.GeminiException {
        approveYellowDefault = r.approveYellow;
        try {
            JSONArray contents = new JSONArray();
            int from = Math.max(0, r.history.size() - 16);
            for (Turn t : r.history.subList(from, r.history.size())) contents.put(textContent(t.user ? "user" : "model", t.text));
            JSONArray userParts = new JSONArray().put(new JSONObject().put("text", r.userText));
            if (r.imageBase64 != null && !r.imageBase64.isEmpty())
                userParts.put(new JSONObject().put("inlineData", new JSONObject().put("mimeType", r.imageMime).put("data", r.imageBase64)));
            contents.put(new JSONObject().put("role", "user").put("parts", userParts));

            List<String> used = new ArrayList<>();
            for (int step = 1; step <= MAX_STEPS; step++) {
                if (cancel != null && cancel.isCancelled()) throw new GeminiClient.GeminiException(GeminiClient.Kind.CANCELLED, 0, "cancelled");
                JSONObject body = new JSONObject()
                    .put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", systemPrompt(r, tools)))))
                    .put("contents", contents)
                    .put("tools", tools.declarations(r.online))
                    .put("toolConfig", new JSONObject().put("functionCallingConfig", new JSONObject().put("mode", "AUTO")))
                    .put("generationConfig", new JSONObject().put("temperature", 0.6).put("maxOutputTokens", 2048));
                JSONObject resp = client.generate(models, body, cancel);
                JSONObject pf = resp.optJSONObject("promptFeedback");
                if (pf != null && pf.has("blockReason")) throw new GeminiClient.GeminiException(GeminiClient.Kind.BLOCKED, 200, pf.optString("blockReason"));
                JSONArray cands = resp.optJSONArray("candidates");
                if (cands == null || cands.length() == 0) throw new GeminiClient.GeminiException(GeminiClient.Kind.PARSE, 200, "no candidates");
                JSONObject cand = cands.getJSONObject(0);
                JSONObject content = cand.optJSONObject("content");
                if (content == null) {
                    if ("SAFETY".equals(cand.optString("finishReason"))) throw new GeminiClient.GeminiException(GeminiClient.Kind.BLOCKED, 200, "safety");
                    throw new GeminiClient.GeminiException(GeminiClient.Kind.PARSE, 200, "empty content");
                }
                JSONArray parts = content.optJSONArray("parts");
                if (parts == null) parts = new JSONArray();
                List<JSONObject> calls = new ArrayList<>();
                StringBuilder text = new StringBuilder();
                for (int i = 0; i < parts.length(); i++) {
                    JSONObject p = parts.getJSONObject(i);
                    if (p.has("functionCall")) calls.add(p.getJSONObject("functionCall"));
                    else if (p.has("text") && !p.optBoolean("thought", false)) text.append(p.getString("text"));
                }
                if (calls.isEmpty()) {
                    String t = text.toString().trim();
                    if (t.isEmpty()) throw new GeminiClient.GeminiException(GeminiClient.Kind.PARSE, 200, "empty answer");
                    return new Answer(t, used, client.lastModelUsed(), step);
                }
                // Echo the model turn exactly (keeps any thought signatures), then answer every call.
                if (!content.has("role")) content.put("role", "model");
                contents.put(content);
                JSONArray responses = new JSONArray();
                for (JSONObject call : calls) {
                    String name = call.optString("name");
                    JSONObject args = call.optJSONObject("args");
                    if (args == null) args = new JSONObject();
                    ToolResult res = execute(name, args);
                    used.add(name + (res.ok ? "" : "(failed)"));
                    responses.put(new JSONObject().put("functionResponse",
                        new JSONObject().put("name", name).put("response", res.data)));
                }
                contents.put(new JSONObject().put("role", "user").put("parts", responses));
            }
            return new Answer("I stopped after " + MAX_STEPS + " tool steps without finishing. Tell me how you'd like to continue.",
                used, client.lastModelUsed(), MAX_STEPS);
        } catch (JSONException e) {
            throw new GeminiClient.GeminiException(GeminiClient.Kind.PARSE, 200, "malformed response");
        }
    }

    /** Runs one tool call with registry, permission and approval checks. Never throws. */
    public ToolResult execute(String name, JSONObject args) {
        Tool t = tools.get(name);
        if (t == null) return ToolResult.fail("Unknown tool " + name);
        if (!tools.isEnabled(name)) { log(t, args, false, ToolResult.fail("disabled")); return ToolResult.fail("Tool disabled by the owner in Settings → Agents"); }
        boolean needsApproval = t.tier() == Tool.Tier.RED || (t.tier() == Tool.Tier.YELLOW && approveYellowDefault);
        boolean approved = !needsApproval || safeApprove(t, args);
        if (!approved) {
            ToolResult r = ToolResult.fail("The owner did not approve this action.");
            log(t, args, false, r);
            return r;
        }
        if (events != null) events.onToolStart(t, args);
        ToolResult r;
        try { r = t.run(args); }
        catch (Exception e) { r = ToolResult.fail("Tool error: " + e.getClass().getSimpleName()); }
        if (r == null) r = ToolResult.fail("Tool returned nothing");
        if (events != null) events.onToolEnd(t, r);
        log(t, args, true, r);
        return r;
    }

    private boolean approveYellowDefault = true;
    public Brain approveYellow(boolean on) { approveYellowDefault = on; return this; }

    private boolean safeApprove(Tool t, JSONObject a) {
        try { return approver.approve(t, a); } catch (RuntimeException e) { return false; }
    }

    private void log(Tool t, JSONObject args, boolean approved, ToolResult r) {
        if (audit == null) return;
        String s = args.toString();
        audit.record(t.name(), t.agent().label, t.tier().name(), GeminiClient.redact(s.length() > 300 ? s.substring(0, 300) : s),
            approved, r.ok, r.summary);
    }
}
