package com.frodrigues.obdmqtt.webhook

import com.frodrigues.obdmqtt.util.jsonString
import com.frodrigues.obdmqtt.util.pidHex
import com.frodrigues.obdmqtt.util.toPlainValue

/**
 * JSON bodies sent to the HA webhook. Each reading payload carries the full
 * snapshot of current values, so a dropped request (tunnel, no signal) is
 * simply superseded by the next one — no buffering needed.
 *
 * ```
 * {"available":true,"ts":1729500000000,"device":"Peugeot 407","pids":{"0C":850,"0D":42}}
 * ```
 */
object WebhookPayload {

    fun readings(pids: Map<Int, Double>, deviceName: String, timestampMs: Long): String =
        buildString {
            append("{\"available\":true")
            append(",\"ts\":$timestampMs")
            append(",\"device\":${jsonString(deviceName)}")
            append(",\"pids\":{")
            pids.entries
                .filter { it.value.isFinite() }
                .sortedBy { it.key }
                .joinTo(this, ",") { (pid, value) -> "\"${pidHex(pid)}\":${value.toPlainValue()}" }
            append("}}")
        }

    /** Marks every sensor unavailable in HA (service stopped / BT lost). */
    fun unavailable(deviceName: String, timestampMs: Long): String =
        "{\"available\":false,\"ts\":$timestampMs,\"device\":${jsonString(deviceName)},\"pids\":{}}"

    /** Connectivity check — has no "pids" key, so the HA template condition ignores it. */
    fun test(): String = "{\"test\":true}"
}
