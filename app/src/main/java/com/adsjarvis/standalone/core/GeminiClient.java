package com.adsjarvis.standalone.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLException;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Google Gemini REST client (Generative Language API, v1beta). Key is sent in
 * the x-goog-api-key header, never in the URL, and is redacted from every
 * error message. Blocking; call from a worker thread.
 */
public final class GeminiClient {

    /** Failure categories — DNS problems are never reported as model problems and vice versa. */
    public enum Kind { NO_KEY, DNS, TLS, CONNECT, TIMEOUT, BAD_REQUEST, AUTH, NOT_FOUND, RATE_LIMIT, SERVER, PARSE, BLOCKED, CANCELLED }

    public static final class GeminiException extends Exception {
        public final Kind kind;
        public final int httpStatus;
        public GeminiException(Kind kind, int httpStatus, String message) { super(message); this.kind = kind; this.httpStatus = httpStatus; }

        /** Plain-language explanation for the chat / diagnostics screen. */
        public String userMessage() {
            switch (kind) {
                case NO_KEY: return "Gemini API key is not set. Settings → AI Brain.";
                case DNS: return "DNS failure: this phone could not resolve the Gemini host. Check Wi-Fi/mobile data or Private DNS.";
                case TLS: return "Secure connection (TLS) to Gemini failed. Check date/time, VPN or network filtering.";
                case CONNECT: return "Could not connect to the Gemini server (network unreachable or blocked).";
                case TIMEOUT: return "Gemini did not respond in time. Retried with backoff; try again.";
                case BAD_REQUEST: return "Gemini rejected the request (HTTP 400): " + getMessage();
                case AUTH: return "Gemini rejected the API key (HTTP " + httpStatus + "). Check the key in Settings → AI Brain.";
                case NOT_FOUND: return "Model not found (HTTP 404). Run model discovery in Settings → AI Brain and pick a listed model.";
                case RATE_LIMIT: return "Gemini quota or rate limit reached (HTTP 429). Try later or choose another model.";
                case SERVER: return "Gemini server error (HTTP " + httpStatus + "). Retried; try again shortly.";
                case BLOCKED: return "Gemini declined to answer this request (safety filter).";
                case CANCELLED: return "Request cancelled.";
                default: return "Unexpected response from Gemini: " + getMessage();
            }
        }
    }

    /** Lets callers abort an in-flight request (also disconnects the socket). */
    public static final class CancelToken {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile HttpURLConnection conn;
        public void cancel() { cancelled.set(true); HttpURLConnection c = conn; if (c != null) c.disconnect(); }
        public boolean isCancelled() { return cancelled.get(); }
    }

    public interface Sleeper { void sleep(long ms) throws InterruptedException; }

    public static final class ModelInfo {
        public final String id, displayName;
        public final boolean generate, embed;
        public ModelInfo(String id, String displayName, boolean generate, boolean embed) {
            this.id = id; this.displayName = displayName; this.generate = generate; this.embed = embed;
        }
    }

    public static final String DEFAULT_BASE = "https://generativelanguage.googleapis.com/v1beta";

    private final String baseUrl;
    private final String apiKey;
    private final int timeoutMs;
    private final int maxRetries;
    private final Sleeper sleeper;
    private volatile String lastModelUsed = "";

    public GeminiClient(String baseUrl, String apiKey) { this(baseUrl, apiKey, 30000, 2, Thread::sleep); }

    public GeminiClient(String baseUrl, String apiKey, int timeoutMs, int maxRetries, Sleeper sleeper) {
        this.baseUrl = (baseUrl == null || baseUrl.trim().isEmpty() ? DEFAULT_BASE : baseUrl.trim()).replaceAll("/+$", "");
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.timeoutMs = timeoutMs; this.maxRetries = maxRetries; this.sleeper = sleeper;
    }

    public String host() {
        try { return new URL(baseUrl).getHost(); } catch (IOException e) { return ""; }
    }
    public String lastModelUsed() { return lastModelUsed; }

    static String modelPath(String model) {
        String m = model == null ? "" : model.trim();
        if (m.startsWith("models/")) m = m.substring(7);
        if (!m.matches("[A-Za-z0-9._\\-]{1,80}")) throw new IllegalArgumentException("invalid model id");
        return "models/" + m;
    }

