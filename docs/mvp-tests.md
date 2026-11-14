# MVP tests (issue #2)

Receiver: a VictoriaMetrics container (OTLP at `/opentelemetry/v1/metrics`)
and a VictoriaLogs container (OTLP logs), both throw-away. Times are UTC.

## Emulator (Android 15, API 35, x86_64), 2026-11-13

Config: the defaults plus `exporter.endpoint`, `exporter.logs_endpoint`,
`resource.device.id` = `emu-1` and one attribute `location=test`. So
`wake_policy` is the default, `when_charging`.

### All six collectors send

Instant queries, `{__name__="NAME", device.id="emu-1"}`, 2026-11-13 ~11:48:

```
hw.battery.charge  => 1
device.screen.on  => 0
system.uptime  => 2626.577
device.boot.count  => 1
device.network.type {'network.connection.type': 'bluetooth'} => 0
device.network.type {'network.connection.type': 'cell'} => 0
device.network.type {'network.connection.type': 'vpn'} => 0
device.network.type {'network.connection.type': 'wifi'} => 1
device.network.type {'network.connection.type': 'wired'} => 0
system.memory.usage {'system.memory.state': 'free'} => 1771495424
system.memory.usage {'system.memory.state': 'used'} => 821710848
system.filesystem.usage {'system.filesystem.mountpoint': '/data', 'system.filesystem.state': 'free'} => 5788688384
system.filesystem.usage {'system.filesystem.mountpoint': '/data', 'system.filesystem.state': 'used'} => 439427072
device.thermal.status  => 0
device.thermal.headroom  => 0.426666647196
device.battery.voltage  => 5
device.battery.plug {'battery.plug': 'ac'} => 1
device.battery.plug {'battery.plug': 'dock'} => 0
device.battery.plug {'battery.plug': 'none'} => 0
device.battery.plug {'battery.plug': 'usb'} => 0
device.battery.plug {'battery.plug': 'wireless'} => 0
device.power.idle  => 0
device.memory.low  => 0
device.wifi.connected => 1
device.wifi.rssi => -50
device.wifi.link_speed => 1
```

(battery: `hw.battery.charge`, `device.battery.voltage`, `device.battery.plug`;
power: `device.screen.on`, `device.power.idle`; boot: `system.uptime`,
`device.boot.count`; network: `device.network.type`, `device.wifi.*`;
device: `system.memory.usage`, `system.filesystem.usage`, `device.memory.low`;
thermal: `device.thermal.status`, `device.thermal.headroom`.) The emulator
reports a Wi-Fi link speed of 1 Mbit/s and a fixed battery; the values are
the emulator's.

Labels of one series (no `app.installation.id`):

```
{'__name__': 'hw.battery.charge', 'device.id': 'emu-1', 'device.manufacturer': 'unknown', 'device.model.identifier': 'Android SDK built for x86_64', 'location': 'test', 'os.name': 'Android', 'os.type': 'linux', 'os.version': '15', 'scope.name': 'dev.code6x.otelagent', 'scope.version': '0.1.0-dev', 'service.name': 'android-otel-device-agent', 'service.version': '0.1.0-dev', 'telemetry.sdk.language': 'java', 'telemetry.sdk.name': 'opentelemetry'}
```

The boot log record in VictoriaLogs:

```
{'_msg': 'device boot', '_time': '2026-11-13T11:02:45Z', 'device.boot.count': '1', 'device.id': 'emu-1', 'event_name': 'device.boot'}
```

### Turning a collector off

At 11:12:24 a config with `"collectors": {"thermal": {"enabled": false}}`
was imported by `adb shell am broadcast` (answer:
`data="ok: config imported; applied"`). Raw points (Unix seconds):

```
device.thermal.status  1794568244, 1794568304            <- none after 1794568344 (11:12:24)
device.screen.on       1794568244, 1794568304, 1794568344, ...
```

