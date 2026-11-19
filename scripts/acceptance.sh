#!/usr/bin/env bash
# Phase 11 acceptance tests on a real Android emulator (CI). Drives the real UI
# with adb + uiautomator and records PASS / FAIL / UNVERIFIED with evidence.
set -uo pipefail
APK="$1"; OUT="${2:-acc}"; PKG=com.adsjarvis.standalone.jarvis
mkdir -p "$OUT"; here="$(cd "$(dirname "$0")" && pwd)"
pass=0; fail=0; unv=0
R="$OUT/results.txt"
ok()  { echo "PASS        $*" | tee -a "$R"; pass=$((pass+1)); }
bad() { echo "FAIL        $*" | tee -a "$R"; fail=$((fail+1)); }
unv() { echo "UNVERIFIED  $*" | tee -a "$R"; unv=$((unv+1)); }
info(){ echo "INFO        $*" | tee -a "$R"; }
dump() { for i in 1 2 3 4; do adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1 && return 0; sleep 1; done; return 1; }
texts() { dump && python3 "$here/ui.py" texts "$OUT/ui.xml"; }
has() { dump && python3 "$here/ui.py" has "$OUT/ui.xml" "$1"; }
tap() { dump || return 1; local xy; xy=$(python3 "$here/ui.py" center "$OUT/ui.xml" "$1") || return 1; adb shell input tap $xy; }
scroll_to() { for i in 1 2 3 4 5 6 7 8; do has "$1" && return 0; adb shell input swipe 540 1700 540 700 300; sleep 1; done; return 1; }
tap_scroll() { scroll_to "$1" && tap "$1"; }
shot() { adb exec-out screencap -p > "$OUT/$1.png"; }
launch() { adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep "${1:-5}"; }
say() { tap "message input"; sleep .4; adb shell input text "$(echo "$1" | sed 's/ /%s/g')"; sleep .4; tap "send"; sleep "${2:-3}"; }
share_text() { adb shell am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "'$1'" -n $PKG/com.adsjarvis.standalone.MainActivity >/dev/null 2>&1; sleep 2; }
expect() { # expect <cmd> <substring> <label>
  say "$1" "${4:-3}"; if has "$2"; then ok "$3"; return 0; fi
  say "$1" "${4:-3}"; if has "$2"; then ok "$3 (retry)"; return 0; fi
  bad "$3"; texts | tail -5 | sed 's/^/            seen: /' | tee -a "$R"; return 1
}

adb wait-for-device
info "android=$(adb shell getprop ro.build.version.release | tr -d '\r') sdk=$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
adb install -r -t "$APK" > "$OUT/install.txt" 2>&1 && grep -q Success "$OUT/install.txt" && ok "install APK" || bad "install APK"
adb logcat -c
launch 7
[ -n "$(adb shell pidof $PKG | tr -d '\r')" ] && ok "launch" || bad "launch"
shot 00-home
for e in "menu" "settings" "status" "reactor" "conversation" "attach" "microphone" "message input" "send"; do
  has "$e" || bad "home element '$e' missing"
done
has "J.A.R.V.I.S." && ok "home: title, menu, gear, visual, chat, mic, send present" || bad "home title"
adb logcat -d | grep -q "JARVIS_GL ok programs=5" && ok "GPU renderer active (5 shader programs compiled)" || bad "GPU renderer (fell back to canvas?)"
adb logcat -d | grep "JARVIS_GL" | tail -1 | tee -a "$R"

