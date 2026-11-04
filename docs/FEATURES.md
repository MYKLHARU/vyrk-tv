# VYRK feature matrix

One row per requirement. **Status**: Done (implemented and verified), Built (implemented, not
verified end to end), Partial (some of it is missing), Not done.
**Evidence**: `core` = unit test in `core/src/test`, `E2E` = `ci/e2e.sh` on the Android TV
emulator, `showcase` = `ci/showcase.sh` captures, `probe` = checked against the live service.

Last verified locally on 2026-11-04: Android TV emulator API 36 (x86_64, 1920×1080, density 320),
minified release APK. The GitHub Actions jobs have **not run yet** (the repository hasn't been
pushed), so nothing below is verified by CI.

## Identity and build

| Requirement | Where | Status | Evidence |
|---|---|---|---|
| VYRK name, angular-V mark, wordmark, TV banner as original vectors | `res/drawable`, `ui/components/Brand.kt` | Done | showcase |
| Obsidian #0B141D / frost #A9E1F3 palette, Plus Jakarta Sans bundled | `ui/theme/Theme.kt`, `res/font` | Done | showcase |
| Package `com.vyrk.tv`, GPL-3.0, minSdk 23, per-ABI + universal APKs | `app/build.gradle.kts`, `LICENSE` | Done | local release build |
| Two modules: pure-Kotlin `core`, Android `app` | `settings.gradle.kts` | Done | build |
| Kotlin 2.4.21, AGP 9.5.0, compileSdk 37, targetSdk 36, Compose 1.12.1, tv-material 1.1.0, Media3 1.9.0 + Jellyfin FFmpeg 1.9.0+1, OkHttp 5, Coil 3 | `gradle/libs.versions.toml` | Done | build |
| Branding properties `vyrk.*` overridable with `-P` | `gradle.properties` | Built | — |
| Store build without REQUEST_INSTALL_PACKAGES and updater UI | `src/store/AndroidManifest.xml`, `Updater.kt` | Built | — |

## Accounts, profiles, add-ons

| Requirement | Where | Status | Evidence |
|---|---|---|---|
| Stremio pairing: `link.stremio.com/api/v2/create` and `read` (101 = pending), QR + link + code | `core/account`, `PairingScreen` | Built | probe (create, read 101), core |
| Account API getUser, addonCollectionGet/Set, datastoreMeta/Get/Put, logout | `core/account/StremioAccount.kt` | Built | probe (error envelope, code 1), core (request bodies) |
| Expired session (code 1) keeps data, offers Reconnect | `ProfileSession`, Settings › About | Built | core |
| Use without an account: Cinemeta + OpenSubtitles | `ProfileSession.installDefaults` | Done | E2E |
| Up to five profiles, colour avatar, default tab, PIN (PBKDF2) with escalating lockout | `AppGraph`, `core/profile/Pin.kt` | Built | core (PIN, lockout), showcase (profiles) |
| "Who's watching?", hold OK to edit | `ProfilePicker`, `ProfilesScreen` | Built | showcase |
| Share stream add-ons, receivers never see URLs | `AppGraph.refreshShared`, Settings › Add-ons | Built | — |
| Add-on protocol: manifest, catalog/meta/stream/subtitles, IDs percent-encoded as stremio-core does | `core/addon` | Done | core, E2E |
| Install from typed URL, `stremio://`, `vyrk://install?url=` with confirmation for external links | `AppRoot`, `InstallConfirm` | Done | E2E (deep link), core (normalisation) |
| Add-ons tab: version, description, tags, redacted URL, reorder, configure QR, refresh, remove, save to account | `AddonsScreen` | Built | showcase |
| Streaming-server add-ons skipped with an explanation | `CatalogRules.needsStreamingServer` | Built | — |
| AddonClient concurrency limit 8, per-provider timeouts | `core/addon/AddonClient.kt` | Done | core |

## Browsing

