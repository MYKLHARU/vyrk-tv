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
ADDON_URL="http://10.0.2.2:${ADDON_PORT:-7000}/manifest.json"
ADDON_LOG=${ADDON_LOG:-}
mkdir -p "$OUT"
TRACE="$OUT/focus-trace.txt"
: > "$TRACE"
STEP=0
FAILURES=0

source "$(dirname "$0")/lib.sh"

diagnostics_on_exit() {
  local ci_exitcode=$?
  trap - EXIT
  if (( ci_exitcode != 0 )); then
    log "journey exited with $ci_exitcode; collecting bounded diagnostics"
    timeout --kill-after=2s 15s "$ADB" logcat -d > "$OUT/logcat-failure.txt" 2>&1 || true
    timeout --kill-after=2s 10s "$ADB" shell dumpsys activity activities > "$OUT/activity-failure.txt" 2>&1 || true
    timeout --kill-after=2s 10s "$ADB" exec-out screencap -p > "$OUT/failure.png" 2>/dev/null || true
  fi
  exit "$ci_exitcode"
}
trap diagnostics_on_exit EXIT

# ---------------------------------------------------------------- setup
adb_run wait-for-device
for _ in $(seq 1 60); do [[ "$(adbs getprop sys.boot_completed)" == 1 ]] && break; sleep 2; done
for _ in $(seq 1 30); do adbs pm path android >/dev/null 2>&1 && break; sleep 2; done
log "device: $(adbs getprop ro.product.model) API $(adbs getprop ro.build.version.sdk), $(adbs wm size)"
if [[ -n "$APK" ]]; then
  adb_run uninstall "$PKG" >/dev/null 2>&1 || true
  adb_run install -r "$APK"
fi
adbs pm clear "$PKG" >/dev/null || true
enable_traces
adb_run logcat -c
adbs am start -n "$PKG/.MainActivity" >/dev/null
sleep 8

# ---------------------------------------------------------------- first run
probe "welcome"
# First run opens straight on the Stremio QR code, with "Use without an account" focused.
expect '^welcome:guest$' "first run shows the QR code with Use without an account"
shot welcome
key KEYCODE_DPAD_CENTER 2 "use without an account"
wait_focus '^hero:watch$' 45
shot home-live-cinemeta

# ---------------------------------------------------------------- install the test add-on from a link
adbs am start -a android.intent.action.VIEW -d "'vyrk://install?url=http%3A%2F%2F10.0.2.2%3A${ADDON_PORT:-7000}%2Fmanifest.json'" "$PKG" >/dev/null
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

# ---------------------------------------------------------------- hold OK on the hero: title menu
longpress KEYCODE_DPAD_CENTER 2
expect '^menu:play$' "hold OK on Watch now opens the title menu"
shot hero-menu
key KEYCODE_BACK 1.5 "close the menu"
expect '^hero:watch$' "Back closes the menu and returns to Watch now"

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

# Handle only the specific recovery prompt shown by the slow software decoder.
# Exercise the real remote choice instead of letting a media shortcut bypass it.
dismiss_stall_prompt() {
  probe "current playback prompt"
  if [[ "$(focus_now)" == prompt:yes ]]; then
    adbs uiautomator dump /sdcard/stall-prompt.xml >/dev/null
    adb_run pull /sdcard/stall-prompt.xml "$OUT/stall-prompt.xml" >/dev/null
    if grep -q 'Playback keeps pausing' "$OUT/stall-prompt.xml"; then
      key KEYCODE_DPAD_RIGHT 0.5 "Keep trying on the software renderer"
      expect '^prompt:no$' "stall recovery Keep trying is reachable"
      key KEYCODE_DPAD_LEFT 0.4 "back to lower quality"
      expect '^prompt:yes$' "prompt owns Left"
      key KEYCODE_DPAD_RIGHT 0.4 "Keep trying again"
      key KEYCODE_DPAD_UP 0.4 "prompt retains focus above"
      expect '^prompt:no$' "prompt prevents focus escaping to the timeline"
      key KEYCODE_DPAD_CENTER 1 "continue the local sample"
      wait_focus '^player:' 10
    fi
  fi
}

