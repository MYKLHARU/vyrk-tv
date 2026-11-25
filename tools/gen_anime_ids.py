#!/usr/bin/env python3
"""Generate app/src/main/assets/anilist-kitsu.txt: AniList ID -> Kitsu ID for the Anime tab's AniList rows.

AniList titles open through the user's add-ons by Kitsu ID (`kitsu:ID`, the ID Anime Kitsu, AIO
Metadata and Torrentio read), so the app needs each AniList title's Kitsu ID. They come from
Fribb/anime-lists (anime-list-mini.json, updated daily), which builds on anime-offline-database
(Open Data Commons Open Database License 1.0). The output is a derivative database under the same
licence; its header says so.

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
OUT = ROOT / "app" / "src" / "main" / "assets" / "anilist-kitsu.txt"
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


def main(argv):
    rows = pairs(load(argv))
    if len(rows) < 10_000:
        sys.exit(f"only {len(rows)} AniList/Kitsu pairs: the source looks wrong, not writing")
    today = datetime.date.today().isoformat()
    header = [
        f"# AniList ID -> Kitsu ID, {len(rows)} titles. Generated {today} by tools/gen_anime_ids.py",
        "# from Fribb/anime-lists (https://github.com/Fribb/anime-lists), which builds on",
        "# anime-offline-database (https://github.com/manami-project/anime-offline-database).",
        "# This derivative database is made available under the Open Data Commons",
        "# Open Database License 1.0 (https://opendatacommons.org/licenses/odbl/1-0/).",
        "# Lines: <anilist> <kitsu>.",
    ]
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text("\n".join(header) + "\n" + "".join(f"{a} {k}\n" for a, k in rows), encoding="utf-8")
    print(f"wrote {OUT.relative_to(ROOT)}: {len(rows)} titles")


if __name__ == "__main__":
    main(sys.argv)
