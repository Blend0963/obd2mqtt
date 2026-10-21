package com.frodrigues.obdmqtt.webhook

import com.frodrigues.obdmqtt.obd.PidRegistry
import com.frodrigues.obdmqtt.settings.AppConfig
import com.frodrigues.obdmqtt.util.pidHex

/**
 * Generates the Home Assistant YAML (trigger-based template sensors) that
 * receives [WebhookPayload] bodies. Paste it into configuration.yaml or a package.
 */
object HaWebhookYaml {

    fun generate(config: AppConfig, pids: Set<Int>): String = buildString {
        val mac = config.btDeviceMac.replace(":", "").ifBlank { "car" }
        appendLine("# ${config.deviceName} — OBD2 → Home Assistant (webhook HTTPS)")
        appendLine("# À coller dans configuration.yaml (ou un fichier de /config/packages/),")
        appendLine("# puis Outils de développement → YAML → Entités de modèle (recharger).")
        appendLine("# L'ID du webhook ci-dessous est secret : traitez-le comme un mot de passe.")
        appendLine("template:")
        appendLine("  - triggers:")
        appendLine("      - trigger: webhook")
        appendLine("        webhook_id: ${yamlString(config.webhookId)}")
        appendLine("        allowed_methods: [POST]")
        appendLine("        local_only: false")
        appendLine("    conditions:")
        appendLine("      - condition: template")
        appendLine("        value_template: \"{{ trigger.json is mapping and trigger.json.pids is mapping }}\"")
        appendLine("    sensor:")
        pids.sorted().forEach { pid ->
            val def = PidRegistry.getOrUnknown(pid)
            val hex = pidHex(pid)
            appendLine("      - name: ${yamlString("${config.deviceName} ${def.name}")}")
            appendLine("        unique_id: ${yamlString("obd2_${mac}_$hex")}")
            if (def.unit.isNotBlank()) appendLine("        unit_of_measurement: ${yamlString(def.unit)}")
            def.haDeviceClass?.let { appendLine("        device_class: $it") }
            // Odometer-style counters only grow (or reset), which HA statistics handle as total_increasing
            val stateClass = if (def.haDeviceClass == "distance") "total_increasing" else "measurement"
            appendLine("        state_class: $stateClass")
            appendLine("        availability: \"{{ trigger.json.available | default(false) and '$hex' in trigger.json.pids }}\"")
            appendLine("        state: \"{{ trigger.json.pids.get('$hex') }}\"")
        }
    }

    private fun yamlString(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
