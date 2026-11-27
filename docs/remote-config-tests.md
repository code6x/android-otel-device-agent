# Remote config tests (issue #3)

Emulator (Android 15, API 35, x86_64), 2026-11-28. Receiver: a throw-away
VictoriaMetrics container. OpAMP server: the opamp-go example server (main
branch, `-no-tls`), in a throw-away container. Managed configurations: the
test device owner `tools/testowner`. Steps: README, "Remote config". Times
are the emulator's local time (UTC+2); the server log is UTC.

Local file: `exporter.endpoint` and `opamp.endpoint` (`ws://10.0.2.2:4320/v1/opamp`).

## Managed configurations: a changed interval, without a restart

`SET --ei interval 30 --es wake_policy always` at 00:18:05:

```
00:18:05.380 managed configurations changed
00:18:05.383 config: endpoint set, wake_policy=always, collectors on: battery, ..., OpAMP on
00:18:05.390 tick: read battery, power, boot, network, device, thermal; charging=true wake=true
00:18:35.394 tick: read battery, power, boot, network, device, thermal; charging=true wake=true
00:19:05.398 tick: read battery, power, boot, network, device, thermal; charging=true wake=true
```

Before, the interval was 60 s and `wake_policy` the default `when_charging`.

## OpAMP: a new config is applied, the server shows hash and health

Saved on the server UI: `{"collectors":{"battery":{"interval":15}},"wake_policy":"never"}`.

```
00:19:28.224 OpAMP config applied, hash d16e9489...6516
00:19:28.227 config: endpoint set, wake_policy=never, ...
00:19:43.251 tick: read battery; charging=true wake=false
```

Server log: `RemoteConfigStatus=APPLIED, RemoteConfigStatusHash=d16e9489...6516`,
and `Healthy=true` on each message. The agent page showed the new effective
config (battery interval 15, `wake_policy` `never`), `Up: true`, `Up since`
and the attribute `service.version` = `0.1.0-dev`.

## A broken remote config is refused

Saved: `{"wake_policy":"sometimes"}`.

```
00:20:26.296 OpAMP config refused, hash 56422a51...4a09: wake_policy: must be one of never, when_charging, always; the last good config stays
```

Server log: `RemoteConfigStatus=FAILED, RemoteConfigStatusHash=56422a51...4a09`.

## An empty remote config after a restart keeps the last good config

The OpAMP server was restarted (it forgets the config and offers an empty
one), and the app was stopped (`am force-stop`) and started again:

```
00:20:46.265 config: endpoint set, wake_policy=never, ..., OpAMP on
00:20:46.287 OpAMP client started, last config hash d16e9489...6516
00:20:46.315 OpAMP connected
00:20:46.320 OpAMP empty config, hash e3b0c442...b855: the last good config stays
00:21:01.319 tick: read battery; charging=true wake=false
```

## OpAMP turned off: no connection

Managed configurations `opamp_enabled` = `false` at 00:21:14:

```
00:21:14.704 config: endpoint set, wake_policy=always, ..., OpAMP off
00:21:14.705 OpAMP client stopped
```

The server got no message in the next 40 s, and the emulator had no TCP
connection to port 4320 (`/proc/net/tcp`, `/proc/net/tcp6`). `wake_policy`
went back to `always` from managed configurations: the saved OpAMP config is
not used while OpAMP is off.

## Status screen

With OpAMP off, the config list showed for example
`collectors > battery > interval = 30  [managed]`, `opamp > enabled = false  [managed]`,
`opamp > endpoint = "ws://..."  [local file]`, `buffer > max_bytes = ...  [default]`.
With OpAMP on again: `OpAMP: connected`,
`collectors > battery > interval = 15  [OpAMP]`, `wake_policy = "never"  [OpAMP]`.

Not tested here: a real phone, and a real MDM.
