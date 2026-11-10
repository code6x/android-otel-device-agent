#!/usr/bin/env python3
"""Raw samples of one series in VictoriaMetrics, and the gaps between them.

Usage: vmgaps.py VM_URL SELECTOR START END
  VM_URL   base URL of VictoriaMetrics (not in the repo: pass it in)
  SELECTOR for example '{__name__="spike.ticks",device.id="spike-phone-1"}'
  START, END  seconds since 1970

It reads /api/v1/export (raw points, no step), so a gap here is a real gap in the
stored data, not a graph artefact.
"""
import json
import sys
import urllib.parse
import urllib.request
from datetime import datetime, timezone

url, sel, start, end = sys.argv[1].rstrip("/"), sys.argv[2], int(sys.argv[3]), int(sys.argv[4])
q = urllib.parse.urlencode({"match[]": sel, "start": start, "end": end})
with urllib.request.urlopen(f"{url}/api/v1/export?{q}") as r:
    lines = [json.loads(x) for x in r.read().decode().splitlines() if x.strip()]

def ts(ms):
    return datetime.fromtimestamp(ms / 1000, timezone.utc).strftime("%H:%M:%S")

for s in lines:
    t = sorted(set(s["timestamps"]))
    name = s["metric"].get("__name__")
    gaps = [(b - a) / 1000 for a, b in zip(t, t[1:])]
    print(f"{name}: {len(t)} points {ts(t[0])} -> {ts(t[-1])} UTC, largest gap {max(gaps or [0]):.0f} s")
    for a, b in zip(t, t[1:]):
        if (b - a) / 1000 > 120:
            print(f"  gap {(b - a) / 1000:.0f} s: {ts(a)} -> {ts(b)}")
