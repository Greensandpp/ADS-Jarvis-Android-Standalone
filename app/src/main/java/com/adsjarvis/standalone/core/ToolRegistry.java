package com.adsjarvis.standalone.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Extensible tool catalog. Owner can disable tools; disabled tools are never offered to the model. */
public final class ToolRegistry {
    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final Set<String> disabled = new HashSet<>();

    public ToolRegistry register(Tool t) {
        if (!t.name().matches("[a-z][a-z0-9_]{1,62}")) throw new IllegalArgumentException("bad tool name " + t.name());
        if (tools.containsKey(t.name())) throw new IllegalArgumentException("duplicate tool " + t.name());
        tools.put(t.name(), t);
        return this;
    }

    public Tool get(String name) { return tools.get(name); }
    public Collection<Tool> all() { return tools.values(); }
    public boolean isEnabled(String name) { return tools.containsKey(name) && !disabled.contains(name); }
    public void setEnabled(String name, boolean on) { if (on) disabled.remove(name); else disabled.add(name); }
    public Set<String> disabledNames() { return new HashSet<>(disabled); }

    public List<Tool> enabled(boolean online) {
        List<Tool> out = new ArrayList<>();
        for (Tool t : tools.values()) if (!disabled.contains(t.name()) && (online || !t.needsNetwork())) out.add(t);
        return out;
    }

    /** Gemini `tools` array with one functionDeclarations entry. */
    public JSONArray declarations(boolean online) {
        try {
            JSONArray decl = new JSONArray();
            for (Tool t : enabled(online)) {
                decl.put(new JSONObject().put("name", t.name())
                    .put("description", t.description() + " [agent: " + t.agent().label + "; approval tier: " + t.tier() + "]")
                    .put("parameters", t.parameters()));
            }
            return new JSONArray().put(new JSONObject().put("functionDeclarations", decl));
        } catch (JSONException e) { throw new IllegalStateException(e); }
    }

    /** Human-readable capability/permission summary (used offline and for planning answers). */
    public String capabilitySummary(boolean online) {
        StringBuilder sb = new StringBuilder();
        for (Tool.Agent a : Tool.Agent.values()) {
            StringBuilder line = new StringBuilder();
            for (Tool t : enabled(online)) if (t.agent() == a) line.append(line.length() == 0 ? "" : ", ").append(t.name())
                .append(t.tier() == Tool.Tier.GREEN ? "" : " (" + t.tier() + " approval)");
            if (line.length() > 0) sb.append("• ").append(a.label).append(": ").append(line).append('\n');
        }
        return sb.toString().trim();
    }
}
