package com.frodrigues.obdmqtt.webhook

import com.frodrigues.obdmqtt.obd.PidRegistry
import com.frodrigues.obdmqtt.settings.AppConfig
import com.frodrigues.obdmqtt.util.pidHex

/**
 * Generates the Home Assistant package (YAML) that receives [WebhookPayload] bodies:
 * trigger-based template sensors for live values and diagnostics, the "clear faults"
 * input_button read back by the app, and a notification automation for new faults.
 */
object HaWebhookYaml {

    /** `{% set d = … %}` prefix: payloads without "diag" render as empty diagnostics. */
    private const val DIAG = "{% set d = trigger.json.diag if trigger.json.diag is mapping else {} %}"

    fun generate(config: AppConfig, pids: Set<Int>): String = buildString {
        val ha = HaEntities(config)
        val mac = ha.mac
        appendLine("# ${config.deviceName} — OBD2 → Home Assistant (webhook HTTPS)")
        appendLine("# Fichier de package : /config/packages/${HaEntities.slugify(config.deviceName)}.yaml")
        appendLine("# configuration.yaml doit contenir (une seule fois) :")
        appendLine("#   homeassistant:")
        appendLine("#     packages: !include_dir_named packages")
        appendLine("# puis redémarrer Home Assistant (ou recharger la configuration YAML).")
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
            appendLine("      - name: ${yamlString(ha.pidName(pid))}")
            appendLine("        unique_id: ${yamlString("obd2_${mac}_$hex")}")
            if (def.unit.isNotBlank()) appendLine("        unit_of_measurement: ${yamlString(def.unit)}")
            def.haDeviceClass?.let { appendLine("        device_class: $it") }
            // Odometer-style counters only grow (or reset), which HA statistics handle as total_increasing
            val stateClass = if (def.haDeviceClass == "distance") "total_increasing" else "measurement"
            appendLine("        state_class: $stateClass")
            appendLine("        availability: \"{{ trigger.json.available | default(false) and '$hex' in trigger.json.pids }}\"")
            appendLine("        state: \"{{ trigger.json.pids.get('$hex') }}\"")
        }

        // ── Diagnostics: stay available with the last known values while the car is parked
        val diagAvailable = "\"{{ trigger.json.diag is mapping }}\""
        appendLine("      - name: ${yamlString(ha.faultCountName)}")
        appendLine("        unique_id: ${yamlString("obd2_${mac}_dtc_count")}")
        appendLine("        icon: mdi:engine-outline")
        appendLine("        availability: $diagAvailable")
        appendLine("        state: \"$DIAG{{ (d.codes or []) | count }}\"")
        appendLine("        attributes:")
        appendLine("          codes: \"$DIAG{{ d.codes or [] }}\"")
        appendLine("          dernier_effacement: \"$DIAG{{ d.last_clear }}\"")
        appendLine("      - name: ${yamlString(ha.faultCodesName)}")
        appendLine("        unique_id: ${yamlString("obd2_${mac}_dtc_codes")}")
        appendLine("        icon: mdi:car-wrench")
        appendLine("        availability: $diagAvailable")
        appendLine("        state: \"$DIAG{{ ((d.codes or []) | map(attribute='code') | join(', ') or 'Aucun')[:255] }}\"")
        appendLine("      - name: ${yamlString(ha.pendingName)}")
        appendLine("        unique_id: ${yamlString("obd2_${mac}_dtc_pending")}")
        appendLine("        icon: mdi:engine-outline")
        appendLine("        availability: $diagAvailable")
        appendLine("        state: \"$DIAG{{ (d.pending or []) | count }}\"")
        appendLine("        attributes:")
        appendLine("          codes: \"$DIAG{{ d.pending or [] }}\"")
        appendLine("      - name: ${yamlString(ha.readinessName)}")
        appendLine("        unique_id: ${yamlString("obd2_${mac}_readiness")}")
        appendLine("        icon: mdi:clipboard-check-outline")
        appendLine("        availability: $diagAvailable")
        appendLine("        state: \"$DIAG{{ (d.incomplete_monitors or []) | count }}\"")
        appendLine("        attributes:")
        appendLine("          tests_incomplets: \"$DIAG{{ d.incomplete_monitors or [] }}\"")
        appendLine("          tests: \"$DIAG{{ d.monitors or [] }}\"")
        appendLine("    binary_sensor:")
        appendLine("      - name: ${yamlString(ha.milName)}")
        appendLine("        unique_id: ${yamlString("obd2_${mac}_mil")}")
        appendLine("        device_class: problem")
        appendLine("        availability: \"$DIAG{{ d.mil is defined and d.mil is not none }}\"")
        appendLine("        state: \"$DIAG{{ d.mil }}\"")

        appendLine()
        appendLine("# Bouton d'effacement : l'application le lit avec le jeton d'accès renseigné dans")
        appendLine("# ses réglages, puis efface les défauts (contact mis, moteur arrêté de préférence).")
        appendLine("input_button:")
        appendLine("  ${ha.clearButtonKey}:")
        appendLine("    name: ${yamlString(ha.clearButtonName)}")
        appendLine("    icon: mdi:broom")

        appendLine()
        appendLine("# Notification à l'apparition d'un nouveau défaut. Remplacez l'action par")
        appendLine("# notify.mobile_app_<votre_téléphone> pour la recevoir sur le téléphone.")
        appendLine("automation:")
        appendLine("  - id: ${yamlString("obd2_${mac}_nouveau_defaut")}")
        appendLine("    alias: ${yamlString("${config.deviceName} — nouveau défaut moteur")}")
        appendLine("    triggers:")
        appendLine("      - trigger: state")
        appendLine("        entity_id: ${ha.faultCountEntityId}")
        appendLine("    conditions:")
        appendLine("      - condition: template")
        appendLine("        value_template: >-")
        appendLine("          {{ trigger.from_state is not none")
        appendLine("             and trigger.from_state.state | is_number")
        appendLine("             and trigger.to_state.state | is_number")
        appendLine("             and trigger.to_state.state | int > trigger.from_state.state | int }}")
        appendLine("    actions:")
        appendLine("      - action: persistent_notification.create")
        appendLine("        data:")
        appendLine("          title: ${yamlString("${config.deviceName} : défaut moteur")}")
        appendLine("          message: |")
        appendLine("            {% for c in state_attr('${ha.faultCountEntityId}', 'codes') or [] %}")
        appendLine("            {{ c.code }} : {{ c.description }}")
        appendLine("            {% endfor %}")
    }

    private fun yamlString(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