| Requirement | Where | Status | Evidence |
|---|---|---|---|
| Fixed 68dp bar, tabs, search/avatar/settings, Live only with TV catalogues | `MainScreen` | Done | E2E |
| Tab focus: radial light, underline, never a pill; one permanent FocusRequester per tab | `MainScreen.NavTab` | Done | E2E, showcase |
| Up into the bar lands on the active tab; Back on a tab → Home; Back on Home → tab strip | `MainScreen` | Done | E2E |
| Hero: backdrop, large logo (up to 480×140), facts, IMDb, genres, synopsis, Starring, Director, Watch now/Resume as the only button, position dots; Left/Right on Watch now change title (no Trailer or arrow buttons, unlike the brief; trailers stay on posters and in the item menu) | `browse/Hero.kt` | Done | emulator check (Left/Right change title), showcase |
| Top bar fades into the artwork on browse pages (no solid edge); plain bar on Library, Add-ons, Search, Settings, Profiles | `MainScreen` | Done | emulator capture |
| Hero titles and position stay locked across the info page, playback and tab switches | `BrowseModel.lockHero` | Done | emulator check (Home → Movies → Home keeps the title) |
| Hero rotates every 9 s, pauses when focused, under overlays/previews, scrolled away, in background, Reduce motion | `BrowsePage` | Built | — |
| Hold Select / Menu on Watch now opens info | `Hero` | Built | — |
| Title header (56%), large logo (up to 440×130), facts, two-line synopsis, hint line | `browse/Hero.kt` | Done | emulator capture, showcase |
| A held OK that opens a menu or info page never clicks inside it (no accidental Resume/Play) | `OverlayWindow` | Done | emulator check (long press on a poster opens the menu, player not started) |
| Automatic muted trailer after 850 ms, artwork until it plays | `preview/Trailers.kt` | Done | showcase (trailer playing on the emulator) |
| Rows: "<catalogue> - <type>", lazy load, placeholders, paging, retry, empty state, See all, hide row | `browse/Rows.kt`, `BrowseModel` | Built | E2E (load), showcase |
| Rows shared across tabs (one row per catalogue for the session), so a catalogue loaded on Home appears at once on Movies or Series | `RowStore`, `BrowseModel` | Done | emulator capture (Movies complete 0.25 s after the first switch) |
| Every catalogue tab is built when add-ons are known; the open tab fetches its first 6 rows at once, other tabs their first 3 | `MainScreen`, `BrowseModel.prefetch` | Done | emulator capture |
| Hero keeps its slot while its titles load and focus waits for it, so it shows on the first visit to a tab | `BrowsePage` | Done | E2E (Movies and Series open on the featured row) |
| Each tab keeps its scroll, row scroll and selected title; Down from the tab strip returns to that title | `BrowseModel`, `BrowsePage` | Done | E2E (Home → Movies → Home keeps the poster) |
| Visited catalogue pages stay composed while hidden; revisits switch in the same frame as the tab highlight, first visits one frame after it; add-on JSON decoded off the main thread | `MainScreen.keepAlive`, `BrowsePage(active)`, `AddonClient` | Done | `ci/perf_tabs.sh` on the emulator: revisit 30 ms median, UI-thread work 4 ms (was 37 ms, 9 ms); see docs/PERFORMANCE.md |
| Images requested ahead at drawn size: hero backdrops and logos, first 8 posters of the first 8 rows; posters use AsyncImage; up to 16 requests per host | `BrowsePage.prefetchImage`, `Rows.kt`, `OkHttpTransport` | Done | emulator capture |
| Focused row sits below the header; vertical moves land on the first title | `browse/Scrolling.kt`, `Rows.kt` | Done | E2E |
| Comfort-zone BringIntoViewSpec (12%/10%) and page spec with atomic flags | `browse/Scrolling.kt` | Done | E2E |
| Poster progress, watched tick, "New" badge with text | `Rows.kt` | Partial | E2E (progress); New badge not exercised with real data |
| Restore exact poster after info page, playback and restart; neighbour or hero fallback | `BrowsePage` | Done | E2E |
| Anime only from reliable signals | `core/catalog` | Done | core |
| Top 10 rows per service, ranked numbers, first 10, before other rows | `CatalogRules`, `Rows.kt` | Done | showcase, core |
| Top 10 suggestion card, install via confirmation, Not now hides it | `Top10Card` | Done | E2E, showcase |
| Previews Off / Manual / Automatic; preview card never takes focus | `PreviewCard` | Partial | E2E; card shows hints, not focusable actions; no Open in YouTube button |

## Info page and sources

| Requirement | Where | Status | Evidence |
|---|---|---|---|
| Overlay window ~92%×90%, 54/46 split, blurred art (Android 12+) under 55% tint, translucent panel, alpha-masked artwork | `overlays/InfoPage.kt` | Done | E2E, showcase |
| No close button; Back returns to the poster | `Overlays` | Done | E2E |
| Below 760dp wide: info and sources as tabs | `InfoPage` | Not done | — |
| Play always starts the best-ranked source | `Playback` | Done | E2E |
| Sources pane: counts, Refresh, resolution and language filters, cards, Recommended, details with ranking reasons, explained exclusions, per-case empty messages | `InfoPage.SourcesPane` | Done | E2E, showcase |
| Settings: show sources on info page, load sources when opening details | Settings › Streams | Built | — |
| Series season guide: season picker, Mark season watched, episodes with still/air date/synopsis/progress/watched, episode menu, unaired local date/time, blur unwatched | `InfoPage.SeasonGuide` | Built | showcase |

