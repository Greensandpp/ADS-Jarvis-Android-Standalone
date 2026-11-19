package com.adsjarvis.standalone;

import static org.junit.Assert.*;

import com.adsjarvis.standalone.CommandEngine.Action;
import com.adsjarvis.standalone.CommandEngine.Lang;
import com.adsjarvis.standalone.CommandEngine.Result;
import org.junit.Test;

public class CommandEngineTest {
    private static Result r(String s) { return CommandEngine.run(s, ""); }

    @Test public void v04BehaviourIsPreserved() {
        assertEquals(Action.NONE, r("hello").action);
        assertEquals(Action.SAVE_MEMORY, r("remember blue").action);
        assertEquals("blue", r("remember blue").memoryValue);
        assertEquals(Action.NONE, r("remember " + "x".repeat(251)).action);
        assertTrue(CommandEngine.run("what do you remember", "blue").response.contains("blue"));
        assertEquals(Action.CLEAR_MEMORY, CommandEngine.run("clear memory", "blue").action);
        assertEquals(Action.OPEN_SETTINGS, r("open settings").action);
        assertEquals(Action.NONE, r("open settings and delete files").action);
        assertFalse(r("open settings and delete files").askAi);
        assertTrue(r("vanakkam").response.contains("Vanakkam"));
    }

    @Test public void languageDetection() {
        assertEquals(Lang.ENGLISH, CommandEngine.detectLanguage("what is the weather"));
        assertEquals(Lang.TAMIL, CommandEngine.detectLanguage("யூடியூப் திற"));
        assertEquals(Lang.TANGLISH, CommandEngine.detectLanguage("youtube open pannu"));
        assertEquals(Lang.TANGLISH, CommandEngine.detectLanguage("time enna da"));
    }

    @Test public void openAppEnglishTanglishTamil() {
        Result en = r("open YouTube");
        assertEquals(Action.OPEN_APP, en.action);
        assertEquals("youtube", en.argument);
        assertEquals("whatsapp", r("whatsapp open pannu").argument);
        assertEquals(Action.OPEN_APP, r("youtube thira").action);
        assertEquals("google maps", r("launch google maps").argument);
        Result ta = r("யூடியூப் திற");
        assertEquals(Action.OPEN_APP, ta.action);
        assertEquals("யூடியூப்", ta.argument);
        assertEquals(Lang.TAMIL, ta.lang);
    }

    @Test public void destructiveOrCompoundTargetsAreRefused() {
        for (String s : new String[]{"open gpay and pay 500", "open files and delete everything",
                "launch uninstall tool", "open " + "a".repeat(60)}) {
            Result x = r(s);
            assertNotEquals(s, Action.OPEN_APP, x.action);
            assertFalse(s, x.askAi);
        }
    }

    @Test public void alarmsAndTimers() {
        Result a = r("set an alarm for 6:30 am");
        assertEquals(Action.SET_ALARM, a.action);
        assertEquals(6, a.hour); assertEquals(30, a.minute);
        Result pm = r("set alarm 7 pm");
        assertEquals(19, pm.hour);
        assertEquals(Action.SET_ALARM, r("alarm vai 5 mani").action);
        Result ta = r("6 மணிக்கு அலாரம்");
        assertEquals(Action.SET_ALARM, ta.action);
        assertEquals(6, ta.hour);
        assertEquals(Action.NONE, r("set alarm 25:00").action);
        Result t = r("set a timer for 5 minutes");
        assertEquals(Action.SET_TIMER, t.action);
        assertEquals(300, t.hour);
        assertEquals(45, r("timer 45 seconds").hour);
    }

    @Test public void deviceCommands() {
        assertEquals(Action.TORCH_ON, r("torch on").action);
        assertEquals(Action.TORCH_ON, r("torch podu").action);
        assertEquals(Action.TORCH_OFF, r("turn off the flashlight").action);
        assertEquals(Action.VOLUME_UP, r("volume up").action);
        assertEquals(Action.VOLUME_DOWN, r("volume kammi pannu").action);
        assertEquals(Action.BATTERY, r("battery level").action);
        assertEquals(Action.BATTERY, r("பேட்டரி எவ்வளவு").action);
        assertEquals(Action.TIME, r("what time is it").action);
        assertEquals(Action.TIME, r("what's the time").action);
        assertEquals(Action.TIME, r("time enna").action);
        assertEquals(Action.TIME, r("நேரம் என்ன").action);
        assertEquals(Action.DATE, r("what's the date").action);
        assertEquals(Action.OPEN_WIFI, r("open wifi").action);
        assertEquals(Action.OPEN_BLUETOOTH, r("bluetooth").action);
    }

    @Test public void dialAndSearchNeverActWithoutUser() {
        Result d = r("call +91 98765 43210");
        assertEquals(Action.DIAL, d.action);
        assertEquals("+919876543210", d.argument);
        assertTrue(d.response.contains("Press call yourself"));
        assertNotEquals(Action.DIAL, r("call mom and send money").action);
        Result s = r("search for best biryani in chennai");
        assertEquals(Action.WEB_SEARCH, s.action);
        assertEquals("best biryani in chennai", s.argument);
    }

    @Test public void unknownGoesToAiWithLanguage() {
        Result x = r("explain quantum computing simply");
        assertEquals(Action.NONE, x.action);
        assertTrue(x.askAi);
        assertEquals("explain quantum computing simply", x.argument);
        assertEquals(Lang.TANGLISH, r("enna nadakuthu inniku").lang);
        assertTrue(r("enna nadakuthu inniku").askAi);
    }
}
