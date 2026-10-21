package com.frodrigues.obdmqtt.obd

import android.util.Log
import com.frodrigues.obdmqtt.bluetooth.BluetoothTransport
import com.frodrigues.obdmqtt.bluetooth.DEFAULT_TIMEOUT_MS
import com.frodrigues.obdmqtt.util.DebugLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

class ObdCommandExecutor(
    private val transport: BluetoothTransport,
    private val protocol: ObdProtocol = ObdProtocol.AUTO
) {

    /** Live polling and diagnostics run in separate coroutines: one command/reply at a time. */
    private val lock = Mutex()

    /** Logs every command and reply to [DebugLog] (initialisation, PID scan, diagnostics). */
    @Volatile var logCommands = true

    /** Protocol actually used by the ECU, known after [connectEcu]. */
    var detectedProtocol: ObdProtocol? = null
        private set

    suspend fun initialize() {
        resetAdapter()
        connectEcu()
    }

    /** Resets and configures the ELM327 only — does not talk to the car's ECUs. */
    suspend fun resetAdapter() {
        val atzResponse = raw("ATZ")
        Log.d(TAG, "ATZ: $atzResponse")
        delay(1000)
        if (!atzResponse.contains("ELM", ignoreCase = true) &&
            !atzResponse.contains("OK", ignoreCase = true)) {
            throw IOException("Adaptateur ELM327 non détecté. Réponse : $atzResponse")
        }
        raw("ATE0")
        raw("ATL0")
        // Headers off: KWP (ISO 14230) headers + checksum would otherwise mix with data bytes
        raw("ATH0")
    }

    /** Battery voltage measured by the adapter on the OBD socket (AT RV); wakes no ECU. */
    suspend fun readBatteryVoltage(): Double? =
        PidParser.parse(PidRegistry.ADAPTER_VOLTAGE, raw("ATRV"))

    /** Selects the protocol and wakes the engine ECU. Throws if it does not answer. */
    suspend fun connectEcu() {
        raw("ATSP${protocol.code}")
        raw("ATAT1")

        // First request wakes the ECU: protocol search (auto) or KWP 5-baud init can take >10 s
        val probe = raw("0100", PROBE_TIMEOUT_MS)
        Log.d(TAG, "0100: $probe")
        if (ObdResponseParser.extractDataBytes(probe, 0x00) == null) {
            throw IOException("Le calculateur ne répond pas (contact mis ?). Réponse : ${probe.take(40)}")
        }
        detectedProtocol = ObdProtocol.fromDpn(raw("ATDPN"))
        Log.d(TAG, "Protocol: ${detectedProtocol?.label}")
    }

    suspend fun sendCommand(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): String =
        lock.withLock { raw(command, timeoutMs) }

    private suspend fun raw(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): String {
        val started = System.currentTimeMillis()
        try {
            val reply = transport.sendCommand(command, timeoutMs)
            if (logCommands) {
                DebugLog.add("→ $command  ← ${DebugLog.printable(reply).take(80)}  (${System.currentTimeMillis() - started} ms)")
            }
            return reply
        } catch (e: Exception) {
            DebugLog.add("→ $command  ✗ ${e.message} (${System.currentTimeMillis() - started} ms)")
            throw e
        }
    }

    companion object {
        private const val TAG = "ObdCommandExecutor"
        private const val PROBE_TIMEOUT_MS = 30_000L
    }
}
