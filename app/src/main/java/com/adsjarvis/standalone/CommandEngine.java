package com.adsjarvis.standalone;

import java.util.Locale;

/** Pure Java command parser: intentionally has no OS, network or device access. */
public final class CommandEngine {
    public enum Action { NONE, SAVE_MEMORY, CLEAR_MEMORY, OPEN_SETTINGS }
    public static final class Result {
        public final Action action;
        public final String response;
        public final String memoryValue;
        Result(Action action, String response, String memoryValue) {
            this.action=action;this.response=response;this.memoryValue=memoryValue;
        }
    }
    private CommandEngine() {}
    public static Result run(String text,String savedMemory) {
        String raw=text==null?"":text.trim();
        String q=raw.toLowerCase(Locale.ROOT);
        if(q.startsWith("remember ")) {
            String memo=raw.substring(9).trim();
            if(memo.isEmpty() || memo.length()>250)return new Result(Action.NONE,"Memory must be between 1 and 250 characters.","");
            return new Result(Action.SAVE_MEMORY,"Saved on this device: "+memo,memo);
        }
        if(q.equals("what do you remember") || q.equals("show memory")) {
            String memo=savedMemory==null?"":savedMemory;
            return new Result(Action.NONE,memo.isEmpty()?"No memory saved yet.":"I remember: "+memo,"");
        }
        if(q.equals("forget memory") || q.equals("clear memory"))return new Result(Action.CLEAR_MEMORY,"Local memory cleared.","");
        if(q.equals("open settings"))return new Result(Action.OPEN_SETTINGS,"Opening Android Settings.","");
        if(q.equals("hello") || q.equals("hi") || q.equals("vanakkam"))return new Result(Action.NONE,"Vanakkam! ADS Jarvis Android prototype is ready.","");
        return new Result(Action.NONE,"I heard: "+raw+". Cloud AI is not connected in this standalone version.","");
    }
}
