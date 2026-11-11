# Changelog

All user-visible changes. Versions follow `vX.Y.Z` tags.

## Unreleased

- Anime is back as its own tab in the top menu, and the Anime switch is gone. Home, Movies and Series always show everything, and Discover lists every catalogue. Anime can be hidden or made the default tab in Settings.

## v0.1.24

- Removed the genre bar under the top menu in Anime mode. Anime pages show every anime title; Discover still filters by genre.

## v0.1.23

- Moved Anime mode into a compact top-menu switch and removed the redundant standalone Anime tab. Regular browsing uses the full page height again.
- Added Discover: a poster grid with Movies/Series, installed catalogue and genre selectors, filtered pagination, retry and empty results. Discover follows Anime mode.
- Removed Live from Settings' Hidden tabs controls and added Discover to the default/hidden tab choices.
- Fixed remote arrows and OK in playback recovery and Still watching prompts so they operate the prompt instead of seeking beneath it.
- Add-ons that use plain http on a public server now show a warning on the install screen and in Settings › Add-ons: anyone on the network can read their address, including a sign-in inside it. Add-ons on your home network are not flagged.
- Release builds no longer write a log line for every remote key press and playback event. The test journeys switch these traces on.

## v0.1.22

- Added a per-profile Switch to Anime / Switch to Regular control. Anime mode limits Home, Movies and Series to anime catalogues and separates films from series using each title's type.
- Added all sixteen anime genre filters, including provider spelling aliases and metadata-tag fallback. Mode and genre are remembered per profile; anime Continue Watching and See all follow the filters.
- Filtered pagination advances by the provider's raw page size and offers Load more on empty filtered pages. Added normal-app remote navigation, empty genre and restart coverage.

## v0.1.21

- Fixed anime season logos from AIO Metadata, including Dan Da Dan Season 3's MAL entry. Prequel relations accept AIO's category format and validate canonical parent IDs using explicit MAL/Kitsu mappings.
- Text fallback waits for metadata and artwork lookups, and no longer permanently hides a valid logo arriving later. Deep-linked titles can use their loaded name for relation validation.
- Added the actual AIO response shape and delayed metadata to the normal app journey, alongside provider-alias regression tests.

## v0.1.20

- Anime seasons without a logo or artwork ID can inherit a series logo through an explicit prequel link when the series names match. This fixes Dandadan Season 3 while keeping that season's episodes, playback IDs and other artwork unchanged. Unrelated prequels and cycles are rejected.
- Logo loading waits for the season fallback lookup before settling on text, with a bounded timeout.

## v0.1.19

- Title text no longer flashes before a logo loads. The logo slot stays reserved during loading; when no logo is available, text appears after a short metadata grace period and stays stable for that visit, including if artwork arrives late.
- Added direct Fanart.tv logo fallbacks using verified IMDb/TMDB mappings, with English-first HD selection, Japanese alternatives, caching and retry backoff. TMDB artwork lookup also includes Japanese images. Existing successful artwork stays selected.

## v0.1.18

- High-bitrate on-demand playback uses a larger, memory-bounded sample buffer and a longer refill cushion. Playback has its own network connections, preserves provider-required headers, and gives large remux files longer to produce their first frame. Live TV keeps short startup and refill targets.
- Artwork keeps the catalogue image you selected while full details arrive. Changing titles clears the previous picture, including when the new image fails, and movie/series artwork with the same ID no longer shares state.
- Anime artwork can use IMDb mappings supplied by the metadata provider, including Anime Catalogs' Kitsu metadata, without changing episode or stream IDs. Backup artwork is prepared even when the supplied logo URL is broken. Empty logos try the next source, and slow or failed logos keep a readable title on screen.
- Added tests against the actual Media3 buffering rules, playback networking and artwork selection, plus isolated emulator checks for high-bitrate playback and delayed or missing artwork.

## v0.1.17

