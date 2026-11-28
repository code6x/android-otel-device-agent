# Metrics reference

This page lists every metric and log record the app sends. The code is the
source of truth: the metric list is `collect/Metrics.kt`, and the readings are
made in `collect/*.kt` and `AgentService.kt`. For the config, see the
[README](../README.md).

## Basics

- All metrics are **gauges** (OTLP type `gauge`, double values). Each point is
  one reading and has the wall-clock time of the reading.
- Log records are OTLP log records with severity `INFO`. The record name is the
  OTLP event name. The text is in the body (a string). The time is the time of the
  event, not the time of sending. Log records are sent only when
  `exporter.logs_endpoint` is set.
- Units follow UCUM, as OpenTelemetry does. `1` means "no unit".
- Names follow the OpenTelemetry semantic conventions where they exist
  (`hw.battery.*`, `system.memory.*`, `system.filesystem.*`, `system.uptime`),
  else `device.*`.
- A state metric (status, plug, health, network type, Shizuku state) has one
  point per possible value: 1 for the current one, 0 for the others.
- If the phone does not give a value, the point is left out.
- "Min Android" is empty when the metric works on every supported version
  (the app needs Android 8.0, API 26, or newer).
- The app does **not** collect the Wi-Fi name (SSID) or BSSID: Android needs the
  location permission for them.

Each collector can be turned on or off with `collectors.NAME.enabled` and has
its own interval `collectors.NAME.interval` (default 60 s).

## Collector `battery`

Default: on. Reads the sticky `Intent.ACTION_BATTERY_CHANGED` and `BatteryManager`.

