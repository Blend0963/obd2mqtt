package com.frodrigues.obdmqtt.webhook

import com.frodrigues.obdmqtt.obd.PidRegistry
import com.frodrigues.obdmqtt.settings.AppConfig
import java.text.Normalizer

/**
 * Generates a Lovelace dashboard (raw configuration editor YAML) for the sensors
 * created by [HaWebhookYaml]. Entity IDs are derived from sensor names exactly
 * like Home Assistant does: "Peugeot 407 Régime moteur" → sensor.peugeot_407_regime_moteur.
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

    fun entityId(config: AppConfig, pid: Int): String =
        "sensor." + slugify("${config.deviceName} ${PidRegistry.getOrUnknown(pid).name}")

    fun generate(config: AppConfig, pids: Set<Int>): String = buildString {
        appendLine("# Tableau de bord ${config.deviceName}")
        appendLine("# Paramètres → Tableaux de bord → Ajouter → Nouveau tableau de bord,")
        appendLine("# puis ⋮ → Modifier → ⋮ → Éditeur de configuration brute, et coller ce contenu.")
        appendLine("title: ${q(config.deviceName)}")
        appendLine("views:")
        appendLine("  - title: ${q(config.deviceName)}")
        appendLine("    path: ${slugify(config.deviceName).replace('_', '-').ifBlank { "voiture" }}")
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

    /** Same rules as Home Assistant's slugify: strip accents, lowercase, non-alphanumerics → "_". */
    fun slugify(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')

    private fun q(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
