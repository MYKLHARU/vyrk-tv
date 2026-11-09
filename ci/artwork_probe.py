#!/usr/bin/env python3
"""Exercise the production hero artwork composables with loopback colour fixtures on an emulator.

Uses the same isolated debug APK as buffer_probe.py. Requires Pillow for screenshot analysis.
Checks metadata arriving after a picture, movie/series IDs colliding, and late/failed images
after rapid title changes. No production profile or real artwork is used.
"""
import argparse
import io
import json
import re
import struct
import subprocess
import threading
import time
import zlib
import xml.etree.ElementTree as ET
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from PIL import Image


def png(rgb):
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xffffffff)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 6 if len(rgb) == 4 else 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(b"\0" + bytes(rgb))) + chunk(b"IEND", b"")


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--apk", type=Path, required=True)
    p.add_argument("--adb", default="adb")
    p.add_argument("--aapt", default="aapt")
    p.add_argument("--serial", default="emulator-5554")
    p.add_argument("--out", type=Path, default=Path("ci/out/artwork"))
    p.add_argument("--port", type=int, default=7110)
    args = p.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    package = "com.vyrk.tv.buffercheck"
    metadata = subprocess.check_output([args.aapt, "dump", "badging", str(args.apk)], text=True, encoding="utf-8")
    if not re.search(r"package: name='com\.vyrk\.tv\.buffercheck'", metadata) or not args.serial.startswith("emulator-"):
        raise SystemExit("Only the isolated benchmark APK and an emulator are supported")

    def adb(*cmd, check=True):
        return subprocess.run([args.adb, "-s", args.serial, *cmd], text=True, encoding="utf-8", capture_output=True, check=check)

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_GET(self):
            name = self.path.split("?")[0]
            if name == "/slow-red.png":
                time.sleep(4)
            if name == "/long-red.png":
                time.sleep(30)
            if name not in ("/red.png", "/blue.png", "/slow-red.png", "/long-red.png", "/empty.png"):
                self.send_error(404)
                return
            body = png((0, 0, 0, 0) if name == "/empty.png" else (0, 0, 255) if name == "/blue.png" else (255, 0, 0))
            self.send_response(200)
            self.send_header("Content-Type", "image/png")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            try:
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                pass

    server = ThreadingHTTPServer(("0.0.0.0", args.port), Handler)
    server.daemon_threads = True
    threading.Thread(target=server.serve_forever, daemon=True).start()
    results = []
    try:
        adb("install", "-r", str(args.apk))
        cases = (("metadata", "red", 5), ("type", "blue", 5), ("missing", "ambient", 6),
                 ("logo-fallback", "blue", 3), ("logo-empty", "blue", 3), ("logo-update", "blue", 5),
                 ("logo-missing", "text", 7), ("logo-slow", "blank", 4), ("logo-late", "blue", 10),
                 ("logo-pending", "blue", 10), ("logo-season", "blue", 5))
        for case, expected, seconds in cases:
            adb("shell", "am", "force-stop", package)
            adb("shell", "am", "start", "-n", f"{package}/com.vyrk.tv.player.ArtworkProbeActivity",
                "--es", "base", f"http://10.0.2.2:{args.port}", "--es", "case", case)
            time.sleep(seconds)
            activity = adb("shell", "dumpsys", "activity", "activities").stdout
            if not re.search(r"(?:topResumedActivity|mResumedActivity)=.*ArtworkProbeActivity", activity):
                raise RuntimeError(f"{case}: probe activity is not resumed")
            capture = subprocess.check_output([args.adb, "-s", args.serial, "exec-out", "screencap", "-p"])
            (args.out / f"{case}.png").write_bytes(capture)
            image = Image.open(io.BytesIO(capture)).convert("RGB")
            if case.startswith("logo-"):
                blue = sum(1 for r, g, b in image.resize((192, 108)).get_flattened_data() if b > 100 and b > r * 2 and b > g * 2)
                adb("shell", "uiautomator", "dump", "/sdcard/window.xml")
                xml = adb("shell", "cat", "/sdcard/window.xml").stdout
                text = any(n.get("text") == "Anime fixture" for n in ET.fromstring(xml).iter("node"))
                actual = "blue" if blue > 100 else "text" if text else "blank"
                result = {"case": case, "expected": expected, "actual": actual, "bluePixels": blue, "titleText": text, "passed": actual == expected}
            else:
                r, g, b = image.getpixel((int(image.width * .9), int(image.height * .25)))
                actual = "red" if r > 70 and r > g * 2 and r > b * 2 else "blue" if b > 70 and b > r * 2 and b > g * 2 else "ambient"
                result = {"case": case, "expected": expected, "actual": actual, "pixel": [r, g, b], "passed": actual == expected}
            results.append(result)
            print(json.dumps(result), flush=True)
    finally:
        adb("shell", "am", "force-stop", package, check=False)
        server.shutdown(); server.server_close()
    (args.out / "results.json").write_text(json.dumps(results, indent=2) + "\n", encoding="utf-8")
    if not all(r["passed"] for r in results):
        raise SystemExit("Artwork identity/selection regression")
    print("PASS: all artwork identity and delayed-load checks passed", flush=True)


if __name__ == "__main__":
    main()
