package com.frodrigues.obdmqtt.obd

object PidParser {

    private val VOLTAGE_REGEX = Regex("""(\d{1,2}(?:[.,]\d+)?)\s*V""", RegexOption.IGNORE_CASE)

    fun parse(pid: Int, response: String): Double? {
        if (pid == PidRegistry.ADAPTER_VOLTAGE) {
            return VOLTAGE_REGEX.find(response)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
        }
        val bytes = ObdResponseParser.extractDataBytes(response, pid) ?: return null
        return runCatching { PidRegistry.getOrUnknown(pid).formula(bytes) }.getOrNull()
    }
}
