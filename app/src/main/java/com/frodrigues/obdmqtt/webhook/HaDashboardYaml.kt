package com.frodrigues.obdmqtt.webhook

import com.frodrigues.obdmqtt.obd.PidRegistry
import com.frodrigues.obdmqtt.settings.AppConfig

/**
 * Generates a Lovelace dashboard (raw configuration editor YAML) for the sensors
 * created by [HaWebhookYaml], with entity IDs from [HaEntities].
 */
object HaDashboardYaml {

    /** [severity]: colour → value from which it applies, as HA's gauge card expects. */
    private data class Gauge(
        val pid: Int,
        val title: String,
        val min: Int,
        val max: Int,
        val severity: List<Pair<String, Number>> = emptyList()
    )

    private val GAUGES = listOf(
        Gauge(0x0C, "Régime", 0, 7000, listOf("green" to 0, "yellow" to 4500, "red" to 5500)),
        Gauge(0x0D, "Vitesse", 0, 220),
        Gauge(0x05, "Liquide de refroidissement", 0, 130, listOf("green" to 0, "yellow" to 100, "red" to 110)),
        Gauge(PidRegistry.ADAPTER_VOLTAGE, "Batterie", 10, 16, listOf("red" to 10, "yellow" to 11.8, "green" to 12.4)),
        Gauge(0x04, "Charge moteur", 0, 100),
        Gauge(0x11, "Papillon", 0, 100),
        Gauge(0x2F, "Carburant", 0, 100, listOf("red" to 0, "yellow" to 15, "green" to 25)),
        Gauge(0x0B, "Pression admission", 0, 255),
    )

    private val HISTORY_GROUPS = listOf(
        "Conduite" to listOf(0x0C, 0x0D),
        "Températures" to listOf(0x05, 0x0F, 0x5C, 0x46),
        "Moteur" to listOf(0x04, 0x11, 0x0B, 0x10),
    )

    fun entityId(config: AppConfig, pid: Int): String = HaEntities(config).pidEntityId(pid)

    fun generate(config: AppConfig, pids: Set<Int>): String = buildString {
        val ha = HaEntities(config)
        appendLine("# Tableau de bord ${config.deviceName}")
        appendLine("# Paramètres → Tableaux de bord → Ajouter → Nouveau tableau de bord,")
        appendLine("# puis ⋮ → Modifier → ⋮ → Éditeur de configuration brute, et coller ce contenu.")
        appendLine("title: ${q(config.deviceName)}")
        appendLine("views:")
        appendLine("  - title: ${q(config.deviceName)}")
        appendLine("    path: ${HaEntities.slugify(config.deviceName).replace('_', '-').ifBlank { "voiture" }}")
        appendLine("    icon: mdi:car")
        appendLine("    cards:")

        val gauges = GAUGES.filter { it.pid in pids }
        if (gauges.isNotEmpty()) {
            appendLine("      - type: grid")
            appendLine("        columns: 2")
            appendLine("        square: false")
            appendLine("        cards:")
            gauges.forEach { g ->
                appendLine("          - type: gauge")
                appendLine("            entity: ${entityId(config, g.pid)}")
                appendLine("            name: ${q(g.title)}")
                appendLine("            min: ${g.min}")
                appendLine("            max: ${g.max}")
                appendLine("            needle: true")
                if (g.severity.isNotEmpty()) {
                    appendLine("            severity:")
                    g.severity.forEach { (colour, from) -> appendLine("              $colour: $from") }
                }
            }
        }

        appendDiagnostics(ha, withClearButton = config.haToken.isNotBlank())

        HISTORY_GROUPS.forEach { (title, group) ->
            val present = group.filter { it in pids }
            if (present.isEmpty()) return@forEach
            appendLine("      - type: history-graph")
            appendLine("        title: ${q(title)}")
            appendLine("        hours_to_show: 1")
            appendLine("        entities:")
            present.forEach { appendLine("          - entity: ${entityId(config, it)}") }
        }

        if (pids.isNotEmpty()) {
            appendLine("      - type: entities")
            appendLine("        title: Toutes les mesures")
            appendLine("        show_header_toggle: false")
            appendLine("        entities:")
            pids.sortedBy { PidRegistry.getOrUnknown(it).name }.forEach { pid ->
                appendLine("          - entity: ${entityId(config, pid)}")
                appendLine("            name: ${q(PidRegistry.getOrUnknown(pid).name)}")
            }
        }
    }

    private fun StringBuilder.appendDiagnostics(ha: HaEntities, withClearButton: Boolean) {
        appendLine("      - type: entities")
        appendLine("        title: Diagnostic moteur")
        appendLine("        show_header_toggle: false")
        appendLine("        entities:")
        appendLine("          - entity: ${ha.milEntityId}")
        appendLine("            name: Voyant moteur")
        appendLine("          - entity: ${ha.faultCodesEntityId}")
        appendLine("            name: Codes défaut")
        appendLine("          - entity: ${ha.pendingEntityId}")
        appendLine("            name: Défauts en attente")
        appendLine("          - entity: ${ha.readinessEntityId}")
        appendLine("            name: Tests antipollution incomplets")
        appendLine("      - type: markdown")
        appendLine("        title: Détail des défauts")
        // Literal block: one markdown list item per line
        appendLine("        content: |")
        appendLine("          {% set codes = state_attr('${ha.faultCountEntityId}', 'codes') or [] %}")
        appendLine("          {% set pending = state_attr('${ha.pendingEntityId}', 'codes') or [] %}")
        appendLine("          {% for c in codes %}")
        appendLine("          - **{{ c.code }}** — {{ c.description }}")
        appendLine("          {% endfor %}")
        appendLine("          {% for c in pending %}")
        appendLine("          - *{{ c.code }} (en attente)* — {{ c.description }}")
        appendLine("          {% endfor %}")
        appendLine("          {% if not codes and not pending %}Aucun défaut mémorisé ✅{% endif %}")
        appendLine("          {% set clear = state_attr('${ha.faultCountEntityId}', 'dernier_effacement') %}")
        appendLine("          {% if clear is mapping %}")
        appendLine("")
        appendLine("          _Dernier effacement : {{ clear.message }}_")
        appendLine("          {% endif %}")
        if (withClearButton) {
            appendLine("      - type: button")
            appendLine("        name: Effacer les défauts")
            appendLine("        icon: mdi:broom")
            appendLine("        show_state: false")
            appendLine("        tap_action:")
            appendLine("          action: perform-action")
            appendLine("          perform_action: input_button.press")
            appendLine("          target:")
            appendLine("            entity_id: ${ha.clearButtonEntityId}")
            appendLine("          confirmation:")
            appendLine("            text: >-")
            appendLine("              Effacer les codes défaut ? Mettez le contact (moteur arrêté). Les tests")
            appendLine("              antipollution seront remis à zéro et le voyant se rallumera si le défaut persiste.")
        }
    }

    private fun q(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
