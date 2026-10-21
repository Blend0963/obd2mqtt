# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Android app (Kotlin + Jetpack Compose) that reads OBD2 data from a vehicle via an ELM327 Bluetooth adapter and publishes sensor readings to Home Assistant via MQTT Discovery. Also includes a Python helper (`ha-dashboard/`) for creating HA dashboards.

- **Package**: `com.frodrigues.obdmqtt`
- **minSdk**: 33 (Android 13+)
- **Protocol confirmed working on**: Jaecoo 7 / OMODA platform (ISO 15765-4 CAN, 11-bit, 500 kbaud)

## Build Commands

```bash
./gradlew assembleDebug       # debug APK
./gradlew assembleRelease     # release APK (requires signing config in CI)
./gradlew test                # run unit tests
./gradlew :app:testDebugUnitTest --tests "com.frodrigues.obdmqtt.obd.PidScannerTest"  # single test class
```

**Versioning**: `versionCode` = `GITHUB_RUN_NUMBER` (local fallback = 1). `versionName` = git tag (e.g. `v1.2.3` → `1.2.3`) on tag builds, `0.0.0-<sha7>` otherwise. Releases are created automatically on `v*` tag pushes via `.github/workflows/`.

## Architecture

```
ELM327 ──BT──► BluetoothTransport (Classic/BLE)
                      │
                      ▼
              ObdCommandExecutor  (AT init: ATZ, ATE0, ATL0, ATH1, ATSP6, ATAT1)
                      │
               PidScanner / Mode22Scanner  (brute-force or cache)
               PidPoller  (fast PIDs: 1s, slow PIDs: 30s)
                      │
                      ▼
          MqttPublisher ──► Home Assistant (MQTT Discovery)
                      ▲
          OBDCollectorService (ForegroundService, START_STICKY)
                      │
          MainViewModel ◄── UI (Compose, Navigation)
```

### Key Components

**`OBDCollectorService`** (`service/`) — The core ForegroundService. Holds global `MutableStateFlow` state (status, pidReadings, btStatus, mqttStatus) as `companion object` properties so the UI can observe without binding. Implements exponential backoff reconnection (5s → 10s → 30s → 60s). Publishes empty payloads (marking sensors `unavailable` in HA) on disconnect.

**`BluetoothTransport`** (`bluetooth/`) — Interface with `ClassicTransport` (SPP) and `BleTransport` (GATT). `BluetoothTransportFactory.create()` selects implementation based on `device.type`.

**`PidRegistry`** / **`Mode22Registry`** (`obd/`) — Static maps of `PidDefinition`. Each `PidDefinition` holds name, unit, optional HA device class, `isFast` flag (for polling tier), and a `formula: (ByteArray) -> Double`. Mode 22 is manufacturer-specific; `Mode22Registry.definitions` is currently empty pending reverse engineering.

**`PidScanner`** — Probes Mode 01 PIDs 0x01–0xFF (excluding support PIDs). Results cached in DataStore (`cachedPids`). Cache cleared via Settings "Rescan PIDs". **`Mode22Scanner`** — Same pattern for Mode 22 (2-byte PIDs), cached separately.

**`PidPoller`** — Splits active PIDs into `fastPids` (`isFast=true`, polled every 1s) and `slowPids` (polled every 30s, every 30th cycle). Emits `PidReading` via a `Flow`.

**`AppSettings`** — DataStore-backed settings. `snapshot()` returns a one-shot `AppConfig` for use in the service. PID caches are hex-encoded comma-separated strings. `selectedPids = null` means "publish all discovered PIDs".

**`TelemetrySink`** (`service/`) — Where readings go, chosen by `AppConfig.publishMode`. `MqttSink` wraps `MqttPublisher` + HA Discovery; `WebhookSink` POSTs the latest snapshot every `webhookIntervalSeconds` from a background loop (failures only flip `serverStatus`, never the BT session).

**`webhook/`** — `WebhookPublisher` (HTTPS POST to `<haUrl>/api/webhook/<id>`, URL validated by `buildWebhookUrl`, which rejects non-HTTPS), `WebhookPayload` (JSON bodies, PIDs keyed by hex), `HaWebhookYaml` (generates the HA trigger-based template sensors YAML copied from Settings).

**`HaDiscoveryPublisher`** — Publishes retained MQTT configs to `homeassistant/sensor/obd2_<mac>/<PID>/config`. State values go to `obd2/<mac>/<PID>/state`.

### MQTT Topics

| Topic | Description |
|---|---|
| `homeassistant/sensor/obd2_<mac>/<PID>/config` | HA Discovery config (retained) |
| `obd2/<mac>/<PID>/state` | Sensor value (retained) |
| `homeassistant/sensor/obd2_<mac>/m22_<PID>/config` | Mode 22 Discovery config (retained) |
| `obd2/<mac>/m22_<PID>/state` | Mode 22 sensor value (retained) |

Webhook payload: `{"available":true,"ts":<ms>,"device":"<name>","pids":{"0C":850,...}}`; `{"available":false,...,"pids":{}}` on disconnect; `{"test":true}` for the connection test (ignored by the HA template condition).

MAC in topics = BT address with colons removed (e.g. `AA:BB:CC:DD:EE:FF` → `AABBCCDDEEFF`).

## Testing

Unit tests live in `app/src/test/`. Use `FakeTransport` (`bluetooth/FakeTransport.kt`) to inject scripted responses for `ObdCommandExecutor`, `PidScanner`, and parser tests. Tests use `kotlinx-coroutines-test` (`runTest`).

There are no Android instrumented tests beyond the scaffold (`androidTest/`).

## Adding PIDs

To add a new Mode 01 PID: add an entry to `PidRegistry.definitions` in `obd/PidRegistry.kt`. Use `isFast = true` for high-frequency sensors (RPM, speed, etc.).

To add a Mode 22 PID after reverse-engineering: add an entry to `Mode22Registry.definitions` in `obd/Mode22Registry.kt` following the same `PidDefinition` pattern.

## HA Dashboard (Python)

`ha-dashboard/` contains standalone Python scripts to create a Lovelace dashboard in Home Assistant:
- `create_dashboard.py` — entry point
- `ha_api.py` — HA WebSocket API wrapper
- `dashboard_config.py` — dashboard layout definition
- `test_config.py` — config validation

Install deps: `pip install -r ha-dashboard/requirements.txt` (`websockets`, `requests`).
