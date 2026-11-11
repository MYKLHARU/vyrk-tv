#!/usr/bin/env bash
# Shared helpers for ci/e2e.sh and ci/showcase.sh. Remote keys only.
# Expects ADB, PKG, OUT, TRACE, STEP, FAILURES to be set by the caller.

log() { echo "[e2e] $*"; }
fail() { echo "[e2e] FAIL: $*" | tee -a "$TRACE"; FAILURES=$((FAILURES + 1)); }
# A disconnected or wedged emulator must fail the journey, not hold it indefinitely.
adb_run() { timeout --kill-after=5s 45s "$ADB" "$@"; }
adbs() { adb_run shell "$@" | tr -d '\r'; }
# VYRK's focus trace and playback events are off unless switched on (see CONTRIBUTING.md).
enable_traces() { adbs setprop log.tag.VyrkFocus DEBUG; adbs setprop log.tag.VyrkEvent DEBUG; }

shot() {
  STEP=$((STEP + 1))
  local name; name=$(printf '%02d-%s' "$STEP" "$1")
  adb_run exec-out screencap -p > "$OUT/$name.png"
  log "screenshot $name"
}

# Capture whole outputs before filtering: an early-exiting grep under pipefail kills the
# producer with SIGPIPE (exit 141) on Linux.
focus_record() { local o; o=$(adb_run logcat -d -s VyrkFocus:I 2>/dev/null || true); printf '%s\n' "$o" | tail -n 1 | tr -d '\r'; }
focus_label() { printf '%s\n' "$1" | sed 's/.*VyrkFocus: [A-Z_0-9]* -> //'; }
focus_now() { focus_label "$(focus_record)"; }
# Read a NEW report, not the previous key's report while the software renderer is busy.
sample_focus() {
  local before record deadline=$((SECONDS + 5))
  before=$(focus_record)
  adb_run shell input keyevent KEYCODE_SHIFT_LEFT >/dev/null || return 1
  while (( SECONDS < deadline )); do
    record=$(focus_record)
    if [[ -n "$record" && "$record" != "$before" && "$record" == *"KEYCODE_SHIFT_LEFT -> "* ]]; then
      focus_label "$record"; return 0
    fi
    sleep 0.15
  done
  return 1
}
focus_matches() {
  local pattern=$1 deadline=$((SECONDS + ${2:-5})) f
  while (( SECONDS < deadline )); do
    f=$(sample_focus || true)
    [[ "$f" =~ $pattern ]] && return 0
    sleep 0.15
  done
  return 1
}
window_now() { local o; o=$(adbs dumpsys window 2>/dev/null || true); printf '%s\n' "$o" | grep 'mCurrentFocus' | head -n 1 | sed 's/.*{//; s/}.*//' || true; }

# Press a key (name or code), wait, record "key | focused element | focused window".
key() {
  local k=$1 wait=${2:-1.3} label=${3:-}
  printf '%s input\n' "$k" > "$OUT/adb-stage.txt"
  adb_run shell input keyevent "$k"
  sleep "$wait"
  local f w
  printf '%s logcat\n' "$k" > "$OUT/adb-stage.txt"
  f=$(sample_focus || true)
  printf '%s window\n' "$k" > "$OUT/adb-stage.txt"
  w=$(window_now)
  echo "$k | ${f:-?} | ${w:-?} ${label:+| $label}" >> "$TRACE"
  if [[ -z "$f" || "$f" == "none" ]]; then fail "focus lost after $k ($label)"; fi
  if [[ "$w" != *"$PKG"* ]]; then
    # Never keep pressing keys in another app (shared emulators, launcher): stop the journey.
    fail "focused window is not VYRK after $k: $w"
    log "aborting so no further keys reach another app"
    adb_run logcat -d > "$OUT/logcat.txt" 2>/dev/null || true
    exit 1
  fi
}

longpress() {
  adb_run shell input keyevent --longpress "$1"
  sleep "${2:-1.5}"
  local f; f=$(sample_focus || true)
  echo "LONGPRESS $1 | ${f:-?} | $(window_now)" >> "$TRACE"
}

# Neutral key: reports focus without moving it.
probe() { key KEYCODE_SHIFT_LEFT 1 "${1:-probe}"; }

expect() {
  local pattern=$1 what=$2 f; f=$(focus_now)
  if focus_matches "$pattern" 5; then log "ok: $what ($(focus_now))"; else fail "$what: expected /$pattern/, focus is '$(focus_now)'"; fi
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
  local pattern=$1 secs=${2:-30}
  focus_matches "$pattern" "$secs" && return 0
  fail "timed out waiting for focus /$pattern/: $(focus_now)"
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
  # Playback started from a title page comes back to it ($1 = expect_title asserts that);
  # close it so the journey carries on from the page beneath.
  [[ ${1:-} == expect_title ]] && expect '^info:' "leaving the player returns to the title page"
  if [[ "$(focus_now)" =~ ^info: ]]; then key KEYCODE_BACK 1.5 "close the title page"; fi
}