## Ranking and playback

| Requirement | Where | Status | Evidence |
|---|---|---|---|
| Stream parsing (resolution, release, codec, HDR/DV, audio, channels, languages, size, 3D, cached, binge group) | `core/stream/StreamInfo.kt` | Done | core |
| Hard limits, presets, display-aware HDR, format/HDR prefs, language order, CAM, determinism | `core/stream/StreamRanker.kt` | Done | core |
| Device fit: decoder max height per codec, physical display HDR/DV, bandwidth from Media3 samples ≥512 KB, ±90/±35 points | `DeviceCapabilities`, `core/playback` | Built | core (rules); emulator values only |
| Memory +1000, same release group +500 | `StreamRanker`, `TitleMemory` | Done | core |
| PlayerActivity on Media3 with FFmpeg audio fallback, HLS/DASH, OkHttp | `player/` | Done | E2E (playback) |
| Connecting screen: the title's logo (or name) fills left to right as a loading bar (metadata → sources → buffer), Other sources and Cancel; status text kept for TalkBack only | `PlayerActivity.LogoLoader`, `PlayerModel.loadProgress` | Done | E2E, emulator capture |
| Up to three candidates, 12 s first-frame timeout, retries, error classes, link refresh, resume position | `core/playback/Recovery.kt`, `PlayerModel` | Done | E2E (broken 4K → refresh → 720p), core |
| Hand-picked source failure asks (Retry / Other sources / Exit); Link expired | `PlayerModel` | Built | core (policy) |
| Stall prompt after three stalls in 60 s | `StallDetector` | Built | core |
| Controls: logo and clock on top, title with year · time left · Ends at, progress with buffer, elapsed/−remaining, centred round buttons around a large Play (labels on focus), key hints, dimmed when paused, 5 s auto-hide; key map. Layout follows Alder-style round controls rather than the brief's button row | `PlayerActivity.Controls` | Done | E2E, emulator capture |
| Audio & subtitles side panel (Audio / Subtitles / Sync & Style), languages as names with flags, unlabelled tracks numbered, codec and channels shown | `PlayerActivity` | Done | E2E, showcase |
| Audio prefs, original language, avoid commentary, remember choice, passthrough, TV-speaker, Clear Dialogue | `core/playback/Tracks.kt`, `AudioEffects.kt` | Built | core (selection) |
| Subtitle modes, forced-only, memory per title, delay ±10 s, size/colour/background, safe markup stripping | `core/subtitles`, `PlayerModel` | Partial | core; delay and style apply to add-on subtitles only, not embedded tracks |
| Up Next with autoplay, binge continuity, Still Watching after three, sleep timers | `PlayerModel` | Built | — |
| Frame-rate matching restoring the original mode | `PlayerActivity` | Built | — |
| Skip intro / recap from IntroDB community timings (Show button / Automatic / Off); Up Next at the credits start when known | `core/playback/Segments.kt`, `PlayerModel` | Done | core, E2E (GoT S1E1 via live IntroDB), live probe |
| Timeline chapter markers: IntroDB segments (intro/recap tinted) merged with Matroska chapters | `core/playback/Chapters.kt`, `PlayerActivity.Timeline` | Done | core, E2E |
| Matroska chapters read with HTTP range requests (EBML SeekHead → Chapters) | `core/playback/MkvChapters.kt` | Built | core (synthetic MKV); no MKV with chapters played on a device yet |
| Smart scrubbing: focusable timeline, accelerating Left/Right with chapter snap, Up for chapter mode, OK to seek | `Scrubber`, `PlayerActivity.Timeline` | Done | core, E2E |
| Mid-film audio failure switches track at the same second (same language, simpler codec first) | `TrackSelector.fallbackAudio`, `PlayerModel` | Built | core; no broken audio track was available to trigger it on a device |
| Cast and crew pages from TMDB: photos on the info page, person page with biography and filmography that opens titles through your add-ons | `core/tmdb`, `overlays/Person.kt` | Done | core (fixtures), emulator check against live TMDB (Game of Thrones → Alex Graves → credit) |
| MediaSession, audio focus, wake mode, live recovery without VOD progress | `PlayerActivity`, `LibraryRules` | Built | core (live rule) |

