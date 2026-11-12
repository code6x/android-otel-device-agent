#!/usr/bin/env python3
"""Summary of the app's samples.csv (pull it with: adb shell run-as dev.code6x.otelspike cat files/samples.csv).

Columns: wall_ms, elapsed_ms, gap_s, level, plugged, screen_on, interval_s, pid.
Prints the time span, the number of samples, the largest gaps (by wall clock, also across
app restarts), the time with the screen off, and each process start (pid change).
"""
import sys
from datetime import datetime, timezone

def ts(ms):
    return datetime.fromtimestamp(ms / 1000, timezone.utc).strftime("%Y-%m-%d %H:%M:%S UTC")

rows = []
for line in open(sys.argv[1]):
    p = line.strip().split(",")
    if len(p) != 8:
        continue
    rows.append((int(p[0]), int(p[1]), int(p[2]), float(p[3]), p[4], int(p[5]), int(p[6]), int(p[7])))
if len(sys.argv) > 2:  # optional start time, in seconds since 1970
    rows = [r for r in rows if r[0] >= int(sys.argv[2]) * 1000]
if len(rows) < 2:
    sys.exit("not enough samples")

gaps = [((b[0] - a[0]) / 1000, a, b) for a, b in zip(rows, rows[1:])]
span_h = (rows[-1][0] - rows[0][0]) / 3_600_000
off_s = sum(g for g, a, b in gaps if a[5] == 0)
print(f"first sample : {ts(rows[0][0])}")
print(f"last sample  : {ts(rows[-1][0])}")
print(f"span         : {span_h:.2f} h, {len(rows)} samples")
print(f"screen off   : {off_s / 3600:.2f} h of {span_h:.2f} h (time after a sample with the screen off)")
print(f"gaps > 120 s : {sum(1 for g, _, _ in gaps if g > 120)}")
print("largest gaps :")
for g, a, b in sorted(gaps, key=lambda x: -x[0])[:5]:
    print(f"  {g:7.1f} s  {ts(a[0])} -> {ts(b[0])}  screen_off={a[5] == 0} interval={a[6]} s")
pids = []
for r in rows:
    if not pids or pids[-1][1] != r[7]:
        pids.append((r[0], r[7]))
print(f"process starts: {len(pids)}")
for t, pid in pids:
    print(f"  {ts(t)}  pid {pid}")
