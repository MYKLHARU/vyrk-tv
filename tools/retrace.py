"""Minimal R8 retrace: rewrites 'at a.b.c(SourceFile:N)' frames using mapping.txt, expanding
inlined frames (innermost first). Usage: retrace.py mapping.txt input.txt"""
import re, sys
from collections import defaultdict

def load(path):
    classes = {}  # obf class -> (orig class, file, methods: obfName -> [(s, e, orig_meth_full, os, oe)])
    cur = None
    cls_re = re.compile(r"^(\S+) -> (\S+):$")
    meth_re = re.compile(r"^\s+(?:(\d+):(\d+):)?(\S+) ([^\s(]+)\(([^)]*)\)(?::(\d+)(?::(\d+))?)? -> (\S+)$")
    for line in open(path, encoding="utf-8", errors="ignore"):
        if line.startswith("#"): continue
        m = cls_re.match(line.rstrip("\n"))
        if m:
            orig, obf = m[1], m[2]
            cur = [orig, orig.split(".")[-1].split("$")[0] + ".kt", defaultdict(list)]
            classes[obf] = cur
            continue
        if cur is None: continue
        s = line.rstrip("\n")
        if s.strip().startswith("# {") and '"sourceFile"' in s:
            fm = re.search(r'"fileName":"([^"]+)"', s)
            if fm: cur[1] = fm[1]
            continue
        m = meth_re.match(s)
        if m:
            a, b, _ret, name, _args, os_, oe, obf = m.groups()
            if a is None: continue
            full = name if "." in name else cur[0] + "." + name
            cur[2][obf].append((int(a), int(b), full, int(os_) if os_ else None, int(oe) if oe else None))
    return classes

def retrace(classes, cls, meth, line):
    c = classes.get(cls)
    if not c: return None
    out = []
    for (a, b, full, os_, oe) in c[2].get(meth, []):
        if line is not None and a <= line <= b:
            if os_ is None: ol = None
            elif oe is not None and oe != os_: ol = os_ + (line - a)
            else: ol = os_
            out.append(f"{full}({ol if ol is not None else '?'})")
    if not out:
        names = {full for (_, _, full, _, _) in c[2].get(meth, [])}
        if names: out = [" | ".join(sorted(names))[:200] + "(?)"]
        else: out = [f"{c[0]}.{meth}(?)"]
    return out

classes = load(sys.argv[1])
frame_re = re.compile(r"at ([\w$.]+)\.([\w$<>]+)\((?:[\w.-]+|Unknown Source)?:?(\d+)?\)")
for line in open(sys.argv[2], encoding="utf-8", errors="ignore"):
    line = line.rstrip("\n")
    m = frame_re.search(line)
    if not m:
        print(line); continue
    r = retrace(classes, m[1], m[2], int(m[3]) if m[3] else None)
    if r is None:
        print(line); continue
    head = line[:m.start()]
    print(head + "at " + r[0] + ("   [inlined into: " + " <- ".join(r[1:]) + "]" if len(r) > 1 else ""))
