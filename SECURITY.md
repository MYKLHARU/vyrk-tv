# Security policy

## Reporting a vulnerability

Please report security problems privately through GitHub's **Report a vulnerability** button on the repository's Security tab (private vulnerability reporting). Don't open a public issue for security reports.

Include what you found, how to reproduce it and which version you used. You'll get an acknowledgement as soon as the maintainer sees it.

## What VYRK protects

- Your Stremio authKey and every add-on transport URL are stored encrypted with a non-exportable Android Keystore AES-GCM key.
- Add-on URLs are shown and logged only as `scheme://host/…`. Copied diagnostics are redacted the same way.
- VYRK talks only to your add-ons, the Stremio account service, IntroDB (intro timings, sent only the IMDb ID, season and episode), TMDB (cast pages, artwork, the age certificates on cards and the title page's details, related titles and extras: sent the title's IMDb or TMDB ID and your device's region, only when the build includes a key), AniList (the Anime tab's rows: sent only genre names and the current season, no account), AniSkip (anime opening and ending timings: sent the MyAnimeList ID and episode; for Kitsu titles, Kitsu's API is asked for the MyAnimeList ID), fanart.tv (logos: sent the title's IMDb or TMDB ID, only when the build includes a key), Stremio's image service at images.metahub.space (artwork: the title's IMDb ID), YouTube (trailers and extras), GitHub (updates), and, only when you connect them, Trakt (what you watch, as you watch it) and Real-Debrid or TorBox (the torrents among a title's sources, to check whether they're ready and to play them). It has no ads, analytics or tracking.
- Updates are installed only if the package name matches, the version is newer and the signing certificate matches the installed app.

## Signing keys

The key in `ci/debug-signing/` is public and is used only for test builds. Official releases are signed with a key kept in GitHub Actions secrets and never committed.
