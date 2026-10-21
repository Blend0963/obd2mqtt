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
    private val protocol: ObdProtocol = ObdProtocol.AUTO,
    /** Protocol that worked last time (AUTO mode): tried first to connect faster. */
    private val lastWorking: ObdProtocol? = null
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

    /**
     * Selects the protocol and wakes the engine ECU. Throws if it does not answer.
     *
     * In AUTO mode the adapter's own search (AT SP 0) is tried after [lastWorking], then
     * each protocol explicitly: the ELM327 search often fails on older K-line ECUs (PSA
     * KWP2000) that answer fine once the protocol is forced.
     */
    suspend fun connectEcu() {
        val attempts = if (protocol != ObdProtocol.AUTO) listOf(protocol)
                       else (listOfNotNull(lastWorking) + ObdProtocol.AUTO + AUTO_FALLBACK).distinct()
        attempts.forEachIndexed { index, p ->
            if (index > 0) {
                DebugLog.add("Essai du protocole : ${p.label}")
                runCatching { raw("ATPC") }  // close the failed session before the next init
            }
            if (probe(p)) {
                detectedProtocol = if (p == ObdProtocol.AUTO) ObdProtocol.fromDpn(raw("ATDPN")) else p
                Log.d(TAG, "Protocol: ${detectedProtocol?.label}")
                return
            }
        }
        throw IOException(
            if (protocol == ObdProtocol.AUTO) "Le calculateur ne répond sur aucun protocole (contact mis ?)"
            else "Le calculateur ne répond pas en ${protocol.label} (contact mis ?)"
        )
    }

    /** True when the ECU answers 01 00 with [p]; a timeout counts as no answer. */
    private suspend fun probe(p: ObdProtocol): Boolean {
        raw("ATSP${p.code}")
        raw("ATAT1")
        // First request wakes the ECU: protocol search (auto) or KWP 5-baud init can take >10 s
        val reply = try { raw("0100", PROBE_TIMEOUT_MS) } catch (e: IOException) { return false }
        return ObdResponseParser.extractDataBytes(reply, 0x00) != null
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

        /** Explicit protocols tried when the adapter's automatic search fails (older cars first). */
        val AUTO_FALLBACK = listOf(
            ObdProtocol.ISO_14230_KWP_FAST,
            ObdProtocol.ISO_14230_KWP_5BAUD,
            ObdProtocol.ISO_9141_2,
            ObdProtocol.ISO_15765_CAN_11_500,
            ObdProtocol.ISO_15765_CAN_29_500,
            ObdProtocol.ISO_15765_CAN_11_250
        )
    }
}