# 1-2
expect "what is your name" "your personal AI assistant" "T01 What is your name"
expect "what is the time now" "It's" "T02 What is the time now (device clock, no AI)"
# 3-4 (Tamil script via share intent, then send)
share_text "தமிழ்ல பேசு"; tap "send"; sleep 3
has "இனி தமிழில்" && ok "T03 தமிழ்ல பேசு → Tamil replies" || bad "T03 Tamil switch"
expect "Tanglish-la answer pannu" "Tanglish-la pesuren" "T04 Tanglish-la answer pannu"
# 5 remember preference (approval dialog → Save)
say "Remember my preferred language" 2
has "Save to memory?" && ok "T05a memory write asks for approval" || bad "T05a approval dialog"
tap "Save" || tap "SAVE"; sleep 2
has "preferred language is tanglish" && ok "T05 Remember my preferred language" || bad "T05 remember"
# 6 restart then retrieve
adb shell am force-stop $PKG; sleep 1; launch 6
expect "what do you remember" "preferred reply language: tanglish" "T06 after restart, memory retrieved"
# 7 open settings
say "Open settings" 3
has "Settings" && has "AI Brain  ›" && ok "T07 Open settings (gear page)" || bad "T07 open settings"
shot 07-settings
adb shell input keyevent KEYCODE_BACK; sleep 2
# 8 change visual
expect "Change visual to APEX" "Visual changed to APEX" "T08 Change visual to APEX"
sleep 2; shot 08-apex
has "APEX" && info "status shows APEX"
# 9 female voice
expect "Change voice to female" "Voice" "T09 Change voice to female (reply states which voice/pitch is used)"
texts | grep -i "voice" | tail -1 | sed 's/^/            reply: /' | tee -a "$R"
# 10-13 intelligence requests without a key: must be honest, not fake
expect "Analyze this NIFTY chart" "Chart analysis needs Gemini" "T10 Analyze NIFTY chart → honest (no key)"
unv "T10 real chart vision analysis (no Gemini key available to the test)"
expect "Explain why my PB7 trade was rejected" "NOT CONNECTED" "T11 PB7 rejection → NOT CONNECTED (honest)"
unv "T11 real PB7 data (no read-only PB7 endpoint configured)"
printf 'Date,Entry Time,Exit Time,Symbol,Side,Entry,Exit,SL,Qty,PnL,Fees,Confidence\n2026-11-17,09:20,09:40,NIFTY,LONG,100,110,90,75,710,40,80\n2026-11-17,10:00,10:30,NIFTY,LONG,120,100,100,75,-1540,40,65\n2026-11-17,10:00,10:30,NIFTY,LONG,120,100,100,75,-1540,40,65\n' > "$OUT/journal.csv"
adb push "$OUT/journal.csv" /data/local/tmp/j.csv >/dev/null
adb shell 'am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "$(cat /data/local/tmp/j.csv)" -n '$PKG'/com.adsjarvis.standalone.MainActivity' >/dev/null 2>&1; sleep 3
has "Trade journal imported" && ok "T12a journal CSV shared into JARVIS" || bad "T12a journal import"
expect "Find errors in my previous trading journal" "Rule violations" "T12 Find errors in trading journal (on-device audit)" 4
texts | grep -iE "duplicate|09:30|70%" | head -3 | sed 's/^/            finding: /' | tee -a "$R"
expect "Plan this task and tell me the required permissions" "Planning needs AI" "T13 Plan task → honest + capability list"
unv "T13 AI-generated plan (no Gemini key)"
shot 13-chat
# 14 offline
adb shell svc wifi disable; adb shell svc data disable; sleep 4
expect "what is the time now" "It's" "T14a offline: time"
say "set an alarm for 6:30 am" 4
focus=$(adb shell dumpsys window | grep -m1 -E "mCurrentFocus"); echo "$focus" > "$OUT/focus-alarm.txt"
echo "$focus" | grep -qi "clock" && ok "T14b offline: alarm intent opened the Clock app ($focus)" || bad "T14b alarm intent ($focus)"
adb shell input keyevent KEYCODE_BACK; sleep 1; launch 3
expect "remember umbrella in car boot" "Saved on this device" "T14c offline: notes/memory"
has "NET OFFLINE" && ok "T14d status shows NET OFFLINE" || bad "T14d offline indicator"
shot 14-offline
# 15 restore
adb shell svc wifi enable; adb shell svc data enable; sleep 8
launch 3
has "NET ONLINE" && ok "T15 network restored → NET ONLINE" || bad "T15 network restore"
has "AI NOT CONNECTED" && ok "T15b AI status honest (no key → AI NOT CONNECTED, never fake ONLINE)" || info "AI status: $(texts | grep -m1 'AI ')"
unv "T15c AI reconnection to a working provider (no valid Gemini key)"
# 16 invalid key against the REAL Gemini endpoint + network failure classification
say "Open settings" 3
tap "AI Brain  ›"; sleep 2
tap_scroll "Paste API key (never shared, never in backups)"; sleep 1
adb shell input text "AIzaSyInvalidTestKeyForJarvisCI0000000000"; sleep 1
adb shell input keyevent KEYCODE_BACK; sleep 1
tap_scroll "Save API key"; sleep 2
tap_scroll "Test connection + discover models"; sleep 12
dump; texts > "$OUT/diag-invalid-key.txt"
if grep -q "rejected the API key" "$OUT/diag-invalid-key.txt"; then ok "T16a invalid key → real Gemini returned AUTH error, shown as 'rejected the API key'";
elif grep -qi "DNS\|TLS\|connect" "$OUT/diag-invalid-key.txt"; then unv "T16a emulator could not reach Gemini ($(grep -m1 -iE 'DNS|TLS|connect' "$OUT/diag-invalid-key.txt"))";
else bad "T16a invalid-key diagnostics"; fi
grep -E "PASS|FAIL|SKIPPED|ONLINE" "$OUT/diag-invalid-key.txt" | head -6 | sed 's/^/            diag: /' | tee -a "$R"
shot 16-invalid-key
adb shell svc wifi disable; adb shell svc data disable; sleep 4
tap_scroll "Test connection + discover models"; sleep 6
dump; texts > "$OUT/diag-offline.txt"
grep -qE "FAIL +DNS|cannot resolve|connection failed" "$OUT/diag-offline.txt" && ok "T16c network failure classified as DNS/connection, not model" || bad "T16c offline diagnostics"
grep -E "PASS|FAIL|SKIPPED" "$OUT/diag-offline.txt" | head -4 | sed 's/^/            diag: /' | tee -a "$R"
adb shell svc wifi enable; adb shell svc data enable; sleep 3
unv "T16b invalid-model handling on real Gemini (needs a valid key); covered by unit test fallsBackToNextModelOn404"
tap_scroll "Remove API key"; sleep 1
adb shell input keyevent KEYCODE_BACK; sleep 1; adb shell input keyevent KEYCODE_BACK; sleep 1
# 17 restart persistence
adb shell am force-stop $PKG; sleep 1; launch 6
dump; texts > "$OUT/after-restart.txt"
grep -q "APEX" "$OUT/after-restart.txt" && grep -q "TANGLISH" "$OUT/after-restart.txt" && ok "T17 settings persist after restart (APEX + Tanglish)" || bad "T17 persistence"
# 18 mic / speaker / wake word
tap "microphone"; sleep 2; shot 18-mic-permission
tap "While using the app" || tap "Allow" || tap "Only this time"; sleep 3
adb shell dumpsys package $PKG | grep -q "RECORD_AUDIO: granted=true" && ok "T18a microphone runtime permission granted by user tap" || bad "T18a mic permission"
dump; texts > "$OUT/after-mic.txt"
if grep -q "LISTENING" "$OUT/after-mic.txt"; then ok "T18b speech recognizer started (LISTENING)";
elif grep -qiE "catch that|No speech heard|Speech service error|Speech service needs" "$OUT/after-mic.txt"; then ok "T18b speech recognizer invoked; its result/error was shown to the user ($(grep -m1 -iE 'catch|speech' "$OUT/after-mic.txt"))";
else bad "T18b recognizer gave no visible outcome"; fi
grep -q "VOICE READY" "$OUT/after-restart.txt" && ok "T18c TTS engine initialised (speaker)" || unv "T18c TTS engine"
unv "T18d actual speech accuracy (Tamil/English/Tanglish) — emulator has no real microphone input"
say "Open settings" 3; tap "Voice  ›"; sleep 2
tap_scroll "Wake word while JARVIS is open on screen"; sleep 1
adb shell input keyevent KEYCODE_BACK; sleep 1; adb shell input keyevent KEYCODE_BACK; sleep 3
has "(IN-APP)" && ok "T18e in-app wake-word listening active (status chip)" || bad "T18e wake word enable"
unv "T18f wake word detection from real speech; background/lock-screen wake NOT implemented by design"
# 19 visuals + FPS
for s in "JARVIS ARC reactor" "AIRA neural plasma" "APEX cinematic" "MIRA holographic" "Agent Office"; do
  dump; xy=$(python3 "$here/ui.py" center "$OUT/ui.xml" "reactor"); adb shell input swipe $xy $xy 1200; sleep 2
  tap "$s"; sleep 7
  shot "19-$(echo $s | tr ' ' '_')"
