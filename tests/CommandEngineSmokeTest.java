package com.adsjarvis.standalone;
public class CommandEngineSmokeTest {
    private static void expect(boolean c, String s) { if(!c)throw new AssertionError(s); }
    public static void main(String[] args) {
        expect(CommandEngine.run("hello", "").action==CommandEngine.Action.NONE,"hello");
        expect(CommandEngine.run("remember blue", "").action==CommandEngine.Action.SAVE_MEMORY,"memory save");
        expect(CommandEngine.run("remember "+"x".repeat(251), "").action==CommandEngine.Action.NONE,"length validation");
        expect(CommandEngine.run("what do you remember", "blue").response.contains("blue"),"memory read");
        expect(CommandEngine.run("clear memory", "blue").action==CommandEngine.Action.CLEAR_MEMORY,"memory clear");
        expect(CommandEngine.run("open settings", "").action==CommandEngine.Action.OPEN_SETTINGS,"settings action");
        expect(CommandEngine.run("open settings and delete files", "").action==CommandEngine.Action.NONE,"no broad unsafe match");
        expect(CommandEngine.run("vanakkam", "").response.contains("Vanakkam"),"Tamil greeting");
        System.out.println("PASS: 8 isolated command engine assertions");
    }
}
