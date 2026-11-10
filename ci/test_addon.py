#!/usr/bin/env python3
"""Local Stremio test add-on for the emulator journeys. Serves on 0.0.0.0:7000
(the emulator reaches it at http://10.0.2.2:7000).

For any IMDb ID it returns, in this order of ranking intent:
  1. a deliberately broken top-ranked 4K link (404),
  2. a working local H.264 720p file (ci/media/sample-720p.mp4),
  3. a remote 480p sample,
  4. a torrent, which VYRK must exclude (no streaming server).
plus an English subtitle. A search-only catalogue returns Big Buck Bunny (tt1254207) for
"bunny", streamed from ci/media/bbb-clip.mp4 when present.

No secrets. Media files are generated or downloaded by the CI job, never committed.
"""
import json
import os
import subprocess
import sys
import time
import struct
import zlib
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote

HERE = Path(__file__).resolve().parent
MEDIA = HERE / "media"
BASE = os.environ.get("VYRK_TEST_ADDON_BASE", "http://10.0.2.2:7000")
REMOTE_480 = "https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/360/Big_Buck_Bunny_360_10s_1MB.mp4"
BBB = "tt1254207"

MANIFEST = {
    "id": "org.vyrk.testaddon",
    "version": "1.0.0",
    "name": "VYRK Test Add-on",
    "description": "Local add-on for automated tests: a broken 4K link, a working 720p file, a remote sample, a subtitle and a torrent.",
    "resources": ["stream", "subtitles", "catalog", {"name": "meta", "types": ["series"], "idPrefixes": ["vyrk-", "mal:62516", "kitsu:49425"]}],
    "types": ["movie", "series"],
    "idPrefixes": ["tt", "vyrk-"],
    "catalogs": [
        {"type": "movie", "id": "vyrk-test-search", "name": "Test search", "extra": [{"name": "search", "isRequired": True}]}
    ],
    "behaviorHints": {"configurable": False},
}

SRT = """1
00:00:01,000 --> 00:00:04,000
VYRK test subtitle: hello.

2
00:00:05,000 --> 00:00:09,000
<i>This line proves add-on subtitles load.</i>
"""


def ensure_media():
    MEDIA.mkdir(exist_ok=True)
    sample = MEDIA / "sample-720p.mp4"
    if not sample.exists():
        try:
            subprocess.run([
                "ffmpeg", "-y", "-loglevel", "error",
                "-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=25",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
                "-t", "600", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-preset", "veryfast",
                "-c:a", "aac", "-b:a", "96k", "-movflags", "+faststart", str(sample),
            ], check=True)
        except (OSError, subprocess.CalledProcessError) as e:
            print(f"warning: could not generate {sample.name}: {e}", file=sys.stderr)


def streams_for(vid: str):
    bbb = vid == BBB and (MEDIA / "bbb-clip.mp4").exists()
    local = f"{BASE}/media/bbb-clip.mp4" if bbb else f"{BASE}/media/sample-720p.mp4"
    if vid.startswith("vyrk-"):
        # Two local sources: suspiciously short first, a full-length alternate.
        return [
            {"name": "VYRK fixture 720p", "title": "Fixture.720p.H264.mp4", "url": f"{BASE}/media/sample-720p.mp4"},
            {"name": "VYRK fixture 480p", "title": "Fixture.480p.H264.mp4", "url": f"{BASE}/media/{'sample-full.mp4' if vid.startswith('vyrk-short') else 'sample-720p.mp4'}"},
        ]
    return [
        {
            "name": "[RD+] VYRK Test\n4K",
            "title": "Broken.Test.2160p.WEB-DL.DDP5.1-VYRK.mkv\nDeliberately broken (404)",
            "url": f"{BASE}/media/broken-2160p.mkv",
            "behaviorHints": {"bingeGroup": "vyrk-test-2160p", "filename": "Broken.Test.2160p.WEB-DL.DDP5.1-VYRK.mkv"},
        },
        {
            "name": "VYRK Test\n720p",
            "title": "Working.Test.720p.WEB-DL.AAC.H.264-VYRK.mp4\nLocal file",
            "url": local,
            "subtitles": [{"id": "vyrk-en", "url": f"{BASE}/subs/en.srt", "lang": "eng"}],
            "behaviorHints": {"bingeGroup": "vyrk-test-720p", "filename": "Working.Test.720p.WEB-DL.AAC.H.264-VYRK.mp4"},
        },
        {
            "name": "VYRK Test\n480p",
            "title": "Remote.Sample.480p.WEBRip.H.264.mp4\nRemote sample",
            "url": REMOTE_480,
            "behaviorHints": {"bingeGroup": "vyrk-test-480p"},
        },
        {
            "name": "VYRK Test\nTorrent 1080p",
            "title": "Torrent.Test.1080p.BluRay.x264.mkv",
            "infoHash": "0123456789abcdef0123456789abcdef01234567",
            "fileIdx": 0,
        },
    ]