# ---------------------------------------------------------------- play: broken 4K fails over
ADDON_START=0
[[ -n "$ADDON_LOG" && -f "$ADDON_LOG" ]] && ADDON_START=$(wc -l < "$ADDON_LOG")
# The player is a new activity: wait for it to take focus rather than sampling at a fixed time.
adb_run shell input keyevent KEYCODE_DPAD_CENTER
echo "KEYCODE_DPAD_CENTER | (player starting) | $(window_now) | Play" >> "$TRACE"
wait_focus '^player:' 30
sleep 25
shot player
probe "player after decoder warmup"
dismiss_stall_prompt
expect '^player:' "player has focus"
if [[ -n "$ADDON_LOG" && -f "$ADDON_LOG" ]]; then
  tail -n +"$((ADDON_START + 1))" "$ADDON_LOG" > "$OUT/addon-requests-during-play.txt"
  PLAYLOG="$OUT/addon-requests-during-play.txt"
  if grep -q 'broken-2160p.mkv HTTP/1.1" 404' "$PLAYLOG" && grep -q 'sample-720p.mp4 HTTP/1.1" 20[06]' "$PLAYLOG"; then
    b=$(awk '/broken-2160p/ { print NR; exit }' "$PLAYLOG")
    r=$(awk '/GET \/stream\// { n = NR } END { print n }' "$PLAYLOG")
    s=$(awk '/sample-720p/ { print NR; exit }' "$PLAYLOG")
    if (( b < r && r < s )); then log "ok: broken 4K failed, link refreshed, fell over to 720p"; else fail "fail-over order wrong (broken=$b refresh=$r sample=$s)"; fi
  else
    fail "fail-over not seen in add-on log"
  fi
fi

key KEYCODE_DPAD_DOWN 1.5 "show controls"
shot player-controls
# The slow software decoder can raise the stall prompt again at any point; it owns the remote, so
# CC would not reach the player while it is up.
dismiss_stall_prompt
key KEYCODE_CAPTIONS 2 "CC opens Audio & subtitles"
# The CI emulator can be slow to give the panel window focus: wait, and press CC once more if it never opened.
for _ in 1 2 3 4 5; do
  [[ "$(focus_now)" =~ ^panel: ]] && break
  if [[ "$(focus_now)" == prompt:yes ]]; then dismiss_stall_prompt; key KEYCODE_CAPTIONS 2 "CC after the stall prompt"; continue; fi
  probe "panel" >/dev/null 2>&1 || true; sleep 1
done
[[ "$(focus_now)" =~ ^panel: ]] || key KEYCODE_CAPTIONS 3 "CC again"
expect '^panel:' "Audio & subtitles panel"
shot audio-subtitles
key KEYCODE_BACK 1.2 "close panel"
expect '^player:' "Back closes the panel and stays in the player"
back_from_player
probe "home after playback"
shot home-after-playback

# ---------------------------------------------------------------- Continue Watching + restart restore
until_focus KEYCODE_DPAD_UP '^hero:watch$' 6 || true
key KEYCODE_DPAD_DOWN 2 "to Continue Watching"
expect '^poster:cw#0$' "Continue Watching row is first"
shot continue-watching
adbs am force-stop "$PKG"
sleep 2
adb_run logcat -c
adbs am start -n "$PKG/.MainActivity" >/dev/null
sleep 12
wait_focus '^poster:cw#0$' 30
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
adb_run shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || true
adb_run pull /sdcard/ui.xml "$OUT/ui-after-remove.xml" >/dev/null 2>&1 || true
if grep -qE 'content-desc="[^"]*, [0-9]+%' "$OUT/ui-after-remove.xml" 2>/dev/null; then
  fail "a poster still shows a progress percentage after removal"
else
  log "ok: no poster shows progress after removal"
fi
key KEYCODE_DPAD_DOWN 1.5
if [[ "$(focus_now)" == poster:cw* ]]; then fail "Continue Watching row still present"; else log "ok: Continue Watching row is gone"; fi

