#!/usr/bin/env python3
"""Report of one device's readings in VictoriaMetrics: gaps, charger, screen, process starts.

Usage: vm_report.py VM_URL DEVICE_ID START END [MAX_GAP]
  VM_URL     base URL of VictoriaMetrics (not in the repo: pass it in)
  DEVICE_ID  the device.id resource attribute
  START, END Unix seconds
  MAX_GAP    seconds (default 120): gaps longer than this are listed

It reads raw points from /api/v1/export (no step), so a gap here is a real gap in
the stored data. The reading times come from device.screen.on (power collector).
A gap is put under "charger" or "battery" by the plug state of the reading before it.
"""
import json
import sys
import urllib.parse
import urllib.request
from datetime import datetime, timezone


def export(url, sel, start, end):
    q = urllib.parse.urlencode({"match[]": sel, "start": start, "end": end})
    with urllib.request.urlopen(f"{url}/api/v1/export?{q}") as r:
        return [json.loads(x) for x in r.read().decode().splitlines() if x.strip()]


def points(series):
    out = {}
    for s in series:
        for t, v in zip(s["timestamps"], s["values"]):
            out[t] = v
    return sorted(out.items())


def ts(ms):
    return datetime.fromtimestamp(ms / 1000, timezone.utc).strftime("%Y-%m-%d %H:%M:%S")


def main():
    url, dev, start, end = sys.argv[1].rstrip("/"), sys.argv[2], int(sys.argv[3]), int(sys.argv[4])
    max_gap = float(sys.argv[5]) if len(sys.argv) > 5 else 120
    d = f'device.id="{dev}"'
    screen = points(export(url, f'{{__name__="device.screen.on",{d}}}', start, end))
    on_battery = dict(points(export(url, f'{{__name__="device.battery.plug",battery.plug="none",{d}}}', start, end)))
    starts = sorted({v for _, v in points(export(url, f'{{__name__="device.agent.start_time",{d}}}', start, end))})
    wake = points(export(url, f'{{__name__="device.agent.wake",{d}}}', start, end))
    if not screen:
        print("no readings")
        return
    times = [t for t, _ in screen]
    print(f"window {ts(start * 1000)} -> {ts(end * 1000)} UTC")
    print(f"readings: {len(times)}, first {ts(times[0])}, last {ts(times[-1])} UTC")
    print(f"process starts (distinct device.agent.start_time): {len(starts)}: " +
          ", ".join(ts(s * 1000) for s in starts))
    n_batt = sum(1 for t in times if on_battery.get(t) == 1)
    print(f"readings on the charger: {len(times) - n_batt}, on battery: {n_batt}")
    print(f"screen on: {sum(v for _, v in screen) / len(screen):.2f} of the readings")
    if wake:
        print(f"timer wakes the CPU: {sum(v for _, v in wake) / len(wake):.2f} of the readings")
    gaps = [(a, b) for a, b in zip(times, times[1:]) if (b - a) / 1000 > max_gap]
    all_gaps = [(b - a) / 1000 for a, b in zip(times, times[1:])]
    print(f"largest gap: {max(all_gaps or [0]):.0f} s")
    for where in ("charger", "battery"):
        sel = [(a, b) for a, b in gaps if (on_battery.get(a) == 1) == (where == "battery")]
        print(f"gaps over {max_gap:.0f} s on the {where}: {len(sel)}")
        for a, b in sel:
            print(f"  {(b - a) / 1000:.0f} s: {ts(a)} -> {ts(b)} UTC")
    # Readings per hour, from the start of the window.
    hours = {}
    for t in times:
        h = (t // 1000 - start) // 3600
        hours[h] = hours.get(h, 0) + 1
    print("readings per hour: " + " ".join(str(hours.get(h, 0)) for h in range((end - start + 3599) // 3600)))


if __name__ == "__main__":
    main()
