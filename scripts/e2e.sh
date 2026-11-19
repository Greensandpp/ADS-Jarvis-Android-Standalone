#!/usr/bin/env bash
# End-to-end test of the real APK on an Android emulator (CI only).
# Drives the actual UI with adb + uiautomator and records evidence.
set -uo pipefail
APK="$1"; OUT="${2:-e2e}"; PKG=com.adsjarvis.standalone.jarvis
mkdir -p "$OUT"; pass=0; fail=0
here="$(cd "$(dirname "$0")" && pwd)"
ok()  { echo "PASS  $*" | tee -a "$OUT/results.txt"; pass=$((pass+1)); }
bad() { echo "FAIL  $*" | tee -a "$OUT/results.txt"; fail=$((fail+1)); }
note(){ echo "INFO  $*" | tee -a "$OUT/results.txt"; }
dump() { for i in 1 2 3 4; do adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1 && return 0; sleep 1; done; return 1; }
has() { dump && python3 "$here/ui.py" has "$OUT/ui.xml" "$1"; }
tap_ui() { dump || return 1; local xy; xy=$(python3 "$here/ui.py" center "$OUT/ui.xml" "$1") || return 1; adb shell input tap $xy; }
shot() { adb exec-out screencap -p > "$OUT/$1.png"; }
expect() { # expect <command> <reply substring> <label>: send, check, retry once
  say "$1"; if has "$2"; then ok "$3"; return; fi
  say "$1"; if has "$2"; then ok "$3 (after retry)"; return; fi
  bad "$3"; python3 "$here/ui.py" texts "$OUT/ui.xml" | tail -6 | sed 's/^/      seen: /' | tee -a "$OUT/results.txt"
}
say() { # type a command into the dock and send it
  tap_ui "message input"; sleep .5
  adb shell input text "$(echo "$1" | sed 's/ /%s/g')"; sleep .5
  tap_ui "send"; sleep "${2:-3}"
}

adb wait-for-device
note "android=$(adb shell getprop ro.build.version.release | tr -d '\r') sdk=$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
adb shell settings put global window_animation_scale 0; adb shell settings put global transition_animation_scale 0

adb install -r -t "$APK" > "$OUT/install.txt" 2>&1 && grep -q Success "$OUT/install.txt" && ok "install" || { bad "install"; cat "$OUT/install.txt"; }
adb logcat -c
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 8
pid=$(adb shell pidof $PKG | tr -d '\r'); [ -n "$pid" ] && ok "launch pid=$pid" || bad "launch"
# Dismiss the first-run toast area / wait for layout.
sleep 2; shot 01-home
for el in "J A R V I S" "message input" "microphone" "send" "reactor" "conversation"; do
  has "$el" && ok "home shows '$el'" || bad "home missing '$el'"
done
dump && python3 "$here/ui.py" texts "$OUT/ui.xml" > "$OUT/home-texts.txt"
grep -q "AI NOT CONNECTED" "$OUT/home-texts.txt" && ok "status honestly shows AI NOT CONNECTED" || bad "AI status not shown"

expect "battery" "Battery is at" "local command: battery"
expect "what time is it" "It's" "local command: time"
expect "remember blue umbrella" "Saved on this device: blue umbrella" "memory save (v0.4 feature)"
expect "what do you remember" "I remember: blue umbrella" "memory recall (v0.4 feature)"
expect "time enna" "It's" "Tanglish command: time enna"
expect "tell me a joke" "AI provider is not connected yet" "graceful AI-not-configured reply"
shot 02-chat

# Open an app through a public intent and come back.
say "open settings" 4
focus=$(adb shell dumpsys window | grep -E "mCurrentFocus|mFocusedApp" | head -2)
echo "$focus" > "$OUT/focus-settings.txt"
echo "$focus" | grep -qi "settings" && ok "open settings via intent" || bad "open settings ($focus)"
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 3
say "open clock" 4
focus=$(adb shell dumpsys window | grep -E "mCurrentFocus" | head -1); echo "$focus" > "$OUT/focus-clock.txt"
echo "$focus" | grep -qi "clock\|deskclock" && ok "open app by name (Clock)" || note "open clock: $focus"
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 3

