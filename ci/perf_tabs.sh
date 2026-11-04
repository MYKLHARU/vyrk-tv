#!/usr/bin/env bash
# Tab-switch timing with remote keys: Home → Movies → Series → Anime → Home, Movies → Home → Movies,
# Series → Anime → Series, three times each at a relaxed (1.5 s) and a fast (0.45 s) pace, then
# a pass along series posters. Works on an emulator or a real TV (adb connect <tv-ip>).
# Summary: python3 tools/perf_summary.py ci/out/perf/perf.txt
# Set KEEP=1 to keep the installed app and its sign-in (on your own TV), otherwise it reinstalls.
set -euo pipefail
ADB=${ADB:-adb}; PKG=com.vyrk.tv; OUT=${OUT:-ci/out/perf}
mkdir -p "$OUT"; TRACE="$OUT/focus-trace.txt"; : > "$TRACE"; STEP=0; FAILURES=0
source ci/lib.sh
"$ADB" shell setprop log.tag.VyrkPerf DEBUG
if [[ "${KEEP:-0}" != 1 ]]; then
  "$ADB" uninstall "$PKG" >/dev/null 2>&1 || true
  "$ADB" install -r "$APK" >/dev/null
  adbs am start -n "$PKG/.MainActivity" >/dev/null; sleep 8
  key KEYCODE_DPAD_RIGHT 1.2; key KEYCODE_DPAD_CENTER 2 "guest"
else
  "$ADB" shell am force-stop "$PKG"; adbs am start -n "$PKG/.MainActivity" >/dev/null; sleep 8
fi
wait_focus '^(hero:watch|poster:.*)$' 45
sleep 6
until_focus KEYCODE_DPAD_UP '^tab:home$' 8
"$ADB" logcat -c; "$ADB" shell dumpsys gfxinfo "$PKG" reset >/dev/null
k() { "$ADB" shell input keyevent "KEYCODE_DPAD_$1"; sleep "$2"; }
for pace in 1.5 0.45; do
  echo "== pace $pace" >> "$OUT/marks.txt"
  for r in 1 2 3; do
    # Home -> Movies -> Series -> Anime -> Home
    k RIGHT $pace; k RIGHT $pace; k RIGHT $pace; k LEFT $pace; k LEFT $pace; k LEFT $pace
    # Movies -> Home -> Movies
    k RIGHT $pace; k LEFT $pace; k RIGHT $pace
    # Series -> Anime -> Series
    k RIGHT $pace; k RIGHT $pace; k LEFT $pace
    # back to Home
    k LEFT $pace; k LEFT $pace
  done
  sleep 2
done
expect '^tab:home$' "ends on the Home tab"
echo "== posters" >> "$OUT/marks.txt"
k RIGHT 1.5; k RIGHT 1.5; k DOWN 1.5; k DOWN 1.5
for i in 1 2 3 4 5 6 7 8; do k RIGHT 0.6; done
"$ADB" logcat -d -s VyrkPerf:I > "$OUT/perf.txt" || true
"$ADB" shell dumpsys gfxinfo "$PKG" > "$OUT/gfxinfo.txt" || true
"$ADB" shell setprop log.tag.VyrkPerf INFO
log "failures: $FAILURES"