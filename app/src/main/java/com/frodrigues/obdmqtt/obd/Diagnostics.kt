package com.frodrigues.obdmqtt.obd

import android.util.Log

/** One emission readiness test (PID 01 01, bytes B–D). */
data class ReadinessMonitor(val name: String, val complete: Boolean)

/** Outcome of a Mode 04 "clear DTCs" request. */
data class ClearResult(val success: Boolean, val message: String, val timestamp: Long = System.currentTimeMillis())

/** Snapshot of the engine ECU diagnostics, read periodically by the service. */
data class DiagnosticReport(
    /** Check-engine light (MIL); null when PID 01 01 is not answered. */
    val milOn: Boolean?,
    /** Confirmed (stored) fault codes, Mode 03. */
    val codes: List<String>,
    /** Pending fault codes, Mode 07 (detected once, not confirmed yet). */
    val pending: List<String>,
    /** Readiness tests supported by the ECU. */
    val monitors: List<ReadinessMonitor>,
    val timestamp: Long = System.currentTimeMillis(),
    val lastClear: ClearResult? = null
) {
    val incompleteMonitors: List<String> get() = monitors.filterNot { it.complete }.map { it.name }
}

object DtcParser {

    private val FRAME_INDEX = Regex("^([0-9A-F]):\\s*(.*)$")
    private val HEX_LINE = Regex("^[0-9A-F ]+$")

    /**
     * Extracts fault codes from a Mode 03 / 07 / 0A reply (headers off).
     *
     * - CAN (ISO 15765): `43 02 01 33 04 20` — a count byte follows the mode byte; long
     *   lists arrive as multi-frame `0: …` / `1: …` lines after a byte-count line.
     * - KWP2000 / ISO 9141: `43 01 33 04 20 00 00` — 3 codes per line, zero-padded,
     *   one line per message.
     *
     * [isCan] comes from the detected protocol; when unknown, the byte-count parity decides.
     */
    fun parse(response: String, mode: Int, isCan: Boolean?): List<String> {
        val messages = mutableListOf<MutableList<Int>>()
        response.uppercase().split('\r', '\n').map { it.trim() }.forEach { line ->
            val frame = FRAME_INDEX.find(line)
            when {
                frame != null -> {
                    val bytes = hexBytes(frame.groupValues[2]) ?: return@forEach
                    if (frame.groupValues[1] == "0" || messages.isEmpty()) messages.add(bytes.toMutableList())
                    else messages.last().addAll(bytes)
                }
                !HEX_LINE.matches(line) -> Unit                      // NO DATA, SEARCHING..., BUS INIT
                line.replace(" ", "").length == 3 -> Unit            // multi-frame byte count, e.g. "00A"
                else -> hexBytes(line)?.let { messages.add(it.toMutableList()) }
            }
        }

        val responseMode = 0x40 + mode
        return messages
            .filter { it.firstOrNull() == responseMode }
            .flatMap { message ->
                val rest = message.drop(1)
                val hasCount = isCan ?: (rest.size % 2 == 1)
                val pairs = if (hasCount) rest.drop(1).take(2 * (rest.firstOrNull() ?: 0)) else rest
                pairs.chunked(2).filter { it.size == 2 && (it[0] != 0 || it[1] != 0) }.map { decode(it[0], it[1]) }
            }
            .distinct()
    }

    /** `01 33` → "P0133", `C1 23` → "U0123". */
    fun decode(b1: Int, b2: Int): String {
        val letter = "PCBU"[(b1 shr 6) and 0x3]
        return "%c%d%X%02X".format(letter, (b1 shr 4) and 0x3, b1 and 0xF, b2)
    }

    private fun hexBytes(text: String): List<Int>? {
        val compact = text.replace(" ", "")
        if (compact.isEmpty() || compact.length % 2 != 0) return null
        return compact.chunked(2).map { it.toIntOrNull(16) ?: return null }
    }
}

object ReadinessParser {

    private val SPARK = listOf(
        "Catalyseur", "Catalyseur chauffé", "Système d'évaporation", "Air secondaire",
        null, "Sonde lambda", "Chauffage sonde lambda", "EGR / distribution variable"
    )
    private val COMPRESSION = listOf(
        "Catalyseur NMHC", "Réduction des NOx", null, "Pression de suralimentation",
        null, "Capteur gaz d'échappement", "Filtre à particules", "EGR / distribution variable"
    )

    /** Parses PID 01 01 data bytes A B C D into MIL state and supported readiness tests. */
    fun parse(bytes: ByteArray): Pair<Boolean, List<ReadinessMonitor>>? {
        if (bytes.size < 4) return null
        val a = bytes.u(0); val b = bytes.u(1); val c = bytes.u(2); val d = bytes.u(3)
        val monitors = mutableListOf<ReadinessMonitor>()
        listOf("Ratés d'allumage", "Système carburant", "Composants").forEachIndexed { i, name ->
            if (b and (1 shl i) != 0) monitors += ReadinessMonitor(name, complete = b and (1 shl (i + 4)) == 0)
        }
        val names = if (b and 0x08 != 0) COMPRESSION else SPARK
        names.forEachIndexed { i, name ->
            if (name != null && c and (1 shl i) != 0) monitors += ReadinessMonitor(name, complete = d and (1 shl i) == 0)
        }
        return (a and 0x80 != 0) to monitors
    }
}

class DtcReader(private val executor: ObdCommandExecutor) {

    private val isCan: Boolean?
        get() = executor.detectedProtocol?.code?.let { it in setOf("6", "7", "8", "9") }

    suspend fun read(lastClear: ClearResult? = null): DiagnosticReport {
        val status = ObdResponseParser.extractDataBytes(executor.sendCommand("0101"), 0x01)
            ?.let { ReadinessParser.parse(it) }
        val codes = DtcParser.parse(executor.sendCommand("03"), 0x03, isCan)
        val pending = DtcParser.parse(executor.sendCommand("07"), 0x07, isCan)
        Log.d(TAG, "MIL=${status?.first} codes=$codes pending=$pending")
        return DiagnosticReport(
            milOn = status?.first,
            codes = codes,
            pending = pending,
            monitors = status?.second.orEmpty(),
            lastClear = lastClear
        )
    }

    /** Mode 04: clears stored codes, freeze frames and readiness tests. */
    suspend fun clear(): ClearResult {
        val response = executor.sendCommand("04", timeoutMs = 10_000).uppercase().replace(Regex("\\s+"), "")
        Log.d(TAG, "04: $response")
        return when {
            response.contains("44") -> ClearResult(true, "Défauts effacés")
            response.contains("7F0422") -> ClearResult(false, "Refusé par le calculateur : coupez le moteur en laissant le contact")
            response.contains("7F04") -> ClearResult(false, "Refusé par le calculateur (${response.take(12)})")
            else -> ClearResult(false, "Pas de réponse du calculateur (contact mis ?)")
        }
    }

    companion object {
        private const val TAG = "DtcReader"
    }
}
