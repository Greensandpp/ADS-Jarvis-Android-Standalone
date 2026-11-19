package com.adsjarvis.standalone.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** One long-term memory with provenance, timestamps and correction history. */
public final class Memory {
    public enum Category { PREFERENCE, SUMMARY, CORRECTION, WORKFLOW, TASK, KNOWLEDGE, DEVICE, TRADING, TOOL_OUTCOME, FEEDBACK }

    public long id;
    public Category category = Category.KNOWLEDGE;
    /** Normalised topic, e.g. "preferred_language"; same key + different text = correction. */
    public String key = "";
    public String text = "";
    /** user | assistant | tool | import */
    public String source = "user";
    public long createdAt, updatedAt;
    public int uses;
    public boolean pinned;
    /** Previous values, newest last: [{"text":..,"at":..}] */
    public JSONArray history = new JSONArray();
    public float[] embedding;

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject().put("id", id).put("category", category.name()).put("key", key).put("text", text)
            .put("source", source).put("createdAt", createdAt).put("updatedAt", updatedAt).put("uses", uses)
            .put("pinned", pinned).put("history", history);
        return o;
    }

    public static Memory fromJson(JSONObject o) throws JSONException {
        Memory m = new Memory();
        m.id = o.optLong("id");
        m.category = Category.valueOf(o.optString("category", "KNOWLEDGE"));
        m.key = o.optString("key", "");
        m.text = o.getString("text");
        m.source = o.optString("source", "import");
        m.createdAt = o.optLong("createdAt");
        m.updatedAt = o.optLong("updatedAt");
        m.uses = o.optInt("uses");
        m.pinned = o.optBoolean("pinned");
        JSONArray h = o.optJSONArray("history");
        m.history = h == null ? new JSONArray() : h;
        return m;
    }

    /** Line injected into the AI prompt (retrieval-augmented generation). */
    public String promptLine() {
        return "[" + category.name().toLowerCase() + (key.isEmpty() ? "" : ":" + key) + "] " + text;
    }
}
