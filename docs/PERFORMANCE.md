# Performance: category switching

How tab switching was measured and what changed. Re-run it on any device with adb, including your
own TV (`adb connect <tv-ip>`, then `KEEP=1 APK=<apk> bash ci/perf_tabs.sh`), and summarise with
`python3 tools/perf_summary.py ci/out/perf/perf.txt`.

## What is measured

`ci/perf_tabs.sh` presses remote keys through Home → Movies → Series → Anime → Home,
Movies → Home → Movies and Series → Anime → Series, three times at a 1.5 s pace and three times at
0.45 s, then moves along a row of series posters. The app logs `VyrkPerf` lines:

- `tab <name> highlight+N ms`: key press to the frame after the tab highlight was drawn.
- `page <name> drawn+N ms`: key press to the frame after the new page was drawn.
- `frame …`: frame timing from `FrameMetrics` (only with `adb shell setprop log.tag.VyrkPerf DEBUG`).
  Compose measures and lays out inside the draw phase, so lazy list item composition shows as `draw`.
- `decode <resource> N ms … on <thread>`: add-on JSON decoding and the thread it ran on.

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

## Limits

- Measured on the emulator, not on TV hardware. The emulator runs on a desktop processor, so the
  absolute numbers are lower than on a TV; the reduction in UI-thread work is what carries over.
- The first visit to a tab still builds that page in one frame (about 14 ms of UI-thread work on
  the emulator); the highlight no longer waits for it.
- Remaining janky frames on the emulator are mostly GPU-bound (full-screen backdrop crossfades on
  the emulator's software renderer), not UI thread.