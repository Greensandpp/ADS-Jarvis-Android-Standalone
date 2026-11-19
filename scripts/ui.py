#!/usr/bin/env python3
"""Tiny uiautomator-dump helper for CI.
  ui.py center <dump.xml> <text-or-content-desc>   -> prints "x y" or exits 1
  ui.py has    <dump.xml> <substring>              -> exit 0 if any text/desc contains it
  ui.py texts  <dump.xml>                          -> prints all visible texts
"""
import re, sys, xml.etree.ElementTree as ET
cmd, path = sys.argv[1], sys.argv[2]
try:
    root = ET.parse(path).getroot()
except Exception:
    sys.exit(2)
nodes = list(root.iter("node"))
if cmd == "texts":
    for n in nodes:
        t = (n.get("text") or "") or (n.get("content-desc") or "") or (n.get("hint") or "")
        if t: print(t)
    sys.exit(0)
needle = sys.argv[3]
for n in nodes:
    t, d, h = n.get("text") or "", n.get("content-desc") or "", n.get("hint") or ""
    hit = (needle in (t, d, h)) if cmd == "center" else (needle in t or needle in d or needle in h)
    if hit:
        if cmd == "center":
            x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
            print((x1 + x2) // 2, (y1 + y2) // 2)
        sys.exit(0)
sys.exit(1)