- Seeking no longer counts skipped content as watched. Rewinding saves the earlier resume point, and moving between episodes no longer counts viewing time twice.
- Cancelling Up Next stays cancelled for the current episode, including at the end of the file. Short-stream protection and its Switch stream, Retry and manual Next episode choices remain available.
- Switching sources refreshes subtitles and track choices. Late subtitle, intro-timing and refreshed-link responses cannot replace the current episode or stream. Malformed subtitle timestamps are skipped safely.
- Audio & subtitles reliably focuses the selected row, including when it starts below the visible list.
- Offline add-on edits survive sync and restart, retry automatically, and show when account sync is pending. Empty account collections now sync correctly, signing out stays signed out, and rapid settings changes persist in order even when leaving a profile.
- Fixed recovery after choosing a lower quality, runtimes written in hours, stale metadata after changing providers, and live channels being treated as on-demand videos.
- Disabled audio effects preserve the original PCM samples. Original-language audio uses add-on or TMDB language metadata, player dialogs respect the app language, and hiding the selected tab returns to Home.
- Added player/session regression tests and separate emulator scenarios for suspiciously short streams and normal full-episode autoplay.

## v0.1.16

- Episode streams that are much shorter than their runtime metadata no longer trigger Up Next or automatic next-episode playback. Early or unverifiable ends offer Switch stream, Retry from the beginning, Next episode and Exit, while retaining the episode's resume point and avoiding a new watched mark. Suspect sources are labelled and placed below alternatives for the current episode.
- Episode-specific runtimes take priority over a show's average, with tolerance for different releases and genuinely short episodes. Incorrect credits timings far before the stream's end are ignored, and Up Next pauses while playback is paused or a panel is open.

## v0.1.15

- Fixed: a hard edge across Home and the other pages near the bottom of the screen (seen above "TMDB Trending – Movies"). The wallpaper stopped short of the bottom of the screen by the height of the top bar; it now reaches the bottom and blends all the way down.

## v0.1.14

- The wallpaper now blends into the app towards the bottom: the picture is strong at the top and through the featured title, then melts into VYRK's own background, where the rows sit.
- Changing title blends the wallpaper properly: the current picture stays until the next one has loaded, then they cross-fade (it used to start fading straight away, so on a slower connection the old picture faded out and the new one popped in). The pictures for the titles either side of the one you're on are fetched ahead.

## v0.1.13

- The featured title's artwork is now a full-screen wallpaper: it fills the whole screen behind the hero and the rows instead of fading out under the hero. Moving into the rows, the wallpaper changes to the title you're on, and rows you scroll past slide away under the title's details.

## v0.1.12

- VYRK updates itself: when you open it, it checks for a new version, downloads it, says "Updating VYRK to …" and installs it. VYRK closes for a moment while that happens; open it again when it's done. It never interrupts a film. On Android 12 and newer, once VYRK has installed one update itself, the next ones install without asking; on older Android (such as the NVIDIA SHIELD), Android asks you to confirm each update. Turn it off in Settings › About & updates › Update automatically.

## v0.1.11

- Fixed: when Up Next moved on to the next episode, it could start at the end of that episode. The episode that's finishing now stops as soon as the next one is chosen, and its position is no longer saved against the next one. Episodes already saved that way start from the beginning.

## v0.1.10

- Holding OK (or pressing Menu) on the featured hero's Watch now opens the title's menu, like on posters and Continue Watching cards: Remove from Continue Watching (when the title is in it), More info, Preview, library and watched. The menu is on glass, like the other panels.
- While a movie loads, the screen shows only the filling logo: Other sources is gone too. Back still leaves.

## v0.1.9

- Featured hero: Left and Right on Watch now move to the previous or next title straight away, and the arrow on that side lights up. You no longer move onto the arrows to use them.
- While a movie loads, the logo fills in without the bright bar moving across it, and there is no Cancel button (Back still leaves). Other sources still shows when there's a choice.

## v0.1.8