# ---------------------------------------------------------------- categories keep their place
until_focus KEYCODE_DPAD_DOWN '^poster:' 4
key KEYCODE_DPAD_RIGHT 1.0; key KEYCODE_DPAD_RIGHT 1.2
KEEP=$(focus_now); log "selected on Home: $KEEP"
key KEYCODE_BACK 1.5 "to the tab strip"
expect '^tab:home$' "Back from a row goes to the tab strip"
key KEYCODE_DPAD_RIGHT 1.5 "Movies"; key KEYCODE_DPAD_LEFT 1.5 "Home again"
key KEYCODE_DPAD_DOWN 2.5 "back into Home"
[[ "$(focus_now)" == 'browse:mode' ]] && key KEYCODE_DPAD_DOWN 1 "past mode switch"
wait_focus "^$KEEP\$" 15
if [[ "$(focus_now)" == "$KEEP" ]]; then log "ok: Home kept the selected title across a tab switch ($KEEP)"; else fail "Home lost its place: expected '$KEEP', focus is '$(focus_now)'"; fi

# ---------------------------------------------------------------- tour every tab
until_focus KEYCODE_DPAD_UP '^tab:home$' 8
for t in movies series discover library; do
  until_focus KEYCODE_DPAD_RIGHT "^tab:$t\$" 3 && {
    sleep 3; shot "tab-$t"; key KEYCODE_DPAD_DOWN 2 "into $t"
    [[ "$(focus_now)" == 'browse:mode' ]] && key KEYCODE_DPAD_DOWN 1 "past mode switch"
    probe "$t content"
    # The featured row is there on the first visit, without scrolling or a second visit.
    [[ $t == movies || $t == series ]] && expect '^hero:watch$' "$t opens with its featured row"
    until_focus KEYCODE_DPAD_UP "^tab:$t\$" 6
  }
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
# Settings is the gear at the right of the bar (no profiles).
until_focus KEYCODE_DPAD_RIGHT '^nav:settings$' 6
key KEYCODE_DPAD_CENTER 2 "open settings"
expect '^settings:profile$' "settings opens on the first group"
shot settings-profile
# Controls sit on the right: Right must still enter at the top row (Default tab), not at Hidden tabs.
key KEYCODE_DPAD_RIGHT 1.2 "into Profile & appearance"
expect '^set:default_tab:0$' "Right enters Profile & appearance at its first row"
key KEYCODE_DPAD_LEFT 1.2 "back to the groups"
expect '^settings:profile$' "Left from the first row returns to its group"
for g in streams picture languages subtitles previews addons about; do
  key KEYCODE_DPAD_DOWN 1.2 "group $g"
  expect "^settings:$g\$" "settings group $g"
  shot "settings-$g"
done
# Right enters a group at its top; Left from any setting returns to that group's entry. Left used
# to land on whichever entry was level with the setting, which opened another group.
until_focus KEYCODE_DPAD_UP '^settings:picture$' 8
key KEYCODE_DPAD_RIGHT 1.2 "into Picture & sound"
expect '^set:ui_size:0$' "Right from a group enters its first setting"
for _ in 1 2 3 4 5; do key KEYCODE_DPAD_DOWN 0.8; done
probe "low in Picture & sound"
key KEYCODE_DPAD_LEFT 1.2 "back to the groups"
expect '^settings:picture$' "Left from a lower setting returns to its own group"
# Add-ons live in Settings now (no Add-ons tab).
until_focus KEYCODE_DPAD_DOWN '^settings:addons$' 6
key KEYCODE_DPAD_RIGHT 1.2 "into Add-ons"
expect '^addons:install_url$' "Settings › Add-ons opens on Install from URL"
until_focus KEYCODE_DPAD_DOWN '^addon:[0-9]+:' 4 && shot settings-addons-list
key KEYCODE_DPAD_LEFT 1.2; key KEYCODE_DPAD_LEFT 1.2; key KEYCODE_DPAD_LEFT 1.2
expect '^settings:addons$' "Left from an add-on returns to the Add-ons group"
key KEYCODE_BACK 1.5 "back to tabs"