The thermal metrics stopped at once (the import makes a new reading
straight away, without thermal); the other collectors went on.

### `wake_policy=when_charging` on battery, screen off

`adb shell dumpsys battery unplug` and `input keyevent KEYCODE_SLEEP` at
11:13:23. After the next reading:

```
2026-11-13T11:14:29Z
  AC powered: false
  USB powered: false
  mWakefulness=Asleep
Wake Locks: size=0
Wake Locks: size=0

Suspend Blockers: size=5
  PowerManagerService.Booting: ref count=0 []
  Pending alarms per uid: [1000:14, 1002:1, 1073:3, u0a79:1]
11-13 13:14:20.001  2399  2418 I OtelAgent: tick: read battery, power, boot, network, device, thermal; charging=false wake=false
```

The app's uid is `u0a148` (10148): it holds no wake lock
(`Wake Locks: size=0`) and has no pending alarm (it is not in
`Pending alarms per uid`). After `dumpsys battery reset` (on the charger
again) the exact alarm is back:

```
2026-11-13T11:15:20Z
  Pending alarms per uid: [1000:15, 1002:1, 1073:3, u0a79:1, u0a148:1]
      type=ELAPSED_WAKEUP origWhen=+59s961ms window=0 exactAllowReason=allow-listed repeatInterval=0 count=0 flags=0x9
          type=ELAPSED_WAKEUP tag=*walarm*:dev.code6x.otelagent/.TickReceiver 
11-13 13:15:20.003  2399  2418 I OtelAgent: tick: read battery, power, boot, network, device, thermal; charging=true wake=true
```

### The other `wake_policy` values

