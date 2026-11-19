package com.adsjarvis.standalone.core;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Long-term memory logic: proposals (owner approves before saving), duplicate
 * and conflict handling (a conflicting value becomes a correction with
 * history), relevance-ranked retrieval and deletion. Nothing is learned
 * silently; every write goes through {@link #commit}.
 */
public final class MemoryEngine {
    public enum Outcome { NEW, DUPLICATE, CORRECTION }

    public static final class Proposal {
        public final Outcome outcome; public final Memory memory; public final Memory existing;
        Proposal(Outcome o, Memory m, Memory e) { outcome = o; memory = m; existing = e; }
        public String describe() {
            switch (outcome) {
                case DUPLICATE: return "Already remembered: " + existing.text;
                case CORRECTION: return "Update memory \"" + existing.key + "\": \"" + existing.text + "\" → \"" + memory.text + "\"";
                default: return "Remember (" + memory.category.name().toLowerCase() + "): " + memory.text;
            }
        }
    }

    public interface Clock { long now(); }

    private final MemoryRepo repo;
    private final Clock clock;
    private boolean enabled = true;

    public MemoryEngine(MemoryRepo repo) { this(repo, System::currentTimeMillis); }
    public MemoryEngine(MemoryRepo repo, Clock clock) { this.repo = repo; this.clock = clock; }

    public void setEnabled(boolean on) { enabled = on; }
    public boolean isEnabled() { return enabled; }
    public MemoryRepo repo() { return repo; }

    public static String normKey(String k) {
        if (k == null) return "";
        return Normalizer.normalize(k, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).trim()
            .replaceAll("[^\\p{L}\\p{M}\\p{N}]+", "_").replaceAll("^_+|_+$", "");
    }

    public Proposal propose(Memory.Category cat, String key, String text, String source) {
        if (text == null || text.trim().isEmpty()) throw new IllegalArgumentException("empty memory");
        if (text.length() > 2000) throw new IllegalArgumentException("memory too long");
        Memory m = new Memory();
        m.category = cat; m.key = normKey(key); m.text = text.trim(); m.source = source;
        for (Memory e : repo.all()) {
            if (sameText(e.text, m.text)) return new Proposal(Outcome.DUPLICATE, m, e);
            if (!m.key.isEmpty() && m.key.equals(e.key) && e.category == cat) return new Proposal(Outcome.CORRECTION, m, e);
        }
        return new Proposal(Outcome.NEW, m, null);
    }

    /** Persists an approved proposal. Returns the stored memory. */
    public Memory commit(Proposal p) {
        long now = clock.now();
        switch (p.outcome) {
            case DUPLICATE:
                p.existing.uses++; p.existing.updatedAt = now; repo.update(p.existing); return p.existing;
            case CORRECTION:
                try { p.existing.history.put(new JSONObject().put("text", p.existing.text).put("at", p.existing.updatedAt)); }
                catch (JSONException ignored) {}
                p.existing.text = p.memory.text;
                p.existing.source = p.memory.source;
                p.existing.updatedAt = now;
                repo.update(p.existing);
                return p.existing;
            default:
                p.memory.createdAt = now; p.memory.updatedAt = now;
                repo.insert(p.memory);
                return p.memory;
        }
    }

    public boolean delete(long id) { if (repo.get(id) == null) return false; repo.delete(id); return true; }

    public List<Memory> corrections() {
        List<Memory> out = new ArrayList<>();
        for (Memory m : repo.all()) if (m.history.length() > 0 || m.category == Memory.Category.CORRECTION) out.add(m);
        return out;
    }

    public Memory byKey(String key) {
        String k = normKey(key);
        for (Memory m : repo.all()) if (m.key.equals(k)) return m;
        return null;
    }

    // ------------------------------------------------------------------ retrieval

    static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        for (String t : Normalizer.normalize(s, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{M}\\p{N}]+")) {
            if (t.length() >= 2 && !STOP.contains(t)) out.add(t);
        }
        return out;
    }

    private static final Set<String> STOP = new HashSet<>(java.util.Arrays.asList(
        "the", "a", "an", "is", "are", "to", "of", "and", "or", "in", "on", "for", "my", "me", "i", "you", "it", "what", "do",
        "please", "jarvis", "can", "be", "with", "that", "this", "was", "la", "da", "pannu", "enna"));

    public static final class Hit {
        public final Memory memory; public final double score;
        Hit(Memory m, double s) { memory = m; score = s; }
    }

    /**
     * BM25-style lexical relevance + recency + usage + pin boost; if both sides
     * have embeddings, cosine similarity is blended in (semantic search).
     */
    public List<Hit> search(String query, float[] queryEmbedding, int k) {
        List<Memory> all = repo.all();
        List<String> q = tokens(query);
        Map<String, Integer> df = new HashMap<>();
        double avgLen = 0;
        List<List<String>> docs = new ArrayList<>();
        for (Memory m : all) {
            List<String> d = tokens(m.text + " " + m.key.replace('_', ' '));
            docs.add(d);
            avgLen += d.size();
            for (String t : new HashSet<>(d)) df.merge(t, 1, Integer::sum);
        }
        avgLen = all.isEmpty() ? 1 : Math.max(1, avgLen / all.size());
        long now = clock.now();
        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            Memory m = all.get(i);
            List<String> d = docs.get(i);
            double lex = 0;
            for (String t : q) {
                int tf = 0; for (String x : d) if (x.equals(t) || (t.length() > 4 && x.startsWith(t.substring(0, t.length() - 1)))) tf++;
                if (tf == 0) continue;
                double idf = Math.log(1 + (all.size() - df.getOrDefault(t, 0) + .5) / (df.getOrDefault(t, 0) + .5));
                lex += idf * (tf * 2.2) / (tf + 1.2 * (.25 + .75 * d.size() / avgLen));
            }
            double sem = 0;
            if (queryEmbedding != null && m.embedding != null && m.embedding.length == queryEmbedding.length) sem = cosine(queryEmbedding, m.embedding);
            double days = Math.max(0, (now - m.updatedAt) / 86_400_000.0);
            double recency = Math.exp(-days / 60.0);
            double score = lex + 3.0 * Math.max(0, sem - .5) + (lex > 0 || sem > .55 ? .25 * recency + .05 * Math.min(m.uses, 10) : 0)
                + (m.pinned ? .4 : 0) + (m.category == Memory.Category.PREFERENCE && lex > 0 ? .3 : 0);
            if (score > .35) hits.add(new Hit(m, score));
        }
        hits.sort((a, b) -> Double.compare(b.score, a.score));
        return hits.size() > k ? new ArrayList<>(hits.subList(0, k)) : hits;
    }

    /** Memories to inject into the prompt: pinned preferences always, plus top relevant ones. */
    public List<String> contextFor(String query, float[] queryEmbedding, int k) {
        if (!enabled) return new ArrayList<>();
        List<String> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (Memory m : repo.all()) if (m.pinned && seen.add(m.id)) out.add(m.promptLine());
        for (Hit h : search(query, queryEmbedding, k)) {
            if (seen.add(h.memory.id)) {
                out.add(h.memory.promptLine());
                h.memory.uses++;
                repo.update(h.memory);
            }
        }
        return out;
    }

    static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]; }
        return na == 0 || nb == 0 ? 0 : dot / Math.sqrt(na * nb);
    }

    private static boolean sameText(String a, String b) {
        return normKey(a).equals(normKey(b));
    }
}
