# Contributing to VYRK

Thanks for helping. VYRK is GPL-3.0; by contributing you agree your work is released under it.

## Build

- JDK 17 or newer and the Android SDK (platform 37).
- `./gradlew :core:test :logic-tests:test` runs the unit tests, including the app player/session regressions with synthetic platform boundaries.
- `./gradlew :app:assembleDebug` or `:app:assembleRelease` builds APKs (release builds are signed with the public debug key unless the `VYRK_*` signing variables are set).

Keep `core` free of Android dependencies. Rules that can be tested without a device (protocol, ranking, library, recovery) belong in `core` with unit tests.

## Strings

No user-visible text is hard-coded in Kotlin.

1. Use `R.string.your_key` in code.
2. Add `"your_key": "…"` to `i18n/en.json` and every other file in `i18n/`.
3. Run `python tools/gen_strings.py` to regenerate `res/values*/strings.xml`. It fails on a missing key or mismatched `%1$s`/`%1$d` placeholders and writes only strings the code uses.

Wording: short, plain and specific; sentence case for buttons ("Add Top 10 rows", "Not now").

## Run the journeys locally

You need an Android TV emulator (or device) visible to `adb`, Python 3, and bash (Git Bash works on Windows).

1. Media for the test add-on: put a 720p H.264 file at `ci/media/sample-720p.mp4` (the add-on generates one with `ffmpeg` if available) and, optionally, a Big Buck Bunny clip at `ci/media/bbb-clip.mp4`. Media files are never committed.
2. Start the add-on and keep its log:
   ```bash
   python ci/test_addon.py > ci/out/addon.log 2>&1 &
   ```
3. Run the journey:
   ```bash
   APK=app/build/outputs/apk/release/app-x86_64-release.apk ADDON_LOG=ci/out/addon.log bash ci/e2e.sh
   ```
4. Showcase captures and README images:
   ```bash
   APK=app/build/outputs/apk/release/app-x86_64-release.apk bash ci/showcase.sh
   ```
   ```bash
   python docs/tools/make_images.py ci/out/showcase .
   ```
   Look at every image before committing it.

The journeys use remote keys only and log the focused element after every key (`VyrkFocus` in logcat). If you add a focusable element, give it a `traceFocus("…")` label.

## Pull requests

- Keep changes focused; update `docs/FEATURES.md` and `CHANGELOG.md` when behaviour changes, and never mark something Done that hasn't been verified.
- Never commit secrets, tokens, real add-on URLs with credentials, or signing keys (other than the public debug key).
