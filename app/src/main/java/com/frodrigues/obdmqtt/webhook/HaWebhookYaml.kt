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

    /**
     * The package also fires every [TICK] seconds without a payload: live readings not
     * refreshed for [STALE_S] seconds then turn unavailable, instead of showing the values
     * of the last drive forever (the app stops sending when the car is switched off, and
     * often cannot say so). Diagnostics and the trip keep their last value on a tick.
     */
    private const val TICK = "/20"
    private const val STALE_S = 60
    private const val HOOK = "trigger.platform == 'webhook'"
    /** Availability of a live reading on a tick: refreshed less than [STALE_S] s ago. */
    private const val FRESH = "(this.attributes.get('recu') is not none and (now() - as_datetime(this.attributes.recu)).total_seconds() < $STALE_S)"
    private const val RECU = "\"{{ now().isoformat() if $HOOK else this.attributes.get('recu') }}\""
    /** A template rendered from the payload, or the entity's own value kept on a tick. */
    private fun hookOr(fromPayload: String, kept: String) = "{% if $HOOK %}$fromPayload{% else %}$kept{% endif %}"

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
        appendLine("      # Sans message de la voiture depuis ${STALE_S} s : mesures en direct indisponibles")
        appendLine("      - trigger: time_pattern")
        appendLine("        seconds: \"$TICK\"")
        appendLine("    conditions:")
        appendLine("      - condition: template")
        appendLine("        value_template: \"{{ not ($HOOK) or (trigger.json is mapping and trigger.json.pids is mapping) }}\"")
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
            appendLine("        availability: \"{{ (trigger.json.available | default(false) and '$hex' in trigger.json.pids) if $HOOK else $FRESH }}\"")
            appendLine("        state: \"{{ trigger.json.pids.get('$hex') if $HOOK else this.state }}\"")
            appendLine("        attributes:")
            appendLine("          recu: $RECU")
        }

        // ── Diagnostics: stay available with the last known values while the car is parked
        val diagAvailable = "\"{{ (trigger.json.diag is mapping) if $HOOK else this.state not in ['unknown', 'unavailable'] }}\""
        appendLine("      - name: ${yamlString(ha.faultCountName)}")
        appendLine("        unique_id: ${yamlString("obd2_${mac}_dtc_count")}")
        appendLine("        icon: mdi:engine-outline")
        appendLine("        availability: $diagAvailable")
        appendLine("        state: \"${hookOr("$DIAG{{ (d.codes or []) | count }}", "{{ this.state }}")}\"")
        appendLine("        attributes:")
        appendLine("          codes: \"${hookOr("$DIAG{{ d.codes or [] }}", "{{ this.attributes.get('codes', []) }}")}\"")
        appendLine("          dernier_effacement: \"${hookOr("$DIAG{{ d.last_clear }}", "{{ this.attributes.get('dernier_effacement') }}")}\"")
        appendLine("      - name: ${yamlString(ha.faultCodesName)}")
        appendLine("        unique_id: ${yamlString("obd2_${mac}_dtc_codes")}")
        appendLine("        icon: mdi:car-wrench")
        appendLine("        availability: $diagAvailable")
        appendLine("        state: \"${hookOr("$DIAG{{ ((d.codes or []) | map(attribute='code') | join(', ') or 'Aucun')[:255] }}", "{{ this.state }}")}\"")
        appendLine("      - name: ${yamlString(ha.pendingName)}")
        appendLine("        unique_id: ${yamlString("obd2_${mac}_dtc_pending")}")
        appendLine("        icon: mdi:engine-outline")
        appendLine("        availability: $diagAvailable")
        appendLine("        state: \"${hookOr("$DIAG{{ (d.pending or []) | count }}", "{{ this.state }}")}\"")
        appendLine("        attributes:")
        appendLine("          codes: \"${hookOr("$DIAG{{ d.pending or [] }}", "{{ this.attributes.get('codes', []) }}")}\"")
        appendLine("      - name: ${yamlString(ha.readinessName)}")
        appendLine("        unique_id: ${yamlString("obd2_${mac}_readiness")}")
        appendLine("        icon: mdi:clipboard-check-outline")
        appendLine("        availability: $diagAvailable")
        appendLine("        state: \"${hookOr("$DIAG{{ (d.incomplete_monitors or []) | count }}", "{{ this.state }}")}\"")
        appendLine("        attributes:")
        appendLine("          tests_incomplets: \"${hookOr("$DIAG{{ d.incomplete_monitors or [] }}", "{{ this.attributes.get('tests_incomplets', []) }}")}\"")
        appendLine("          tests: \"${hookOr("$DIAG{{ d.monitors or [] }}", "{{ this.attributes.get('tests', []) }}")}\"")

        // ── Trip: cumulative, so the last one received stays valid while the car is parked
        val trip = "(trigger.json.trip if $HOOK else none)"
        appendLine("      - name: ${yamlString(ha.tripName)}")
        appendLine("        unique_id: ${yamlString("obd2_${mac}_trip")}")
        appendLine("        icon: mdi:map-marker-path")
        appendLine("        unit_of_measurement: \"km\"")
        appendLine("        state: \"{{ $trip.distance_km if $trip is mapping else this.state | float(none) }}\"")
        appendLine("        attributes:")
        appendLine("          trajet: \"{{ $trip if $trip is mapping else this.attributes.get('trajet') }}\"")
        listOf(
            Triple(ha.fuelRateName, "fuel_lh", "L/h"),
            Triple(ha.fuelL100Name, "fuel_l100_now", "L/100km"),
        ).forEach { (name, key, unit) ->
            appendLine("      - name: ${yamlString(name)}")
            appendLine("        unique_id: ${yamlString("obd2_${mac}_$key")}")
            appendLine("        icon: mdi:gas-station")
            appendLine("        unit_of_measurement: ${yamlString(unit)}")
            appendLine("        state_class: measurement")
            appendLine("        availability: \"{{ (trigger.json.available | default(false) and $trip is mapping and $trip.$key is not none) if $HOOK else $FRESH }}\"")
            appendLine("        state: \"{{ $trip.$key if $HOOK else this.state }}\"")
            appendLine("        attributes:")
            appendLine("          recu: $RECU")
        }
        appendLine("    binary_sensor:")
        appendLine("      - name: ${yamlString(ha.milName)}")
        appendLine("        unique_id: ${yamlString("obd2_${mac}_mil")}")
        appendLine("        device_class: problem")
        appendLine("        availability: \"${hookOr("$DIAG{{ d.mil is defined and d.mil is not none }}", "{{ this.state in ['on', 'off'] }}")}\"")
        appendLine("        state: \"${hookOr("$DIAG{{ d.mil }}", "{{ this.state }}")}\"")

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
