# Security policy

## Reporting a vulnerability

Please report security problems privately through GitHub's **Report a vulnerability** button on the repository's Security tab (private vulnerability reporting). Don't open a public issue for security reports.

Include what you found, how to reproduce it and which version you used. You'll get an acknowledgement as soon as the maintainer sees it.

## What VYRK protects

- Your Stremio authKey and every add-on transport URL are stored encrypted with a non-exportable Android Keystore AES-GCM key.
- Add-on URLs are shown and logged only as `scheme://host/…`. Copied diagnostics are redacted the same way.
- VYRK talks only to your add-ons, the Stremio account service, IntroDB (intro timings, sent only the IMDb ID, season and episode), TMDB (cast pages, only when the build includes a key), YouTube (trailers) and GitHub (updates). It has no ads, analytics or tracking.
- Updates are installed only if the package name matches, the version is newer and the signing certificate matches the installed app.

## Signing keys

The key in `ci/debug-signing/` is public and is used only for test builds. Official releases are signed with a key kept in GitHub Actions secrets and never committed.
