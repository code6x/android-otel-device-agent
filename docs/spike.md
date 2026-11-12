# Spike report (issue #1)

Status 2026-11-12: all four questions are answered; the decision for #2 is at
the end.

## Setup

| part | version |
|---|---|
| phone | OnePlus Nord CE 2 Lite (CPH2409), OxygenOS, Android 12 (API 31) |
| emulator (first runs) | Android 15 (API 35), x86_64 |
| opentelemetry-android | `io.opentelemetry.android:android-agent:1.8.1`, `core:1.8.1-alpha` |
| OpenTelemetry Java SDK | 1.66.0 (BOM) |
| OpAMP client | `io.opentelemetry.contrib:opentelemetry-opamp-client:1.62.1-alpha` |
| OpAMP server | opamp-go example server, local build |
| receiver | VictoriaMetrics v1.156.0 (`/opentelemetry/v1/metrics`) |

The test app is in `spike/`. It reads the battery every 60 s in a foreground
service and sends the same metrics through **two pipelines**, told apart by the
resource attribute `spike.buffer`:

- `library`: opentelemetry-android with its disk buffering.
- `own`: the SDK's periodic reader into `FileQueueExporter` (`spike/app/.../OwnQueue.kt`):
  each export is one file with the OTLP/HTTP protobuf body; a sender posts the
  files in order and deletes a file only after a 2xx answer.

Tools: `spike/tools/vmgaps.py` (raw points of one series from `/api/v1/export`,
and every gap > 120 s), `spike/tools/samples.py` (summary of the app's
`samples.csv`).

### Phone setup findings (also for the #2 setup checklist)

- With default settings OxygenOS froze the app about 2 min after start, even
  with a foreground service (`OplusHansManager: freeze uid … scene: LcdOn`).
  The Doze allow-list (`cmd deviceidle whitelist +dev.code6x.otelspike`) did
  **not** stop it. What worked: App info → Battery → **Allow background
  activity**.
- A VPN with per-app split tunneling sent the app's traffic outside the VPN,
  so the receiver could not be reached. The own queue kept every batch and sent
  it when the app was added to the VPN.

Settings changed on the phone for the test (undo after the spike):
- Doze allow-list: `cmd deviceidle whitelist -dev.code6x.otelspike`.
- App info → Battery → Allow background activity (set by hand).
- VPN: the app is included in the split tunnel (set by hand).
- "Keep screen on while charging" was turned on for the cut tests and off
  again at 09:50 UTC on 2026-11-11 (nothing to undo).

## Q1. Disk buffer for metrics

Test on the phone, 2026-11-11 (UTC), interval 60 s:

- **cut1** 08:55:56–09:30:56 (35 min): receiver paused (`docker pause`: TCP
  connects, then no answer, like a lost network).
- **cut2** 09:40:56–09:54:17: receiver stopped; the app was force-stopped at
  09:48:56 and started again at 09:49:17, while the receiver was still down.

Raw points of `spike.ticks{device.id="spike-phone-1"}` (`vmgaps.py`):

```
== cut1, window 08:45:00-09:40:00 UTC
-- spike.buffer=own
spike.ticks: 59 points 08:45:22 -> 09:39:59 UTC, largest gap 90 s
-- spike.buffer=library
spike.ticks: 55 points 08:45:22 -> 09:39:22 UTC, largest gap 60 s
== cut2, window 09:40:00-10:04:17 UTC
-- spike.buffer=own
spike.ticks: 24 points 09:40:59 -> 10:03:18 UTC, largest gap 77 s
-- spike.buffer=library
spike.ticks: 10 points 09:54:17 -> 10:03:17 UTC, largest gap 60 s
-- spike.buffer=library, window 09:30:00-10:04:17 UTC
spike.ticks: 20 points 09:30:22 -> 10:03:17 UTC, largest gap 895 s
  gap 895 s: 09:39:22 -> 09:54:17
```

(The 90 s and 77 s gaps in `own` are the interval change of Q3 and the 21 s of
the restart; no gap is over 120 s.)

**Answer.**
- Network cut of 35 min: **both** keep the metrics. No gap in either pipeline.
- Cut **with an app restart**: the **library loses everything** it had not sent
  yet: 15 min of points are missing (09:39:22 → 09:54:17). The **own queue
  keeps all of them**: no gap over 120 s.
