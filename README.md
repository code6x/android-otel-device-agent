# android-otel-device-agent

An Android app that reads **device health** (battery, power, boot, network,
memory and storage, thermal) and sends it over **OTLP/HTTP** (OpenTelemetry)
to any OTLP receiver. It keeps the data on disk while the network is down,
and sends it later, in order.

Where to send, what to send and how often are settings, not code.

- One foreground service with a timer reads the collectors (default every 60 s).
- Each reading goes to a **disk queue** first (one OTLP request body per file).
  A sender posts the files in order and deletes a file only after a 2xx answer.
  The queue lives through network cuts and app restarts.
- Settings come from built-in defaults and a **local config file** (JSON). The
  same JSON shape will be used for managed configurations and OpAMP later (#3).

Status: MVP (issue #2). The design came from a spike: [`docs/spike.md`](docs/spike.md).

## Install

Download the signed APK from [GitHub Releases](https://github.com/code6x/android-otel-device-agent/releases)
and install it. Android 8.0 (API 26) or newer. Then:

1. Import a config (see [Config](#config)) with at least `exporter.endpoint`.
2. Press **Start** in the app.
3. Go through the [setup checklist](#setup-checklist) on the same screen.

The service starts again by itself after a reboot and after an app update,
if it was running before.

## What it collects

Each collector can be turned on or off, and has its own interval (default 60 s).
All metrics are **gauges**: each point is one reading, with the wall-clock time
of the reading. So a gap in the data is a gap in the readings.

Names follow the OpenTelemetry semantic conventions where they exist
(`hw.battery.*`, `system.memory.*`, `system.filesystem.*`, `system.uptime`),
else `device.*`.

| Collector | Name | Unit | Attributes | What |
|---|---|---|---|---|
| battery | `hw.battery.charge` | `1` | | Battery level, 0 to 1 (0.87 = 87 %) |
| battery | `device.battery.voltage` | `V` | | Battery voltage |
| battery | `device.battery.temperature` | `Cel` | | Battery temperature, °C |
| battery | `device.battery.current` | `A` | | Battery current. Positive is into the battery on most phones; some phones use the other sign |
| battery | `device.battery.charge_counter` | `A.h` | | Remaining charge |
| battery | `device.battery.cycle_count` | `{cycle}` | | Charge cycles (Android 14+, when the phone reports it) |
| battery | `device.battery.status` | `1` | `battery.status`: `charging`, `discharging`, `not_charging`, `full`, `unknown` | 1 for the current status, 0 for the others |
| battery | `device.battery.plug` | `1` | `battery.plug`: `ac`, `usb`, `wireless`, `dock`, `none` | 1 for the current power source, 0 for the others |
| battery | `device.battery.health` | `1` | `battery.health`: `good`, `overheat`, `dead`, `over_voltage`, `failure`, `cold`, `unknown` | 1 for the current health, 0 for the others |
| power | `device.screen.on` | `1` | | 1 when the screen is on |
| power | `device.power.save_mode` | `1` | | 1 when battery saver is on |
| power | `device.power.idle` | `1` | | 1 in idle (Doze) mode |
| boot | `system.uptime` | `s` | | Time since boot, deep sleep included |
| boot | `device.boot.count` | `{boot}` | | Boots since the phone was set up (`Settings.Global.BOOT_COUNT`) |
| boot | `device.boot.time` | `s` | | Time of the last boot, Unix seconds |
| boot | log record `device.boot` | | `device.boot.count`, `device.boot.time` | One OTLP log record per boot, at the boot time (needs `exporter.logs_endpoint`) |
| network | `device.network.connected` | `1` | | 1 when there is an active network |
| network | `device.network.type` | `1` | `network.connection.type`: `wifi`, `cell`, `wired`, `vpn`, `bluetooth` | 1 for each transport of the active network (a VPN over Wi-Fi is both `vpn` and `wifi`) |
| network | `device.wifi.connected` | `1` | | 1 when Wi-Fi is connected |
| network | `device.wifi.rssi` | `dBm` | | Wi-Fi signal strength |
| network | `device.wifi.link_speed` | `Mbit/s` | | Wi-Fi link speed |
| device | `system.memory.limit` | `By` | | Total RAM |
| device | `system.memory.usage` | `By` | `system.memory.state`: `free` (available to apps), `used` | RAM |
| device | `device.memory.low` | `1` | | 1 when the system is low on memory |
| device | `system.filesystem.limit` | `By` | `system.filesystem.mountpoint`: `/data` | Size of `/data` |
| device | `system.filesystem.usage` | `By` | `system.filesystem.mountpoint`: `/data`; `system.filesystem.state`: `free`, `used` | Space on `/data` |
| thermal | `device.thermal.status` | `1` | | 0 none, 1 light, 2 moderate, 3 severe, 4 critical, 5 emergency, 6 shutdown (Android 10+) |
| thermal | `device.thermal.headroom` | `1` | | Thermal headroom; 1.0 is where severe throttling starts (Android 11+, when the phone reports it) |
| (always) | `device.agent.start_time` | `s` | | Start time of the app process, Unix seconds. A new value means a new process |
| (always) | `device.agent.queue.size` | `By` | | Bytes waiting in the disk queue |
| (always) | `device.agent.queue.dropped` | `{file}` | | Queue files dropped by the size cap or max age since the process started |
| (always) | `device.agent.wake` | `1` | | 1 when the timer wakes the CPU for readings (see `wake_policy`) |

The `device.agent.*` metrics are sent with every reading.

The app does **not** collect the Wi-Fi name (SSID) or BSSID: Android needs the
location permission for them.

**Resource attributes** (on every metric and log record): `service.name`
(`android-otel-device-agent`), `service.version`, `device.id` (from the
config; left out when empty), `device.model.identifier`, `device.manufacturer`,
`os.name`, `os.type`, `os.version`, `telemetry.sdk.*`, and the free-form
attributes from `resource.attributes` in the config. There is **no**
`app.installation.id` (a random id per install: it is private, and it makes a
new series after each reinstall).

### Notes for VictoriaMetrics

VictoriaMetrics takes OTLP at `/opentelemetry/v1/metrics`. It keeps the names
as they are, with dots, so a query must quote them:
`{__name__="hw.battery.charge", device.id="phone-1"}`. It drops the units, so
use the table above. Every resource attribute becomes a label.

## Config

One JSON document. Every key is optional: a key that is not set keeps its
default. Example (all keys):

```json
{
  "exporter": {
    "endpoint": "https://otlp.example.com/v1/metrics",
    "logs_endpoint": "https://otlp.example.com/v1/logs",
    "protocol": "http/protobuf",
    "headers": { "Authorization": "Bearer REPLACE_ME" },
    "timeout": 10
  },
  "resource": {
    "device.id": "tablet-1",
    "attributes": { "location": "lobby" }
  },
  "collectors": {
    "battery": { "enabled": true, "interval": 60 },
    "power":   { "enabled": true, "interval": 60 },
    "boot":    { "enabled": true, "interval": 300 },
    "network": { "enabled": true, "interval": 60 },
    "device":  { "enabled": true, "interval": 300 },
    "thermal": { "enabled": false }
  },
  "buffer": {
    "max_bytes": 20971520,
    "max_age": 604800
  },
  "wake_policy": "when_charging"
}
```

| Key | Default | What |
|---|---|---|
| `exporter.endpoint` | empty | Full URL of the OTLP/HTTP **metrics** endpoint (for VictoriaMetrics: `http://HOST:8428/opentelemetry/v1/metrics`). Empty: the data stays in the queue until an endpoint is set |
| `exporter.logs_endpoint` | empty | Full URL of the OTLP/HTTP **logs** endpoint, for the boot log record. Empty: no log records |
| `exporter.protocol` | `http/protobuf` | The only value supported now |
| `exporter.headers` | none | HTTP headers for both endpoints, for example a token. The app does not show their values |
| `exporter.timeout` | `10` | Seconds, 1–300 |
| `resource.device.id` | empty | The `device.id` resource attribute |
| `resource.attributes` | none | More resource attributes (text values) |
| `collectors.NAME.enabled` | `true` | NAME is `battery`, `power`, `boot`, `network`, `device` or `thermal` |
| `collectors.NAME.interval` | `60` | Seconds, 10–86400 |
| `buffer.max_bytes` | `20971520` (20 MiB) | Disk queue size cap. When it is full, the oldest file is dropped first |
| `buffer.max_age` | `604800` (7 days) | Seconds. Older queue files are dropped |
| `wake_policy` | `when_charging` | `never`, `when_charging` or `always`; see below |

A wrong value (a bad type, a number out of range, an unknown `wake_policy`)
rejects the whole import, and the config in use stays as it was. An unknown
key is ignored with a warning.

The app screen shows the config in use, each value with where it came from
(`default` or `local file`).

### Import the config

**By file.** In the app: **Import config file**, then pick the JSON file. Or
open a `.json` file from a file manager or share it to the app ("Open with" /
share sheet). The app asks before it uses a config from another app.

**By QR code.** Put the JSON text in a QR code. Scan it with any QR scanner
app, then share the text to this app. The app shows it and asks before it
uses it.

**By adb** (scripted setup). Encode the file in base64, so the shell does not
break the JSON:

```bash
adb shell am broadcast -a dev.code6x.otelagent.SET_CONFIG \
  -n dev.code6x.otelagent/.ConfigReceiver \
  --es json_b64 "$(base64 -w0 config.json)" --ez start true
```

The answer is in the `data` of the result line, for example
`Broadcast completed: result=-1, data="ok: config imported; applied"`
(`result=0` and `data="error: …"` when the config is wrong). Other extras:

- `--es json '{"wake_policy":"always"}'`: the JSON as text (mind the quotes);
- `--ez reset true`: remove the local config file and go back to the defaults;
- `--ez start true`: start the service too, if it does not run. Android lets
  a background app start a foreground service only in some cases. If the app
  is on the battery optimisation allow-list (setup checklist) it works; else
  the answer says `service NOT started`, and you press **Start** in the app.

Only the adb shell can send this broadcast: the receiver asks for the
`android.permission.DUMP` permission, which normal apps cannot get.

A new config takes effect at once: all collectors read again with the new
settings. A collector that is turned off sends nothing from then on.

## `wake_policy`: waking the CPU

When the phone is on battery with the screen off, the CPU sleeps, and a
plain timer stops with it. In the spike, a 60 s timer then came 2–3 minutes
apart. On the charger, every reading came on time.

So `wake_policy` decides whether the timer wakes the CPU:

| Value | On the charger | On battery | Battery cost |
|---|---|---|---|
| `never` | does not wake the CPU | does not wake the CPU | None from waking. Readings may come late (minutes apart) when the screen is off |
| `when_charging` (**default**) | wakes the CPU for each reading | does not wake the CPU | None on battery (the charger pays for the wakes). On battery, readings may come late |
| `always` | wakes the CPU for each reading | wakes the CPU for each reading | The CPU wakes once per interval (60 s by default) for a short time. This keeps the readings on time, but uses more battery than the other values. A longer interval costs less. |

How the app wakes the CPU: an exact alarm (`setExactAndAllowWhileIdle`) for
each reading, and a short wake lock until the reading is sent. Without the
"Alarms & reminders" permission (Android 12+ may not grant it), it holds a
partial wake lock between readings instead. When it does not wake the CPU,
the app sets no alarm and holds no wake lock.

On battery, Android's Doze mode allows such alarms only about once in 9
minutes, unless the app is on the battery optimisation allow-list (setup
checklist).

## Setup checklist

The app screen shows this list with the current state, and buttons that open
the Android screen for each item where one exists.

- **Notifications allowed** (Android 13+). The service shows a quiet notification.
- **Battery optimisation off** for this app. Lets it run and send in the
  background, and start again after an update.
- **Alarms & reminders** (exact alarms) allowed, when `wake_policy` is not
  `never` (Android 12+; on Android 14+ the user must allow it).
- **OnePlus / OPPO** (OxygenOS, ColorOS): App info → Battery → **Allow
  background activity**. Without it the spike's app was frozen 2 minutes after
  start, even with a foreground service; the Doze allow-list did not help.
- **Xiaomi** (HyperOS / MIUI): **Autostart** on, and battery saver **No
  restrictions** (App info → Battery saver).
- **A VPN with per-app split tunneling**: **include this app**, or the
  receiver may not be reachable. The queue keeps the data meanwhile.

## Build and test

```bash
./gradlew :app:testDebugUnitTest   # unit tests
./gradlew :app:assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
```

The `spike/` folder is the separate spike project of issue #1; it is not
part of the app build. Releases: [`docs/release.md`](docs/release.md).
Test results of the MVP: [`docs/mvp-tests.md`](docs/mvp-tests.md).

## License

Apache-2.0, see [LICENSE](LICENSE).
