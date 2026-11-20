# Original JARVIS (≈90 MB) vs native ADS JARVIS (196 KB) — evidence-based comparison

Status: **NOT COMPLETE.** The native app does not contain the original app's features.

## 1. Where the original is

| | Original JARVIS | Native ADS JARVIS v2.1.0 |
|---|---|---|
| Repository | `Greensandpp/Jarvis` (Flutter + Kotlin) | `Greensandpp/ADS-Jarvis-Android-Standalone` (Java) |
| Baseline | `feature/jarvis-four-modes-consolidated-20261115@a04e650` (+ AIRA/5-mode staging `staging/aira-five-modes-20261119@807934c`) | `feature/jarvis-real-app@0dbb25b` |
| Package | `com.greensoil.jarvis.tamiltest[.visualstaging/.visualrelease]` | `com.adsjarvis.standalone.jarvis` |
| Source | ~31,500 lines in 85 files (Dart + Kotlin) | 5,849 lines Java + 17 KB shaders |
| Image assets | 9.4 MB (hologram suit + reactor PNGs) | none |
| Plugins | 21 (speech, TTS, contacts, overlay, notifications, Shizuku, volume, brightness, webview, …) | none (Android framework only) |

The native app was a **separate, new project**; it is not a stripped copy. The original source is
intact and accessible; nothing was deleted from it.

## 2. What the original APK's bytes are (CI audit run 44656419776, built from a04e650)

Debug APK = **172.6 MB** on current Flutter (CI artifact zip 93.3 MB). Release APK = **68.9 MB**.

| Component (debug) | In APK |
|---|---|
| Flutter engine `libflutter.so` × 3 CPU types (x86_64, arm64, armv7) | 109.6 MB |
| Vulkan validation layer (debug-only) | 15.7 MB |
| Dart debug runtime (`kernel_blob.bin`, isolate snapshot) | 30.0 MB |
| Kotlin/Java + plugin code (dex) | 5.8 MB |
| App images (hologram suit, reactor cores, logos) | 9.4 MB |
| Resources, fonts, other | ~2 MB |

In the release build the app's own compiled Dart code (`libapp.so`) is ~8 MB per CPU type.
Conclusion: ~80% of the 90 MB is the Flutter runtime and debug tooling; the genuinely app-specific
content is ~31.5k lines of code, 9.4 MB of images and 21 plugins — and **that content is what the
native app lacks.**

## 3. Module inventory — original vs native

Legend: ✅ present in native · 🟡 partial/different · ❌ missing in native · ⛔ intentionally not ported (security/Play Protect)

### Visual experiences
| Original module | Native | Notes |
|---|---|---|
| `JarvisHudWidget` (2,131 lines): 3D hologram consciousness core, gyroscopic rings, radial audio equalizer, tap ripples, phase colours | 🟡 | native ARC shader is new, not this widget |
| JARVIS **hologram suit avatar** HUD style (uses `jarvis_male_hologram*.png`, torso rings, suit scanlines, emitter pedestal) | ❌ | assets and painters not ported |
| Reactor core images (`jarvis_core_cyan/gold.png`) | ❌ | |
| AIRA 10-model engine, 6 states, picker, per-model settings (staging branch) | 🟡 | native has 1 AIRA plasma shader |
| APEX / MIRA local renderers | 🟡 | native shaders are different, not the originals |
| Agent Office view (honest DISCONNECTED) | 🟡 | native Office shows on-device agent activity |
| Air-gestures HUD, arc-reactor charging HUD, radar/network/optical/translator HUD dialogs | ❌ | |
| Liquid-glass UI (glass widgets, mode switcher, chat bubbles) | ❌ | native UI is plain views |

### Voice
| Original | Native | |
|---|---|---|
| Native continuous SpeechRecognizer (Kotlin) with always-on "Hey Jarvis" | 🟡 | native: push-to-talk + in-app wake only |
| **Edge neural TTS** (Microsoft neural voices, 650 lines) + flutter_tts | ❌ | native uses Android TTS only |
| Polyglot Diplomat live voice translation | ❌ | |
| Gemini Live bidirectional audio (staging) | ❌ | |
| Sound radar | ❌ | |

### AI brain & learning
| Original | Native | |
|---|---|---|
| `ai_service` (OpenAI-compatible providers incl. Gemini) | 🟡 | native: Gemini tool-calling (newer) |
| `task_executor` observe→think→act **screen automation** + `recovery_engine` | ❌ | needs accessibility service |
| Hermes self-evolving workflows, macro recorder, skill memory | ❌ | |
| Personality / emotion, proactive agent suggestions | ❌ | |
| Conversational memory | ✅ | native MemoryEngine is more complete |
| Brain-dump multi-task decoupler | ❌ | |

### Android / device integrations
| Original | Native | |
|---|---|---|
| Accessibility service: tap/swipe/type/read screen in other apps; form filling | ⛔/❌ | Play Protect risk on iQOO; needs owner decision |
| Floating overlay bubble + co-pilot | ⛔/❌ | SYSTEM_ALERT_WINDOW |
| Notification listener inbox triage, SMS inbox, email triage | ❌ | |
| Contacts search, calls, message drafting/dispatch | 🟡 | native drafts via intents only, no contacts |
| Shizuku, WRITE_SETTINGS, brightness, volume | 🟡 | native: volume + torch only |
| Phone storage file CRUD, camera vision (optical solver, Jarvis Eye) | ❌ | native: image attach to Gemini |
| App usage stats, installed-app launcher | 🟡 | native launcher via `<queries>` |
| Calendar/reminders, todos, notes | 🟡 | native: reminders + calendar intent + memory |
| Telegram remote control | ❌ | |
| Smart home (Home Assistant), network audit, airspace radar, commute, sports, finance, charging telemetry, diagnostics | ❌ (diagnostics 🟡) | |
| Sentry anti-theft, Code Black lockout | ❌ | |
| Onboarding, permissions screen, task history, session replay | 🟡 | |
| Settings (3,163 + 1,038 lines in original) | 🟡 | native has 11 new sections, not the original options |

### Only in the native app (not in the original)
Tool-calling brain with approvals/audit, verified-ONLINE diagnostics (DNS/TLS/key/model), THINKING
watchdog + cancel, memory approvals/corrections/encrypted backup/eval suite, GreenSoil risk gate +
journal analyzer, minimal permission set, 42-check emulator acceptance suite.

## 4. Implication
"Restore everything into the native Java app" means re-implementing ~31.5k lines of Dart UI/services
in Java, plus re-introducing the high-risk permissions Play Protect flagged on the iQOO. The
alternative is to make the original Flutter app the single primary app and port the native app's
~5.8k lines of new capability into it. This choice belongs to the owner (see the open question).
