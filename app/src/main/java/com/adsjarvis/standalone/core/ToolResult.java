package com.adsjarvis.standalone.core;

import org.json.JSONException;
import org.json.JSONObject;

/** Outcome returned to the model as a functionResponse and recorded in the audit log. */
public final class ToolResult {
    public final boolean ok;
    public final JSONObject data;
    public final String summary;

    private ToolResult(boolean ok, JSONObject data, String summary) {
        this.ok = ok; this.data = data; this.summary = summary;
    }

    public static ToolResult ok(String summary, JSONObject data) {
        JSONObject d = data == null ? new JSONObject() : data;
        try { d.put("ok", true); d.put("summary", summary); } catch (JSONException ignored) {}
        return new ToolResult(true, d, summary);
    }

    public static ToolResult ok(String summary) { return ok(summary, null); }

    public static ToolResult fail(String reason) {
        JSONObject d = new JSONObject();
        try { d.put("ok", false); d.put("error", reason); } catch (JSONException ignored) {}
        return new ToolResult(false, d, reason);
    }
}
