package com.adsjarvis.standalone.core;

import java.util.EnumSet;
import java.util.Map;
import java.util.EnumMap;

/**
 * OFFLINE → CONNECTING → READY → LISTENING → THINKING → SPEAKING → READY, plus
 * ERROR, RETRYING and CANCELLED. Illegal transitions are rejected; THINKING has
 * a deadline so a failed request can never leave the assistant stuck.
 */
public final class AssistantState {
    public enum S { OFFLINE, CONNECTING, READY, LISTENING, THINKING, RETRYING, SPEAKING, ERROR, CANCELLED }

    private static final Map<S, EnumSet<S>> NEXT = new EnumMap<>(S.class);
    static {
        NEXT.put(S.OFFLINE, EnumSet.of(S.CONNECTING, S.READY, S.LISTENING, S.THINKING, S.SPEAKING, S.ERROR));
        NEXT.put(S.CONNECTING, EnumSet.of(S.READY, S.ERROR, S.OFFLINE, S.CANCELLED));
        NEXT.put(S.READY, EnumSet.of(S.LISTENING, S.THINKING, S.SPEAKING, S.CONNECTING, S.OFFLINE, S.ERROR));
        NEXT.put(S.LISTENING, EnumSet.of(S.THINKING, S.READY, S.ERROR, S.CANCELLED, S.OFFLINE, S.SPEAKING));
        NEXT.put(S.THINKING, EnumSet.of(S.SPEAKING, S.READY, S.ERROR, S.RETRYING, S.CANCELLED, S.OFFLINE));
        NEXT.put(S.RETRYING, EnumSet.of(S.THINKING, S.ERROR, S.CANCELLED, S.READY));
        NEXT.put(S.SPEAKING, EnumSet.of(S.READY, S.LISTENING, S.CANCELLED, S.ERROR, S.OFFLINE));
        NEXT.put(S.ERROR, EnumSet.of(S.READY, S.OFFLINE, S.CONNECTING, S.LISTENING, S.THINKING));
        NEXT.put(S.CANCELLED, EnumSet.of(S.READY, S.OFFLINE, S.LISTENING, S.THINKING));
    }

    public interface Listener { void onState(S from, S to, String detail); }

    public static final long THINKING_DEADLINE_MS = 90_000;

    private S state = S.OFFLINE;
    private long enteredAt;
    private final Clock clock;
    private Listener listener;

    public interface Clock { long now(); }

    public AssistantState() { this(System::currentTimeMillis); }
    public AssistantState(Clock clock) { this.clock = clock; enteredAt = clock.now(); }

    public void setListener(Listener l) { listener = l; }
    public synchronized S get() { return state; }

    public synchronized boolean to(S next, String detail) {
        if (next == state) return true;
        if (!NEXT.get(state).contains(next)) return false;
        S prev = state;
        state = next;
        enteredAt = clock.now();
        if (listener != null) listener.onState(prev, next, detail == null ? "" : detail);
        return true;
    }

    /** Call periodically: if THINKING/RETRYING outlived its deadline, force ERROR. */
    public synchronized boolean enforceDeadline() {
        if ((state == S.THINKING || state == S.RETRYING) && clock.now() - enteredAt > THINKING_DEADLINE_MS) {
            return to(S.ERROR, "timed out");
        }
        return false;
    }
}