- On the emulator (Android 15, 2026-11-10/11) a restart during a cut gave the
  same result: the library's first point after it was at the time the receiver
  came back, the own queue had no gap over 30 s. (The emulator's short
  library-only cut test had gaps of 276 s and 300 s and is not used here.)
- So the smallest own queue that works is a folder of files, one OTLP/HTTP
  request body per file, deleted after a 2xx. About 120 lines of Kotlin
  (`OwnQueue.kt`); no database needed.

## Q2. Staying alive (24 h, screen off)

The 24 h test on a **Xiaomi phone with HyperOS 2 (Android 15) moves to #2**: it
will be run on the kitchen display there. This spike ran it on a OnePlus Nord
CE 2 Lite on OxygenOS (Android 12), from 2026-11-11 08:43:21 to 2026-11-12
08:43:21 UTC, with the phone in normal daily use (on battery most of the day,
on the charger at night).

Raw points of `spike.ticks` in the receiver over the 24 h (`vmgaps.py`):

```
-- spike.buffer=own
spike.ticks: 1189 points 08:43:22 -> 08:43:17 UTC, largest gap 196 s   (145 gaps > 120 s, median of those 157 s)
-- spike.buffer=library
spike.ticks: 1154 points 08:43:21 -> 08:42:38 UTC, largest gap 895 s  (the 895 s is the cut2 restart of Q1)
```

The counter of the own pipeline was reset only once, at 09:49:17 (the planned
force-stop of Q1): the system never killed the service in 24 h.

Points per hour (own pipeline, 60 s interval = 60 per hour) next to the
screen (`device.screen.on` averaged over the hour). From the battery level, the
phone was on the charger from about 20:00 (the level stopped falling at 26 %
and rose to 100 %) to about 04:00 UTC. The hour ending 09:43 (cut2 and the
restart of Q1) is left out:

```
hour ending :43  10  11  12  13  14  15  16  17  18  19  20  21  22  23  00  01  02  03  04  05  06  07  08
points           59  44  28  22  25  23  30  57  40  61  55  56  61  61  61  61  61  61  58  58  60  49  34
screen on       .81 .34 .18 .00 .16 .00 .07 .02 .15 .56 .29 .16 .00 .00 .00 .00 .00 .00 .41 .19 .60 .02 .29
```

Over the 24 h the screen was off about 70 % of the time
(`avg_over_time(device.screen.on[24h])` = 0.31).

Phone state, read at the start of the test (2026-11-11 08:44 UTC):

```
dumpsys deviceidle whitelist | grep otelspike  ->  user,dev.code6x.otelspike,<uid>
am get-standby-bucket dev.code6x.otelspike    ->  5 (EXEMPTED)
appops: RUN_ANY_IN_BACKGROUND: allow; START_FOREGROUND: allow (running)
App info -> Battery -> Allow background activity: on (set by hand)
```

The Autostart state was not read (see below). The Doze allow-list entry was
added by adb and did not help on its own (setup findings above). The final pull of `samples.csv`, the
queue folder and the phone state at the end could not be done: the phone's
wireless debugging port had changed and no new port was given in time. The
receiver data above stands in for it: its last own point (08:43:17 UTC on
2026-11-12) came less than a minute before the end, so the own queue was
most likely sending on time, with no backlog.

**Answer.**
- **The foreground service stays alive for 24 h** on OxygenOS, with "Allow
  background activity" on (see the setup findings above). Without that setting
  it was frozen after 2 min.
- **But the 60 s tick is not kept** when the phone is on battery with the screen
  off: in the hours ending 13:43 to 16:43 only 22–30 ticks per hour came, 2–3
  min apart (145 gaps over 120 s in the 24 h, at most 196 s). Other hours with
  the screen mostly off came out better (40–57), so the CPU did not always
  sleep. On the charger, also with the screen off (the
  hours ending 22:43 to 03:43 above), every tick came on time. The tick is a `Handler.postDelayed`
  with no wake lock, whose clock (`uptimeMillis`) stops while the CPU sleeps;
  on the charger the CPU does not sleep. The data fits this, but it is not
  proven: the phone's `samples.csv` (wall clock and elapsed time per tick)
  could not be pulled at the end. So the "at most 2 min apart" goal is **not met with this
  code**.
