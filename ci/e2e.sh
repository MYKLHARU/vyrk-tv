#!/usr/bin/env bash
# VYRK emulator journey: drives the real app on an Android TV emulator with remote keys only.
# Writes screenshots, a per-key focus trace and logcat to $OUT. Fails if focus is ever lost,
# leaves the app, or an expected screen isn't reached.
#
# Env: ADB (default adb), APK (APK to install), OUT (default ci/out/e2e),
#      ADDON_LOG (test add-on request log, to verify the fail-over).
set -euo pipefail

ADB=${ADB:-adb}
PKG=com.vyrk.tv
OUT=${OUT:-ci/out/e2e}
APK=${APK:-}
ADDON_URL='http://10.0.2.2:7000/manifest.json'
ADDON_LOG=${ADDON_LOG:-}
mkdir -p "$OUT"
TRACE="$OUT/focus-trace.txt"
: > "$TRACE"
STEP=0
FAILURES=0

log() { echo "[e2e] $*"; }
fail() { echo "[e2e] FAIL: $*" | tee -a "$TRACE"; FAILURES=$((FAILURES + 1)); }
adbs() { "$ADB" shell "$@" | tr -d '\r'; }

shot() {
  STEP=$((STEP + 1))
  local name; name=$(printf '%02d-%s' "$STEP" "$1")
  "$ADB" exec-out screencap -p > "$OUT/$name.png"
  log "screenshot $name"
}

focus_now() { "$ADB" logcat -d -s VyrkFocus:I | tail -n 1 | sed 's/.*VyrkFocus: [A-Z_0-9]* -> //' | tr -d '\r'; }
window_now() { adbs dumpsys window | grep -m1 'mCurrentFocus' | sed 's/.*{//; s/}.*//' ; }

# Press a key (name or code), wait, record "key | focused element | focused window".
key() {
  local k=$1 wait=${2:-1.3} label=${3:-}
  "$ADB" shell input keyevent "$k"
  sleep "$wait"
  # Back goes through the back dispatcher (Android 13+), not key dispatch: probe to read focus.
  if [[ "$k" == KEYCODE_BACK ]]; then "$ADB" shell input keyevent KEYCODE_SHIFT_LEFT; sleep 0.8; fi
  local f w; f=$(focus_now); w=$(window_now)
  echo "$k | ${f:-?} | ${w:-?} ${label:+| $label}" >> "$TRACE"
  if [[ -z "$f" || "$f" == "none" ]]; then fail "focus lost after $k ($label)"; fi
  if [[ "$w" != *"$PKG"* ]]; then fail "focused window is not VYRK after $k: $w"; fi
}

longpress() {
  "$ADB" shell input keyevent --longpress "$1"
  sleep "${2:-1.5}"
  local f; f=$(focus_now)
  echo "LONGPRESS $1 | ${f:-?} | $(window_now)" >> "$TRACE"
}

# Neutral key: reports focus without moving it.
probe() { key KEYCODE_SHIFT_LEFT 1 "${1:-probe}"; }

expect() {
  local pattern=$1 what=$2 f; f=$(focus_now)
  if [[ "$f" =~ $pattern ]]; then log "ok: $what ($f)"; else fail "$what: expected /$pattern/, focus is '$f'"; fi
}

# Press KEY until focus matches PATTERN (max N presses).
until_focus() {
  local k=$1 pattern=$2 max=${3:-12} i=0
  while (( i < max )); do
    key "$k" 1.1 "seek /$pattern/"
    [[ "$(focus_now)" =~ $pattern ]] && return 0
    i=$((i + 1))
  done
  fail "never reached /$pattern/ with $k"
  return 1
}

wait_focus() {
  local pattern=$1 secs=${2:-30} i=0
  while (( i < secs )); do
    probe "wait /$pattern/" >/dev/null 2>&1 || true
    [[ "$(focus_now)" =~ $pattern ]] && return 0
    sleep 1; i=$((i + 1))
  done
  fail "timed out waiting for focus /$pattern/"
  return 1
}

# On-screen keyboard: 7 columns, a–z. Moves from the current letter to the target and presses OK.
KB_ROW=0; KB_COL=0
type_letter() {
  local ch=$1 idx row col
  idx=$(( $(printf '%d' "'$ch") - 97 )); row=$((idx / 7)); col=$((idx % 7))
  while (( KB_ROW < row )); do key KEYCODE_DPAD_DOWN 0.6; KB_ROW=$((KB_ROW + 1)); done
  while (( KB_ROW > row )); do key KEYCODE_DPAD_UP 0.6; KB_ROW=$((KB_ROW - 1)); done
  while (( KB_COL < col )); do key KEYCODE_DPAD_RIGHT 0.6; KB_COL=$((KB_COL + 1)); done
  while (( KB_COL > col )); do key KEYCODE_DPAD_LEFT 0.6; KB_COL=$((KB_COL - 1)); done
  key KEYCODE_DPAD_CENTER 0.8 "type $ch"
}

