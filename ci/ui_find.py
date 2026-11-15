#!/usr/bin/env python3
"""Find an element in a `uiautomator dump` and print its centre as "x y".

The phone interface exposes its Compose test tags as resource IDs, so elements are found by tag.

Usage: ui_find.py DUMP.xml id TAG       exact resource-id
       ui_find.py DUMP.xml prefix TAG   first resource-id starting with TAG
       ui_find.py DUMP.xml text TEXT    exact text or content description
Prints nothing (and exits 1) when there is no match.
"""
import re
import sys
import xml.etree.ElementTree as ET


def centre(bounds: str):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds or "")
    if not m:
        return None
    x1, y1, x2, y2 = map(int, m.groups())
    if x2 <= x1 or y2 <= y1:
        return None
    return (x1 + x2) // 2, (y1 + y2) // 2


def main() -> int:
    path, mode, want = sys.argv[1], sys.argv[2], sys.argv[3]
    try:
        root = ET.parse(path).getroot()
    except (ET.ParseError, OSError):
        return 1
    for node in root.iter("node"):
        rid = node.get("resource-id", "")
        text = node.get("text", "")
        desc = node.get("content-desc", "")
        hit = (mode == "id" and rid == want) or (mode == "prefix" and rid.startswith(want)) or (mode == "text" and want in (text, desc))
        if hit:
            c = centre(node.get("bounds", ""))
            if c:
                print(f"{c[0]} {c[1]}")
                return 0
    return 1


if __name__ == "__main__":
    sys.exit(main())
