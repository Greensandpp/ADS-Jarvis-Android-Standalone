package com.adsjarvis.standalone.core;

import org.json.JSONObject;

/**
 * A capability the AI may call. Tools are registered in {@link ToolRegistry};
 * the model chooses them from their declarations, so new abilities are added
 * by registering reviewed tools, not by hardcoding user phrases.
 */
public interface Tool {
    /** Internal specialist responsible for the tool (JARVIS supervises all). */
    enum Agent {
        RESEARCH("Research Intelligence"), TRADING("Trading Analyst"), ENGINEERING("Software Engineering"),
        AUTOMATION("Android/PC Automation"), MEMORY("Memory & Planning");
        public final String label;
        Agent(String l) { label = l; }
    }

    /** GREEN read/search/draft; YELLOW changes local data/settings; RED external or irreversible. */
    enum Tier { GREEN, YELLOW, RED }

    String name();
    String description();
    /** OpenAPI-subset JSON schema of the arguments (Gemini functionDeclarations format). */
    JSONObject parameters();
    Agent agent();
    Tier tier();
    boolean needsNetwork();
    ToolResult run(JSONObject args) throws Exception;
}
