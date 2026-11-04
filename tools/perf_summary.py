#!/usr/bin/env python3
"""Summarises VyrkPerf tab-switch timing from ci/perf_tabs.sh: highlight and page latency after
the key press, and UI-thread work in the switch frame, for first visits and revisits.
Usage: perf_summary.py perf.txt [another-run.txt ...]"""
import re, sys, statistics as st
def parse(path):
    ev = []; frames = []
    for line in open(path, encoding="utf-8", errors="ignore"):
        line = re.sub(r"^.*VyrkPerf\s*:\s*", "", line.strip())
        m = re.match(r"tab (\w+) key=\S+ applied\+(\d+)ms drawn\+(\d+)ms", line)
        if m: ev.append(dict(kind="before", tab=m[1], highlight=int(m[3]), page=int(m[3]))); continue
        m = re.match(r"tab (\w+) key=\S+ highlight\+(\d+)ms", line)
        if m: ev.append(dict(kind="after", tab=m[1], highlight=int(m[2]), page=None)); continue
        m = re.match(r"page (\w+) key=\S+ drawn\+(\d+)ms", line)
        if m and ev and ev[-1]["tab"] == m[1] and ev[-1]["page"] is None: ev[-1]["page"] = int(m[2]); continue
        m = re.match(r"(?:slow )?frame (\d+)ms input=(\d+) anim=(\d+) layout=(\d+) draw=(\d+) sync=(\d+) gpu=(\d+) key=\S+\+(\d+)ms", line)
        if m and ev: ev[-1].setdefault("frames", []).append(dict(total=int(m[1]), work=int(m[3]) + int(m[4]) + int(m[5]) + int(m[6]), since=int(m[8])))
    seen = set()
    for e in ev:
        e["first"] = e["tab"] not in seen; seen.add(e["tab"])
        fr = [f for f in e.get("frames", []) if f["since"] <= 150]
        e["work"] = max([f["work"] for f in fr], default=0)
    return ev
def summary(name, ev):
    for first in (True, False):
        g = [e for e in ev if e["first"] == first and e["page"] is not None]
        if not g: continue
        h = [e["highlight"] for e in g]; p = [e["page"] for e in g]; w = [e["work"] for e in g]
        print(f"{name:7s} {'first visit' if first else 'revisit':11s} n={len(g):3d}  highlight median {st.median(h):5.0f} max {max(h):4d} | page median {st.median(p):5.0f} max {max(p):4d} | main-thread work in switch frame median {st.median(w):4.0f} max {max(w):3d} ms")
for i, path in enumerate(sys.argv[1:]):
    summary(f"run {i + 1}", parse(path))