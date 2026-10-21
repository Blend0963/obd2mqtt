package com.frodrigues.obdmqtt.webhook

import com.frodrigues.obdmqtt.obd.DiagnosticReport
import com.frodrigues.obdmqtt.obd.DtcDescriptions
import com.frodrigues.obdmqtt.util.jsonString
import com.frodrigues.obdmqtt.util.pidHex
import com.frodrigues.obdmqtt.util.toPlainValue

/**
 * JSON bodies sent to the HA webhook. Each reading payload carries the full
 * snapshot of current values, so a dropped request (tunnel, no signal) is
 * simply superseded by the next one — no buffering needed.
 *
 * ```
 * {"available":true,"ts":1729500000000,"device":"Peugeot 407","pids":{"0C":850,"0D":42},
 *  "diag":{"mil":false,"codes":[],"pending":[],"incomplete_monitors":[],"monitors":[],"ts":…}}
 * ```
 * "diag" is present once the fault codes have been read, and is kept in the
 * "unavailable" payload so HA still shows the last known faults while parked.
 */
object WebhookPayload {

    fun readings(
        pids: Map<Int, Double>,
        deviceName: String,
        timestampMs: Long,
        diag: DiagnosticReport? = null
    ): String = buildString {
        append("{\"available\":true")
        append(",\"ts\":$timestampMs")
        append(",\"device\":${jsonString(deviceName)}")
        append(",\"pids\":{")
        pids.entries
            .filter { it.value.isFinite() }
            .sortedBy { it.key }
            .joinTo(this, ",") { (pid, value) -> "\"${pidHex(pid)}\":${value.toPlainValue()}" }
        append("}")
        diag?.let { append(",\"diag\":").append(diagnostics(it)) }
        append("}")
    }

    /** Marks live sensors unavailable in HA (service stopped / BT lost). */
    fun unavailable(deviceName: String, timestampMs: Long, diag: DiagnosticReport? = null): String = buildString {
        append("{\"available\":false,\"ts\":$timestampMs,\"device\":${jsonString(deviceName)},\"pids\":{}")
        diag?.let { append(",\"diag\":").append(diagnostics(it)) }
        append("}")
    }

    fun diagnostics(report: DiagnosticReport): String = buildString {
        append("{\"mil\":${report.milOn ?: "null"}")
        append(",\"codes\":").append(codes(report.codes))
        append(",\"pending\":").append(codes(report.pending))
        append(",\"incomplete_monitors\":[")
        report.incompleteMonitors.joinTo(this, ",") { jsonString(it) }
        append("],\"monitors\":[")
        report.monitors.joinTo(this, ",") { "{\"name\":${jsonString(it.name)},\"complete\":${it.complete}}" }
        append("],\"ts\":${report.timestamp}")
        report.lastClear?.let {
            append(",\"last_clear\":{\"success\":${it.success},\"message\":${jsonString(it.message)},\"ts\":${it.timestamp}}")
        }
        append("}")
    }

    private fun codes(list: List<String>) = list.joinToString(",", "[", "]") {
        "{\"code\":${jsonString(it)},\"description\":${jsonString(DtcDescriptions.describe(it))}}"
    }

    /** Connectivity check — has no "pids" key, so the HA template condition ignores it. */
    fun test(): String = "{\"test\":true}"
}
