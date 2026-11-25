#!/usr/bin/env python3
"""Generate the app's anime ID lists in app/src/main/assets:

- anilist-kitsu.txt: AniList ID -> Kitsu ID, for the Anime tab's AniList rows. AniList titles open
  through the user's add-ons by Kitsu ID (`kitsu:ID`, the ID Anime Kitsu, AIO Metadata and
  Torrentio read), so the app needs each AniList title's Kitsu ID.
- anime-art-ids.txt: Kitsu / MyAnimeList ID -> IMDb ID (else TMDB ID), so anime titles get logos
  and backdrops (TMDB, fanart.tv, Cinemeta's images) even when their add-on gives no IMDb ID.

Both come from Fribb/anime-lists (anime-list-mini.json, updated daily), which builds on
anime-offline-database (Open Data Commons Open Database License 1.0). The outputs are derivative
databases under the same licence; their headers say so.

Titles newer than the snapshot are looked up at run time through an add-on that reads AniList or
MyAnimeList IDs (see AniListRows.kt), so run this before a release, not on every build.

Usage: python3 tools/gen_anime_ids.py [path/to/anime-list-mini.json]
       (without a path it downloads the current file from GitHub)
"""
import datetime
import json
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "app" / "src" / "main" / "assets"
OUT = ASSETS / "anilist-kitsu.txt"
ART = ASSETS / "anime-art-ids.txt"
SOURCE = "https://raw.githubusercontent.com/Fribb/anime-lists/master/anime-list-mini.json"


def load(argv):
    if len(argv) > 1:
        return json.loads(Path(argv[1]).read_text(encoding="utf-8"))
    with urllib.request.urlopen(SOURCE, timeout=60) as r:
        return json.loads(r.read().decode("utf-8"))


def pairs(entries):
    out = {}
    for e in entries:
        a, k = e.get("anilist_id"), e.get("kitsu_id")
        if isinstance(a, int) and isinstance(k, int) and a > 0 and k > 0:
            # One Kitsu ID per AniList ID; the list never disagrees with itself, but keep the first.
            out.setdefault(a, k)
    return sorted(out.items())


def art(entries):
    """(kitsu, mal) -> the IMDb ID (the first, for a title IMDb lists twice), else "tv:N" / "movie:N" on TMDB."""
    out = {}
    for e in entries:
        k, m = e.get("kitsu_id"), e.get("mal_id")
        k = k if isinstance(k, int) and k > 0 else None
        m = m if isinstance(m, int) and m > 0 else None
        if k is None and m is None:
            continue
        imdb = e.get("imdb_id")
        imdb = imdb[0] if isinstance(imdb, list) and imdb else imdb if isinstance(imdb, str) else None
        ref = imdb if imdb and imdb.startswith("tt") else None
        tmdb = e.get("themoviedb_id")
        if ref is None and isinstance(tmdb, dict):
            kind = "tv" if "tv" in tmdb else "movie" if "movie" in tmdb else None
            if kind and isinstance(tmdb[kind], int):
                ref = f"{kind}:{tmdb[kind]}"
        if ref:
            out.setdefault((k, m), ref)
    return sorted(out.items(), key=lambda kv: (kv[0][0] or 0, kv[0][1] or 0))


def header(what, count, today, lines):
    return [
        f"# {what}, {count} titles. Generated {today} by tools/gen_anime_ids.py",
        "# from Fribb/anime-lists (https://github.com/Fribb/anime-lists), which builds on",
        "# anime-offline-database (https://github.com/manami-project/anime-offline-database).",
        "# This derivative database is made available under the Open Data Commons",
        "# Open Database License 1.0 (https://opendatacommons.org/licenses/odbl/1-0/).",
        f"# Lines: {lines}.",
    ]


def main(argv):
    entries = load(argv)
    rows = pairs(entries)
    refs = art(entries)
    if len(rows) < 10_000 or len(refs) < 5_000:
        sys.exit(f"only {len(rows)} AniList/Kitsu pairs and {len(refs)} artwork IDs: the source looks wrong, not writing")
    today = datetime.date.today().isoformat()
    ASSETS.mkdir(parents=True, exist_ok=True)
    OUT.write_text("\n".join(header("AniList ID -> Kitsu ID", len(rows), today, "<anilist> <kitsu>")) + "\n" +
                   "".join(f"{a} {k}\n" for a, k in rows), encoding="utf-8")
    ART.write_text("\n".join(header("Kitsu / MyAnimeList ID -> IMDb ID, else TMDB ID", len(refs), today,
                                     "<kitsu or -> <mal or -> <ttIMDb | tv:TMDB | movie:TMDB>")) + "\n" +
                   "".join(f"{k or '-'} {m or '-'} {ref}\n" for (k, m), ref in refs), encoding="utf-8")
    print(f"wrote {OUT.relative_to(ROOT)}: {len(rows)} titles; {ART.relative_to(ROOT)}: {len(refs)} titles")


if __name__ == "__main__":
    main(sys.argv)
