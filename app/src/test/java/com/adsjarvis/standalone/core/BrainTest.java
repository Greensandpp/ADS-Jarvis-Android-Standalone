package com.adsjarvis.standalone.core;

import static org.junit.Assert.*;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Drives the real Brain + GeminiClient against a server speaking the Gemini REST protocol. */
public class BrainTest {
    private MockWebServer server;
    private final List<Long> sleeps = new ArrayList<>();

    @Before public void up() throws Exception { server = new MockWebServer(); server.start(); }
    @After public void down() throws Exception { server.shutdown(); }

    private GeminiClient client(String key) {
        return new GeminiClient(server.url("/v1beta").toString(), key, 2000, 2, sleeps::add);
    }
    private void json(int code, String body) { server.enqueue(new MockResponse().setResponseCode(code).setBody(body)); }

    static Tool tool(String name, Tool.Tier tier, boolean net, AtomicInteger runs, ToolResult result) {
        return new Tool() {
            public String name() { return name; }
            public String description() { return "test tool " + name; }
            public JSONObject parameters() { return Schema.obj().str("q", "query", false).build(); }
            public Agent agent() { return Agent.AUTOMATION; }
            public Tier tier() { return tier; }
            public boolean needsNetwork() { return net; }
            public ToolResult run(JSONObject a) { runs.incrementAndGet(); return result; }
        };
    }

    static Brain.Request req(String text) { Brain.Request r = new Brain.Request(); r.userText = text; return r; }

    static String callResp(String name, String args) {
        return "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"functionCall\":{\"name\":\"" + name
            + "\",\"args\":" + args + "},\"thoughtSignature\":\"sig123\"}]},\"finishReason\":\"STOP\"}]}";
    }
    static String textResp(String t) {
        return "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"" + t + "\"}]},\"finishReason\":\"STOP\"}]}";
    }

    @Test public void toolCallingRoundTripUsesRealToolResult() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        ToolRegistry reg = new ToolRegistry().register(tool("get_time", Tool.Tier.GREEN, false, runs,
            ToolResult.ok("10:42", new JSONObject().put("time", "10:42"))));
        json(200, callResp("get_time", "{}"));
        json(200, textResp("It is 10:42."));
        List<String> audit = new ArrayList<>();
        Brain b = new Brain(client("k-123"), reg, Arrays.asList("gemini-x")).audit((t, ag, tier, a, ap, ok, s) -> audit.add(t + ":" + ok));
        Brain.Request r = new Brain.Request();
        r.userText = "what's the time";
        r.memories = Collections.singletonList("Owner prefers Tanglish");
        Brain.Answer ans = b.run(r, null);
        assertEquals("It is 10:42.", ans.text);
        assertEquals(1, runs.get());
        assertEquals(Collections.singletonList("get_time"), ans.toolsUsed);
        assertEquals(Collections.singletonList("get_time:true"), audit);

        RecordedRequest first = server.takeRequest(1, TimeUnit.SECONDS);
        assertEquals("/v1beta/models/gemini-x:generateContent", first.getPath());
        assertEquals("k-123", first.getHeader("x-goog-api-key"));
        JSONObject b1 = new JSONObject(first.getBody().readUtf8());
        assertEquals("get_time", b1.getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations").getJSONObject(0).getString("name"));
        assertTrue(b1.getJSONObject("systemInstruction").toString().contains("Owner prefers Tanglish"));
        assertTrue(b1.getJSONObject("systemInstruction").toString().contains("NO TRADE"));

        JSONObject b2 = new JSONObject(server.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8());
        JSONArray c = b2.getJSONArray("contents");
        assertEquals("sig123", c.getJSONObject(1).getJSONArray("parts").getJSONObject(0).getString("thoughtSignature"));
        JSONObject fr = c.getJSONObject(2).getJSONArray("parts").getJSONObject(0).getJSONObject("functionResponse");
        assertEquals("get_time", fr.getString("name"));
        assertEquals("10:42", fr.getJSONObject("response").getString("time"));
    }

