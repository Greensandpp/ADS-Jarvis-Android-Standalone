package com.adsjarvis.standalone.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Small builder for tool parameter schemas. */
public final class Schema {
    private final JSONObject props = new JSONObject();
    private final JSONArray required = new JSONArray();

    public static Schema obj() { return new Schema(); }

    public Schema str(String name, String desc, boolean req) { return prop(name, "string", desc, req, null); }
    public Schema num(String name, String desc, boolean req) { return prop(name, "number", desc, req, null); }
    public Schema bool(String name, String desc, boolean req) { return prop(name, "boolean", desc, req, null); }
    public Schema oneOf(String name, String desc, boolean req, String... values) { return prop(name, "string", desc, req, values); }

    private Schema prop(String name, String type, String desc, boolean req, String[] values) {
        try {
            JSONObject p = new JSONObject().put("type", type).put("description", desc);
            if (values != null) { JSONArray e = new JSONArray(); for (String v : values) e.put(v); p.put("enum", e); }
            props.put(name, p);
            if (req) required.put(name);
        } catch (JSONException ignored) {}
        return this;
    }

    public JSONObject build() {
        try {
            JSONObject o = new JSONObject().put("type", "object").put("properties", props);
            if (required.length() > 0) o.put("required", required);
            return o;
        } catch (JSONException e) { throw new IllegalStateException(e); }
    }
}
