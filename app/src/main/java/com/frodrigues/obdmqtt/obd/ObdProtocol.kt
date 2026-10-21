package com.frodrigues.obdmqtt.obd

/** ELM327 protocol numbers (AT SP n). The Peugeot 407 exists in both CAN and KWP variants: AUTO handles both. */
enum class ObdProtocol(val code: String, val label: String) {
    AUTO("0", "Automatique (recommandé)"),
    ISO_15765_CAN_11_500("6", "ISO 15765-4 CAN 11 bits 500 kbit/s"),
    ISO_15765_CAN_29_500("7", "ISO 15765-4 CAN 29 bits 500 kbit/s"),
    ISO_15765_CAN_11_250("8", "ISO 15765-4 CAN 11 bits 250 kbit/s"),
    ISO_14230_KWP_FAST("5", "ISO 14230-4 KWP2000 (init rapide)"),
    ISO_14230_KWP_5BAUD("4", "ISO 14230-4 KWP2000 (init 5 bauds)"),
    ISO_9141_2("3", "ISO 9141-2");

    companion object {
        /** Parses an `AT DPN` reply such as "A6" (auto-detected CAN) or "5". */
        fun fromDpn(response: String): ObdProtocol? {
            val code = response.trim().uppercase().removePrefix("A").take(1)
            return entries.firstOrNull { it != AUTO && it.code == code }
        }
    }
}