# ---------------------------------------------------------------- setup
log "device: $(adbs getprop ro.product.model) API $(adbs getprop ro.build.version.sdk), $(adbs wm size)"
if [[ -n "$APK" ]]; then
  "$ADB" uninstall "$PKG" >/dev/null 2>&1 || true
  "$ADB" install -r "$APK"
fi
adbs pm clear "$PKG" >/dev/null || true
"$ADB" logcat -c
adbs am start -n "$PKG/.MainActivity" >/dev/null
sleep 8

# ---------------------------------------------------------------- first run
probe "welcome"
expect '^welcome:sign_in$' "first run shows sign-in focused"
shot welcome
key KEYCODE_DPAD_RIGHT 1.2 "to guest"
expect '^welcome:guest$' "guest button"
key KEYCODE_DPAD_CENTER 2 "use without an account"
wait_focus '^hero:watch$' 45
shot home-live-cinemeta

# ---------------------------------------------------------------- install the test add-on from a link
adbs am start -a android.intent.action.VIEW -d "'vyrk://install?url=http%3A%2F%2F10.0.2.2%3A7000%2Fmanifest.json'" "$PKG" >/dev/null
sleep 5
probe "install dialog"
expect '^install:confirm$' "install confirmation from link"
shot install-confirm
key KEYCODE_DPAD_CENTER 3 "install"
probe "after install"
expect '^hero:watch$' "focus back on hero after install"

# ---------------------------------------------------------------- dismiss the Top 10 card
key KEYCODE_DPAD_DOWN 1.3 "to top10 card"
expect '^top10card:add$' "Top 10 card focused"
shot top10-card
key KEYCODE_DPAD_RIGHT 1.0
expect '^top10card:not_now$' "Not now"
key KEYCODE_DPAD_CENTER 1.5 "dismiss card"
expect '^hero:watch$' "focus returns to hero after Not now"

# ---------------------------------------------------------------- a row, preview card, info page
key KEYCODE_DPAD_DOWN 2 "into first row"
expect '^poster:.*#0$' "first title of a row"
shot row-title-header
key KEYCODE_MEDIA_PLAY_PAUSE 4 "preview card"
shot preview-card
expect '^poster:' "preview never takes focus"
key KEYCODE_BACK 1.2 "close preview"
expect '^poster:' "focus stays on poster after closing preview"
key KEYCODE_DPAD_CENTER 5 "open info page"
expect '^info:play$' "info page opens with Play focused"
shot info-page

# ---------------------------------------------------------------- play: broken 4K fails over
ADDON_START=0
[[ -n "$ADDON_LOG" && -f "$ADDON_LOG" ]] && ADDON_START=$(wc -l < "$ADDON_LOG")
key KEYCODE_DPAD_CENTER 3 "Play"
sleep 30
shot player
probe "player"
expect '^player:' "player has focus"
if [[ -n "$ADDON_LOG" && -f "$ADDON_LOG" ]]; then
  tail -n +"$((ADDON_START + 1))" "$ADDON_LOG" > "$OUT/addon-requests-during-play.txt"
  PLAYLOG="$OUT/addon-requests-during-play.txt"
  if grep -q 'broken-2160p.mkv HTTP/1.1" 404' "$PLAYLOG" && grep -q 'sample-720p.mp4 HTTP/1.1" 20[06]' "$PLAYLOG"; then
    b=$(grep -n 'broken-2160p' "$PLAYLOG" | head -1 | cut -d: -f1)
    r=$(grep -n 'GET /stream/' "$PLAYLOG" | tail -1 | cut -d: -f1)
    s=$(grep -n 'sample-720p' "$PLAYLOG" | head -1 | cut -d: -f1)
    if (( b < r && r < s )); then log "ok: broken 4K failed, link refreshed, fell over to 720p"; else fail "fail-over order wrong (broken=$b refresh=$r sample=$s)"; fi
  else
    fail "fail-over not seen in add-on log"
  fi
fi