## Library

| Requirement | Where | Status | Evidence |
|---|---|---|---|
| Library item and watched bitfield ported from stremio-core | `core/library` | Done | core (stremio-core vectors) |
| Continue Watching rules, 70% watched, 90% credits, advance to next episode | `LibraryRules` | Done | core |
| Save locally on every update; push every 3 min, on pause and exit; pending vs confirmed | `ProfileSession`, `PendingChanges` | Built | core (pending rule) |
| Library tab: filters, sorts, Sync now, "Saved on this device only", Hidden (N) | `LibraryScreen` | Built | showcase |
| Continue Watching first row, newest first, Resume focused on open | `BrowsePage` | Done | E2E |
| Remove from Continue Watching (RewindLibraryItem) everywhere, toast 3.5 s, focus to neighbour or hero | `ItemMenu`, `LibraryRules.rewind` | Done | E2E |
| Item menu entries | `ItemMenu` | Done | E2E |
| New episodes: eligibility, lastVideosIds every 6 h, released after last watched and not in the future | `core/library/NewEpisodes.kt` | Built | core |

## Search, settings, design, remote

| Requirement | Where | Status | Evidence |
|---|---|---|---|
| Search: search catalogues, debounce, cancellation, merged ranking per type | `SearchScreen`, `core/search` | Done | E2E ("matrix"), showcase, core |
| Alphabetical keyboard takes focus; hardware keyboard types; voice; recent searches | `SearchScreen` | Built | E2E (keyboard) |
| Settings: eight groups, profile vs device settings, read-only device report | `SettingsScreen` | Done | E2E (all groups) |
| Fitted 960×540 canvas, interface size 0.72/0.84/1.0, dialogs re-provide density | `ui/theme/FittedDensity.kt` | Done | 1080p emulator |
| Glow + zoom focus, no white borders; overlays as separate windows | `ui/components` | Done | E2E, showcase |
| Focus never lost, per-key focus trace | `KeyBus`, `ci/e2e.sh` | Done | E2E (104 keys) |

## Security, updates, languages

| Requirement | Where | Status | Evidence |
|---|---|---|---|
| authKey and add-on URLs in a Keystore AES-GCM vault (`addons.enc`, `auth.enc`) | `data/Storage.kt` | Built | — |
| URL redaction for display, logs and diagnostics | `core/addon/AddonUrls.kt` (`Redact`) | Done | core, E2E (logcat scan) |
| Watch Next for the active unlocked profile only | `data/WatchNext.kt` | Built | — |
| Deep links respect PINs | `AppRoot` (links wait for an opened profile) | Built | — |
| GitHub Releases updater: latest, no pre-releases, resumable download, package/version/certificate checks | `data/Updater.kt`, `core/update` | Built | core (rules) |
| Release signing from environment; committed debug key for CI only | `app/build.gradle.kts`, `ci/debug-signing` | Built | local build |
| English + es, pt-BR, fr, de, hi, it generated by a checking script; in-app language override | `i18n/`, `tools/gen_strings.py` | Built | script check (290 keys × 7) |
| Content descriptions on posters and icon buttons | `Rows.kt`, `Controls.kt` | Built | E2E (uiautomator dump) |
| CI: build, e2e, showcase jobs | `.github/workflows/ci.yml` | Built | not run yet |

## Not verified, and why

- **GitHub Actions**: the repository has not been pushed, so build, e2e and showcase have not run on CI. README images come from local runs of `ci/showcase.sh` and `ci/e2e.sh` against the real release APK (API 36 emulator), not from a CI showcase run.
- **A real Stremio account**: approving pairing, two-way library sync, saving the add-on collection and session expiry need a real account. Only the endpoints and error envelopes were checked live.
- **Physical TVs and receivers**: decoder limits, HDR and Dolby Vision output, audio passthrough, Clear Dialogue on real audio, frame-rate switching and Fire OS were not available.
- **In-app update installs**: there is no tagged release yet.
- **Watch Next on the Google TV home screen**: not checked on a Google TV launcher.
- **Translations and 720p/4K layouts on screen**: strings were generated and validated, but only English at 1080p was looked at.
- **Live TV catalogues**: none of the installed add-ons provides one.
- **Matroska chapters on a device and mid-film audio fallback**: tested in core only; no test file with chapters or a deliberately broken audio track was played.
- **API 34 emulator**: CI targets API 34 (tv_1080p); local runs used API 36.
