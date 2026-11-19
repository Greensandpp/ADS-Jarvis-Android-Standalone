# GreenSoil JARVIS 2.0 — Feature matrix

Evidence sources: JVM unit tests (44 in CI run 44583046038, run on the Android unit-test
classpath), and the Phase 11 acceptance script (`scripts/acceptance.sh`) run on
Android 15 (API 35) and Android 16 (API 36) emulators in GitHub Actions: 36 PASS · 0 FAIL · 7 UNVERIFIED on each.
Nothing here was verified on the owner's iQOO 13, and no real Gemini key was
available, so cloud-AI answers are **UNVERIFIED**.

## Phase 0 audit (before this work, v1.0.0 on `feature/jarvis-real-app`)
- UI technology: native Android Java, programmatic views (no XML layouts, no WebView, no Compose, no Flutter).
- Working: canvas HUD (5 styles), chat dock, SpeechRecognizer, TTS, local command engine (EN/TA/Tanglish), Gemini/OpenAI chat without tools, Keystore key storage, history persistence.
- Broken/limited: AI was a plain chat call with no tools; status said "AI ONLINE" from configuration alone; no settings screen (long-press dialog only); no long-term memory beyond one note; no trading logic; visuals were canvas-only.
- The "Could not reach Google" / DNS-TLS / HTTP 404 screenshots referenced in the request were not provided to this session; the strings do not occur in any accessible branch. Root causes were addressed generically (below).

## Matrix

| Area | Requirement | Status | Evidence |
|---|---|---|---|
| AI | Gemini generateContent with function calling | WORKING (mock) / UNVERIFIED (live) | BrainTest.toolCallingRoundTripUsesRealToolResult |
| AI | Model discovery, manual/auto selection, fallback | WORKING (mock) | BrainTest.modelDiscoveryAndFallbackOrder, fallsBackToNextModelOn404 |
| AI | DNS / TLS / connect / timeout / 400 / 401 / 403 / 404 / 429 / 5xx distinguished | WORKING | BrainTest (unit) + live emulator: DNS FAIL when offline; real Google 400 API_KEY_INVALID → key error |
| AI | Retry + exponential backoff, cancel, timeout | WORKING | BrainTest.retriesWithBackoff…, cancelStopsTheRequest, timeoutIs… |
| AI | ONLINE only after verified request | WORKING | status shows "AI NOT CONNECTED"/"NOT VERIFIED" in acceptance T15b/T16 |
| AI | Never stuck in THINKING | WORKING | AssistantState deadline watchdog + unit test |
| AI | Tool catalog, plan/execute/verify loop, approvals | WORKING (mock) | BrainTest red/disabled/unknown/max-steps tests |
| AI | Real Gemini answers, chart vision, AI planning | UNVERIFIED | no Gemini key provided (request declined) |
| Offline | Time/date, alarms, reminders, apps, notes, journal audit | WORKING | acceptance T02, T14a–d, T12 |
| Memory | Approved writes, duplicates, corrections + history, ranking, RAG, deletion | WORKING | MemoryTest; acceptance T05, T06, T20 |
| Memory | Semantic (embedding) retrieval | PARTIAL | ranking blends embeddings (unit-tested); embeddings are not yet computed on device |
| Memory | Encrypted export/import with integrity check | WORKING (unit) / UNVERIFIED (device UI) | MemoryTest.encryptedBackupRoundTripAndTamperDetection |
| Learning | Feedback/corrections retrieved later; supervised EvalSuite | WORKING (unit); eval run needs AI | MemoryTest.evalSuiteScoresAnswers |
| Trading | Risk gate (09:30, 5m close, ≥70%, ₹1,200, ₹3,000/day, single position, stale quote, SL size) | WORKING | TradingTest (10 independent blocks) |
| Trading | Lot sizing, TP1–TP8, trailing plan | WORKING | TradingTest |
| Trading | Journal audit (errors, violations, giveback, hypotheses) | WORKING | TradingTest; acceptance T12 on device |
| Trading | Live market data, OI, bid/ask | MISSING | no market-data API connected (blocked: credentials/provider) |
| Trading | PB7 PAPER read-only status | WORKING as NOT CONNECTED | acceptance T11; needs owner's read-only endpoint |
| Trading | Telegram reports | MISSING | backlog |
| Agents | Five internal agents, delegation via tools, audit, task history, cancel | WORKING (mock AI) | Brain + Settings → Agents; Office visual shows real tool activity |
| Voice | Mic permission, recognizer, error reporting, TTS, pitch/speed/voice | WORKING (emulator) | acceptance T18a–c |
| Voice | Tamil/English/Tanglish recognition quality, female voice availability | UNVERIFIED | emulator has no real mic; depends on iQOO TTS voices |
| Voice | In-app wake word (foreground) | WORKING (enabled) / UNVERIFIED (detection) | acceptance T18e |
| Voice | Background / lock-screen wake word | MISSING (by design) | needs foreground mic service; not tested on device |
| Device | Apps, alarms, timers, reminders, calendar, dialer, SMS/WhatsApp drafts, TradingView, battery/network | WORKING (apps/alarm/settings on emulator); others UNVERIFIED on device | acceptance T07, T14b |
| PC | Windows pairing / agent | MISSING | backlog: companion app not built |
| Visuals | GPU ARC, AIRA, MIRA, APEX, Office; long-press + voice switching; persistence | WORKING | acceptance T08, T17, T19; GL programs=5 |
| Visuals | Reference-video fidelity (photoreal humanoids) | NOT MET | procedural shaders only; no licensed 3D assets |
| Visuals | FPS on iQOO 13 | UNVERIFIED | emulator uses software GPU (2–17 FPS, not representative) |
| Settings | 11 sections, functional controls, honest 'not available' rows | WORKING | acceptance T07; screenshots |
| Security | Keystore secrets, app lock, audit log, revocation, data deletion | WORKING (code) / app lock UNVERIFIED on device | |
| Build | Debug APK, validation, prerelease | WORKING | CI run logs |

## Backlog (blocked or not yet built)
1. Live Gemini verification — needs the owner's key (enter in ⚙ → AI Brain, run the test).
2. Device embeddings for semantic memory (`embedContent` is implemented in GeminiClient; wire into commit/query).
3. Live market data / OI / bid-ask — needs a broker or data-vendor API and credentials.
4. Telegram reporting — needs bot token and owner approval.
5. Windows companion agent with pairing, file transfer and remote launch.
6. Background/lock-screen wake word — needs a microphone foreground service and on-device testing.
7. Photoreal APEX/MIRA humanoids — needs licensed or owner-supplied 3D/animation assets.
8. Stable release signing key — CI debug keys change per build, so updates require reinstalling the test app.
