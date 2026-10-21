package com.frodrigues.obdmqtt.obd

import android.util.Log
import com.frodrigues.obdmqtt.bluetooth.BluetoothTransport
import kotlinx.coroutines.delay
import java.io.IOException

class ObdCommandExecutor(
    private val transport: BluetoothTransport,
    private val protocol: ObdProtocol = ObdProtocol.AUTO
) {

    /** Protocol actually used by the ECU, known after [initialize]. */
    var detectedProtocol: ObdProtocol? = null
        private set

    suspend fun initialize() {
        val atzResponse = transport.sendCommand("ATZ")
        Log.d(TAG, "ATZ: $atzResponse")
        delay(1000)
        if (!atzResponse.contains("ELM", ignoreCase = true) &&
            !atzResponse.contains("OK", ignoreCase = true)) {
            throw IOException("Adaptateur ELM327 non détecté. Réponse : $atzResponse")
        }
        Log.d(TAG, "ATE0: ${transport.sendCommand("ATE0")}")
        Log.d(TAG, "ATL0: ${transport.sendCommand("ATL0")}")
        // Headers off: KWP (ISO 14230) headers + checksum would otherwise mix with data bytes
        Log.d(TAG, "ATH0: ${transport.sendCommand("ATH0")}")
        Log.d(TAG, "ATSP${protocol.code}: ${transport.sendCommand("ATSP${protocol.code}")}")
        Log.d(TAG, "ATAT1: ${transport.sendCommand("ATAT1")}")

        // First request wakes the ECU: protocol search (auto) or KWP 5-baud init can take >10 s
        val probe = transport.sendCommand("0100", timeoutMs = PROBE_TIMEOUT_MS)
        Log.d(TAG, "0100: $probe")
        if (ObdResponseParser.extractDataBytes(probe, 0x00) == null) {
            throw IOException("Le calculateur ne répond pas (contact mis ?). Réponse : ${probe.take(40)}")
        }
        detectedProtocol = ObdProtocol.fromDpn(transport.sendCommand("ATDPN"))
        Log.d(TAG, "Protocol: ${detectedProtocol?.label}")
    }

    suspend fun sendCommand(command: String): String = transport.sendCommand(command)

    companion object {
        private const val TAG = "ObdCommandExecutor"
        private const val PROBE_TIMEOUT_MS = 30_000L
    }
}
