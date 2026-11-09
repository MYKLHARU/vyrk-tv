#!/usr/bin/env python3
"""Compare pinned Media3 defaults with VYRK's production buffering on the same jittery stream.

Build an isolated debug app (never use the production app ID):
  ./gradlew :app:assembleDebug -Pvyrk.applicationId=com.vyrk.tv.buffercheck -Pvyrk.versionName=buffer-probe
Run with --apk, --adb, --aapt and --ffmpeg paths. Generates 30s of synthetic 120 Mb/s AVC,
serves it at 480 Mb/s with deliberate 4s/12s pauses, and records startup/rebuffers/heap.
The debug-only activity reuses PlaybackLoadControls. It does not sign in, install add-ons,
or touch the production app's profile. This is a controlled network test, not a Shield test.
"""
import argparse
import json
import re
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--apk", type=Path, required=True)
    p.add_argument("--adb", default="adb")
    p.add_argument("--aapt", default="aapt")
    p.add_argument("--serial", default="emulator-5554")
    p.add_argument("--ffmpeg", default="ffmpeg")
    p.add_argument("--out", type=Path, default=Path("ci/out/buffering"))
    p.add_argument("--port", type=int, default=7108)
    args = p.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    package = "com.vyrk.tv.buffercheck"
    metadata = subprocess.check_output([args.aapt, "dump", "badging", str(args.apk)], text=True, encoding="utf-8")
    if not re.search(r"package: name='com\.vyrk\.tv\.buffercheck'", metadata):
        raise SystemExit("Refusing to install: benchmark APK must use com.vyrk.tv.buffercheck")
    if not args.serial.startswith("emulator-"):
        raise SystemExit("This script only controls an emulator")

    def adb(*cmd, check=True):
        return subprocess.run([args.adb, "-s", args.serial, *cmd], text=True, encoding="utf-8", capture_output=True, check=check)

    media = args.out / "synthetic-120mbps.mkv"
    if not media.exists():
        subprocess.run([args.ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=25",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000", "-t", "30", "-c:v", "libx264", "-preset", "ultrafast",
            "-pix_fmt", "yuv420p", "-b:v", "120M", "-minrate", "120M", "-maxrate", "120M", "-bufsize", "240M",
            "-x264-params", "nal-hrd=cbr:filler=1", "-c:a", "aac", "-b:a", "128k", str(media)], check=True)
    size = media.stat().st_size
    if not 400_000_000 <= size <= 500_000_000:
        raise SystemExit(f"Unexpected fixture size {size}; expected ~450 MB of CBR video")
    lock = threading.Lock()
    pauses = {"baseline": set(), "tuned": set()}
    transfers = []
    rate = 480_000_000 / 8
    gates = [(int(size * 2 / 30), 4), (int(size * 24 / 30), 12)]

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *_):
            pass

        def do_GET(self):
            run = self.path.strip("/").split(".")[0]
            if run not in pauses:
                self.send_error(404)
                return
            start, end = 0, size - 1
            value = self.headers.get("Range")
            if value:
                match = re.fullmatch(r"bytes=(\d+)-(\d*)", value)
                if not match:
                    self.send_error(400)
                    return
                start = int(match[1]); end = min(int(match[2]) if match[2] else end, end)
                if start >= size or end < start:
                    self.send_response(416); self.send_header("Content-Range", f"bytes */{size}"); self.send_header("Content-Length", "0"); self.end_headers()
                    return
            self.send_response(206 if value else 200)
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Content-Type", "video/x-matroska")
            self.send_header("Content-Length", str(end - start + 1))
            self.send_header("Connection", "close")
            if value:
                self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
            self.end_headers()
            transfer = {"run": run, "start": start, "end": end, "sent": 0, "error": None}
            with lock:
                transfers.append(transfer)
            try:
                with media.open("rb") as f:
                    f.seek(start)
                    pos = start
                    while pos <= end:
                        for index, (gate, seconds) in enumerate(gates):
                            if gate < start or gate > end or pos < gate:
                                continue
                            with lock:
                                wait = index not in pauses[run]
                                pauses[run].add(index)
                            if wait:
                                time.sleep(seconds)
                        boundary = min([g for g, _ in gates if g > pos] + [end + 1])
                        # Larger pacing quanta also work on hosts with coarse sleep timers;
                        # tiny sleeps otherwise turn the intended fast link into a slow one.
                        chunk = f.read(min(4 * 1024 * 1024, end - pos + 1, boundary - pos))
                        if not chunk:
                            break
                        tick = time.monotonic()
                        self.wfile.write(chunk)
                        pos += len(chunk)
                        transfer["sent"] += len(chunk)
                        time.sleep(max(0, len(chunk) / rate - (time.monotonic() - tick)))
            except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError) as error:
                transfer["error"] = type(error).__name__

    server = ThreadingHTTPServer(("0.0.0.0", args.port), Handler)
    server.daemon_threads = True
    threading.Thread(target=server.serve_forever, daemon=True).start()
    results = []
    reverse = False
    try:
        adb("install", "-r", str(args.apk))
        # Bypass the emulator's slow user-mode host bridge for this throughput test.
        adb("reverse", f"tcp:{args.port}", f"tcp:{args.port}")
        reverse = True
        for run in ("baseline", "tuned"):
            adb("shell", "am", "force-stop", package)
            adb("shell", "run-as", package, "rm", "-f", f"files/buffer-probe-{run}.json", check=False)
            adb("shell", "am", "start", "-n", f"{package}/com.vyrk.tv.player.BufferProbeActivity",
                "--es", "url", f"http://127.0.0.1:{args.port}/{run}.mkv", "--es", "run", run,
                "--ez", "baseline", str(run == "baseline").lower())
            deadline = time.monotonic() + 140
            while time.monotonic() < deadline:
                response = adb("shell", "run-as", package, "cat", f"files/buffer-probe-{run}.json", check=False)
                if response.returncode == 0 and response.stdout.strip().startswith("{"):
                    data = json.loads(response.stdout)
                    data["serverPausesTriggered"] = sorted(pauses[run])
                    results.append(data)
                    print(json.dumps(data), flush=True)
                    break
                time.sleep(1)
            else:
                raise RuntimeError(f"{run}: no probe result; inspect BufferProbeActivity logcat")
    finally:
        server.shutdown(); server.server_close()
        adb("shell", "am", "force-stop", package, check=False)
        if reverse:
            adb("reverse", "--remove", f"tcp:{args.port}", check=False)
    evidence = {"fixtureBytes": size, "approxBitrateBps": size * 8 / 30, "bandwidthBps": rate * 8,
        "pausesSeconds": [4, 12], "results": results, "transfers": transfers}
    (args.out / "results.json").write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    if len(results) != 2 or any(r["outcome"] != "ended" or r["positionMs"] < 29_000 for r in results):
        raise SystemExit("Playback did not complete normally; results cannot establish a buffering improvement")
    if any(r["serverPausesTriggered"] != [0, 1] for r in results):
        raise SystemExit("Both network pauses must be exercised")
    if results[1]["stalls"] >= results[0]["stalls"]:
        raise SystemExit("No reduction in rebuffer events was measured")
    print("PASS: tuned buffering reduced rebuffer events on the controlled stream", flush=True)


if __name__ == "__main__":
    main()
