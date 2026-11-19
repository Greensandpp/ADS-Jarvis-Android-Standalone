package com.adsjarvis.standalone;

import static org.junit.Assert.*;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Exercises the real HTTP client against a local server (no internet, no real key). */
public class AiClientTest {
    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> auth = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String response = "";
    private volatile long delayMs = 0;

    @Before public void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            path.set(ex.getRequestURI().getPath());
            String a = ex.getRequestHeaders().getFirst("x-goog-api-key");
            auth.set(a != null ? "goog:" + a : ex.getRequestHeaders().getFirst("Authorization"));
            try (InputStream in = ex.getRequestBody()) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                in.transferTo(bo);
                body.set(bo.toString("UTF-8"));
            }
            try { Thread.sleep(delayMs); } catch (InterruptedException ignored) {}
            byte[] out = response.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, out.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(out); }
        });
        server.start();
    }

    @After public void stop() { server.stop(0); }

    private String base() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    private static List<AiClient.Turn> hist() {
        return new ArrayList<>(Arrays.asList(new AiClient.Turn(true, "hi"), new AiClient.Turn(false, "Vanakkam!")));
    }

    @Test public void geminiRequestAndReply() throws Exception {
        response = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Chennai is sunny.\"}]}}]}";
        AiClient.Reply r = new AiClient(3000).chat(
            new AiClient.Config(AiClient.Provider.GEMINI, base(), "gemini-2.5-flash", "test-key-123"), hist(), "weather?");
        assertTrue(r.text, r.ok);
        assertEquals("Chennai is sunny.", r.text);
        assertEquals("/models/gemini-2.5-flash:generateContent", path.get());
        assertEquals("goog:test-key-123", auth.get());
        JSONObject b = new JSONObject(body.get());
        assertEquals(3, b.getJSONArray("contents").length());
        assertEquals("model", b.getJSONArray("contents").getJSONObject(1).getString("role"));
        assertTrue(b.getJSONObject("systemInstruction").toString().contains("Tanglish"));
        assertFalse("key must not be in body", body.get().contains("test-key-123"));
    }

    @Test public void openAiCompatibleRequestAndReply() throws Exception {
        response = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Sure da!\"}}]}";
        AiClient.Reply r = new AiClient(3000).chat(
            new AiClient.Config(AiClient.Provider.OPENAI_COMPATIBLE, base() + "/v1/", "gpt-x", "sk-test"), hist(), "help me");
        assertTrue(r.ok);
        assertEquals("Sure da!", r.text);
        assertEquals("/v1/chat/completions", path.get());
        assertEquals("Bearer sk-test", auth.get());
        JSONObject b = new JSONObject(body.get());
        assertEquals("system", b.getJSONArray("messages").getJSONObject(0).getString("role"));
        assertEquals("gpt-x", b.getString("model"));
    }

    @Test public void httpErrorsBecomeClearMessages() {
        AiClient c = new AiClient(3000);
        AiClient.Config cfg = new AiClient.Config(AiClient.Provider.GEMINI, base(), "m", "bad");
        response = "{\"error\":{\"message\":\"API key not valid. key=bad\"}}";
        status = 401;
        AiClient.Reply r = c.chat(cfg, hist(), "x");
        assertFalse(r.ok);
        assertTrue(r.text.contains("rejected the API key"));
        assertFalse("raw provider error must not leak", r.text.contains("key=bad"));
        status = 429; assertTrue(c.chat(cfg, hist(), "x").text.contains("429"));
        status = 503; assertTrue(c.chat(cfg, hist(), "x").text.contains("having problems"));
        status = 404; assertTrue(c.chat(cfg, hist(), "x").text.contains("model name"));
        status = 200; response = "{\"candidates\":[]}";
        assertTrue(c.chat(cfg, hist(), "x").text.contains("empty answer"));
        response = "not json";
        assertTrue(c.chat(cfg, hist(), "x").text.contains("could not read"));
    }

    @Test public void timeoutAndUnreachable() {
        delayMs = 1500;
        response = "{}";
        AiClient.Reply r = new AiClient(300).chat(new AiClient.Config(AiClient.Provider.GEMINI, base(), "m", "k"), hist(), "x");
        assertFalse(r.ok);
        assertTrue(r.text, r.text.contains("timed out"));
        delayMs = 0;
        AiClient.Reply off = new AiClient(2000).chat(
            new AiClient.Config(AiClient.Provider.GEMINI, "https://no-such-host.invalid/v1beta", "m", "k"), hist(), "x");
        assertFalse(off.ok);
        assertTrue(off.text, off.text.contains("internet") || off.text.contains("reach"));
    }

    @Test public void notConfiguredAndUrlPolicy() {
        AiClient.Reply r = new AiClient().chat(new AiClient.Config(AiClient.Provider.GEMINI, "", "", ""), hist(), "x");
        assertFalse(r.ok);
        assertTrue(r.text.contains("not set up"));
        assertNull(AiClient.validUrl("https://generativelanguage.googleapis.com/v1beta"));
        assertNull(AiClient.validUrl("http://127.0.0.1:11434/v1"));
        assertNotNull(AiClient.validUrl("http://example.com"));
        assertNotNull(AiClient.validUrl("https://user:pw@example.com"));
    }

    @Test public void contextIsCappedAt12Turns() {
        List<AiClient.Turn> many = new ArrayList<>();
        for (int i = 0; i < 30; i++) many.add(new AiClient.Turn(i % 2 == 0, "t" + i));
        List<AiClient.Turn> t = AiClient.trim(many);
        assertEquals(12, t.size());
        assertEquals("t29", t.get(11).text);
    }
}