- First run opens straight on the Stremio sign-in QR code; "Use without an account" is a small button beside it.
- Profiles are gone: VYRK opens straight to Home with one profile, with no profile picker or PIN. Settings is a gear at the top right instead of the avatar menu. The add-on sharing switches (only used between profiles) are gone too.
- Featured hero: Watch now is the only button (hold OK or press Menu for the details), with arrows at the left and right edges. The hero is the same height on every page.
- Home features its own titles, taken from all its rows, instead of repeating the Movies (and Series) hero.
- Top 10 rows use the new outlined number artwork.

## v0.1.7

- Home: the featured artwork now reaches down behind the first row, with a soft teal haze and violet light. The title logo is smaller and the same size in the hero and in the header above the rows. The carousel arrows are quieter.
- Continue Watching cards are wider, closer to a cinema frame.
- Settings: every row's controls start in the same column, and options are separate pills that wrap when needed. Poster size and Accent colour sit side by side, like Featured hero and Hero rotation (one under the other at the Large interface size). The group's header stays in place while the rows scroll. Hidden rows and the Top 10 suggestion are proper rows, and language lists use tick boxes.
- Panels have a soft aurora light, and buttons are pills everywhere.
- An empty Library shows an icon with its message.

## v0.1.6

- New look: deep navy with soft teal and violet light, frosted glass panels, thin edges and a brighter frost accent.
- Top bar: tabs have icons, and the open tab is an accent pill. Search is a compact field. Settings moved into the menu under your avatar, with Switch profile.
- Featured hero: Add to library and More info buttons next to Watch now, and arrows beside the position dots. Left and Right on Watch now no longer change the title; use the arrows.
- Continue Watching cards are landscape: the title's backdrop and logo, a play button, a progress bar, the episode and the time left, and a tick on watched titles.
- Settings: a glass sidebar with an icon for each group, a header for the open group, and compact rows with a short description each. Short choices are segmented controls, switches are On/Off pills, hidden tabs are tick boxes and the accent colour is a row of swatches.
- Reduce motion also stops the card zoom and the artwork cross-fades.

## v0.1.5

- Top 10 numbers redrawn: each sits in a box as tall as the poster, cut off where the poster begins, and fades to clear towards it; the numbers get fainter down the ranks.
- Play no longer picks files the TV can't decode: 10-bit, HDR and Dolby Vision HEVC files are skipped on TVs without a 10-bit HEVC decoder, and Dolby Vision-only files on TVs without a Dolby Vision decoder, even with Source choice set to Add-on order.
- When a file fails to decode, VYRK tries a different kind of file next and skips that kind for the rest of the session. A source that shows nothing within 12 seconds is skipped instead of being retried twice.
- Settings › Picture & sound shows whether the TV decodes 10-bit HEVC and Dolby Vision.
- Settings: Down on a group's last setting stays there instead of opening the group below.

## v0.1.4

- Top 10 rows: the rank numbers are as tall as the posters, drawn as a thin outline that fades towards the bottom, with each poster over the number's edge.

## v0.1.3

- Fixed: moving along a row could leave a title showing the logo of the title you had just left (seen in v0.1.2).

## v0.1.2

- The title page is full screen: the title's artwork fills the screen behind its logo, facts, synopsis, cast with photos, and Play, Trailer, library and more buttons. Down or the more button shows episodes or sources with the details and full cast; Back returns.
- While you move along a row, the header no longer jumps as a title's details load, and a title's logo no longer changes to a different one moments after it appears.
- Runtimes read the same everywhere, such as "1h 35m".
- The no-sources message points to Settings › Add-ons.
- Cast photos, person pages and TMDB artwork load again: TMDB refused the key because of an invisible character in front of it.

## v0.1.1

- Add-ons moved from the top bar into Settings › Add-ons, with the profile sharing switches.
- Settings is translucent: its panels sit over the artwork you were last looking at, blurred.
- Settings: pressing Left from a setting goes back to its own group. It used to land on whichever group was level with the setting and open that one instead. Right enters a group at its first setting.