# Long-press the reactor -> customization; choose AIRA; save; verify persistence after restart.
dump; xy=$(python3 "$here/ui.py" center "$OUT/ui.xml" "reactor")
adb shell input swipe $xy $xy 1500; sleep 2
has "Customize JARVIS" && ok "long-press opens customization" || bad "long-press customization"
shot 03-customize
tap_ui "AIRA neural"; sleep 1; shot 04-aira-preview
tap_ui "Save" || tap_ui "SAVE"; sleep 2
has "AIRA" && ok "AIRA style applied" || bad "AIRA style"
adb shell am force-stop $PKG; sleep 1
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 6
dump && python3 "$here/ui.py" texts "$OUT/ui.xml" > "$OUT/after-restart.txt"
grep -q "^AIRA" "$OUT/after-restart.txt" && ok "style persists across restart" || bad "style persistence"
grep -q "Opening clock." "$OUT/after-restart.txt" && ok "conversation history persists across restart" || bad "history persistence"
shot 05-aira-after-restart
# Cycle remaining styles for screenshots.
for s in "Agent Office" "APEX" "MIRA" "JARVIS ARC reactor"; do
  dump; xy=$(python3 "$here/ui.py" center "$OUT/ui.xml" "reactor"); adb shell input swipe $xy $xy 1500; sleep 2
  tap_ui "$s"; sleep 1; tap_ui "Save" || tap_ui "SAVE"; sleep 2
  shot "06-style-$(echo $s | tr ' ' '_')"
done
has "NOT CONNECTED" >/dev/null; # Office text is drawn on canvas, not in a11y tree.

# Microphone: runtime permission prompt, then speech service availability.
tap_ui "microphone"; sleep 2; shot 07-mic-permission
dump; python3 "$here/ui.py" texts "$OUT/ui.xml" > "$OUT/mic-dialog.txt"
if grep -qiE "Allow|While using the app|record audio" "$OUT/mic-dialog.txt"; then ok "mic runtime permission requested"; else bad "mic permission dialog"; fi
tap_ui "While using the app" || tap_ui "Allow" || tap_ui "Only this time"; sleep 3
adb shell dumpsys package $PKG | grep -E "RECORD_AUDIO: granted" > "$OUT/mic-granted.txt" && ok "RECORD_AUDIO granted by user tap" || bad "mic grant"
dump; python3 "$here/ui.py" texts "$OUT/ui.xml" > "$OUT/after-mic.txt"
if grep -q "LISTENING" "$OUT/after-mic.txt"; then ok "speech recognizer started (LISTENING)";
elif grep -q "NO SPEECH RECOGNITION SERVICE" "$OUT/after-mic.txt"; then note "emulator image has no speech service; app reported it gracefully";
else note "speech state: $(grep -E 'VOICE|SPEECH|LISTEN|PROCESS' "$OUT/after-mic.txt" | head -1)"; fi
shot 08-after-mic
grep -q "VOICE READY" "$OUT/after-restart.txt" && ok "TTS engine initialised (VOICE READY)" || note "TTS: $(grep -o 'VOICE [A-Z]*' "$OUT/after-restart.txt" | head -1)"

# Offline: local commands must keep working with networking disabled.
adb shell svc wifi disable; adb shell svc data disable; sleep 3
expect "date enna" "Today is" "local commands work offline (Tanglish date)"
shot 09-offline
adb shell svc wifi enable; adb shell svc data enable

adb logcat -d > "$OUT/logcat.txt"
if grep -E "FATAL EXCEPTION" -A4 "$OUT/logcat.txt" | grep -q "$PKG"; then bad "no crashes (FATAL found)"; else ok "no FATAL exceptions in logcat"; fi
adb shell dumpsys meminfo $PKG | grep -E "TOTAL PSS|TOTAL:" | head -2 > "$OUT/meminfo.txt"
note "memory: $(head -1 "$OUT/meminfo.txt" | tr -s ' ')"
echo "SUMMARY pass=$pass fail=$fail" | tee -a "$OUT/results.txt"
[ "$fail" -eq 0 ]
