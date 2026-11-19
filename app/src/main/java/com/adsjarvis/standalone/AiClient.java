package com.adsjarvis.standalone;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Minimal chat client for a user-configured AI provider. The API key is
 * supplied at runtime by the owner (stored encrypted on the device by
 * KeyVault); nothing is hardcoded. Blocking: call from a worker thread.
 */
public final class AiClient {
    public enum Provider { GEMINI, OPENAI_COMPATIBLE }

    public static final class Config {
        public final Provider provider;
        public final String baseUrl;
        public final String model;
        public final String apiKey;

        public Config(Provider provider, String baseUrl, String model, String apiKey) {
            this.provider = provider;
            this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
            this.model = model == null ? "" : model.trim();
            this.apiKey = apiKey == null ? "" : apiKey.trim();
        }

        public boolean isComplete() { return !apiKey.isEmpty() && !model.isEmpty() && validUrl(baseUrl) == null; }

        public static String defaultBaseUrl(Provider p) {
            return p == Provider.GEMINI ? "https://generativelanguage.googleapis.com/v1beta" : "https://api.openai.com/v1";
        }
    }

    public static final class Turn {
        public final boolean fromUser;
        public final String text;
        public Turn(boolean fromUser, String text) { this.fromUser = fromUser; this.text = text; }
    }

    /** Outcome with a user-presentable message; never contains the key. */
    public static final class Reply {
        public final boolean ok;
        public final String text;
        Reply(boolean ok, String text) { this.ok = ok; this.text = text; }
    }

    public static final String SYSTEM_PROMPT =
        "You are JARVIS, a concise, warm personal assistant on the owner's Android phone. "
        + "Reply in the same language the user used: Tamil script for Tamil, English for English, "
        + "and natural Tanglish (Tamil in Latin letters mixed with English) for Tanglish. "
        + "Keep answers short enough to be spoken aloud (under 80 words unless asked for detail). "
        + "You cannot make payments, send messages, or control apps yourself; say so if asked.";

    private final int timeoutMs;

    public AiClient() { this(20000); }
    public AiClient(int timeoutMs) { this.timeoutMs = timeoutMs; }

    /** Returns null if acceptable, else a reason. HTTPS only, except loopback for local models. */
    public static String validUrl(String url) {
        if (url == null || url.isEmpty()) return "Base URL is empty";
        String l = url.toLowerCase(Locale.ROOT);
        if (l.startsWith("https://")) return l.contains("@") ? "Do not put credentials in the URL" : null;
        if (l.startsWith("http://127.0.0.1") || l.startsWith("http://localhost")) return null;
        return "Base URL must start with https://";
    }

    public Reply chat(Config cfg, List<Turn> history, String userText) {
        if (cfg == null || !cfg.isComplete())
            return new Reply(false, "AI provider is not set up. Long-press the reactor → AI provider.");
        try {
            String url;
            JSONObject body;
            if (cfg.provider == Provider.GEMINI) {
                url = cfg.baseUrl + "/models/" + cfg.model + ":generateContent";
                body = geminiBody(history, userText);
            } else {
                url = cfg.baseUrl + "/chat/completions";
                body = openAiBody(cfg.model, history, userText);
            }
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            if (cfg.provider == Provider.GEMINI) c.setRequestProperty("x-goog-api-key", cfg.apiKey);
            else c.setRequestProperty("Authorization", "Bearer " + cfg.apiKey);
            byte[] out = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = c.getOutputStream()) { os.write(out); }
            int code = c.getResponseCode();
            String resp = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
            c.disconnect();
            if (code == 401 || code == 403) return new Reply(false, "The AI provider rejected the API key (HTTP " + code + ").");
            if (code == 404) return new Reply(false, "Model or endpoint not found (HTTP 404). Check the model name.");
            if (code == 429) return new Reply(false, "AI provider rate limit or quota reached (HTTP 429). Try later.");
            if (code >= 500) return new Reply(false, "AI provider is having problems (HTTP " + code + ").");
            if (code != 200) return new Reply(false, "AI provider returned HTTP " + code + ".");
            String text = cfg.provider == Provider.GEMINI ? parseGemini(resp) : parseOpenAi(resp);
            if (text == null || text.trim().isEmpty()) return new Reply(false, "The AI provider returned an empty answer.");
            return new Reply(true, text.trim());
        } catch (UnknownHostException e) {
            return new Reply(false, "No internet connection. Local commands still work.");
        } catch (SocketTimeoutException e) {
            return new Reply(false, "The AI provider timed out. Check your connection.");
        } catch (IOException e) {
            return new Reply(false, "Could not reach the AI provider. Local commands still work.");
        } catch (JSONException e) {
            return new Reply(false, "The AI provider sent a response I could not read.");
        }
    }

    static JSONObject geminiBody(List<Turn> history, String userText) throws JSONException {
        JSONArray contents = new JSONArray();
        for (Turn t : trim(history)) contents.put(geminiTurn(t.fromUser ? "user" : "model", t.text));
        contents.put(geminiTurn("user", userText));
        return new JSONObject()
            .put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", SYSTEM_PROMPT))))
            .put("contents", contents)
            .put("generationConfig", new JSONObject().put("maxOutputTokens", 512).put("temperature", 0.7));
    }

    private static JSONObject geminiTurn(String role, String text) throws JSONException {
        return new JSONObject().put("role", role).put("parts", new JSONArray().put(new JSONObject().put("text", text)));
    }

    static JSONObject openAiBody(String model, List<Turn> history, String userText) throws JSONException {
        JSONArray msgs = new JSONArray().put(new JSONObject().put("role", "system").put("content", SYSTEM_PROMPT));
        for (Turn t : trim(history)) msgs.put(new JSONObject().put("role", t.fromUser ? "user" : "assistant").put("content", t.text));
        msgs.put(new JSONObject().put("role", "user").put("content", userText));
        return new JSONObject().put("model", model).put("messages", msgs).put("max_tokens", 512);
    }

    /** Keep the last 12 turns of context to bound cost and latency. */
    static List<Turn> trim(List<Turn> history) {
        if (history == null) return java.util.Collections.emptyList();
        int from = Math.max(0, history.size() - 12);
        return history.subList(from, history.size());
    }

    static String parseGemini(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        JSONArray cands = o.optJSONArray("candidates");
        if (cands == null || cands.length() == 0) return null;
        JSONObject content = cands.getJSONObject(0).optJSONObject("content");
        if (content == null) return null;
        JSONArray parts = content.optJSONArray("parts");
        if (parts == null) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) sb.append(parts.getJSONObject(i).optString("text", ""));
        return sb.toString();
    }

    static String parseOpenAi(String json) throws JSONException {
        JSONArray choices = new JSONObject(json).optJSONArray("choices");
        if (choices == null || choices.length() == 0) return null;
        JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
        return msg == null ? null : msg.optString("content", null);
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream is = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n, total = 0;
            while ((n = is.read(buf)) > 0) {
                total += n;
                if (total > 1_000_000) throw new IOException("response too large");
                bo.write(buf, 0, n);
            }
            return bo.toString("UTF-8");
        }
    }
}