## v0.1.0

First public release: an Android TV client for Stremio add-ons, built with Kotlin and Compose for TV. Installing over a `ci-latest` test build needs an uninstall first, because test builds are signed with a public key. What has and hasn't been verified, and how, is in [docs/FEATURES.md](https://github.com/MYKLHARU/vyrk-tv/blob/main/docs/FEATURES.md).

- Sign in with Stremio by QR code and link, or use VYRK without an account (Cinemeta and OpenSubtitles).
- Home with a featured hero, Continue Watching, Top 10 rows per streaming service and catalogue rows.
- Title header with an optional muted trailer while a poster has focus.
- Top 10 suggestion card that adds the Streaming Catalogs add-on after a confirmation.
- Info page with credits, ranked sources with reasons, and a season guide for series.
- Play starts the best source for this TV; broken links are refreshed or skipped automatically.
- Player with controls, Audio & subtitles panel, subtitle sync for add-on subtitles, Up Next and sleep timers.
- Restyled player: round centred controls with a large Play button, clock, time left and a side panel for audio and subtitles.
- Home: the top bar fades into the artwork, title logos are much larger, the featured row has just Watch now with dots (Left/Right change title) and keeps its place when you come back.
- Category switching answers the remote at once: visited pages are kept instead of rebuilt, the tab highlight never waits for a page, and add-on data is decoded off the UI thread. Measured with ci/perf_tabs.sh (docs/PERFORMANCE.md).
- Cinematic home: the backdrop fills the right of the hero without the blue wash (a dark fade comes in only from the left and bottom), title logos are trimmed and scaled to fill a larger box, and Watch now glows. Continue Watching never shows a blank card: titles missing a name or poster in the library (Game of Thrones) are filled from their metadata, with Cinemeta's poster as a fallback, and progress shows as a slim blue bar. With a TMDB key, titles an add-on gives no logo or backdrop get TMDB's English logo and a textless backdrop.
- A slow add-on can no longer make other rows time out while they wait their turn.
- Fixed a crash while playing from servers that ignore byte ranges: reading a file's chapters could pull the whole video into memory.
- On TV hardware, opening a category no longer stalls for over a second: catalogue names were matched against streaming services by compiling thousands of patterns on the UI thread. On an NVIDIA SHIELD a tab switch went from about 1.2 s to 46 ms (first visit 250 ms). Search ranking and source parsing had the same pattern and were fixed too.
- Faster categories: rows are shared between tabs and fetched ahead, images load ahead at the size they are shown, the featured row appears on the first visit to a tab, and each tab keeps its place when you come back.
- Holding OK on a title opens its menu without starting playback.
- While a title loads, its logo fills like a loading bar instead of showing loading text.
- Remove from Continue Watching from the item menu, synced the way Stremio does it.
- Focus returns to the same poster after the info page, playback or a restart.
- Search with an on-screen keyboard, voice search and recent searches.
- Library with filters, sorts and Sync now.
- Add-ons tab: install from URL or link, reorder, configure by QR, refresh, remove, save to account.
- Up to five profiles with colours, PINs and per-profile settings.
- Settings for profile and appearance, streams, picture and sound, languages, subtitles, previews, add-ons and updates.
- English, Spanish, Brazilian Portuguese, French, German, Hindi and Italian, with an in-app language override.
- Watch Next on the launcher and in-app updates from GitHub Releases.
- Skip intro and Skip recap using IntroDB community timings, with Show button, Automatic and Off in Settings › Streams.
- Up next appears when the credits start, if IntroDB knows when that is.
- The player timeline shows chapters and marks intros and recaps; Left and Right scrub faster the longer you press, Up jumps between chapters, OK goes there.
- Chapters from Matroska files show on the timeline.
- If an audio track stops working mid-film, VYRK switches to the next best track at the same second and tells you.
- Cast and crew with photos on the info page, and a page for each person with their films and shows (needs a TMDB key in the build).
