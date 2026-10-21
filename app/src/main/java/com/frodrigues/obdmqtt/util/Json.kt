package com.frodrigues.obdmqtt.util

fun String.jsonEscape(): String =
    replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

fun jsonString(value: String): String = "\"${value.jsonEscape()}\""

/** Formats a reading the same way for MQTT and webhook payloads: "850", "12.5". */
fun Double.toPlainValue(): String = toBigDecimal().stripTrailingZeros().toPlainString()

fun pidHex(pid: Int): String = pid.toString(16).padStart(2, '0').uppercase()