# ---------------------------------------------------------------- skip intro, timeline and chapters
# Game of Thrones S1E1: IntroDB has its intro at 437-531 s (needs a sample of 10 minutes or more).
adbs am start -a android.intent.action.VIEW -d "'vyrk://open?type=series&id=tt0944947'" "$PKG" >/dev/null
sleep 7
probe "series info"
expect '^info:play$' "vyrk://open opens the series info page"
shot info-series
# The title page is two pages: the more button (or Down) slides up episodes and details; Back returns.
until_focus KEYCODE_DPAD_RIGHT '^info:more$' 4
key KEYCODE_DPAD_CENTER 1.6 "page two"
expect '^info:watched$' "more opens page two"
shot info-series-page-two
key KEYCODE_BACK 1.6 "back to page one"
expect '^info:play$' "Back on page two returns to Play"
adb_run shell input keyevent KEYCODE_DPAD_CENTER
wait_focus '^player:' 40
sleep 12
dismiss_stall_prompt
for _ in $(seq 1 15); do adb_run shell input keyevent KEYCODE_MEDIA_FAST_FORWARD; sleep 0.4; done
sleep 7
shot skip-intro-pill
EVENTS=$(adb_run logcat -d -s VyrkEvent:I 2>/dev/null || true)
# IntroDB is an external service: report, but don't fail the build on its availability.
if printf '%s\n' "$EVENTS" | grep -q 'skip_offered INTRO'; then log "ok: Skip intro offered from IntroDB timings"
else log "warn: Skip intro not seen ($(printf '%s\n' "$EVENTS" | grep -o 'introdb_segments [0-9]*' | tail -n 1 || echo 'no IntroDB answer'))"; fi
dismiss_stall_prompt
key KEYCODE_DPAD_DOWN 1.5 "show controls"
key KEYCODE_DPAD_UP 1.2 "to timeline"
# The stall prompt can come back between the dismissal and these keys; close it and try again.
for _ in 1 2; do
  [[ "$(focus_now)" == prompt:yes ]] || break
  dismiss_stall_prompt
  key KEYCODE_DPAD_DOWN 1.5 "show controls again"
  key KEYCODE_DPAD_UP 1.2 "to timeline again"
done
expect '^player:timeline$' "timeline takes focus above the controls"
key KEYCODE_DPAD_RIGHT 0.4 "scrub"; key KEYCODE_DPAD_RIGHT 0.4
shot timeline-scrub
key KEYCODE_DPAD_UP 1.0 "chapter mode"
shot timeline-chapters
key KEYCODE_DPAD_CENTER 2 "seek"
expect '^player:timeline$' "focus stays on the timeline after seeking"

# Use local, deterministic episode metadata for both the short and full-file scenarios.
back_from_player
open_fixture() {
  adbs am start -a android.intent.action.VIEW -d "'vyrk://open?type=series&id=$1'" "$PKG" >/dev/null
  sleep 4
  probe "fixture info"
  expect '^info:play$' "fixture opens on Play"
  key KEYCODE_DPAD_CENTER 4 "play fixture"
  wait_focus '^player:' 40
  sleep 4
}
seek_to_sample_end() {
  dismiss_stall_prompt
  for _ in $(seq 1 22); do
    adb_run shell input keyevent KEYCODE_MEDIA_FAST_FORWARD
    sleep 0.4
  done
  sleep 3
}

# A ten-minute file presented as a 45-minute episode must stop with recovery choices.
open_fixture vyrk-short
adb_run logcat -c
seek_to_sample_end
wait_focus '^player:incomplete_switch$' 15
shot short-stream-recovery
EV=$(adb_run logcat -d -s VyrkEvent:I 2>/dev/null || true)
if printf '%s\n' "$EV" | grep -q 'play_start vyrk-short:1:2'; then fail "short stream advanced automatically"; fi
key KEYCODE_DPAD_RIGHT 0.8 "Retry"
expect '^player:incomplete_retry$' "Retry is reachable"
key KEYCODE_DPAD_CENTER 3 "retry short source from the beginning"
EV=$(adb_run logcat -d -s VyrkEvent:I 2>/dev/null || true)
if printf '%s\n' "$EV" | grep -q 'play_start vyrk-short:1:1 0'; then log "ok: Retry starts at zero"; else fail "Retry did not restart at zero"; fi
seek_to_sample_end
wait_focus '^player:incomplete_switch$' 15
key KEYCODE_DPAD_CENTER 1.5 "Switch stream"
wait_focus '^source:org.vyrk.testaddon:' 10
shot short-stream-sources
expect '^source:org.vyrk.testaddon:1$' "full alternative is offered before the short source"
key KEYCODE_BACK 1.0 "back to recovery choices"
wait_focus '^player:incomplete_switch$' 10
key KEYCODE_DPAD_RIGHT 0.5
key KEYCODE_DPAD_RIGHT 0.5
expect '^player:incomplete_next$' "manual Next episode is reachable"
key KEYCODE_DPAD_CENTER 4 "manual next episode"
EV=$(adb_run logcat -d -s VyrkEvent:I 2>/dev/null || true)
if printf '%s\n' "$EV" | grep -q 'play_start vyrk-short:1:2 0'; then log "ok: manual Next starts at zero"; else fail "manual Next did not start episode two"; fi
seek_to_sample_end
wait_focus '^player:incomplete_switch$' 15
key KEYCODE_DPAD_CENTER 1.5 "Switch stream on episode two"
wait_focus '^source:org.vyrk.testaddon:1$' 10
key KEYCODE_DPAD_CENTER 4 "play full alternate"
key KEYCODE_DPAD_DOWN 0.8 "show full alternate controls"
expect '^player:play_pause$' "Switch stream recovers normal playback"
shot full-alternate-playing
back_from_player

