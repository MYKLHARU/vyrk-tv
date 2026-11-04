# Performance: category switching

How tab switching was measured and what changed. Re-run it on any device with adb, including your
own TV: `adb connect <tv-ip>`, install and sign in once, then
`ANDROID_SERIAL=<tv-ip>:5555 KEEP=1 bash ci/perf_tabs.sh` (keeps the app and its sign-in; without
`KEEP=1` it reinstalls `$APK` and continues as a guest). Summarise with
`python3 tools/perf_summary.py ci/out/perf/perf.txt`. Don't use the remote during a run: the
script checks VYRK is in front before each round, but stray presses change the sequence.

## What is measured

`ci/perf_tabs.sh` presses remote keys through Home → Movies → Series → Anime → Home,
Movies → Home → Movies and Series → Anime → Series, three times at a 1.5 s pace and three times at
0.45 s, then moves along a row of series posters. The app logs `VyrkPerf` lines:

- `tab <name> highlight+N ms`: key press to the frame after the tab highlight was drawn.
- `page <name> drawn+N ms`: key press to the frame after the new page was drawn.
- `frame …`: frame timing from `FrameMetrics` (only with `adb shell setprop log.tag.VyrkPerf DEBUG`).
  Compose measures and lays out inside the draw phase, so lazy list item composition shows as `draw`.
- `decode <resource> N ms … on <thread>`: add-on JSON decoding and the thread it ran on.
- `stall N ms …`: any UI-thread message of 150 ms or more, what dispatched it, and where it spent
  its time (`stall self` / `stall incl` frames from stacks sampled every 10 ms). Only with the
  DEBUG property set before the app starts. Release builds are obfuscated: decode the frames with
  `python3 tools/retrace.py app/build/outputs/mapping/release/mapping.txt <log>` using the mapping
  of the exact build that ran.

Timings include waiting for the next display frame (16.7 ms at 60 Hz) and the frame after, so
about 30 ms is the floor for this method.

## Root cause

Every tab change disposed the whole page and built the new one (`key(app.tab) { BrowsePage(…) }`
in `MainScreen`), and the tab highlight was drawn in that same frame. The frame had to compose the
hero and every visible row and poster, measure and lay them out, before the highlight could move.
On a TV processor, several times slower than the emulator's, that is the delay between pressing
the remote and seeing anything happen. Two things added to it: `BrowsePage` read the whole session
state, which changes on every poster focus (so pages recomposed on every focus move), and the poster
callbacks were a new object on every recomposition, so every visible row recomposed with the page.
Add-on JSON (including full metas for the hero and the header) was decoded on the main thread.

## Changes

- Visited catalogue pages stay composed while hidden: not measured, drawn or focusable
  (`MainScreen.keepAlive`). Coming back shows them as they were, without rebuilding.
- A kept page switches in the same frame as the highlight. A page not built yet follows one frame
  after the highlight, and is skipped if another key arrives first.
- Hidden pages pause hero rotation, backdrops, trailers and the preview card (`active`).
- `BrowsePage` reads only `hiddenContinueWatching` and `newEpisodes` from the session state; poster
  callbacks are one instance per row.
- `AddonClient` decodes on `Dispatchers.Default` whoever calls it.

## Results (Android TV emulator, API 34, x86_64, release build)

| | Before | After |
|---|---|---|
| Highlight after key, revisit (median / max) | 37 / 68 ms | 30 / 39 ms |
| Page after key, revisit (median / max) | 37 / 68 ms | 30 / 40 ms |
| UI-thread work in the switch frame, revisit (median / max) | 9 / 21 ms | 4 / 9 ms |
| Highlight after key, first visit (median) | 55 ms | 32 ms |
| Page after key, first visit (median) | 55 ms | 75 ms (one frame after the highlight, by design) |
| Frames with a slow UI thread during the run | 84 | 5 |
| Janky frames (gfxinfo) | 22.6 % | 20.9 % |
| Add-on JSON decodes on the main thread | 9 of 9 | 0 of 9 |

Revisits are 82 of the 86 switches in a run. Memory with all four catalogue pages kept: 174 MB PSS,
15 MB Java heap (emulator).

## On TV hardware: NVIDIA SHIELD Android TV (2019, Android 11)

The same script on a real TV, signed in to a real account with its own add-ons, found what the
emulator could not: with the changes above, revisits were fast (46 ms), but the first visit to
Movies, Series or Anime took about 1.4 s to show its page. The stall sampler put 93 % of a
1.24 s UI-thread message in `java.util.regex.Pattern.compile`, called from
`CatalogRules.streamingService` → `wordMatch` (via `CatalogRules.homeRows` ←
`BrowseModel.rebuild` ← the `LaunchedEffect` in `BrowsePage`). `wordMatch` built two new `Regex`
objects for every catalogue and every service alias (11 services, about 30 aliases), so a rebuild
compiled thousands of patterns. Android's ICU regex compiler is slow on a TV processor; on a
desktop JVM or the emulator's small test add-on it never showed. Before the changes above, every
tab switch rebuilt the page and paid this cost, which is the delay seen on the TV.

Fixes:

- `CatalogRules` compiles its pattern once and prepares the aliases once (same matching rules;
  the existing tests pass unchanged).
- `BrowseModel.rebuild` returns at once when the add-ons and hidden rows are the ones it was last
  built from, so a page showing for the first time does not rebuild what `MainScreen` built.
- The same per-call `Regex` was removed from `SearchRanking.normalise` (four compiles per search
  result) and `StreamInfo` (one per stream).

Results on the SHIELD, same account, same script, all runs release builds:

| | Before (eb5128e) | Kept pages (2c676e3) | Kept pages + regex fix |
|---|---|---|---|
| Highlight after key, revisit (median / max) | 1228 / 1292 ms | 46 / 66 ms | 46 / 69 ms |
| Page after key, revisit (median / max) | 1228 / 1292 ms | 46 / 66 ms | 46 / 69 ms |
| Highlight after key, first visit (median) | 1232 ms | 30 ms | 38 ms |
| Page after key, first visit (median / max) | 1232 / 1263 ms | 1366 / 1419 ms | 250 / 275 ms |
| Scripted switches that registered | 68 of 86 | 86 of 86 | 86 of 86 |
| UI-thread stalls of 150 ms or more | (not sampled) | 3 × 1.22–1.24 s | none over 156 ms |
| Frames: 90th / 95th / 99th percentile (gfxinfo) | 1150 / 1200 / 1250 ms | 30 / 36 / 89 ms | 32 / 40 / 113 ms |
| Janky frames (gfxinfo) | 39.7 % | 26.7 % | 29.1 % |

The before build had only the timing lines added (same `Perf.kt`, key hook and tab log as the
emulator "before" run). Its missing switches are presses that arrived while the UI thread was
blocked. The gfxinfo totals of the last two runs differ by run-to-run variation; they cover the
whole run, including the poster pass.

## Limits

- One TV model. Other TVs will differ in absolute numbers.
- The first visit to a tab still builds that page in one frame: about 100–140 ms of UI-thread work
  on the SHIELD (14 ms on the emulator), then about 150 ms of follow-up work before the next frame.
  The highlight does not wait for it; the page shows about 250 ms after the key.
- A revisit costs about 13 ms of UI-thread work on the SHIELD, which with frame alignment is 2–3
  display frames (46 ms median).
- Remaining janky frames on the emulator are mostly GPU-bound (full-screen backdrop crossfades on
  the emulator's software renderer); on the SHIELD about half of the slow frames are UI-bound
  (first-visit builds, poster moves) and a quarter GPU-bound.