- The service was not killed; the timer is late. So the fix is in #2: wake
  the CPU for each tick with an exact alarm
  (`AlarmManager.setExactAndAllowWhileIdle`), or hold a partial wake lock all
  the time (simple, but it costs battery). An exact alarm needs
  `SCHEDULE_EXACT_ALARM`: granted by default on Android 12, but on Android 14
  and later the user must allow it (`USE_EXACT_ALARM` is for alarm and
  calendar apps only). Doze limits such alarms to about one per 9 min in deep
  idle for apps that are not on the battery-optimization allow-list; this app
  was on it. This spike did not test the alarm: #2 tests it again, on the
  kitchen display (HyperOS).

## Q3. OpAMP on Android

Yes: the `opamp-client` runs in the app (WebSocket transport over OkHttp), receives a
remote config, applies it and reports the status with the hash.

The test set the config of the phone's agent on the opamp-go example server:
`interval_seconds: 30` at 08:47:56, then `interval_seconds: 60` at 08:52:56.

Hashes (sha256 of the config body):

```
c90d8d7c5b67bbcdfe1daf95909942c35bb72cb31d248291963f14b07cff6609  "interval_seconds: 30"
3ee63feb32bdfd2ce0dec7157776794314873aea5d73347285aa4e70e6a132ee  "interval_seconds: 60"
e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855  "" (empty, the start)
```

Server log (instance uid and agent description cut):

```
08:47:56.216 AgentToServer: SequenceNum=4, ..., RemoteConfigStatus=APPLIED, RemoteConfigStatusHash=c90d8d7c…6609
08:47:56.216 ServerToAgent: RemoteConfigHash=c90d8d7c…6609
08:47:56.220 AgentToServer: SequenceNum=5, ..., EffectiveConfigFiles=1
08:52:56.233 AgentToServer: SequenceNum=6, ..., RemoteConfigStatus=APPLIED, RemoteConfigStatusHash=3ee63feb…32ee
08:52:56.235 AgentToServer: SequenceNum=7, ..., EffectiveConfigFiles=1
```

App side (`samples.csv`; columns time, gap s, battery %, plugged, screen on,
interval s). The interval went 60 → 30 → 60:

```
08:47:22 60 90.0 none 1 60
08:48:25 63 90.0 none 1 30
08:48:55 30 90.0 none 1 30
...
08:52:26 30 90.0 none 1 30
08:53:55 89 89.0 none 1 60
08:54:55 60 89.0 none 1 60
```

The app's logcat lines (`OPAMP config received, hash=…`, `OPAMP config applied:
interval 60 s -> 30 s`, `OPAMP reported status APPLIED, remote hash=…`) had
already rolled out of the phone's log buffer when it was pulled at 10:04, so the
server log and `samples.csv` above are the evidence. The saved emulator log
(2026-11-11 00:02) shows the app side of the same flow after a restart:
`OPAMP client started, ..., last config hash=c90d8d7c…6609` (the applied
config was stored), then `OPAMP config received, hash=e3b0…` and
`OPAMP empty config, ...: kept interval 30 s`.

Notes for #2:
- The new interval starts with the next tick (63 s and 89 s gaps above), not at
  once. Fine for us.
- After the app restart at 09:49:17 the example server offered the empty
  config again (`RemoteConfigHash=e3b0…`) and the app reported it as APPLIED.
  The app kept its last interval (it stores it), which is what we want. #2's
  server must send the config again after a reconnect, or the app must keep
  it, as here.
- The OpAMP client pulls OpenTelemetry API 1.67.0, opentelemetry-android is
  built on 1.66.0, and mixed versions crashed at start. The spike holds all
  core artifacts at 1.66.0 (`enforcedPlatform`); #2 must do the same or wait
  for matching releases.

## Q4. Receiver: VictoriaMetrics

Yes, the metrics arrive through `/opentelemetry/v1/metrics`.

Query results at 2026-11-11 09:55:00 UTC (`app.installation.id` redacted):

**Gauge** `device.battery.level` (unit `%`):

```
{__name__="device.battery.level", spike.buffer="library", battery.plugged="ac",
 device.id="spike-phone-1", device.manufacturer="OnePlus", device.model.identifier="CPH2409",
 device.model.name="CPH2409", android.os.api_level="31", os.build_id="RKQ1.211119.001",
 os.description="Android Version 12 (Build RKQ1.211119.001 API level 31)", os.name="Android",
 os.type="linux", os.version="12", app.installation.id="<redacted>", location="spike-test",
 scope.name="otel-spike", scope.version="unknown", service.name="otel-spike",
 service.version="0.1-spike", telemetry.sdk.language="java", telemetry.sdk.name="opentelemetry",
 telemetry.sdk.version="1.8.1-alpha"} => 100
{__name__="device.battery.level", spike.buffer="own", battery.plugged="ac",
 device.id="spike-phone-1", device.model.identifier="CPH2409", location="spike-test",
 os.version="12", scope.name="otel-spike", scope.version="unknown", service.name="otel-spike",
 service.version="0.1-spike", telemetry.sdk.language="java", telemetry.sdk.name="opentelemetry",
 telemetry.sdk.version="1.66.0"} => 100