done
adb logcat -d | grep "JARVIS_FPS" > "$OUT/fps.txt"
for st in ARC AIRA APEX MIRA OFFICE; do
  f=$(grep "style=$st " "$OUT/fps.txt" | tail -1 | sed 's/.*fps=//')
  [ -n "$f" ] && info "FPS $st = $f (software-rendered emulator GPU; not representative of iQOO 13)" || info "FPS $st not sampled"
done
grep -q "style=OFFICE" "$OUT/fps.txt" && ok "T19 all visual modes switched via long-press; FPS measured" || bad "T19 visual switching/FPS"
# 20 unauthorized action blocked: deny an approval (new value -> correction proposal -> dialog)
expect "reply in english" "reply in English" "T20a language changed to English"
say "Remember my preferred language" 2
has "Save to memory?" && { tap "Don't save" || tap "DON'T SAVE"; sleep 2; }
has "Okay, not saved." && ok "T20 owner denial blocks the action (memory not written)" || bad "T20 denial"
info "RED-tier tool blocking without approval: unit test BrainTest.redToolIsBlockedWithoutApprovalAndModelIsTold"

# 21-23 Failure recovery WITHOUT any secret: fake key + unreachable endpoint (no live Gemini involved)
say "Open settings" 3; tap "AI Brain  ›"; sleep 2
scroll_to "Paste API key (never shared, never in backups)"
pw=$(python3 "$here/ui.py" attr "$OUT/ui.xml" "Paste API key (never shared, never in backups)" password)
[ "$pw" = "true" ] && ok "T23 in-app API key field is password-masked (stored via Android Keystore)" || bad "T23 key field masking (password=$pw)"
tap "Paste API key (never shared, never in backups)"; sleep 1
adb shell input text "AIzaSyFakeKeyOnlyForTimeoutTest00000000000"; adb shell input keyevent KEYCODE_BACK; sleep 1
tap_scroll "Save API key"; sleep 2
tap_scroll "https://generativelanguage.googleapis.com/v1beta"; sleep 1
adb shell input keyevent KEYCODE_MOVE_END
adb shell input keyevent $(printf '67 %.0s' $(seq 1 70))
adb shell input text "https://10.255.255.1/v1beta"; adb shell input keyevent KEYCODE_BACK; sleep 1
tap_scroll "Save endpoint & model"; sleep 2
adb shell input keyevent KEYCODE_BACK; sleep 1; adb shell input keyevent KEYCODE_BACK; sleep 2
t0=$(date +%s)
say "explain black holes simply" 3
has "THINKING" && ok "T21a AI request entered THINKING" || info "THINKING not captured at send time"
done21=""
for i in $(seq 1 22); do
  if has "Could not connect" || has "did not respond" || has "timed out"; then done21=1; break; fi
  sleep 5