class Handler(SimpleHTTPRequestHandler):
    def log_message(self, fmt, *args):
        sys.stderr.write("addon: " + (fmt % args) + "\n")

    def _json(self, obj, code=200):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = unquote(self.path.split("?")[0])
        if path == "/anime-mode/manifest.json":
            return self._json({"id": "org.vyrk.animefixture", "name": "Anime mode fixture", "version": "1.0.0",
                "types": ["movie", "series", "anime"], "resources": ["catalog"], "catalogs": [
                    {"type": "movie", "id": "anime/films", "name": "Anime fixture films"},
                    {"type": "series", "id": "anime/shows", "name": "Anime fixture shows",
                     "extra": [{"name": "genre", "options": ["Action", "Comedy", "Science Fiction"]}]},
                    {"type": "anime", "id": "anime/mixed", "name": "Anime fixture mixed"},
                    {"type": "series", "id": "regular", "name": "Regular fixture shows"}]})
        if path.startswith("/anime-mode/catalog/"):
            film = {"id": "mal:90001", "type": "movie", "name": "Anime Fixture Film", "genres": ["Action"], "poster": f"{BASE}/artwork/logo.png"}
            action = {"id": "mal:90002", "type": "series", "name": "Anime Fixture Action", "genres": ["Action"], "poster": f"{BASE}/artwork/logo.png"}
            comedy = {"id": "mal:90003", "type": "series", "name": "Anime Fixture Comedy", "genres": ["Comedy"], "poster": f"{BASE}/artwork/logo.png"}
            items = [film] if "/films" in path else [action, comedy]
            if "/mixed" in path: items = [film, action, comedy]
            if "/regular" in path: items = [{"id": "tt90004", "type": "series", "name": "Regular Fixture Show"}]
            if "genre=" in path:
                genre = path.split("genre=", 1)[1].removesuffix(".json").split("&")[0]
                items = [m for m in items if genre in m.get("genres", [])]
            return self._json({"metas": items})
        if path == "/manifest.json":
            return self._json(MANIFEST)
        if path == "/meta/series/mal:62516.json":
            # Actual AIO field shape: MAL child, lower-case relation, canonical IMDb parent.
            # Delay past the old four-second text latch; ordinary InfoPage loading must wait.
            time.sleep(6)
            return self._json({"meta": {"id": "mal:62516", "type": "series", "name": "DAN DA DAN Season 3",
                "_kitsuId": "50163", "_malId": "62516", "logo": None,
                "links": [{"name": "DAN DA DAN Season 2", "category": "prequel", "url": "stremio:///detail/series/kitsu:49425"}]}})
        if path == "/meta/series/kitsu:49425.json":
            return self._json({"meta": {"id": "tt30217403", "type": "series", "name": "DAN DA DAN",
                "_kitsuId": "49425", "_malId": "60543", "imdb_id": "tt30217403", "logo": f"{BASE}/artwork/logo.png"}})
        if path == "/artwork/logo.png":
            def chunk(kind, data):
                return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xffffffff)
            body = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(b"\0\xff\0\0")) + chunk(b"IEND", b"")
            self.send_response(200); self.send_header("Content-Type", "image/png"); self.send_header("Content-Length", str(len(body))); self.end_headers()
            self.wfile.write(body)
            return
        if path.startswith("/meta/series/vyrk-"):
            mid = path.rsplit("/", 1)[-1].removesuffix(".json")
            runtime = "45 min" if mid == "vyrk-short" else "10 min"
            return self._json({"meta": {"id": mid, "type": "series", "name": "VYRK Playback Fixture", "runtime": runtime,
                "videos": [{"id": f"{mid}:1:{n}", "title": f"Episode {n}", "season": 1, "episode": n,
                    "released": "2020-01-01T00:00:00.000Z", "runtime": runtime} for n in (1, 2)]}})
        if path.startswith("/stream/"):
            parts = path[len("/stream/"):].removesuffix(".json").split("/")
            vid = parts[1] if len(parts) > 1 else ""
            return self._json({"streams": streams_for(vid) if vid.startswith(("tt", "vyrk-")) else []})
        if path.startswith("/subtitles/"):
            return self._json({"subtitles": [{"id": "vyrk-en-addon", "url": f"{BASE}/subs/en.srt", "lang": "eng"}]})
        if path.startswith("/catalog/movie/vyrk-test-search/"):
            extra = path.rsplit("/", 1)[-1].removesuffix(".json")
            query = dict(p.split("=", 1) for p in extra.split("&") if "=" in p).get("search", "").lower()
            metas = []
            if "bunny" in query:
                metas.append({
                    "id": BBB, "type": "movie", "name": "Big Buck Bunny", "releaseInfo": "2008",
                    "poster": "https://images.metahub.space/poster/medium/tt1254207/img",
                    "background": "https://images.metahub.space/background/medium/tt1254207/img",
                    "logo": "https://images.metahub.space/logo/medium/tt1254207/img",
                    "description": "A giant rabbit takes on three bullying rodents. (c) Blender Foundation, CC BY 3.0.",
                })
            return self._json({"metas": metas})
        if path == "/subs/en.srt":
            body = SRT.encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/x-subrip; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if path == "/media/broken-2160p.mkv":
            return self._json({"error": "deliberately broken"}, 404)
        if path.startswith("/media/"):
            f = MEDIA / Path(path).name
            if f.exists():
                self.path = "/" + f.name
                return super().do_GET()
        return self._json({"error": "not found"}, 404)

    def translate_path(self, path):
        return str(MEDIA / Path(unquote(path.split("?")[0])).name)


if __name__ == "__main__":
    ensure_media()
    port = int(os.environ.get("PORT", "7000"))
    print(f"VYRK test add-on on :{port} (base {BASE})", flush=True)
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
