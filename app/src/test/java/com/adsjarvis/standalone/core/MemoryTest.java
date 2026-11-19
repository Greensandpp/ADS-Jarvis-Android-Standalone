package com.adsjarvis.standalone.core;

import static org.junit.Assert.*;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class MemoryTest {
    long now = 1_000_000_000L;
    MemoryEngine eng = new MemoryEngine(new MemoryRepo.InMemory(), () -> now);

    private Memory add(Memory.Category c, String k, String t) { return eng.commit(eng.propose(c, k, t, "user")); }

    @Test public void newDuplicateAndCorrectionWithHistory() {
        MemoryEngine.Proposal p = eng.propose(Memory.Category.PREFERENCE, "Preferred language", "Owner prefers Tamil replies", "user");
        assertEquals(MemoryEngine.Outcome.NEW, p.outcome);
        Memory m = eng.commit(p);
        assertEquals("preferred_language", m.key);
        assertEquals(MemoryEngine.Outcome.DUPLICATE, eng.propose(Memory.Category.PREFERENCE, "x", "owner prefers  Tamil replies!", "user").outcome);
        now += 1000;
        MemoryEngine.Proposal c = eng.propose(Memory.Category.PREFERENCE, "preferred language", "Owner prefers Tanglish replies", "user");
        assertEquals(MemoryEngine.Outcome.CORRECTION, c.outcome);
        assertTrue(c.describe().contains("Tamil") && c.describe().contains("Tanglish"));
        eng.commit(c);
        assertEquals(1, eng.repo().all().size());
        Memory after = eng.byKey("preferred_language");
        assertEquals("Owner prefers Tanglish replies", after.text);
        assertEquals("Owner prefers Tamil replies", after.history.optJSONObject(0).optString("text"));
        assertEquals(1, eng.corrections().size());
    }

    @Test public void relevanceRankingFindsTheRightMemory() {
        add(Memory.Category.PREFERENCE, "preferred_language", "Owner prefers Tanglish replies");
        add(Memory.Category.TRADING, "risk", "Maximum risk per NIFTY entry is 1200 rupees");
        add(Memory.Category.KNOWLEDGE, "", "Owner's car is a white Creta");
        add(Memory.Category.KNOWLEDGE, "", "அம்மாவின் பிறந்தநாள் மார்ச் 5");
        List<MemoryEngine.Hit> h = eng.search("what language do I prefer?", null, 3);
        assertEquals("preferred_language", h.get(0).memory.key);
        assertTrue(eng.search("nifty risk per trade", null, 3).get(0).memory.text.contains("1200"));
        assertTrue(eng.search("பிறந்தநாள் எப்போது", null, 3).get(0).memory.text.contains("மார்ச்"));
        assertTrue(eng.search("weather in delhi", null, 3).isEmpty());
    }

    @Test public void semanticEmbeddingsBlendIn() {
        Memory a = add(Memory.Category.KNOWLEDGE, "", "Owner drinks filter coffee every morning");
        Memory b = add(Memory.Category.KNOWLEDGE, "", "Owner's gym is near Anna Nagar");
        a.embedding = new float[]{1, 0, 0}; b.embedding = new float[]{0, 1, 0};
        List<MemoryEngine.Hit> h = eng.search("morning beverage habit", new float[]{.95f, .1f, 0}, 2);
        assertEquals(a.id, h.get(0).memory.id);
    }

    @Test public void contextIncludesPinnedAndRespectsDisable() {
        Memory p = add(Memory.Category.PREFERENCE, "name", "Call the owner Karthi");
        p.pinned = true;
        eng.repo().update(p);
        assertTrue(eng.contextFor("random question", null, 4).get(0).contains("Karthi"));
        eng.setEnabled(false);
        assertTrue(eng.contextFor("Karthi", null, 4).isEmpty());
    }

    @Test public void deleteRemoves() {
        Memory m = add(Memory.Category.KNOWLEDGE, "", "temporary note");
        assertTrue(eng.delete(m.id));
        assertFalse(eng.delete(m.id));
        assertTrue(eng.repo().all().isEmpty());
    }

    @Test public void encryptedBackupRoundTripAndTamperDetection() throws Exception {
        add(Memory.Category.PREFERENCE, "lang", "Tamil");
        JSONArray arr = new JSONArray();
        for (Memory m : eng.repo().all()) arr.put(m.toJson());
        JSONObject payload = new JSONObject().put("memories", arr).put("settings", new JSONObject().put("visual", "APEX"));
        String file = Backup.encrypt(payload, "correct horse".toCharArray());
        assertFalse(file.contains("Tamil"));
        JSONObject back = Backup.decrypt(file, "correct horse".toCharArray());
        assertEquals("Tamil", Memory.fromJson(back.getJSONArray("memories").getJSONObject(0)).text);
        try { Backup.decrypt(file, "wrong password".toCharArray()); fail(); }
        catch (Backup.BackupException e) { assertTrue(e.getMessage().contains("Wrong password")); }
        JSONObject t = new JSONObject(file);
        String d = t.getString("data");
        t.put("data", (d.charAt(0) == 'A' ? 'B' : 'A') + d.substring(1));
        try { Backup.decrypt(t.toString(), "correct horse".toCharArray()); fail(); }
        catch (Backup.BackupException e) { assertNotNull(e.getMessage()); }
        try { Backup.encrypt(payload, "short".toCharArray()); fail(); } catch (Backup.BackupException e) { }
        try { Backup.decrypt("{}", "x".toCharArray()); fail(); } catch (Backup.BackupException e) { }
    }

    @Test public void evalSuiteScoresAnswers() throws Exception {
        List<EvalSuite.Case> cases = EvalSuite.parse(EvalSuite.DEFAULT);
        EvalSuite.Result good = EvalSuite.run(cases, p -> p.contains("55%") ? "NO TRADE: confidence below 70%."
            : p.contains("live order") ? "I only work in paper mode." : p.contains("UPI") ? "I never handle PINs." : "I'm JARVIS.");
        assertEquals(4, good.passed);
        EvalSuite.Result bad = EvalSuite.run(cases, p -> "Buy now! Order placed. Payment done.");
        assertEquals(0, bad.passed);
        assertEquals(1.0, good.score(), 1e-9);
    }
}
