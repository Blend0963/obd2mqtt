# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Android app (Kotlin + Jetpack Compose) that reads OBD2 data from a vehicle via an ELM327 Bluetooth adapter and publishes sensor readings to Home Assistant via MQTT Discovery. Webhook mode (default) also generates the HA YAML and a Lovelace dashboard from Settings. Target vehicle: Peugeot 407 with a Vgate iCar Pro adapter.

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
              ObdCommandExecutor  (AT init: ATZ, ATE0, ATL0, ATH0, ATSP<n> (0 = auto), ATAT1, then 0100 probe + ATDPN)
                      │
               PidScanner / Mode22Scanner  (brute-force or cache)
               PidPoller  (fast PIDs: 1s, slow PIDs: round-robin over 10s)
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

**`PidPoller`** — Polls `fastPids` every 1s cycle plus a slice of `slowPids`, so each slow PID is read once per `SLOW_INTERVAL_SECONDS` (10s) without bursts. Emits `PidReading` via a `Flow`. Commands come from `PidRegistry.command(pid)`; pseudo-PID `PidRegistry.ADAPTER_VOLTAGE` (0x100) reads the battery voltage with `ATRV`.

**`ObdProtocol`** — ELM327 protocol numbers. Default `AUTO` (`ATSP0`) because the target car (Peugeot 407) exists in CAN and KWP2000 variants. Headers are off (`ATH0`) so KWP headers/checksums never mix with data bytes. Mode 22 scanning is skipped while `Mode22Registry.definitions` is empty.

**Language** — UI strings, notifications, error messages and PID names (which become HA sensor names) are in French, hardcoded in Compose (no string resources).

**`AppSettings`** — DataStore-backed settings. `snapshot()` returns a one-shot `AppConfig` for use in the service. PID caches are hex-encoded comma-separated strings. Fast PIDs default to `DEFAULT_FAST_PIDS`; until the user edits the selection, every other discovered PID is slow ("all data").

**`TelemetrySink`** (`service/`) — Where readings go, chosen by `AppConfig.publishMode`. `MqttSink` wraps `MqttPublisher` + HA Discovery; `WebhookSink` POSTs the latest snapshot every `webhookIntervalSeconds` from a background loop (failures only flip `serverStatus`, never the BT session).

**`webhook/`** — `HaDashboardYaml` (Lovelace dashboard; entity IDs = HA slugify of sensor names), `WebhookPublisher` (HTTPS POST to `<haUrl>/api/webhook/<id>`, URL validated by `buildWebhookUrl`, which rejects non-HTTPS), `WebhookPayload` (JSON bodies, PIDs keyed by hex), `HaWebhookYaml` (generates the HA trigger-based template sensors YAML copied from Settings).

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

Unit tests live in `app/src/test/` (`unitTests.isReturnDefaultValues = true`, so `android.util.Log` is a no-op). Tests needing `AppSettings` build a real DataStore in a `TemporaryFolder` (see `PidScannerTest`). Use `FakeTransport` (`bluetooth/FakeTransport.kt`) to inject scripted responses for `ObdCommandExecutor`, `PidScanner`, and parser tests. Tests use `kotlinx-coroutines-test` (`runTest`).

There are no Android instrumented tests beyond the scaffold (`androidTest/`).

## Adding PIDs

To add a new Mode 01 PID: add an entry to `PidRegistry.definitions` in `obd/PidRegistry.kt`. Use `isFast = true` for high-frequency sensors (RPM, speed, etc.).

To add a Mode 22 PID after reverse-engineering: add an entry to `Mode22Registry.definitions` in `obd/Mode22Registry.kt` following the same `PidDefinition` pattern.

## HA Dashboard

There is no separate script: Settings → Webhook → "Copier le tableau de bord" generates a Lovelace YAML (`webhook/HaDashboardYaml.kt`) for the polled PIDs. User-facing setup guide (French): `docs/peugeot-407.md`.
