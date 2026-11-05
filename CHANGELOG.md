# Changelog

All user-visible changes. Versions follow `vX.Y.Z` tags.

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
