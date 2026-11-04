#!/usr/bin/env bash
# Shared helpers for ci/e2e.sh and ci/showcase.sh. Remote keys only.
# Expects ADB, PKG, OUT, TRACE, STEP, FAILURES to be set by the caller.

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
  if [[ "$w" != *"$PKG"* ]]; then
    # Never keep pressing keys in another app (shared emulators, launcher): stop the journey.
    fail "focused window is not VYRK after $k: $w"
    log "aborting so no further keys reach another app"
    "$ADB" logcat -d > "$OUT/logcat.txt" 2>/dev/null || true
    exit 1
  fi
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

# On-screen keyboard: rows a-g, h-n, o-u (7 columns), v-z (5), then 123 / Space / Delete / Clear.
# Focus moves geometrically, so row 3 clamps the column to 4.
KB_ROW=0; KB_COL=0
kb_move() {
  local row=$1 col=$2
  while (( KB_ROW < row )); do key KEYCODE_DPAD_DOWN 0.6; KB_ROW=$((KB_ROW + 1)); (( KB_ROW == 3 && KB_COL > 4 )) && KB_COL=4; done
  while (( KB_ROW > row )); do key KEYCODE_DPAD_UP 0.6; KB_ROW=$((KB_ROW - 1)); done
  while (( KB_COL < col )); do key KEYCODE_DPAD_RIGHT 0.6; KB_COL=$((KB_COL + 1)); done
  while (( KB_COL > col )); do key KEYCODE_DPAD_LEFT 0.6; KB_COL=$((KB_COL - 1)); done
}
type_letter() {
  local ch=$1 idx
  idx=$(( $(printf '%d' "'$ch") - 97 ))
  kb_move $((idx / 7)) $((idx % 7))
  key KEYCODE_DPAD_CENTER 0.8 "type $ch"
}
# Space: from "v" go down to "123", right to Space, press, then up lands on "x".
type_space() {
  kb_move 3 0
  key KEYCODE_DPAD_DOWN 0.6
  key KEYCODE_DPAD_RIGHT 0.6
  key KEYCODE_DPAD_CENTER 0.8 "type space"
  key KEYCODE_DPAD_UP 0.6
  KB_ROW=3; KB_COL=2
}
# Leave the player: press Back only while PlayerActivity is in front.
back_from_player() {
  local i=0
  while (( i < 5 )) && [[ "$(window_now)" == *PlayerActivity* ]]; do
    key KEYCODE_BACK 1.5 "leave player"
    i=$((i + 1))
  done
  sleep 2
}