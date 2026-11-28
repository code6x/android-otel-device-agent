# Shizuku collector tests (issue #4)

Receiver: throw-away VictoriaMetrics and VictoriaLogs containers on the host.
Emulator: Android 15 (API 35, x86_64), 2026-11-28. Times are UTC (the
emulator's logcat is UTC+2). Shizuku 13.6.0 from its GitHub releases.

Config: the defaults plus the endpoints, `resource.device.id` = `emu-4`, and
all four Shizuku collectors on with `interval` 10 and
`collectors.apps.packages` = `"com.android.systemui, com.example.none"`.

## Without Shizuku

Shizuku not installed. The service runs; every tick reads all collectors; the
four Shizuku collectors log (and show on the app screen):

```
W OtelAgent: collector apps: not available: Shizuku is not running (not installed, or not started since the last boot)
W OtelAgent: collector boot_reason: not available: Shizuku is not running (...)
W OtelAgent: collector batterystats: not available: Shizuku is not running (...)
W OtelAgent: collector thermal_sensors: not available: Shizuku is not running (...)
I OtelAgent: tick: read apps, boot_reason, batterystats, thermal_sensors; charging=true wake=true
```

`{__name__="device.agent.shizuku", device.id="emu-4"}`:

```
{'shizuku.state': 'not_running'} 1
{'shizuku.state': 'no_permission'} 0
{'shizuku.state': 'ready'} 0
{'shizuku.state': 'too_old'} 0
```

## After a reboot, before Shizuku is started

13:45:08 `adb reboot`; 13:45:29 boot done. The service started by itself
(`service created`) and logged the same four "not available" lines every tick;
the other six collectors sent as usual.

Then Shizuku was installed and started (`libshizuku.so` by adb). Without an
app restart the state changed to `Shizuku runs, but this app has no Shizuku
permission` (metric: `no_permission` 1). The setup list showed **Ask for
Shizuku permission**; after **Allow all the time** the state was `ready`.

## With Shizuku

Instant queries, 2026-11-28 ~13:48:

```
device.app.running {'app.package': 'com.android.systemui'} 1
device.app.running {'app.package': 'com.example.none'} 0
device.boot.reason {'device.boot.reason': 'reboot,shell', 'device.shutdown.reason': 'reboot,shell'} 1
device.thermal.temperature {'device.thermal.sensor': 'test temperature sensor', 'device.thermal.type': 'skin'} 30.8
device.agent.shizuku {'shizuku.state': 'ready'} 1   (the other three 0)
```

## Log records after a boot, with Shizuku started late

13:48:15 battery level set to 70 and unplugged (`dumpsys battery`), then
`adb reboot` (the emulator is on AC again after boot). 13:48:35 the service
ran; `batterystats` was "not available" (Shizuku stopped by the reboot).
13:48:41 Shizuku started. The next tick sent both records (VictoriaLogs):

```
{"_time":"2026-11-28T13:48:21Z","event_name":"device.boot.reason","_msg":"boot reason: reboot,shell; last shutdown reason: reboot,shell"}
{"_time":"2026-11-28T13:48:45.505Z","event_name":"device.battery.history","_msg":"battery from 2026-11-28T13:48:15Z to 2026-11-28T13:48:45Z: level 70% to 100% (min 100%, max 100%); power source changes: 2026-11-28T13:48:27Z ac", "device.battery.plug.changes":"2026-11-28T13:48:27Z ac", ...}
```

The `min 100%` was a bug (the start level was not counted); fixed in the same
PR, with a unit test.

## Not tested here

A real phone with Shizuku (the Done-when box in #4): the four collectors, and
a reboot before Shizuku is started. See the PR for what to check.