    @Test public void redToolIsBlockedWithoutApprovalAndModelIsTold() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        ToolRegistry reg = new ToolRegistry().register(tool("send_message", Tool.Tier.RED, false, runs, ToolResult.ok("sent")));
        json(200, callResp("send_message", "{\"q\":\"hi\"}"));
        json(200, textResp("I did not send it because you declined."));
        Brain b = new Brain(client("k"), reg, Arrays.asList("m")).approver((t, a) -> false);
        Brain.Answer ans = b.run(req("send hi"), null);
        assertEquals(0, runs.get());
        assertTrue(ans.toolsUsed.get(0).contains("failed"));
        server.takeRequest();
        String second = server.takeRequest().getBody().readUtf8();
        assertTrue(second.contains("did not approve"));
    }

    @Test public void disabledAndUnknownToolsNeverRun() {
        AtomicInteger runs = new AtomicInteger();
        ToolRegistry reg = new ToolRegistry().register(tool("open_app", Tool.Tier.GREEN, false, runs, ToolResult.ok("ok")));
        reg.setEnabled("open_app", false);
        Brain b = new Brain(client("k"), reg, Arrays.asList("m"));
        assertFalse(b.execute("open_app", new JSONObject()).ok);
        assertFalse(b.execute("rm_rf", new JSONObject()).ok);
        assertEquals(0, runs.get());
        assertFalse(reg.declarations(true).toString().contains("open_app"));
    }

    @Test public void offlineHidesNetworkTools() {
        AtomicInteger runs = new AtomicInteger();
        ToolRegistry reg = new ToolRegistry()
            .register(tool("web_research", Tool.Tier.GREEN, true, runs, null))
            .register(tool("get_time", Tool.Tier.GREEN, false, runs, null));
        String off = reg.declarations(false).toString();
        assertFalse(off.contains("web_research"));
        assertTrue(off.contains("get_time"));
    }

    @Test public void retriesWithBackoffOn429And503ThenSucceeds() throws Exception {
        json(429, "{\"error\":{\"message\":\"quota\"}}");
        json(503, "{\"error\":{\"message\":\"overloaded\"}}");
        json(200, textResp("ok"));
        Brain b = new Brain(client("k"), new ToolRegistry(), Arrays.asList("m"));
        assertEquals("ok", b.run(req("x"), null).text);
        assertEquals(2, sleeps.size());
        assertTrue(sleeps.get(1) > sleeps.get(0));
    }

    @Test public void fallsBackToNextModelOn404() throws Exception {
        json(404, "{\"error\":{\"message\":\"models/old is not found for API version v1beta\"}}");
        json(200, textResp("hello"));
        GeminiClient c = client("k");
        Brain b = new Brain(c, new ToolRegistry(), Arrays.asList("old-model", "gemini-2.5-flash"));
        Brain.Answer a = b.run(req("x"), null);
        assertEquals("gemini-2.5-flash", a.model);
        assertTrue(server.takeRequest().getPath().contains("old-model"));
        assertTrue(server.takeRequest().getPath().contains("gemini-2.5-flash"));
    }

    @Test public void errorKindsAreDistinctAndRedacted() {
        GeminiClient c = client("AIzaSECRETSECRETSECRET123");
        String[][] cases = {{"400", "BAD_REQUEST"}, {"401", "AUTH"}, {"403", "AUTH"}, {"404", "NOT_FOUND"}};
        for (String[] k : cases) {
            json(Integer.parseInt(k[0]), "{\"error\":{\"message\":\"bad key AIzaSECRETSECRETSECRET123 key=AIzaSECRETSECRETSECRET123\"}}");
            try { c.generate(Arrays.asList("m"), new JSONObject(), null); fail(); }
            catch (GeminiClient.GeminiException e) {
                assertEquals(k[1], e.kind.name());
                assertFalse(e.getMessage().contains("SECRET"));
                assertFalse(e.userMessage().contains("SECRET"));
            }
        }
        for (int i = 0; i < 3; i++) json(500, "{}");
        try { c.generate(Arrays.asList("m"), new JSONObject(), null); fail(); }
        catch (GeminiClient.GeminiException e) { assertEquals(GeminiClient.Kind.SERVER, e.kind); }
        // Behind an HTTP proxy (this sandbox) DNS happens at the proxy; CI and phones resolve directly.
        if (System.getProperty("https.proxyHost") == null) {
        GeminiClient dns = new GeminiClient("https://no-such-host.invalid/v1beta", "k", 2000, 0, ms -> {});
        try { dns.generate(Arrays.asList("m"), new JSONObject(), null); fail(); }
        catch (GeminiClient.GeminiException e) {
            assertEquals(GeminiClient.Kind.DNS, e.kind);
            assertTrue(e.userMessage().startsWith("DNS failure"));
        }
        }
        GeminiClient nokey = new GeminiClient(server.url("/v1beta").toString(), "", 2000, 0, ms -> {});
        try { nokey.generate(Arrays.asList("m"), new JSONObject(), null); fail(); }
        catch (GeminiClient.GeminiException e) { assertEquals(GeminiClient.Kind.NO_KEY, e.kind); }
    }

    /** Exact error body Google returned to the Android 15/16 emulators for an invalid key (CI run 44580226200). */
    @Test public void realGoogleInvalidKeyResponseIsAuthNotBadRequest() {
        json(400, "{\"error\":{\"code\":400,\"message\":\"API key not valid. Please pass a valid API key.\",\"status\":\"INVALID_ARGUMENT\","
            + "\"details\":[{\"@type\":\"type.googleapis.com/google.rpc.ErrorInfo\",\"reason\":\"API_KEY_INVALID\",\"domain\":\"googleapis.com\"}]}}");
        try { client("bad").generate(Arrays.asList("m"), new JSONObject(), null); fail(); }
        catch (GeminiClient.GeminiException e) {
            assertEquals(GeminiClient.Kind.AUTH, e.kind);
            assertEquals(400, e.httpStatus);
            assertTrue(e.userMessage().contains("rejected the API key"));
        }
        json(400, "{\"error\":{\"message\":\"Invalid JSON payload received.\"}}");
        try { client("k").generate(Arrays.asList("m"), new JSONObject(), null); fail(); }
        catch (GeminiClient.GeminiException e) { assertEquals(GeminiClient.Kind.BAD_REQUEST, e.kind); }
    }

    @Test public void timeoutIsReportedAsTimeoutNotModelError() {
        GeminiClient c = new GeminiClient(server.url("/v1beta").toString(), "k", 300, 1, sleeps::add);
        for (int i = 0; i < 2; i++) server.enqueue(new MockResponse().setBody("{}").setHeadersDelay(1500, TimeUnit.MILLISECONDS));
        try { c.generate(Arrays.asList("m"), new JSONObject(), null); fail(); }
        catch (GeminiClient.GeminiException e) { assertEquals(GeminiClient.Kind.TIMEOUT, e.kind); }
        assertEquals(1, sleeps.size());
    }

    @Test public void cancelStopsTheRequest() throws Exception {
        server.enqueue(new MockResponse().setBody(textResp("late")).setHeadersDelay(3, TimeUnit.SECONDS));
        GeminiClient c = new GeminiClient(server.url("/v1beta").toString(), "k", 10000, 0, ms -> {});
        GeminiClient.CancelToken tok = new GeminiClient.CancelToken();
        new Thread(() -> { try { Thread.sleep(300); } catch (InterruptedException ignored) {} tok.cancel(); }).start();
        long t0 = System.currentTimeMillis();
        try { c.generate(Arrays.asList("m"), new JSONObject(), tok); fail(); }
        catch (GeminiClient.GeminiException e) { assertEquals(GeminiClient.Kind.CANCELLED, e.kind); }
        assertTrue(System.currentTimeMillis() - t0 < 2500);
    }

    @Test public void modelDiscoveryAndFallbackOrder() throws Exception {
        json(200, "{\"models\":[{\"name\":\"models/gemini-2.0-flash\",\"supportedGenerationMethods\":[\"generateContent\"]},"
            + "{\"name\":\"models/text-embedding-004\",\"supportedGenerationMethods\":[\"embedContent\"]}],\"nextPageToken\":\"p2\"}");
        json(200, "{\"models\":[{\"name\":\"models/gemini-2.5-flash\",\"supportedGenerationMethods\":[\"generateContent\",\"countTokens\"]},"
            + "{\"name\":\"models/gemini-2.5-pro\",\"supportedGenerationMethods\":[\"generateContent\"]}]}");
        List<GeminiClient.ModelInfo> ms = client("k").listModels(null);
        assertEquals(4, ms.size());
        assertTrue(server.takeRequest().getPath().startsWith("/v1beta/models?pageSize="));
        assertTrue(server.takeRequest().getPath().contains("pageToken=p2"));
        List<String> order = GeminiClient.fallbackOrder("my-model", ms);
        assertEquals(Arrays.asList("my-model", "gemini-2.5-flash", "gemini-2.0-flash", "gemini-2.5-pro"), order);
    }

    @Test public void diagnosticsSeparateDnsFromKeyFromModel() throws Exception {
        Diagnostics noDns = new Diagnostics(h -> { throw new UnknownHostException(h); });
        Diagnostics.Report r1 = noDns.run(client("k"), server.url("/v1beta").toString(), "m");
        assertFalse(r1.online);
        assertEquals(Diagnostics.Status.FAIL, r1.steps.get(0).status);
        assertEquals(Diagnostics.Status.SKIPPED, r1.steps.get(1).status);

        Diagnostics ok = new Diagnostics(h -> new InetAddress[]{InetAddress.getLoopbackAddress()});
        json(403, "{}");                                   // handshake probe without key
        json(401, "{\"error\":{\"message\":\"API key not valid\"}}");
        Diagnostics.Report r2 = ok.run(client("bad"), server.url("/v1beta").toString(), "m");
        assertEquals(Diagnostics.Status.PASS, r2.steps.get(1).status);
        assertEquals(Diagnostics.Status.FAIL, r2.steps.get(2).status);
        assertTrue(r2.steps.get(2).detail.contains("rejected the API key"));
        assertFalse(r2.online);

        json(403, "{}");
        json(200, "{\"models\":[{\"name\":\"models/gemini-2.5-flash\",\"supportedGenerationMethods\":[\"generateContent\"]}]}");
        json(404, "{\"error\":{\"message\":\"not found\"}}");
        json(200, textResp("OK"));
        Diagnostics.Report r3 = ok.run(client("good"), server.url("/v1beta").toString(), "gemini-1.0-pro");
        assertTrue(r3.summary(), r3.online);
        assertEquals("gemini-2.5-flash", r3.workingModel);
        assertTrue(r3.steps.get(3).detail.contains("fell back"));
    }

    @Test public void stateMachineRejectsIllegalAndEnforcesDeadline() {
        long[] now = {0};
        AssistantState s = new AssistantState(() -> now[0]);
        assertTrue(s.to(AssistantState.S.CONNECTING, ""));
        assertTrue(s.to(AssistantState.S.READY, ""));
        assertFalse(s.to(AssistantState.S.RETRYING, ""));
        assertTrue(s.to(AssistantState.S.THINKING, ""));
        assertFalse(s.enforceDeadline());
        now[0] += AssistantState.THINKING_DEADLINE_MS + 1;
        assertTrue(s.enforceDeadline());
        assertEquals(AssistantState.S.ERROR, s.get());
        assertTrue(s.to(AssistantState.S.READY, ""));
    }

    @Test public void blockedPromptIsReported() {
        json(200, "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}");
        try { new Brain(client("k"), new ToolRegistry(), Arrays.asList("m")).run(req("x"), null); fail(); }
        catch (GeminiClient.GeminiException e) { assertEquals(GeminiClient.Kind.BLOCKED, e.kind); }
    }

    @Test public void stopsAfterMaxSteps() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        ToolRegistry reg = new ToolRegistry().register(tool("loop", Tool.Tier.GREEN, false, runs, ToolResult.ok("again")));
        for (int i = 0; i < Brain.MAX_STEPS; i++) json(200, callResp("loop", "{}"));
        Brain.Answer a = new Brain(client("k"), reg, Arrays.asList("m")).run(req("x"), null);
        assertEquals(Brain.MAX_STEPS, runs.get());
        assertTrue(a.text.contains("stopped"));
    }
}