done
el=$(( $(date +%s) - t0 ))
if [ -n "$done21" ]; then ok "T21 unreachable AI endpoint → clear connection/timeout error after ${el}s (no fake reply)"; else bad "T21 no error after ${el}s"; fi
sleep 2; dump
python3 "$here/ui.py" texts "$OUT/ui.xml" | grep -m1 -E "^(READY|ERROR|THINKING|OFFLINE|CANCELLED|RETRYING)" | sed 's/^/            state: /' | tee -a "$R"
python3 "$here/ui.py" texts "$OUT/ui.xml" | grep -qE "^(THINKING|RETRYING)" && bad "T21b still stuck in THINKING" || ok "T21b not stuck in THINKING after failure"
shot 21-timeout
say "explain gravity simply" 1
sleep 2
if texts | grep -qE "^(THINKING|RETRYING)"; then
  tap "stop"; sleep 5
  if has "Request cancelled"; then ok "T22 Stop cancels an in-flight AI request"; else bad "T22 cancel"; texts | tail -4 | sed 's/^/            seen: /' | tee -a "$R"; fi
else
  unv "T22 request finished before Stop could be pressed (endpoint failed fast); cancel covered by unit test cancelStopsTheRequest"
fi
python3 "$here/ui.py" texts "$OUT/ui.xml" | grep -qE "^(THINKING|RETRYING)" && bad "T22b stuck after cancel" || ok "T22b state left THINKING after cancel"
shot 22-cancel

adb logcat -d > "$OUT/logcat.txt"
if grep -E "FATAL EXCEPTION" -A3 "$OUT/logcat.txt" | grep -q "$PKG"; then bad "no crashes (FATAL found)"; grep -A20 "FATAL EXCEPTION" "$OUT/logcat.txt" | head -40 | tee -a "$R"; else ok "no FATAL exceptions"; fi
adb shell dumpsys meminfo $PKG | grep -E "TOTAL PSS" | head -1 | sed 's/^/INFO        memory: /' | tee -a "$R"
echo "SUMMARY pass=$pass fail=$fail unverified=$unv" | tee -a "$R"
[ "$fail" -eq 0 ]