key KEYCODE_DPAD_DOWN 1.5 "show controls"
shot player-controls
key KEYCODE_CAPTIONS 2 "CC opens Audio & subtitles"
expect '^panel:' "Audio & subtitles panel"
shot audio-subtitles
key KEYCODE_BACK 1.2 "close panel"
key KEYCODE_BACK 1.2 "hide controls"
key KEYCODE_BACK 4 "exit player"
probe "home after playback"
shot home-after-playback

# ---------------------------------------------------------------- Continue Watching + restart restore
until_focus KEYCODE_DPAD_UP '^hero:watch$' 6 || true
key KEYCODE_DPAD_DOWN 2 "to Continue Watching"
expect '^poster:cw#0$' "Continue Watching row is first"
shot continue-watching
adbs am force-stop "$PKG"
sleep 2
"$ADB" logcat -c
adbs am start -n "$PKG/.MainActivity" >/dev/null
sleep 12
probe "after restart"
expect '^poster:cw#0$' "focus restored to the Continue Watching poster after restart"
shot restored-after-restart

# ---------------------------------------------------------------- Remove from Continue Watching
longpress KEYCODE_DPAD_CENTER 2
probe "item menu"
expect '^menu:play$' "item menu opens on Play"
shot item-menu
key KEYCODE_DPAD_DOWN 1.0
expect '^menu:remove_cw$' "Remove from Continue Watching directly under Play"
key KEYCODE_DPAD_CENTER 1.0 "remove"
shot removed-toast
sleep 1
probe "after remove"
expect '^hero:watch$' "focus lands on the hero when the row empties"
"$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || true
"$ADB" pull /sdcard/ui.xml "$OUT/ui-after-remove.xml" >/dev/null 2>&1 || true
if grep -qE 'content-desc="[^"]*, [0-9]+%' "$OUT/ui-after-remove.xml" 2>/dev/null; then
  fail "a poster still shows a progress percentage after removal"
else
  log "ok: no poster shows progress after removal"
fi
key KEYCODE_DPAD_DOWN 1.5
if [[ "$(focus_now)" == poster:cw* ]]; then fail "Continue Watching row still present"; else log "ok: Continue Watching row is gone"; fi

# ---------------------------------------------------------------- tour every tab
until_focus KEYCODE_DPAD_UP '^tab:home$' 8
for t in movies series anime library addons; do
  until_focus KEYCODE_DPAD_RIGHT "^tab:$t\$" 3 && { sleep 3; shot "tab-$t"; key KEYCODE_DPAD_DOWN 2 "into $t"; probe "$t content"; until_focus KEYCODE_DPAD_UP "^tab:$t\$" 6; }
done

# ---------------------------------------------------------------- search "matrix"
until_focus KEYCODE_DPAD_RIGHT '^nav:search$' 4
key KEYCODE_DPAD_CENTER 2 "open search"
expect '^key:a$' "keyboard takes focus on open"
KB_ROW=0; KB_COL=0
for ch in m a t r i x; do type_letter "$ch"; done
sleep 6
shot search-matrix
key KEYCODE_BACK 1.5 "leave search"

# ---------------------------------------------------------------- every settings group
until_focus KEYCODE_DPAD_RIGHT '^nav:settings$' 6
key KEYCODE_DPAD_CENTER 2 "open settings"
expect '^settings:profile$' "settings opens on the first group"
shot settings-profile
for g in streams picture languages subtitles previews addons about; do
  key KEYCODE_DPAD_DOWN 1.2 "group $g"
  expect "^settings:$g\$" "settings group $g"
  shot "settings-$g"
done
key KEYCODE_BACK 1.5 "back to tabs"

# ---------------------------------------------------------------- results
"$ADB" logcat -d > "$OUT/logcat.txt" || true
APP_UID=$(adbs cmd package list packages -U "$PKG" | grep -m1 -oE 'uid:[0-9]+' | cut -d: -f2)
"$ADB" logcat -d --uid="$APP_UID" > "$OUT/logcat-app.txt" 2>/dev/null || grep -i vyrk "$OUT/logcat.txt" > "$OUT/logcat-app.txt" || true
if grep -qiE 'authKey|password=|token=|10\.0\.2\.2:7000/[^ ]*manifest' "$OUT/logcat-app.txt"; then fail "secret-like text or an unredacted add-on URL in VYRK's logcat"; fi
if grep -q 'FATAL EXCEPTION' "$OUT/logcat.txt"; then fail "app crashed (see logcat.txt)"; fi
log "trace: $(wc -l < "$TRACE") keys"
if (( FAILURES > 0 )); then log "$FAILURES check(s) failed"; exit 1; fi
log "journey passed"