`always` on battery sets the exact alarm (uid `u0a148` is in the pending
list); `never` on the charger sets none, and the app holds no wake lock (the
one listed is the system's audio, uid 1041):

```
Broadcast completed: result=-1, data="ok: config imported; applied"
== always (battery) 11:52:11
  Pending alarms per uid: [1000:13, 1002:1, 1073:3, u0a79:1, u0a148:1]
Wake Locks: size=0

11-13 13:52:08.924  2960  2977 I OtelAgent: tick: read battery, power, boot, network, device, thermal; charging=false wake=true
Broadcast completed: result=-1, data="ok: config imported; applied"
== never (charger) 11:52:15
  Pending alarms per uid: [1000:14, 1002:1, 1073:3, u0a79:1]
Wake Locks: size=1
  PARTIAL_WAKE_LOCK                 'AudioMix' ACQ=-222ms (uid=1041)
11-13 13:52:12.068  2960  2977 I OtelAgent: tick: read battery, power, boot, network, device, thermal; charging=true wake=false
```

### Config import by file and by share sheet

- **File:** the JSON file was put in the emulator's Downloads. In the app:
  **Import config file**, then the file in the system file picker. Log:
  `local config imported (0 warnings)`, and the receiver got the new
  attribute (`count by (location)` of `hw.battery.charge` showed
  `location="from-file"`).
- **Share sheet / QR text:** `am start -a android.intent.action.SEND -t text/plain
  --es android.intent.extra.TEXT '<json>'` to the app: it showed "Use this
  config?" with Import / Cancel; after Import the local file had the new
  value (`from-share`).
- **adb broadcast:** used for every test above (answer
  `data="ok: config imported; applied"`).

### 30 min network cut with an app restart in it

```
2026-11-13T11:15:30Z cut start: docker pause (receiver does not answer)
2026-11-13T11:25:30Z restart 1: kill -9 of the app process 2399 (system-style kill; START_STICKY restarts it)
2026-11-13T11:26:30Z pid after restart 1: 2608
2026-11-13T11:35:30Z restart 2: am force-stop, then start again by adb broadcast
2026-11-13T11:36:30Z pid after restart 2: 
2026-11-13T11:45:30Z cut end: docker unpause
2026-11-13T11:47:30Z done; queue files: 0
```

`docker pause` makes the receiver accept TCP and then not answer, like a lost
network. Restart 1 killed the process with `kill -9` (as the system does);
Android started the service again (`START_STICKY`) one second later. The
force-stop step (restart 2) did not run: the adb command gave no output and
the process stayed the same (pid 2608 until the next install). So this test
has one restart in the cut.

`tools/vm_report.py` over 11:05–11:50 (raw points of `device.screen.on`):

```
window 2026-11-13 11:05:00 -> 2026-11-13 11:50:00 UTC
readings: 39, first 2026-11-13 11:10:44, last 2026-11-13 11:47:31 UTC
process starts (distinct device.agent.start_time): 2: 2026-11-13 11:10:44, 2026-11-13 11:25:31
readings on the charger: 38, on battery: 1
screen on: 0.10 of the readings
timer wakes the CPU: 0.97 of the readings
largest gap: 60 s
gaps over 120 s on the charger: 0
gaps over 120 s on the battery: 0
readings per hour: 39
```

No reading is lost: one reading per minute from 11:10:44 to 11:47:31,
largest gap 60 s, over the 30 min cut and the restart in it. The two
`device.agent.start_time` values are the two processes. After the cut the
queue sent all 30 files in order and was empty.

### Unit tests

`./gradlew :app:testDebugUnitTest`: 37 tests, 0 failures: config parsing and
merging (`ConfigLoaderTest`), queue size cap, max age, order and the sender
rules (`QueueTest`), each collector's mapping (`CollectorMappingTest`), the
OTLP bodies (`OtlpEncoderTest`) and the timer plan (`TickPlanTest`).

## Redmi Note 12S on HyperOS 2 (Android 15), 2026-11-14

The phone runs a kiosk app and stays on the charger. The kiosk
turns the screen on when it sees motion and off 60 s after the last motion.
The signed release APK 0.1.0 was installed with `pm install` (the owner
tapped Install). Config: the defaults plus `exporter.endpoint`,
`exporter.logs_endpoint` and `resource.device.id`, imported by adb
broadcast, so `wake_policy` is the default, `when_charging`.

Phone settings changed for this app only (undo them in the same screens):

- Security → Autostart: on.
- App info → Battery saver: **No restrictions** (the app is now on the Doze
  allow-list: `dumpsys deviceidle whitelist`; standby bucket 5 = exempted).
- Alarms & reminders: allowed.

The notification permission was not given: the foreground service runs
without it, its notification is only hidden.

### On the charger, screen off, `when_charging`, 1 h

The service started at 07:10:45. The phone was off the charger for the first
three readings, so the window starts at the first reading on the charger.
`tools/vm_report.py` over 07:13:45–08:13:45:

```
window 2026-11-14 07:13:45 -> 2026-11-14 08:13:45 UTC
readings: 60, first 2026-11-14 07:13:45, last 2026-11-14 08:12:48 UTC
process starts (distinct device.agent.start_time): 1: 2026-11-14 07:10:45
readings on the charger: 60, on battery: 0
screen on: 0.07 of the readings
timer wakes the CPU: 1.00 of the readings
largest gap: 60 s
gaps over 120 s on the charger: 0
gaps over 120 s on the battery: 0
readings per hour: 60
```

No gap over 120 s. The screen was on in about 7 % of the readings (4 of 60); the kiosk
wakes it on motion. At the end, `dumpsys alarm` shows the app's exact alarm for
the next reading:

```
ELAPSED_WAKEUP #4: Alarm{... dev.code6x.otelagent}
  tag=*walarm*:dev.code6x.otelagent/.TickReceiver
  type=ELAPSED_WAKEUP origWhen=+22s229ms window=0 exactAllowReason=allow-listed ...
```

`dumpsys power` "Wake Locks" lists only the kiosk app's locks: the app holds
none between readings. Its short `otelagent:tick` lock (about 70 ms for each
reading) shows only in the wake lock history.
