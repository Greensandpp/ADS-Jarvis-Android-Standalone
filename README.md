# GreenSoil JARVIS — Android (standalone, native Java)

Independent native Android app (no Flutter). Not connected to GreenSoil AIRA,
OpenClaw, Railway, trading bots or Pine scripts. No keys are bundled.

## v1.0.0 (debug)
- One home screen: animated JARVIS ARC reactor HUD, transcript, bottom dock
  with text input, microphone and send. Tap the reactor to talk.
- **Long-press the reactor** to customize: visual style (JARVIS ARC, AIRA,
  Agent Office, APEX, MIRA), language (English + Tanglish auto / Tamil /
  English), female-voice preference and installed-voice picker, brightness,
  reduce motion, AI provider, and clearing history / the saved key.
- Voice: Android `SpeechRecognizer` (`en-IN` or `ta-IN`) and the phone's TTS engine.
- Local commands (English / Tamil / Tanglish): open apps by name, Settings,
  Wi-Fi panel, Bluetooth, alarm & timer (Clock app confirms), dialer (you
  press call), web search, torch, volume, battery, time, date, memory.
- AI: your own Gemini or OpenAI-compatible key, encrypted with the Android
  Keystore. Clear messages when not configured, offline, rejected or rate-limited.
- Agent Office style shows NOT CONNECTED — there is no agent backend.
- Permissions: INTERNET, ACCESS_NETWORK_STATE, RECORD_AUDIO (asked on first mic
  tap), SET_ALARM. No accessibility, overlay, contacts, SMS, calls or package listing.

Not implemented: wake word, lock-screen control, WhatsApp/SMS automation, payments.

## Why v0.4 was 16 KB
It was a complete build of a ~290-line framework-only prototype: three classes,
no resources (not even an icon) and no dependencies. Nothing was stripped.

## Build
GitHub Actions `ADS Jarvis Standalone Android Debug`: unit tests → debug APK →
package validation → Android 15/16 emulator end-to-end test. The debug package
is `com.adsjarvis.standalone.jarvis`, so it installs beside v0.4.
