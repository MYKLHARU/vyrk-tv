#!/usr/bin/env bash
# VYRK showcase journey: captures the README screenshots from the real app with live
# catalogues, driven by remote keys only. Captures go to $OUT; docs/tools/make_images.py
# turns them into docs/images.
set -euo pipefail

ADB=${ADB:-adb}
PKG=com.vyrk.tv
OUT=${OUT:-ci/out/showcase}
APK=${APK:-}
mkdir -p "$OUT"
TRACE="$OUT/focus-trace.txt"
: > "$TRACE"
STEP=0
FAILURES=0
source "$(dirname "$0")/lib.sh"

cap() { "$ADB" exec-out screencap -p > "$OUT/$1.png"; log "capture $1"; }

"$ADB" wait-for-device
for _ in $(seq 1 60); do [[ "$(adbs getprop sys.boot_completed)" == 1 ]] && break; sleep 2; done
for _ in $(seq 1 30); do adbs pm path android >/dev/null 2>&1 && break; sleep 2; done
if [[ -n "$APK" ]]; then "$ADB" uninstall "$PKG" >/dev/null 2>&1 || true; "$ADB" install -r "$APK"; fi
adbs pm clear "$PKG" >/dev/null || true
enable_traces
"$ADB" logcat -c
adbs am start -n "$PKG/.MainActivity" >/dev/null
sleep 9

# First run
probe "welcome"
cap first-run
key KEYCODE_DPAD_CENTER 2 "guest"
wait_focus '^hero:watch$' 45
sleep 4

# The test add-on (streams for playback)
adbs am start -a android.intent.action.VIEW -d "'vyrk://install?url=http%3A%2F%2F10.0.2.2%3A${ADDON_PORT:-7000}%2Fmanifest.json'" "$PKG" >/dev/null
sleep 5
if focus_matches '^install:confirm$' 15; then key KEYCODE_DPAD_CENTER 4 "install test add-on"; fi
wait_focus '^hero:watch$' 20 || true

# Home: the featured title over the rows (the Top 10 rows come from TMDB)
sleep 8
cap home

# Watch from the hero for 35 seconds so Continue Watching has a title
key KEYCODE_DPAD_CENTER 3 "watch now"
sleep 35
back_from_player
probe "home with continue watching"

# A Top 10 row: the title header over the media cards
until_focus KEYCODE_DPAD_DOWN '^poster:top10:movie#' 8 || true
key KEYCODE_DPAD_RIGHT 1.5
sleep 4
cap top10-rows

# A film's title page: the hero, then its Details and Cast & crew tabs
key KEYCODE_DPAD_CENTER 6 "info page"
sleep 2
cap info-page
key KEYCODE_DPAD_DOWN 2 "to the tabs"
# A film opens on Details; otherwise step to it.
focus_matches '^info:tab:details$' 3 || until_focus KEYCODE_DPAD_RIGHT '^info:tab:details$' 3 || until_focus KEYCODE_DPAD_LEFT '^info:tab:details$' 4 || true
sleep 2
cap info-details
if until_focus KEYCODE_DPAD_RIGHT '^info:tab:cast$' 4; then sleep 3; cap info-cast; fi
key KEYCODE_BACK 2 "back to Play"
# Add it, and the next title, to Library
until_focus KEYCODE_DPAD_RIGHT '^info:library$' 4 && key KEYCODE_DPAD_CENTER 1.5 "add to library"
key KEYCODE_BACK 2 "close the title page"
key KEYCODE_DPAD_RIGHT 1.2
longpress KEYCODE_DPAD_CENTER 2
until_focus KEYCODE_DPAD_DOWN '^menu:library$' 6 && key KEYCODE_DPAD_CENTER 1.5 "add second title"

# A series' title page: its Episodes tab
until_focus KEYCODE_DPAD_DOWN '^poster:top10:series#' 6 || true
key KEYCODE_DPAD_CENTER 7 "series info"
key KEYCODE_DPAD_DOWN 1.5 "episodes tab"
key KEYCODE_DPAD_DOWN 2 "seasons"
cap series-info
key KEYCODE_BACK 1.6 "back to Play"
key KEYCODE_BACK 2 "close the title page"

# The Anime tab: add Anime Kitsu from its card, then AniList's rows
for _ in $(seq 1 8); do [[ "$(focus_now)" =~ ^(tab|nav): ]] && break; key KEYCODE_DPAD_UP 1 "up to the tabs"; done
if until_focus KEYCODE_DPAD_LEFT '^tab:home$' 8 && until_focus KEYCODE_DPAD_RIGHT '^tab:anime$' 6; then
  sleep 3
  key KEYCODE_DPAD_DOWN 1.5 "into Anime"
  if focus_matches '^anilist:add_kitsu$' 10; then
    key KEYCODE_DPAD_CENTER 2 "add Anime Kitsu"
    # Not key(): the focused button goes with the card once the add-on is in.
    if focus_matches '^install:confirm$' 30; then adb_run shell input keyevent KEYCODE_DPAD_CENTER; fi
  fi
  wait_focus '^poster:anilist:' 30 || true
  # The header's artwork and logo come from TMDB and fanart.tv by VYRK's anime IDs: give them time.
  sleep 14
  cap anime
fi

# Search "big buck bunny" and play it
until_focus KEYCODE_DPAD_UP '^(tab|nav):' 10
until_focus KEYCODE_DPAD_RIGHT '^nav:search$' 8
key KEYCODE_DPAD_CENTER 2 "search"
KB_ROW=0; KB_COL=0
for w in big buck bunny; do
  for ((i = 0; i < ${#w}; i++)); do type_letter "${w:i:1}"; done
  [[ "$w" != bunny ]] && type_space
done
sleep 7
cap search
until_focus KEYCODE_DPAD_RIGHT '^search:movie#' 10 || true
key KEYCODE_DPAD_CENTER 6 "bbb info"
key KEYCODE_DPAD_CENTER 3 "play bbb"
sleep 20
key KEYCODE_DPAD_DOWN 1.2 "controls"
cap player
key KEYCODE_CAPTIONS 2.5
cap audio-subtitles
back_from_player

# Home, Library, Settings (Streams, then Add-ons)
until_focus KEYCODE_DPAD_UP '^(tab|nav):' 10
until_focus KEYCODE_DPAD_LEFT '^tab:home$' 8
sleep 3
until_focus KEYCODE_DPAD_RIGHT '^tab:library$' 8 && { sleep 3; cap library; }
until_focus KEYCODE_DPAD_RIGHT '^nav:settings$' 6
key KEYCODE_DPAD_CENTER 2
key KEYCODE_DPAD_DOWN 1.5 "streams"
cap settings
until_focus KEYCODE_DPAD_DOWN '^settings:addons$' 8 && { sleep 2; cap addons; }
key KEYCODE_BACK 1.5

log "captures: $(ls "$OUT"/*.png | wc -l)"
if (( FAILURES > 0 )); then log "$FAILURES navigation check(s) failed (captures kept)"; fi