    /** generateContent on the first model in `models` that works; falls back on 404/429/5xx. */
    public JSONObject generate(List<String> models, JSONObject body, CancelToken cancel) throws GeminiException {
        Set<String> order = new LinkedHashSet<>(models);
        GeminiException last = null;
        for (String m : order) {
            if (m == null || m.trim().isEmpty()) continue;
            try {
                JSONObject r = call("POST", "/" + modelPath(m) + ":generateContent", body, cancel);
                lastModelUsed = m;
                return r;
            } catch (GeminiException e) {
                last = e;
                if (e.kind != Kind.NOT_FOUND && e.kind != Kind.RATE_LIMIT && e.kind != Kind.SERVER) throw e;
            } catch (IllegalArgumentException e) {
                last = new GeminiException(Kind.NOT_FOUND, 0, "invalid model id");
            }
        }
        throw last != null ? last : new GeminiException(Kind.NOT_FOUND, 0, "no model configured");
    }

    /** Lists models available to this key (paged). */
    public List<ModelInfo> listModels(CancelToken cancel) throws GeminiException {
        List<ModelInfo> out = new ArrayList<>();
        String token = "";
        for (int page = 0; page < 10; page++) {
            JSONObject r = call("GET", "/models?pageSize=200" + (token.isEmpty() ? "" : "&pageToken=" + token), null, cancel);
            JSONArray ms = r.optJSONArray("models");
            if (ms != null) for (int i = 0; i < ms.length(); i++) {
                JSONObject m = ms.optJSONObject(i);
                if (m == null) continue;
                String id = m.optString("name", "").replaceFirst("^models/", "");
                JSONArray methods = m.optJSONArray("supportedGenerationMethods");
                boolean gen = false, emb = false;
                if (methods != null) for (int k = 0; k < methods.length(); k++) {
                    String s = methods.optString(k);
                    gen |= "generateContent".equals(s);
                    emb |= "embedContent".equals(s);
                }
                out.add(new ModelInfo(id, m.optString("displayName", id), gen, emb));
            }
            token = r.optString("nextPageToken", "");
            if (token.isEmpty()) break;
        }
        return out;
    }

    /** Ranks discovered chat models: preferred first, then flash/pro, newest-looking ids first. */
    public static List<String> fallbackOrder(String preferred, List<ModelInfo> discovered) {
        List<String> out = new ArrayList<>();
        if (preferred != null && !preferred.isEmpty()) out.add(preferred);
        List<String> flash = new ArrayList<>(), other = new ArrayList<>();
        for (ModelInfo m : discovered) {
            if (!m.generate) continue;
            String id = m.id.toLowerCase(Locale.ROOT);
            if (id.contains("embedding") || id.contains("tts") || id.contains("image") || id.contains("live") || id.contains("audio")) continue;
            if (id.contains("flash")) flash.add(m.id); else if (id.startsWith("gemini")) other.add(m.id);
        }
        flash.sort((a, b) -> b.compareTo(a));
        other.sort((a, b) -> b.compareTo(a));
        for (String s : flash) if (!out.contains(s)) out.add(s);
        for (String s : other) if (!out.contains(s)) out.add(s);
        return out;
    }

    public float[] embed(String model, String text, CancelToken cancel) throws GeminiException {
        try {
            JSONObject body = new JSONObject().put("content", new JSONObject().put("parts",
                new JSONArray().put(new JSONObject().put("text", text.length() > 6000 ? text.substring(0, 6000) : text))));
            JSONObject r = call("POST", "/" + modelPath(model) + ":embedContent", body, cancel);
            JSONArray v = r.getJSONObject("embedding").getJSONArray("values");
            float[] f = new float[v.length()];
            for (int i = 0; i < f.length; i++) f[i] = (float) v.getDouble(i);
            return f;
        } catch (JSONException e) {
            throw new GeminiException(Kind.PARSE, 200, "embedding missing");
        }
    }

    // ------------------------------------------------------------------ transport

