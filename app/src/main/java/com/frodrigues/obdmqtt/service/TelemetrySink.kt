package com.frodrigues.obdmqtt.service

import com.frodrigues.obdmqtt.mqtt.HaDiscoveryPublisher
import com.frodrigues.obdmqtt.mqtt.MqttPublisher
import com.frodrigues.obdmqtt.obd.DiagnosticReport
import com.frodrigues.obdmqtt.obd.Mode22Registry
import com.frodrigues.obdmqtt.obd.PidReading
import com.frodrigues.obdmqtt.settings.AppConfig
import com.frodrigues.obdmqtt.settings.PublishMode
import com.frodrigues.obdmqtt.util.jsonString
import com.frodrigues.obdmqtt.util.pidHex
import com.frodrigues.obdmqtt.util.toPlainValue
import com.frodrigues.obdmqtt.webhook.HaEntities
import com.frodrigues.obdmqtt.webhook.WebhookPayload
import com.frodrigues.obdmqtt.webhook.WebhookPublisher
import com.frodrigues.obdmqtt.webhook.buildWebhookUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/** Where readings go: an MQTT broker or an HTTPS webhook on Home Assistant. */
interface TelemetrySink {
    /**
     * Connects and announces sensors. Background work must be launched in [scope],
     * which is cancelled when polling stops.
     */
    suspend fun start(scope: CoroutineScope, mac: String, supportedPids: Set<Int>, mode22Pids: Set<Int>)

    suspend fun publish(reading: PidReading, snapshot: Map<Int, Double>)

    /** Fault codes / check-engine light; HA keeps showing them after [stop]. */
    suspend fun publishDiagnostics(report: DiagnosticReport)

    /** Marks sensors unavailable and releases resources. Must not throw. */
    suspend fun stop()

    companion object {
        /** [onClearRequest] is called when HA asks to clear the fault codes (MQTT button). */
        fun create(
            config: AppConfig,
            onStatus: (ConnectionStatus) -> Unit,
            onClearRequest: () -> Unit = {}
        ): TelemetrySink =
            when (config.publishMode) {
                PublishMode.MQTT -> MqttSink(config, onStatus, onClearRequest)
                PublishMode.WEBHOOK -> WebhookSink(config, onStatus)
            }
    }
}

class MqttSink(
    private val config: AppConfig,
    private val onStatus: (ConnectionStatus) -> Unit,
    private val onClearRequest: () -> Unit
) : TelemetrySink {

    private val publisher = MqttPublisher(config)
    private var mac = ""
    private var stateTopics: List<String> = emptyList()

    override suspend fun start(scope: CoroutineScope, mac: String, supportedPids: Set<Int>, mode22Pids: Set<Int>) {
        require(config.mqttHost.isNotBlank()) { "Aucun serveur MQTT configuré" }
        this.mac = mac
        onStatus(ConnectionStatus.CONNECTING)
        publisher.connect()
        onStatus(ConnectionStatus.CONNECTED)

        stateTopics = supportedPids.map { pid -> "obd2/$mac/${pidHex(pid)}/state" }

        HaDiscoveryPublisher(
            publish = { t, p -> publisher.publish(t, p, retain = true) },
            config = config
        ).publishDiscovery(supportedPids, mac)

        mode22Pids.forEach { pid ->
            val def = Mode22Registry.getOrUnknown(pid)
            val pidHex = pid.toString(16).padStart(4, '0').uppercase()
            val topic = "homeassistant/sensor/obd2_${mac}/m22_$pidHex/config"
            val stateTopic = "obd2/$mac/m22_$pidHex/state"
            val payload = buildString {
                append("{")
                append("\"name\":\"${config.deviceName} ${def.name}\"")
                append(",\"state_topic\":\"$stateTopic\"")
                if (def.unit.isNotBlank()) append(",\"unit_of_measurement\":\"${def.unit}\"")
                append(",\"unique_id\":\"${mac}_m22_$pidHex\"")
                def.haDeviceClass?.let { append(",\"device_class\":\"$it\"") }
                append(",\"device\":{\"identifiers\":[\"obd2_$mac\"],\"name\":\"${config.deviceName}\"}")
                append("}")
            }
            publisher.publish(topic, payload, retain = true)
        }

        publishDiagnosticsDiscovery(mac)
        publisher.subscribe("obd2/$mac/diag/clear") { onClearRequest() }
    }

    /** Diagnostics entities read their values from one retained JSON topic. */
    private suspend fun publishDiagnosticsDiscovery(mac: String) {
        val ha = HaEntities(config)
        val stateTopic = "obd2/$mac/diag/state"
        val device = "{\"identifiers\":[\"obd2_$mac\"],\"name\":${jsonString(config.deviceName)}}"
        val state = ",\"state_topic\":\"$stateTopic\""
        fun icon(name: String) = ",\"icon\":\"mdi:$name\""
        fun template(jinja: String) = ",\"value_template\":${jsonString(jinja)}"

        listOf(
            Triple("binary_sensor", "mil", ha.milName) to
                state + ",\"device_class\":\"problem\"" + template("{{ 'ON' if value_json.mil else 'OFF' }}"),
            Triple("sensor", "dtc_count", ha.faultCountName) to
                state + icon("engine-outline") + template("{{ value_json.codes | count }}") +
                ",\"json_attributes_topic\":\"$stateTopic\"",
            Triple("sensor", "dtc_codes", ha.faultCodesName) to
                state + icon("car-wrench") +
                template("{{ (value_json.codes | map(attribute='code') | join(', ') or 'Aucun')[:255] }}"),
            Triple("sensor", "dtc_pending", ha.pendingName) to
                state + icon("engine-outline") + template("{{ value_json.pending | count }}"),
            Triple("sensor", "readiness", ha.readinessName) to
                state + icon("clipboard-check-outline") + template("{{ value_json.incomplete_monitors | count }}"),
            Triple("button", "clear_dtc", ha.clearButtonName) to
                ",\"command_topic\":\"obd2/$mac/diag/clear\"" + icon("broom"),
        ).forEach { (entity, extra) ->
            val (component, id, name) = entity
            val payload = "{\"name\":${jsonString(name)},\"unique_id\":\"${mac}_$id\",\"device\":$device$extra}"
            publisher.publish("homeassistant/$component/obd2_$mac/$id/config", payload, retain = true)
        }
    }

    override suspend fun publishDiagnostics(report: DiagnosticReport) {
        publisher.publish("obd2/$mac/diag/state", WebhookPayload.diagnostics(report), retain = true)
    }

    override suspend fun publish(reading: PidReading, snapshot: Map<Int, Double>) {
        publisher.publish("obd2/$mac/${pidHex(reading.pid)}/state", reading.value.toPlainValue(), retain = true)
    }

    override suspend fun stop() {
        stateTopics.forEach { topic -> runCatching { publisher.publishEmpty(topic) } }
        publisher.disconnect()
        onStatus(ConnectionStatus.DISCONNECTED)
    }
}

