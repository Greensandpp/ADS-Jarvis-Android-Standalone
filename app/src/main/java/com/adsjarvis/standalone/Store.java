package com.adsjarvis.standalone;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Shared settings. Uses the same private prefs file as v0.4/v1.0 ("MainActivity")
 * so the owner's memory note, style, history and encrypted key are preserved.
 */
public final class Store {
    private Store() {}
    public static SharedPreferences prefs(Context c) { return c.getSharedPreferences("MainActivity", Context.MODE_PRIVATE); }

    // General
    public static final String NAME = "assistant_name", ACCENT = "accent", REPLY_LANG = "reply_lang", INPUT_LANG = "voice_lang",
        STARTUP_LISTEN = "startup_listen", STARTUP_GREET = "startup_greet", NOTIFY = "notify_reminders";
    // AI
    public static final String PROVIDER = "ai_provider", BASE = "ai_base_url", MODEL = "ai_model", MODEL_AUTO = "ai_model_auto",
        FALLBACK = "ai_fallback", DISCOVERED = "ai_discovered_models", APPROVE_YELLOW = "approve_yellow", RESP_LEN = "response_length",
        LAST_OK = "ai_last_ok", LAST_OK_MODEL = "ai_last_ok_model", VERIFIED = "ai_verified", LAST_ERR = "ai_last_errors", EMBED_MODEL = "embed_model";
    // Voice
    public static final String FEMALE = "prefer_female", VOICE_GENDER = "voice_gender", VOICE = "tts_voice", PITCH = "tts_pitch", SPEED = "tts_speed",
        WAKE = "wake_enabled", WAKE_WORD = "wake_word", WAKE_SENS = "wake_sensitivity", CONVO = "conversation_mode", SPEAK_REPLIES = "speak_replies";
    // Memory
    public static final String MEM_ON = "memory_enabled", MEM_AUTO = "memory_auto_approve", EVAL_HISTORY = "eval_history";
    // Trading
    public static final String T_INSTR = "t_instrument", T_HTF = "t_htf_confirm", T_IND = "t_indicators", T_CONF = "t_conf", T_RISK = "t_risk",
        T_DAILY = "t_daily", T_NLOT = "t_nifty_lot", T_SLOT = "t_sensex_lot", T_SLIP = "t_slippage", T_BROK = "t_brokerage",
        T_STAT = "t_statutory", T_JOURNAL = "t_journal_csv", PB7_URL = "pb7_url", PB7_TOKEN = "pb7_token_enc";
    // Visual
    public static final String STYLE = "visual_style", QUALITY = "v_quality", FPS = "v_fps", DENSITY = "v_density", GLOW = "v_glow",
        SPEED_V = "v_speed", BRIGHT = "brightness", REDUCE = "reduce_motion", SAVER = "v_battery_saver";
    // Security
    public static final String APP_LOCK = "app_lock";
    // Legacy keys from v1.0
    public static final String HISTORY = "history_v1", HINTED = "longpress_hint_shown";
}