# The same ten-minute file with a ten-minute episode runtime must still autoplay S1E2 at zero.
open_fixture vyrk-full
adb_run logcat -c
seek_to_sample_end
NEXT_START=""
for _ in $(seq 1 25); do
  EV=$(adb_run logcat -d -s VyrkEvent:I 2>/dev/null || true)
  NEXT_START=$(printf '%s\n' "$EV" | grep -o 'play_start vyrk-full:1:2 [0-9]*' | tail -n 1 || true)
  [[ -n "$NEXT_START" ]] && break
  sleep 1
done
if [[ -z "$NEXT_START" ]]; then fail "the full fixture never autoplayed episode two"
elif (( ${NEXT_START##* } < 5000 )); then log "ok: full episode autoplays next at ${NEXT_START##* } ms"
else fail "next episode started at ${NEXT_START##* } ms"; fi
shot full-stream-next-episode
back_from_player

# Ordinary info page, using AIO's real MAL/relation/canonical-parent response shape.
adbs am start -a android.intent.action.VIEW -d "'vyrk://open?type=series&id=mal:62516'" "$PKG" >/dev/null
sleep 11
wait_focus '^info:play$' 10
shot aio-season-logo
adbs uiautomator dump /sdcard/aio-season-logo.xml >/dev/null
adb_run pull /sdcard/aio-season-logo.xml "$OUT/aio-season-logo.xml" >/dev/null
if python - "$OUT/aio-season-logo.xml" <<'PY'
import sys, xml.etree.ElementTree as E
nodes = list(E.parse(sys.argv[1]).iter('node'))
title = 'DAN DA DAN Season 3'
assert any(n.get('class') == 'android.widget.ImageView' and n.get('content-desc') == title for n in nodes), 'AIO season logo was not rendered'
assert not any(n.get('text') == title for n in nodes), 'Text fallback hid the AIO logo'
PY
then log "ok: delayed AIO season metadata renders its inherited logo in the normal info page"
else fail "AIO season logo failed in normal info page"; fi
key KEYCODE_BACK 1 "close AIO fixture"

if ! python ci/anime_mode_probe.py --adb "$ADB" --package "$PKG" --out "$OUT/anime-mode"; then
  fail "anime mode normal-app journey failed"
fi

# ---------------------------------------------------------------- results
adb_run logcat -d > "$OUT/logcat.txt" || true
PKGS=$(adbs cmd package list packages -U "$PKG" || true)
APP_UID=$(printf '%s\n' "$PKGS" | grep -oE 'uid:[0-9]+' | head -n 1 | cut -d: -f2 || true)
adb_run logcat -d --uid="$APP_UID" > "$OUT/logcat-app.txt" 2>/dev/null || grep -i vyrk "$OUT/logcat.txt" > "$OUT/logcat-app.txt" || true
if grep -qiE 'authKey|password=|token=|10\.0\.2\.2:7000/[^ ]*manifest' "$OUT/logcat-app.txt"; then fail "secret-like text or an unredacted add-on URL in VYRK's logcat"; fi
if grep -q 'FATAL EXCEPTION' "$OUT/logcat.txt"; then fail "app crashed (see logcat.txt)"; fi
log "trace: $(wc -l < "$TRACE") keys"
if (( FAILURES > 0 )); then log "$FAILURES check(s) failed"; exit 1; fi
log "journey passed"