    JSONObject call(String method, String path, JSONObject body, CancelToken cancel) throws GeminiException {
        if (apiKey.isEmpty()) throw new GeminiException(Kind.NO_KEY, 0, "no key");
        GeminiException last = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (cancel != null && cancel.isCancelled()) throw new GeminiException(Kind.CANCELLED, 0, "cancelled");
            try {
                return once(method, path, body, cancel);
            } catch (GeminiException e) {
                last = e;
                boolean retryable = e.kind == Kind.RATE_LIMIT || e.kind == Kind.SERVER || e.kind == Kind.TIMEOUT;
                if (!retryable || attempt == maxRetries) throw e;
                long delay = (long) (600 * Math.pow(2, attempt) + Math.random() * 250);
                try { sleeper.sleep(delay); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new GeminiException(Kind.CANCELLED, 0, "interrupted");
                }
            }
        }
        throw last;
    }

    private JSONObject once(String method, String path, JSONObject body, CancelToken cancel) throws GeminiException {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(baseUrl + path).openConnection();
            if (cancel != null) cancel.conn = c;
            c.setConnectTimeout(Math.min(timeoutMs, 15000));
            c.setReadTimeout(timeoutMs);
            c.setRequestMethod(method);
            c.setRequestProperty("x-goog-api-key", apiKey);
            c.setRequestProperty("Accept", "application/json");
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] out = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = c.getOutputStream()) { os.write(out); }
            }
            int code = c.getResponseCode();
            String text = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code >= 400) {
                String msg = redact(providerMessage(text));
                // Google returns HTTP 400 INVALID_ARGUMENT (reason API_KEY_INVALID) for a bad key: that is a key problem.
                if (code == 400 && (text.contains("API_KEY_INVALID") || msg.toLowerCase(java.util.Locale.ROOT).contains("api key not valid")))
                    throw new GeminiException(Kind.AUTH, code, msg);
                switch (code) {
                    case 400: throw new GeminiException(Kind.BAD_REQUEST, code, msg);
                    case 401: case 403: throw new GeminiException(Kind.AUTH, code, msg);
                    case 404: throw new GeminiException(Kind.NOT_FOUND, code, msg);
                    case 429: throw new GeminiException(Kind.RATE_LIMIT, code, msg);
                    default: throw new GeminiException(code >= 500 ? Kind.SERVER : Kind.BAD_REQUEST, code, msg);
                }
            }
            try { return new JSONObject(text); }
            catch (JSONException e) { throw new GeminiException(Kind.PARSE, code, "response was not JSON"); }
        } catch (GeminiException e) {
            throw e;
        } catch (UnknownHostException e) {
            throw new GeminiException(Kind.DNS, 0, "could not resolve " + host());
        } catch (SSLException e) {
            throw new GeminiException(Kind.TLS, 0, e.getClass().getSimpleName());
        } catch (SocketTimeoutException e) {
            throw new GeminiException(Kind.TIMEOUT, 0, "timeout");
        } catch (ConnectException | NoRouteToHostException e) {
            throw new GeminiException(Kind.CONNECT, 0, e.getClass().getSimpleName());
        } catch (InterruptedIOException e) {
            throw new GeminiException(Kind.CANCELLED, 0, "interrupted");
        } catch (IOException e) {
            if (cancel != null && cancel.isCancelled()) throw new GeminiException(Kind.CANCELLED, 0, "cancelled");
            throw new GeminiException(Kind.CONNECT, 0, e.getClass().getSimpleName());
        } finally {
            if (c != null) c.disconnect();
            if (cancel != null) cancel.conn = null;
        }
    }

    private static String providerMessage(String body) {
        try {
            JSONObject err = new JSONObject(body).optJSONObject("error");
            if (err != null) return err.optString("message", "error");
        } catch (JSONException ignored) {}
        return body.length() > 160 ? body.substring(0, 160) : body;
    }

    /** Removes anything that looks like a key or token from provider messages. */
    public static String redact(String s) {
        if (s == null) return "";
        return s.replaceAll("AIza[0-9A-Za-z_\\-]{10,}", "[redacted-key]")
            .replaceAll("(?i)(key|token)=([^&\\s]+)", "$1=[redacted]");
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream is = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n, total = 0;
            while ((n = is.read(buf)) > 0) {
                total += n;
                if (total > 4_000_000) throw new IOException("response too large");
                bo.write(buf, 0, n);
            }
            return bo.toString("UTF-8");
        }
    }
}
