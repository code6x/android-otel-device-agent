# Receiver tests (issue #5)

Three receivers from the README "Quick start", tested on the Android
emulator (Android 15, API 35, x86_64), 2026-11-28. The app is a debug build
of this branch. The emulator reaches the host as `10.0.2.2`. Each container
was bound to the host loopback only (`-p 127.0.0.1:PORT:PORT`) and removed
after the test. Config is set by adb broadcast (see the README, "By adb").
All collector intervals were set to 15 s (`battery`, `power`, `boot`,
`network`, `device`), and `resource.device.id` was `emu-1`.

| Receiver | Image | Result |
|---|---|---|
| OpenTelemetry Collector | `otel/opentelemetry-collector-contrib:0.165.0` | pass: metrics and log record |
| VictoriaMetrics | `victoriametrics/victoria-metrics:v1.156.0` | pass: metrics (no logs, as expected) |
| Grafana Alloy | `grafana/alloy:v1.22.0` | pass: metrics reach Alloy (debug exporter only) |

## OpenTelemetry Collector

```bash
docker run -d --name aoda5-otelcol -p 127.0.0.1:4318:4318 \
  -v "$PWD/otelcol.yaml:/etc/otelcol-contrib/config.yaml:ro" \
  otel/opentelemetry-collector-contrib:0.165.0
adb -s emulator-5554 shell am broadcast -a dev.code6x.otelagent.SET_CONFIG \
  -n dev.code6x.otelagent/.ConfigReceiver \
  --es json_b64 "$(base64 -w0 config.json)" --ez start true
```

Endpoints: `http://10.0.2.2:4318/v1/metrics` and `.../v1/logs`. Answer:
`ok: config imported; service started`. Evidence in `docker logs`:

```
Metric #0
     -> Name: hw.battery.charge
     -> DataType: Gauge
Value: 1.000000
...
Logs ... "log records": 1
EventName: device.boot
Body: Str(device boot)
```

The first metrics batch had 31 metrics, 51 data points.

## VictoriaMetrics

```bash
docker run -d --name aoda5-vm -p 127.0.0.1:8428:8428 \
  victoriametrics/victoria-metrics:v1.156.0
```

Endpoint: `http://10.0.2.2:8428/opentelemetry/v1/metrics`, no
`logs_endpoint`. Answer: `ok: config imported; applied`.

```bash
curl -G 'http://127.0.0.1:8428/api/v1/query' \
  --data-urlencode 'query={__name__="hw.battery.charge"}'
```

Result (shortened):

```json
{"status":"success","data":{"resultType":"vector","result":[{"metric":{"__name__":"hw.battery.charge","device.id":"emu-1","os.name":"Android","os.version":"15","scope.name":"dev.code6x.otelagent","service.name":"android-otel-device-agent", ...},"value":[1795876990,"1"]}]}}
```

Note: a plain `curl 'http://...?query={__name__="..."}'` fails, because curl
treats `{}` in a URL as a glob (and the quotes need escaping). Use
`curl -G --data-urlencode` as above (or `curl -g`).

## Grafana Alloy

Alloy config (the README config with the cloud exporter replaced):

```alloy
otelcol.receiver.otlp "default" {
  http { endpoint = "0.0.0.0:4318" }
  output {
    metrics = [otelcol.exporter.debug.default.input]
    logs    = [otelcol.exporter.debug.default.input]
  }
}
otelcol.exporter.debug "default" { verbosity = "detailed" }
```

```bash
docker run -d --name aoda5-alloy -p 127.0.0.1:4318:4318 \
  -v "$PWD/config.alloy:/etc/alloy/config.alloy:ro" grafana/alloy:v1.22.0 \
  run --stability.level=experimental --server.http.listen-addr=0.0.0.0:12345 \
  /etc/alloy/config.alloy
```

`otelcol.exporter.debug` is "experimental" in Alloy, so it needs
`--stability.level=experimental` (without it Alloy refuses to start). This
flag is only for the test. Endpoint: `http://10.0.2.2:4318/v1/metrics`.
Evidence in `docker logs`:

```
level=info msg=Metrics component_id=otelcol.exporter.debug.default resource_metrics=1 metrics=31 data_points=51
... Resource attributes: -> device.id: Str(emu-1) ...
     -> Name: hw.battery.charge
```

`hw.battery.charge` was in 8 batches. The log record `device.boot` was not
seen in this run: the app sends it once per phone boot, and it was already sent
in the earlier tests. Logs through the receiver were shown with the
Collector.

## Not tested

- Alloy to Grafana Cloud (`otelcol.exporter.otlphttp`, `otelcol.auth.basic`)
  and the Prometheus remote write variant: no cloud account. The config
  follows the Grafana docs.
- The app sending directly to the Grafana Cloud OTLP gateway.
- `https://` endpoints (all tests used plain `http` on the emulator).