| Name | Type | Unit | Attributes | Source | Min Android | What |
|---|---|---|---|---|---|---|
| `hw.battery.charge` | gauge | `1` | none | `Intent.ACTION_BATTERY_CHANGED` extras `EXTRA_LEVEL` / `EXTRA_SCALE` | | Battery level, 0 to 1 (0.87 = 87 %) |
| `device.battery.voltage` | gauge | `V` | none | `Intent.ACTION_BATTERY_CHANGED` extra `EXTRA_VOLTAGE` (mV, divided by 1000). Left out when 0 or less | | Battery voltage |
| `device.battery.temperature` | gauge | `Cel` | none | `Intent.ACTION_BATTERY_CHANGED` extra `EXTRA_TEMPERATURE` (tenths of °C, divided by 10) | | Battery temperature, °C |
| `device.battery.current` | gauge | `A` | none | `BatteryManager.BATTERY_PROPERTY_CURRENT_NOW` via `getLongProperty` | | Battery current. The sign is kept as the phone gives it: positive is into the battery on most phones, but some phones (for example OnePlus) use the other sign. The unit of the raw value is chosen by `collectors.battery.current_unit`: see [Battery current unit](../README.md#battery-current-unit) |
| `device.battery.charge_counter` | gauge | `A.h` | none | `BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER` via `getIntProperty` (µAh, divided by 1,000,000). Left out when 0 or less | | Remaining charge |
| `device.battery.cycle_count` | gauge | `{cycle}` | none | `Intent.ACTION_BATTERY_CHANGED` extra `EXTRA_CYCLE_COUNT` | 14 (API 34) | Charge cycles (only when the phone reports it) |
| `device.battery.status` | gauge | `1` | `battery.status`: `charging`, `discharging`, `not_charging`, `full`, `unknown` | `Intent.ACTION_BATTERY_CHANGED` extra `EXTRA_STATUS` | | 1 for the current status, 0 for the others |
| `device.battery.plug` | gauge | `1` | `battery.plug`: `ac`, `usb`, `wireless`, `dock`, `none` | `Intent.ACTION_BATTERY_CHANGED` extra `EXTRA_PLUGGED` (a bit mask) | | 1 for the current power source, 0 for the others. If the mask has more than one bit, more than one value is 1 |
| `device.battery.health` | gauge | `1` | `battery.health`: `good`, `overheat`, `dead`, `over_voltage`, `failure`, `cold`, `unknown` | `Intent.ACTION_BATTERY_CHANGED` extra `EXTRA_HEALTH` | | 1 for the current health, 0 for the others |

## Collector `power`

Default: on. Reads `PowerManager`.

| Name | Type | Unit | Attributes | Source | Min Android | What |
|---|---|---|---|---|---|---|
| `device.screen.on` | gauge | `1` | none | `PowerManager.isInteractive` | | 1 when the screen is on |
| `device.power.save_mode` | gauge | `1` | none | `PowerManager.isPowerSaveMode` | | 1 when battery saver is on |
| `device.power.idle` | gauge | `1` | none | `PowerManager.isDeviceIdleMode` | | 1 in idle (Doze) mode |

## Collector `boot`

Default: on. Reads the clock and the boot count. It also sends one log record per boot
(see [Log records](#log-records)).

| Name | Type | Unit | Attributes | Source | Min Android | What |
|---|---|---|---|---|---|---|
| `system.uptime` | gauge | `s` | none | `SystemClock.elapsedRealtime()` | | Time since boot, deep sleep included |
| `device.boot.count` | gauge | `{boot}` | none | `Settings.Global.BOOT_COUNT`. Left out when the setting is missing | | Boots since the phone was set up |
| `device.boot.time` | gauge | `s` | none | `System.currentTimeMillis()` minus `SystemClock.elapsedRealtime()` | | Time of the last boot, Unix seconds |

## Collector `network`

Default: on. Reads `ConnectivityManager` and `WifiManager`.

| Name | Type | Unit | Attributes | Source | Min Android | What |
|---|---|---|---|---|---|---|
| `device.network.connected` | gauge | `1` | none | `ConnectivityManager.getActiveNetwork()` and `getNetworkCapabilities()` | | 1 when there is an active network |
| `device.network.type` | gauge | `1` | `network.connection.type`: `wifi`, `cell`, `wired`, `vpn`, `bluetooth` | `NetworkCapabilities.hasTransport()` with `TRANSPORT_WIFI`, `TRANSPORT_CELLULAR`, `TRANSPORT_ETHERNET`, `TRANSPORT_VPN`, `TRANSPORT_BLUETOOTH` | | 1 for each transport of the active network, 0 for the others (a VPN over Wi-Fi is both `vpn` and `wifi`) |
| `device.wifi.connected` | gauge | `1` | none | The active network has `TRANSPORT_WIFI`, or `WifiInfo.getSupplicantState()` is `SupplicantState.COMPLETED` | | 1 when Wi-Fi is connected |
| `device.wifi.rssi` | gauge | `dBm` | none | `WifiManager.getConnectionInfo().getRssi()`. Only when Wi-Fi is on and connected; -127 (no value) is left out | | Wi-Fi signal strength |
| `device.wifi.link_speed` | gauge | `Mbit/s` | none | `WifiManager.getConnectionInfo().getLinkSpeed()`. Only when Wi-Fi is on and connected; 0 or less is left out | | Wi-Fi link speed |

## Collector `device`

Default: on. RAM and the space on `/data`.

| Name | Type | Unit | Attributes | Source | Min Android | What |
|---|---|---|---|---|---|---|
| `system.memory.limit` | gauge | `By` | none | `ActivityManager.MemoryInfo.totalMem` | | Total RAM |
| `system.memory.usage` | gauge | `By` | `system.memory.state`: `free`, `used` | `free`: `ActivityManager.MemoryInfo.availMem`. `used`: `totalMem` minus `availMem` | | RAM. `free` is the memory available to apps |
| `device.memory.low` | gauge | `1` | none | `ActivityManager.MemoryInfo.lowMemory` | | 1 when the system is low on memory |
| `system.filesystem.limit` | gauge | `By` | `system.filesystem.mountpoint`: `/data` | `StatFs(/data).totalBytes` (the path is `Environment.getDataDirectory()`) | | Size of `/data` |
| `system.filesystem.usage` | gauge | `By` | `system.filesystem.mountpoint`: `/data`; `system.filesystem.state`: `free`, `used` | `free`: `StatFs(/data).availableBytes`. `used`: `totalBytes` minus `availableBytes` | | Space on `/data` |

## Collector `thermal`

Default: on. Reads `PowerManager`.

| Name | Type | Unit | Attributes | Source | Min Android | What |
|---|---|---|---|---|---|---|
| `device.thermal.status` | gauge | `1` | none | `PowerManager.getCurrentThermalStatus()` | 10 (API 29) | 0 none, 1 light, 2 moderate, 3 severe, 4 critical, 5 emergency, 6 shutdown |
| `device.thermal.headroom` | gauge | `1` | none | `PowerManager.getThermalHeadroom(0)`. Left out when the phone gives no value (NaN) | 11 (API 30) | Thermal headroom; 1.0 is where severe throttling starts |

## Shizuku collectors

These four collectors run shell commands through Shizuku. They are **off by
default**. Setup: see [Shizuku collectors](../README.md#shizuku-collectors-optional).
If Shizuku is not ready, the collector sends nothing.

### Collector `apps`

Needs `collectors.apps.packages`. One point for each package in the list.

| Name | Type | Unit | Attributes | Source | Min Android | What |
|---|---|---|---|---|---|---|
| `device.app.running` | gauge | `1` | `app.package`: the package name from the config | Shell command `ps -A -o NAME` | | 1 when the package has a running process (its main process, or a `package:name` one) |

### Collector `boot_reason`

| Name | Type | Unit | Attributes | Source | Min Android | What |
|---|---|---|---|---|---|---|
| `device.boot.reason` | gauge | `1` | `device.boot.reason`: text of `sys.boot.reason`; `device.shutdown.reason`: text of `sys.boot.reason.last` | System properties `sys.boot.reason` and `sys.boot.reason.last`, read with `getprop` | | Always 1. The attributes hold the reason of this boot and of the last shutdown. The values are free text from the phone |

It also sends one log record per boot (see [Log records](#log-records)).

### Collector `thermal_sensors`

One point for each sensor.

| Name | Type | Unit | Attributes | Source | Min Android | What |
|---|---|---|---|---|---|---|
| `device.thermal.temperature` | gauge | `Cel` | `device.thermal.sensor`: sensor name (`mName`); `device.thermal.type`: `cpu`, `gpu`, `battery`, `skin`, `usb_port`, `power_amplifier`, `bcl_voltage`, `bcl_current`, `bcl_percentage`, `npu`, `tpu`, `display`, `modem`, `soc`, `wifi`, `camera`, `flashlight`, `speaker`, `ambient`, `pogo`, or the type number if it is not known | Shell command `dumpsys thermalservice`, block "Current temperatures from HAL" (`mValue`, `mType`, `mName`) | | Temperature of each sensor |

### Collector `batterystats`

Sends no metric, only a log record (see [Log records](#log-records)). It reads
`dumpsys batterystats --history`.

## Collector-independent metrics (agent)

Not a collector. These are sent with every reading (every time at least one
collector is due).

| Name | Type | Unit | Attributes | Source | Min Android | What |
|---|---|---|---|---|---|---|
| `device.agent.shizuku` | gauge | `1` | `shizuku.state`: `ready`, `not_running`, `no_permission`, `too_old` | Shizuku API (`Shizuku.pingBinder()`, `Shizuku.isPreV11()`, Shizuku permission check). Sent only when a Shizuku collector is on | | 1 for the current Shizuku state, 0 for the others |
| `device.agent.start_time` | gauge | `s` | none | Start time of the app process (the app's own clock) | | Start time of the app process, Unix seconds. A new value means a new process |
| `device.agent.queue.size` | gauge | `By` | none | Size of the disk queue for metrics (the app's own queue) | | Bytes waiting in the metrics queue (log records are not counted) |
| `device.agent.queue.dropped` | gauge | `{file}` | none | Counter of the app's two disk queues (metrics and logs) | | Queue files dropped by the size cap or max age since the process started, both queues together |
| `device.agent.wake` | gauge | `1` | none | `wake_policy` from the config, and `BatteryManager.EXTRA_PLUGGED` for `when_charging` | | 1 when the timer wakes the CPU for readings |

## Log records

Sent to `exporter.logs_endpoint`. Without it, no log record is sent. All have
severity `INFO`.

| Name | Collector | Attributes | Body | Source | When |
|---|---|---|---|---|---|
| `device.boot` | `boot` | `device.boot.time`: Unix seconds of the boot (as text); `device.boot.count`: boot count (as text; left out when the phone has none) | `device boot` | Same as the `boot` metrics: `Settings.Global.BOOT_COUNT`, clocks | Once per boot, when the app first sees a new boot. Time of the record: the boot time |
| `device.boot.reason` | `boot_reason` | `device.boot.reason`: reason of this boot; `device.shutdown.reason`: reason of the last shutdown | `boot reason: <reason>; last shutdown reason: <reason>` | `getprop sys.boot.reason`, `getprop sys.boot.reason.last` | Once per boot. Time of the record: the boot time |
| `device.battery.history` | `batterystats` | `device.agent.gap.start`, `device.agent.gap.end`: UTC time as `yyyy-MM-ddTHH:mm:ssZ`. When there is a history, also: `device.battery.level.start`, `device.battery.level.end`, `device.battery.level.min`, `device.battery.level.max` (percent, as text); `device.battery.plug.changes`: each power source change with its time (empty when none) | With history: `battery from <start> to <end>: level <a>% to <b>% (min <c>%, max <d>%); power source changes: <list or none>`. Without: `no battery history for <start> to <end> (the history was reset or is empty)` | Shell command `dumpsys batterystats --history` | After a boot, or after a gap with no reading (more than 3 intervals and at least 5 min). A gap seen while Shizuku is not ready is kept and sent later. If the dump fails 3 times, the gap is dropped. Time of the record: the time of the reading |

## Resource attributes

On every metric and every log record. Fixed ones first; the free-form ones
from `resource.attributes` in the config are added after them (so they can
replace a fixed one); `device.id` is set last.

| Name | Source |
|---|---|
| `service.name` | Fixed: `android-otel-device-agent` |
| `service.version` | The app version (`BuildConfig.VERSION_NAME`) |
| `device.id` | Config key `resource.device.id`. Left out when empty |
| `device.model.identifier` | `Build.MODEL` |
| `device.manufacturer` | `Build.MANUFACTURER` |
| `os.name` | Fixed: `Android` |
| `os.type` | Fixed: `linux` |
| `os.version` | `Build.VERSION.RELEASE` |
| `telemetry.sdk.language` | Fixed: `java` |
| `telemetry.sdk.name` | Fixed: `opentelemetry` |
| any name | Config key `resource.attributes` (text values) |

There is **no** `app.installation.id` (a random id per install: it is private,
and it makes a new series after each reinstall).

The instrumentation scope of every point and record is `dev.code6x.otelagent`,
with the app version.