```

**Counter** `spike.ticks` (unit `{tick}`, cumulative):

```
{__name__="spike.ticks", spike.buffer="library", device.id="spike-phone-1", ... (same labels as above)} => 5
{__name__="spike.ticks", spike.buffer="own", device.id="spike-phone-1", ... (same labels as above)} => 6
```

All metric names in the database: `device.battery.level`,
`device.battery.temperature`, `device.battery.voltage`, `device.screen.on`,
`spike.tick.interval`, `spike.ticks`,
`otel.sdk.metric_reader.collection.duration_{bucket,count,sum}`.

How OTLP turns into series:
- **Names stay as they are**, with dots. No `_total` for counters, no unit
  suffix, no `.` → `_`. Queries must quote them or use `{__name__="…"}`.
- **Units are dropped.** Put the unit in the name if a dashboard needs it, or
  keep a table.
- **Type** is not stored. A gauge and a counter look the same; a cumulative
  counter starts again at 0 after an app restart (values 5 and 6 above), so use
  `increase()`/`rate()`, which handle resets. A histogram becomes
  `_bucket`/`_count`/`_sum` series.
- **Every resource attribute becomes a label**, next to the data point
  attributes (`battery.plugged`) and the scope (`scope.name`,
  `scope.version`).
- opentelemetry-android adds many resource attributes of its own (`os.*`,
  `device.manufacturer`, `device.model.name`, `android.os.api_level`) and
  **`app.installation.id`**, a random id per install. That id is private data
  and adds a new series on each reinstall: #2 should drop it or make it
  optional.

## Decision for #2

- **Buffer: own file queue.** opentelemetry-android's disk buffering keeps
  metrics over a network cut but loses what it has not sent when the app
  restarts during a cut (Q1). #2 uses the own queue of `OwnQueue.kt`: one
  OTLP/HTTP protobuf body per file, sent in order, deleted after a 2xx. Give
  it a size cap (drop the oldest file) so a long cut cannot fill the disk.
- **Service model: one foreground service, with a timer that wakes the CPU.**
  The foreground service lived 24 h on OxygenOS (Q2), but a `Handler` tick
  slows to 2–3 min on battery with the screen off. #2 schedules each reading
  with an exact alarm (`setExactAndAllowWhileIdle`) or a partial wake lock held
  all the time, and stores the time of each reading so gaps show. WorkManager stays out
  (15 min minimum). The setup checklist of #2 needs: "Alarms & reminders"
  (exact alarms) allowed on Android 14+; OnePlus/OPPO "Allow
  background activity"; Xiaomi Autostart and battery saver "No restrictions";
  "include this app" for a VPN with split tunneling. The 24 h test on HyperOS 2
  (Android 15) runs in #2, on the kitchen display.
- **OpAMP client: yes.** `opentelemetry-opamp-client` works on Android over
  WebSocket, applies a remote config and reports its hash (Q3). #2 keeps the
  last applied config on the phone (the server may send an empty one after a
  reconnect) and pins the OpenTelemetry core versions to the ones
  opentelemetry-android uses.
- **Receiver: OTLP into VictoriaMetrics works as it is** (Q4). #2 drops
  `app.installation.id` from the resource (or makes it optional) and keeps a
  list of the units, as VictoriaMetrics does not store them.
