package com.adsjarvis.standalone.core;

import static org.junit.Assert.*;

import com.adsjarvis.standalone.core.LocalIntents.Kind;
import org.junit.Test;

public class LocalIntentsTest {
    private static void is(String s, Kind k, String v) {
        LocalIntents.Match m = LocalIntents.match(s);
        assertNotNull(s, m);
        assertEquals(s, k, m.kind);
        if (v != null) assertEquals(s, v, m.value);
    }

    @Test public void acceptancePhrases() {
        is("What's your name?", Kind.NAME, null);
        is("Jarvis, what is your name", Kind.NAME, null);
        is("உன் பெயர் என்ன?", Kind.NAME, null);
        is("Open settings.", Kind.OPEN_JARVIS_SETTINGS, null);
        is("Change visual to APEX.", Kind.SET_VISUAL, "APEX");
        is("switch to aira", Kind.SET_VISUAL, "AIRA");
        is("change style to agent office", Kind.SET_VISUAL, "OFFICE");
        is("Change to APEX", Kind.SET_VISUAL, "APEX");
        is("mira mode podu", Kind.SET_VISUAL, "MIRA");
        is("Change voice to female.", Kind.SET_VOICE_GENDER, "female");
        is("female voice podu", Kind.SET_VOICE_GENDER, "female");
        is("தமிழ்ல பேசு.", Kind.SET_REPLY_LANG, "tamil");
        is("Tanglish-la answer pannu.", Kind.SET_REPLY_LANG, "tanglish");
        is("reply in english", Kind.SET_REPLY_LANG, "english");
        is("Remember my preferred language.", Kind.REMEMBER_PREF_LANG, null);
        is("open phone settings", Kind.OPEN_PHONE_SETTINGS, null);
        is("what can you do?", Kind.CAPABILITIES, null);
        is("stop", Kind.STOP, null);
    }

    @Test public void openEndedRequestsAreNotIntercepted() {
        for (String s : new String[]{"Analyze this NIFTY chart.", "Explain why my PB7 trade was rejected.",
                "Find errors in my previous trading journal.", "Plan this task and tell me the required permissions.",
                "I want a new option in settings. Help me design it.", "what's the time now", "remember to buy milk"})
            assertNull(s, LocalIntents.match(s));
    }
}
