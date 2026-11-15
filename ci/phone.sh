#!/usr/bin/env bash
# VYRK phone journey: drives the touch interface on a phone emulator by tapping elements found by
# their test tags (ci/ui_find.py over uiautomator dumps). Writes screenshots, the taps it made and
# logcat to $OUT. Fails if an expected element never appears, VYRK leaves the front, or it crashes.
#
# Env: ADB (default adb), APK (APK to install), OUT (default ci/out/phone).
set -euo pipefail

ADB=${ADB:-adb}
PKG=com.vyrk.tv
OUT=${OUT:-ci/out/phone}
APK=${APK:-}
ADDON_URL="http://10.0.2.2:${ADDON_PORT:-7000}/manifest.json"
mkdir -p "$OUT"
TRACE="$OUT/taps.txt"
: > "$TRACE"
STEP=0
FAILURES=0
HERE="$(dirname "$0")"
source "$HERE/lib.sh"

on_exit() {
  local code=$?
  trap - EXIT
  timeout --kill-after=2s 15s "$ADB" logcat -d > "$OUT/logcat.txt" 2>&1 || true
  if (( code != 0 )); then
    timeout --kill-after=2s 10s "$ADB" exec-out screencap -p > "$OUT/failure.png" 2>/dev/null || true
    # In the job log too: the crash buffer and VYRK's own errors.
    echo "----- crash buffer -----"; timeout 10s "$ADB" logcat -d -b crash 2>/dev/null | tail -n 120 || true
    echo "----- errors -----"; grep -E " E (AndroidRuntime|Vyrk|ExoPlayer|MediaCodec)| F |FATAL" "$OUT/logcat.txt" | tail -n 60 || true
  fi
  exit "$code"
}
trap on_exit EXIT

dump() {
  adb_run shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || true
  adb_run exec-out cat /sdcard/ui.xml > "$OUT/ui.xml" 2>/dev/null || true
}
# Wait until an element is on screen; prints its centre. MODE is id, prefix or text.
find_el() {
  local mode=$1 want=$2 secs=${3:-20} deadline at
  deadline=$((SECONDS + secs))
  while (( SECONDS < deadline )); do
    dump
    if at=$(python3 "$HERE/ui_find.py" "$OUT/ui.xml" "$mode" "$want"); then echo "$at"; return 0; fi
    sleep 1
  done
  return 1
}
front_is_vyrk() {
  local w; w=$(window_now)
  [[ "$w" == *"$PKG"* ]] || { fail "VYRK is not in front: $w"; return 1; }
}
tap() {
  local mode=$1 want=$2 label=${3:-$2} secs=${4:-20} at
  if at=$(find_el "$mode" "$want" "$secs"); then
    adb_run shell input tap $at
    echo "tap $mode:$want at $at | $label" >> "$TRACE"
    log "tap $label"
    sleep 1.5
    return 0
  fi
  fail "never saw $mode '$want' ($label)"
  cp "$OUT/ui.xml" "$OUT/missing-$(echo "$want" | tr -c 'a-zA-Z0-9' '_').xml" 2>/dev/null || true
  return 1
}
expect_el() { find_el "$1" "$2" "${3:-20}" >/dev/null || { fail "expected $1 '$2' (${4:-$2})"; return 1; }; log "ok: ${4:-$2}"; }
crashed() {
  local o; o=$(adb_run logcat -d -b crash 2>/dev/null || true)
  [[ "$o" == *"Process: $PKG"* ]]
}

# ---------------------------------------------------------------- setup
adb_run wait-for-device
for _ in $(seq 1 60); do [[ "$(adbs getprop sys.boot_completed)" == 1 ]] && break; sleep 2; done
for _ in $(seq 1 30); do adbs pm path android >/dev/null 2>&1 && break; sleep 2; done
log "device: $(adbs getprop ro.product.model) API $(adbs getprop ro.build.version.sdk), $(adbs wm size)"
adbs settings put system accelerometer_rotation 0 || true
adbs settings put system user_rotation 0 || true
if [[ -n "$APK" ]]; then
  adb_run uninstall "$PKG" >/dev/null 2>&1 || true
  adb_run install -r "$APK"
