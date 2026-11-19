package com.adsjarvis.standalone.core;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLException;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Step-by-step Gemini connectivity check. Each layer is tested separately so a
 * DNS failure, TLS failure, bad key and wrong model produce different results.
 * Status becomes ONLINE only when a real generateContent call succeeds.
 */
public final class Diagnostics {
    public enum Status { PASS, FAIL, SKIPPED }

    public static final class Step {
        public final String name; public final Status status; public final String detail; public final long ms;
        Step(String name, Status status, String detail, long ms) { this.name = name; this.status = status; this.detail = detail; this.ms = ms; }
        @Override public String toString() { return status + "  " + name + " (" + ms + " ms) — " + detail; }
    }

    public static final class Report {
        public final List<Step> steps = new ArrayList<>();
        public boolean online;
        public List<GeminiClient.ModelInfo> models = new ArrayList<>();
        public String workingModel = "";
        public String summary() {
            StringBuilder sb = new StringBuilder(online ? "ONLINE via " + workingModel : "NOT ONLINE").append('\n');
            for (Step s : steps) sb.append(s).append('\n');
            return sb.toString().trim();
        }
    }

    public interface Resolver { InetAddress[] resolve(String host) throws UnknownHostException; }

    private final Resolver resolver;
    public Diagnostics() { this(InetAddress::getAllByName); }
    public Diagnostics(Resolver resolver) { this.resolver = resolver; }

    public Report run(GeminiClient client, String baseUrl, String preferredModel) {
        Report rep = new Report();
        String host = client.host();
        long t0 = System.currentTimeMillis();
        // 1. DNS
        try {
            InetAddress[] a = resolver.resolve(host);
            rep.steps.add(new Step("DNS " + host, Status.PASS, a.length + " address(es)", System.currentTimeMillis() - t0));
        } catch (UnknownHostException e) {
            rep.steps.add(new Step("DNS " + host, Status.FAIL, "cannot resolve host — check internet / Private DNS", System.currentTimeMillis() - t0));
            skipRest(rep, "DNS failed");
            return rep;
        }
        // 2. TLS / HTTPS reachability (no key sent)
        t0 = System.currentTimeMillis();
        try {
            URL u = new URL(baseUrl.replaceAll("/+$", "") + "/models");
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(10000); c.setReadTimeout(10000);
            int code = c.getResponseCode(); // 403 without key is expected and proves TLS works
            String tls = c instanceof HttpsURLConnection ? "TLS ok" : "plain HTTP (local test server)";
            c.disconnect();
            rep.steps.add(new Step("HTTPS handshake", Status.PASS, tls + ", HTTP " + code + " without key", System.currentTimeMillis() - t0));
        } catch (SSLException e) {
            rep.steps.add(new Step("HTTPS handshake", Status.FAIL, "TLS error " + e.getClass().getSimpleName() + " — check clock, VPN, filtering", System.currentTimeMillis() - t0));
            skipRest(rep, "TLS failed");
            return rep;
        } catch (IOException e) {
            rep.steps.add(new Step("HTTPS handshake", Status.FAIL, "connection failed: " + e.getClass().getSimpleName(), System.currentTimeMillis() - t0));
            skipRest(rep, "connection failed");
            return rep;
        }
        // 3. Auth + model discovery
        t0 = System.currentTimeMillis();
        try {
            rep.models = client.listModels(null);
            int gen = 0; for (GeminiClient.ModelInfo m : rep.models) if (m.generate) gen++;
            rep.steps.add(new Step("API key + model discovery", Status.PASS, gen + " chat models available", System.currentTimeMillis() - t0));
        } catch (GeminiClient.GeminiException e) {
            rep.steps.add(new Step("API key + model discovery", Status.FAIL, e.userMessage(), System.currentTimeMillis() - t0));
            if (e.kind == GeminiClient.Kind.AUTH || e.kind == GeminiClient.Kind.NO_KEY) { skipRest(rep, "key problem"); return rep; }
        }
        // 4. Real generation with fallback
        t0 = System.currentTimeMillis();
        List<String> order = GeminiClient.fallbackOrder(preferredModel, rep.models);
        try {
            JSONObject body = new JSONObject().put("contents", new JSONArray().put(new JSONObject().put("role", "user")
                .put("parts", new JSONArray().put(new JSONObject().put("text", "Reply with the single word OK.")))));
            JSONObject r = client.generate(order.isEmpty() ? java.util.Collections.singletonList(preferredModel) : order, body, null);
            rep.workingModel = client.lastModelUsed();
            boolean fellBack = !rep.workingModel.equals(preferredModel);
            rep.steps.add(new Step("Generate with " + rep.workingModel, Status.PASS,
                (fellBack ? "preferred model '" + preferredModel + "' unavailable, fell back. " : "") + "reply received", System.currentTimeMillis() - t0));
            rep.online = r.has("candidates");
        } catch (Exception e) {
            String msg = e instanceof GeminiClient.GeminiException ? ((GeminiClient.GeminiException) e).userMessage() : e.getClass().getSimpleName();
            rep.steps.add(new Step("Generate with " + preferredModel, Status.FAIL, msg, System.currentTimeMillis() - t0));
        }
        return rep;
    }

    private static void skipRest(Report rep, String why) {
        String[] names = {"HTTPS handshake", "API key + model discovery", "Generate"};
        for (String n : names) {
            boolean done = false;
            for (Step s : rep.steps) if (s.name.startsWith(n)) done = true;
            if (!done) rep.steps.add(new Step(n, Status.SKIPPED, "skipped: " + why, 0));
        }
    }
}