/**
 * Sends the latest snapshot every [AppConfig.webhookIntervalSeconds] from a
 * background loop, so a slow or dead WAN link never stalls OBD polling and
 * never tears down the Bluetooth session. Failed sends are retried on the
 * next tick with whatever is newest.
 */
class WebhookSink(
    private val config: AppConfig,
    private val onStatus: (ConnectionStatus) -> Unit
) : TelemetrySink {

    private var publisher: WebhookPublisher? = null
    private var senderJob: Job? = null
    private val pending = AtomicReference<Map<Int, Double>?>(null)
    @Volatile private var lastSnapshot: Map<Int, Double> = emptyMap()
    @Volatile private var diagnostics: DiagnosticReport? = null

    override suspend fun start(scope: CoroutineScope, mac: String, supportedPids: Set<Int>, mode22Pids: Set<Int>) {
        // Invalid URL / ID is a config error: throw so the service shows "Reconnecting"
        val p = WebhookPublisher(buildWebhookUrl(config.haUrl, config.webhookId))
        publisher = p
        onStatus(ConnectionStatus.CONNECTING)
        val intervalMs = config.webhookIntervalSeconds.coerceAtLeast(1) * 1000L
        senderJob = scope.launch {
            while (isActive) {
                delay(intervalMs)
                val snapshot = pending.getAndSet(null) ?: continue
                try {
                    p.post(WebhookPayload.readings(snapshot, config.deviceName, System.currentTimeMillis(), diagnostics))
                    onStatus(ConnectionStatus.CONNECTED)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onStatus(ConnectionStatus.DISCONNECTED)
                    pending.compareAndSet(null, snapshot)  // keep it unless a newer one arrived
                }
            }
        }
    }

    override suspend fun publish(reading: PidReading, snapshot: Map<Int, Double>) {
        lastSnapshot = snapshot
        pending.set(snapshot)
    }

    override suspend fun publishDiagnostics(report: DiagnosticReport) {
        diagnostics = report
        pending.compareAndSet(null, lastSnapshot)  // make the next tick send it even without new readings
    }

    override suspend fun stop() {
        senderJob?.cancel()
        senderJob = null
        publisher?.let { p ->
            runCatching {
                WebhookPublisher(p.url, timeoutMs = 5_000)
                    .post(WebhookPayload.unavailable(config.deviceName, System.currentTimeMillis(), diagnostics))
            }
        }
        publisher = null
        onStatus(ConnectionStatus.DISCONNECTED)
    }
}
