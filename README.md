# GreenSoil JARVIS — Android (native Java, no Flutter / WebView)

A personal AI assistant: Gemini tool-calling brain, long-term memory, five
internal agents, GreenSoil PAPER-ONLY trading intelligence, GPU visuals and a
complete settings screen. Independent of OpenClaw, Railway, PB7 code and Pine
scripts — it never modifies them.

## Architecture

```
app/src/main/java/com/adsjarvis/standalone/
  core/                 pure Java, JVM-tested (43 tests)
    GeminiClient        generateContent + function calling, model discovery, embeddings,
                        retry/backoff, cancel, fallback, DNS/TLS/HTTP error classes
    Brain               LLM tool-calling loop (plan → tool → verify → answer), approvals, audit
    ToolRegistry/Tool   extensible catalog; agent + GREEN/YELLOW/RED tier per tool
    AssistantState      OFFLINE→CONNECTING→READY→LISTENING→THINKING→SPEAKING (+ERROR/RETRYING/CANCELLED), watchdog
    Diagnostics         DNS → TLS → key/model discovery → real generation
    MemoryEngine        approved writes, duplicates, corrections + history, ranked retrieval (RAG)
    Backup              PBKDF2 + AES-256-GCM + SHA-256 encrypted export/import
    EvalSuite           supervised, measurable evaluation (no self-modification)
    RiskEngine          GreenSoil rules gate, lot sizing, TP1–TP8, trailing plan (PAPER ONLY)
    JournalAnalyzer     journal stats, data errors, rule violations, hypotheses
    LocalIntents        instant offline intents (name, settings, visual, voice, language)
  Jarvis                app controller (provider, brain, memory, state, verified ONLINE status)
  AndroidTools          ~30 registered tools (device, apps, reminders, drafts, trading, research, GitHub read)
  MainActivity          home: menu · J.A.R.V.I.S. status · gear · GPU visual · activity · chat · dock
  SettingsActivity      11 sections, all functional
  VisualEngine          OpenGL ES 2.0 shaders (assets/shaders): ARC, AIRA, MIRA, APEX, OFFICE
  VoiceInput/Speaker    speech recognition (push-to-talk, in-app wake word), TTS (voice, pitch, speed)
  JarvisDb              private SQLite: memories, audit log, tasks, reminders
  KeyVault              Android Keystore AES-GCM for API key / PB7 token
```

## Setup (owner)
1. Install the APK from the latest `v2.0.0-jarvis-debug` prerelease.
2. ⚙ → **AI Brain** → paste your Gemini API key → **Save** → **Test connection + discover models**.
   JARVIS shows **AI ONLINE** only after a real request succeeds.
3. ⚙ → **Voice** → grant microphone, pick language / voice / pitch / speed.
4. Optional: ⚙ → **Trading AI** → import your journal CSV, PB7 read-only URL.

## Permissions
INTERNET, ACCESS_NETWORK_STATE, RECORD_AUDIO (runtime), SET_ALARM, POST_NOTIFICATIONS (runtime).
No accessibility, overlay, contacts, SMS, call, location or package-listing permissions.

## Recovery
- AI stuck/failing: ⚙ → Diagnostics → run the connection test; read the redacted error log (AI Brain).
- Bad settings: ⚙ → Backup → restore an encrypted backup, or Security → Delete ALL JARVIS data.
- Crash: ⚙ → Diagnostics → Crash log.
- Uninstalling the test app removes all JARVIS data (no cloud copies exist).

See `docs/FEATURE_MATRIX.md` for the verified status of every requirement.
