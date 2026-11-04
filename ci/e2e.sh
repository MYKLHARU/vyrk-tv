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

# ---------------------------------------------------------------- setup
"$ADB" wait-for-device
for _ in $(seq 1 60); do [[ "$(adbs getprop sys.boot_completed)" == 1 ]] && break; sleep 2; done
for _ in $(seq 1 30); do adbs pm path android >/dev/null 2>&1 && break; sleep 2; done
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
# The player is a new activity: wait for it to take focus rather than sampling at a fixed time.
"$ADB" shell input keyevent KEYCODE_DPAD_CENTER
echo "KEYCODE_DPAD_CENTER | (player starting) | $(window_now) | Play" >> "$TRACE"
wait_focus '^player:' 30
sleep 25
shot player
probe "player"
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
key KEYCODE_CAPTIONS 2 "CC opens Audio & subtitles"
# The CI emulator can be slow to give the panel window focus: wait, and press CC once more if it never opened.
for _ in 1 2 3 4 5; do [[ "$(focus_now)" =~ ^panel: ]] && break; probe "panel" >/dev/null 2>&1 || true; sleep 1; done
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

# ---------------------------------------------------------------- categories keep their place
until_focus KEYCODE_DPAD_DOWN '^poster:' 4
key KEYCODE_DPAD_RIGHT 1.0; key KEYCODE_DPAD_RIGHT 1.2
KEEP=$(focus_now); log "selected on Home: $KEEP"
key KEYCODE_BACK 1.5 "to the tab strip"
expect '^tab:home$' "Back from a row goes to the tab strip"
key KEYCODE_DPAD_RIGHT 1.5 "Movies"; key KEYCODE_DPAD_LEFT 1.5 "Home again"
key KEYCODE_DPAD_DOWN 2.5 "back into Home"
if [[ "$(focus_now)" == "$KEEP" ]]; then log "ok: Home kept the selected title across a tab switch ($KEEP)"; else fail "Home lost its place: expected '$KEEP', focus is '$(focus_now)'"; fi

# ---------------------------------------------------------------- tour every tab
until_focus KEYCODE_DPAD_UP '^tab:home$' 8
for t in movies series anime library addons; do
  until_focus KEYCODE_DPAD_RIGHT "^tab:$t\$" 3 && {
    sleep 3; shot "tab-$t"; key KEYCODE_DPAD_DOWN 2 "into $t"; probe "$t content"
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

# ---------------------------------------------------------------- skip intro, timeline and chapters
# Game of Thrones S1E1: IntroDB has its intro at 437-531 s (needs a sample of 10 minutes or more).
adbs am start -a android.intent.action.VIEW -d "'vyrk://open?type=series&id=tt0944947'" "$PKG" >/dev/null
sleep 7
probe "series info"
expect '^info:play$' "vyrk://open opens the series info page"
"$ADB" shell input keyevent KEYCODE_DPAD_CENTER
wait_focus '^player:' 40
sleep 12
for _ in $(seq 1 15); do "$ADB" shell input keyevent KEYCODE_MEDIA_FAST_FORWARD; sleep 0.4; done
sleep 7
shot skip-intro-pill
EVENTS=$("$ADB" logcat -d -s VyrkEvent:I 2>/dev/null || true)
# IntroDB is an external service: report, but don't fail the build on its availability.
if printf '%s\n' "$EVENTS" | grep -q 'skip_offered INTRO'; then log "ok: Skip intro offered from IntroDB timings"
else log "warn: Skip intro not seen ($(printf '%s\n' "$EVENTS" | grep -o 'introdb_segments [0-9]*' | tail -n 1 || echo 'no IntroDB answer'))"; fi
key KEYCODE_DPAD_DOWN 1.5 "show controls"
key KEYCODE_DPAD_UP 1.2 "to timeline"
expect '^player:timeline$' "timeline takes focus above the controls"
key KEYCODE_DPAD_RIGHT 0.4 "scrub"; key KEYCODE_DPAD_RIGHT 0.4
shot timeline-scrub
key KEYCODE_DPAD_UP 1.0 "chapter mode"
shot timeline-chapters
key KEYCODE_DPAD_CENTER 2 "seek"
expect '^player:timeline$' "focus stays on the timeline after seeking"
back_from_player

# ---------------------------------------------------------------- results
"$ADB" logcat -d > "$OUT/logcat.txt" || true
PKGS=$(adbs cmd package list packages -U "$PKG" || true)
APP_UID=$(printf '%s\n' "$PKGS" | grep -oE 'uid:[0-9]+' | head -n 1 | cut -d: -f2 || true)
"$ADB" logcat -d --uid="$APP_UID" > "$OUT/logcat-app.txt" 2>/dev/null || grep -i vyrk "$OUT/logcat.txt" > "$OUT/logcat-app.txt" || true
if grep -qiE 'authKey|password=|token=|10\.0\.2\.2:7000/[^ ]*manifest' "$OUT/logcat-app.txt"; then fail "secret-like text or an unredacted add-on URL in VYRK's logcat"; fi
if grep -q 'FATAL EXCEPTION' "$OUT/logcat.txt"; then fail "app crashed (see logcat.txt)"; fi
log "trace: $(wc -l < "$TRACE") keys"
if (( FAILURES > 0 )); then log "$FAILURES check(s) failed"; exit 1; fi
log "journey passed"
