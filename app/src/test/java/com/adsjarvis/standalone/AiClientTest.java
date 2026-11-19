package com.adsjarvis.standalone;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** TEST DOUBLE: local MockWebServer imitating Gemini/OpenAI APIs — not a live provider test. */
public class AiClientTest {
    private MockWebServer server;

    @Before public void start() throws Exception { server = new MockWebServer(); server.start(); }
    @After public void stop() throws Exception { server.shutdown(); }

    private String base() { return server.url("/").toString().replaceAll("/$", ""); }
    private void respond(int code, String body) { server.enqueue(new MockResponse().setResponseCode(code).setBody(body)); }

    private static List<AiClient.Turn> hist() {
        return new ArrayList<>(Arrays.asList(new AiClient.Turn(true, "hi"), new AiClient.Turn(false, "Vanakkam!")));
    }

    @Test public void geminiRequestAndReply() throws Exception {
        respond(200, "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Chennai is sunny.\"}]}}]}");
        AiClient.Reply r = new AiClient(3000).chat(
            new AiClient.Config(AiClient.Provider.GEMINI, base(), "gemini-2.5-flash", "test-key-123"), hist(), "weather?");
        assertTrue(r.text, r.ok);
        assertEquals("Chennai is sunny.", r.text);
        RecordedRequest req = server.takeRequest(2, TimeUnit.SECONDS);
        assertEquals("/models/gemini-2.5-flash:generateContent", req.getPath());
        assertEquals("test-key-123", req.getHeader("x-goog-api-key"));
        String body = req.getBody().readUtf8();
        JSONObject b = new JSONObject(body);
        assertEquals(3, b.getJSONArray("contents").length());
        assertEquals("model", b.getJSONArray("contents").getJSONObject(1).getString("role"));
        assertTrue(b.getJSONObject("systemInstruction").toString().contains("Tanglish"));
        assertFalse("key must not be in body", body.contains("test-key-123"));
    }

    @Test public void openAiCompatibleRequestAndReply() throws Exception {
        respond(200, "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Sure da!\"}}]}");
        AiClient.Reply r = new AiClient(3000).chat(
            new AiClient.Config(AiClient.Provider.OPENAI_COMPATIBLE, base() + "/v1/", "gpt-x", "sk-test"), hist(), "help me");
        assertTrue(r.ok);
        assertEquals("Sure da!", r.text);
        RecordedRequest req = server.takeRequest(2, TimeUnit.SECONDS);
        assertEquals("/v1/chat/completions", req.getPath());
        assertEquals("Bearer sk-test", req.getHeader("Authorization"));
        JSONObject b = new JSONObject(req.getBody().readUtf8());
        assertEquals("system", b.getJSONArray("messages").getJSONObject(0).getString("role"));
        assertEquals("gpt-x", b.getString("model"));
    }

    @Test public void httpErrorsBecomeClearMessages() {
        AiClient c = new AiClient(3000);
        AiClient.Config cfg = new AiClient.Config(AiClient.Provider.GEMINI, base(), "m", "bad");
        respond(401, "{\"error\":{\"message\":\"API key not valid. key=bad\"}}");
        AiClient.Reply r = c.chat(cfg, hist(), "x");
        assertFalse(r.ok);
        assertTrue(r.text.contains("rejected the API key"));
        assertFalse("raw provider error must not leak", r.text.contains("key=bad"));
        respond(429, "{}"); assertTrue(c.chat(cfg, hist(), "x").text.contains("429"));
        respond(503, "{}"); assertTrue(c.chat(cfg, hist(), "x").text.contains("having problems"));
        respond(404, "{}"); assertTrue(c.chat(cfg, hist(), "x").text.contains("model name"));
        respond(200, "{\"candidates\":[]}"); assertTrue(c.chat(cfg, hist(), "x").text.contains("empty answer"));
        respond(200, "not json"); assertTrue(c.chat(cfg, hist(), "x").text.contains("could not read"));
    }

    @Test public void timeoutAndUnreachable() {
        server.enqueue(new MockResponse().setBody("{}").setHeadersDelay(1500, TimeUnit.MILLISECONDS));
        AiClient.Reply r = new AiClient(300).chat(new AiClient.Config(AiClient.Provider.GEMINI, base(), "m", "k"), hist(), "x");
        assertFalse(r.ok);
        assertTrue(r.text, r.text.contains("timed out"));
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