fi
adbs pm clear "$PKG" >/dev/null || true
adb_run logcat -c
adb_run logcat -b crash -c || true
adbs am start -n "$PKG/.MainActivity" >/dev/null
sleep 8

# ---------------------------------------------------------------- first run
expect_el id welcome:guest 30 "first run offers sign-in and Use without an account"
expect_el id pairing:open 5 "Open Stremio sign-in"
shot phone-welcome
tap id welcome:guest "use without an account"
expect_el id nav:browse 30 "bottom navigation"
expect_el prefix poster: 60 "catalogue rows with posters"
sleep 4
shot phone-home

# Install the test add-on from a link: the sheet asks first.
adbs am start -a android.intent.action.VIEW -d "'vyrk://install?url=http%3A%2F%2F10.0.2.2%3A${ADDON_PORT:-7000}%2Fmanifest.json'" "$PKG" >/dev/null
tap id install:confirm "install the test add-on" 20

# Rows further down, and a catalogue page from the strip.
adb_run shell input swipe 540 1700 540 600 400
sleep 2
shot phone-home-rows
tap id tab:movies "Movies"
sleep 4
shot phone-movies
tap id tab:home "Home"

# Search "bunny" with the keyboard; the test add-on answers with Big Buck Bunny.
tap id nav:search "Search"
sleep 1
adb_run shell input text "bunny"
sleep 6
adbs input keyevent KEYCODE_ENTER || true   # the keyboard's Search key hides it
sleep 1
expect_el prefix search:movie# 30 "search results"
shot phone-search
tap prefix search:movie# "first result"
expect_el id info:play 20 "title page"
sleep 4
shot phone-info
adb_run shell input swipe 540 1600 540 500 400
sleep 2
shot phone-info-sources

# Play: the player turns to landscape; controls on a tap, then audio & subtitles.
tap id info:play "play"
sleep 25
front_is_vyrk
[[ "$(window_now)" == *PlayerActivity* ]] || fail "the player didn't open: $(window_now)"
shot phone-player
size=$(adbs wm size | sed 's/.*: //'); w=${size%x*}; h=${size#*x}
# The player is landscape whichever way the display reports its size.
cx=$(( (w > h ? w : h) / 2 )); cy=$(( (w > h ? h : w) / 2 ))
adb_run shell input tap "$cx" "$cy"
sleep 1
shot phone-player-controls
tap id player:audio_subs "audio and subtitles" 8 || true
sleep 1
shot phone-player-audio-subs
adbs input keyevent KEYCODE_BACK
sleep 1
adbs input keyevent KEYCODE_BACK
sleep 3
[[ "$(window_now)" == *PlayerActivity* ]] && fail "Back didn't leave the player"
expect_el id info:play 10 "leaving the player returns to the title page"
adbs input keyevent KEYCODE_BACK
sleep 2

# Library and Settings.
tap id nav:library "Library"
sleep 2
shot phone-library
tap id nav:settings "Settings"
sleep 2
shot phone-settings
tap id settings:addons "Add-ons"
sleep 2
shot phone-settings-addons
adbs input keyevent KEYCODE_BACK
sleep 1

# Landscape on a phone (rotation is allowed outside the player).
tap id nav:browse "Home"
adbs settings put system user_rotation 1 || true
sleep 4
shot phone-home-landscape
adbs settings put system user_rotation 0 || true
sleep 2

if crashed; then fail "VYRK crashed (see logcat)"; fi
front_is_vyrk || true
log "screenshots: $(ls "$OUT"/*.png 2>/dev/null | wc -l)"
if (( FAILURES > 0 )); then log "$FAILURES check(s) failed"; exit 1; fi
log "phone journey passed"
