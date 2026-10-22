package com.frodrigues.obdmqtt.webhook

import com.frodrigues.obdmqtt.obd.PidRegistry
import com.frodrigues.obdmqtt.settings.AppConfig

/** STANDARD uses built-in cards only; HACS uses nicer community cards (installed via HACS). */
enum class DashboardStyle { STANDARD, HACS }

/**
 * Generates a Lovelace dashboard (raw configuration editor YAML) for the sensors
 * created by [HaWebhookYaml], with entity IDs from [HaEntities]: one view (tab) per
 * theme — overview, driving, engine, battery, diagnostics, history, all values.
 * Cards and views only list PIDs the car actually supports.
 */
object HaDashboardYaml {

    /** HACS frontend cards used by [DashboardStyle.HACS]. */
    val HACS_CARDS = listOf(
        "Modern Circular Gauge (selvalt7/modern-circular-gauge)",
        "mini-graph-card (kalkih/mini-graph-card)"
    )

    private const val BLUE = "#2196f3"
    private const val GREEN = "#4caf50"
    private const val YELLOW = "#ffc107"
    private const val RED = "#f44336"

    /** [segments]: value from which a colour applies, ascending. */
    private data class Gauge(
        val title: String,
        val min: Number,
        val max: Number,
        val segments: List<Pair<Number, String>> = emptyList()
    )

    private val VOLTAGE_SEGMENTS: List<Pair<Number, String>> = listOf(10 to RED, 11.8 to YELLOW, 12.4 to GREEN, 15 to RED)
    private val TRIM_SEGMENTS: List<Pair<Number, String>> = listOf(-25 to RED, -10 to YELLOW, -5 to GREEN, 5 to YELLOW, 10 to RED)

    private val GAUGES: Map<Int, Gauge> = mapOf(
        0x0D to Gauge("Vitesse", 0, 220),
        0x0C to Gauge("Régime", 0, 7000, listOf(0 to GREEN, 4500 to YELLOW, 5500 to RED)),
        0x05 to Gauge("Moteur", 0, 130, listOf(0 to BLUE, 70 to GREEN, 100 to YELLOW, 110 to RED)),
        PidRegistry.ADAPTER_VOLTAGE to Gauge("Batterie", 10, 16, VOLTAGE_SEGMENTS),
        0x42 to Gauge("Calculateur", 10, 16, VOLTAGE_SEGMENTS),
        0x2F to Gauge("Carburant", 0, 100, listOf(0 to RED, 15 to YELLOW, 25 to GREEN)),
        0x11 to Gauge("Papillon", 0, 100),
        0x04 to Gauge("Charge", 0, 100, listOf(0 to GREEN, 85 to YELLOW)),
        0x49 to Gauge("Pédale", 0, 100),
        0x5A to Gauge("Pédale", 0, 100),
        0x0B to Gauge("Admission", 0, 255),
        0x0E to Gauge("Avance", -10, 60),
        0x10 to Gauge("Débit d'air", 0, 200),
        0x62 to Gauge("Couple", 0, 100),
        0x0F to Gauge("Air admission", -20, 80, listOf(-20 to BLUE, 0 to GREEN, 50 to YELLOW, 65 to RED)),
        0x5C to Gauge("Huile", 0, 150, listOf(0 to BLUE, 70 to GREEN, 120 to YELLOW, 135 to RED)),
        0x46 to Gauge("Extérieur", -20, 50),
        0x3C to Gauge("Catalyseur", 0, 1000, listOf(0 to GREEN, 800 to YELLOW, 900 to RED)),
        0x33 to Gauge("Atmosphère", 70, 110),
        0x0A to Gauge("Carburant", 0, 765),
        0x23 to Gauge("Rampe", 0, 200000),
        0x59 to Gauge("Rampe", 0, 200000),
        0x06 to Gauge("Court terme B1", -25, 25, TRIM_SEGMENTS),
        0x07 to Gauge("Long terme B1", -25, 25, TRIM_SEGMENTS),
        0x08 to Gauge("Court terme B2", -25, 25, TRIM_SEGMENTS),
        0x09 to Gauge("Long terme B2", -25, 25, TRIM_SEGMENTS),
    )

    private sealed interface Card
    private data class GaugeGrid(val title: String?, val pids: List<Int>, val columns: Int) : Card
    private data class Graph(val title: String, val pids: List<Int>, val hours: Int) : Card
    private data class Stats(val title: String, val stats: List<Stat>) : Card
    private data class Stat(val pid: Int, val name: String, val type: String, val period: String)
    private data class EntityList(val title: String, val pids: List<Int>) : Card
    private data object DiagnosticsSummary : Card
    private data object DiagnosticsDetail : Card

    private data class View(val title: String, val path: String, val icon: String, val cards: List<Card>)

    private val VIEWS = listOf(
        View("Aperçu", "apercu", "mdi:car", listOf(
            GaugeGrid(null, listOf(0x0D, 0x0C, 0x05, PidRegistry.ADAPTER_VOLTAGE, 0x2F), columns = 3),
            DiagnosticsSummary,
            Graph("Conduite", listOf(0x0D, 0x0C), hours = 1),
        )),
        View("Conduite", "conduite", "mdi:speedometer", listOf(
            GaugeGrid(null, listOf(0x0D, 0x0C), columns = 2),
            GaugeGrid("Moteur", listOf(0x11, 0x04, 0x49, 0x5A, 0x0B, 0x0E, 0x10, 0x62), columns = 4),
            Graph("Vitesse et régime (15 min)", listOf(0x0D, 0x0C), hours = 0),
            Graph("Papillon et charge (15 min)", listOf(0x11, 0x04, 0x5A), hours = 0),
            Stats("Records du jour", listOf(
                Stat(0x0D, "Vitesse max", "max", "day"),
                Stat(0x0C, "Régime max", "max", "day"),
                Stat(0x0D, "Vitesse moyenne", "mean", "day"),
            )),
        )),
        View("Moteur", "moteur", "mdi:engine", listOf(
            GaugeGrid("Températures", listOf(0x05, 0x0F, 0x5C, 0x46, 0x3C), columns = 3),
            Graph("Températures", listOf(0x05, 0x0F, 0x5C, 0x3C, 0x3D, 0x67), hours = 1),
            GaugeGrid("Pressions", listOf(0x0B, 0x33, 0x0A, 0x23, 0x59), columns = 3),
            Graph("Pressions", listOf(0x0B, 0x22, 0x23, 0x59), hours = 1),
            GaugeGrid("Correction de richesse", listOf(0x06, 0x07, 0x08, 0x09), columns = 4),
            Graph("Sondes lambda", listOf(0x14, 0x15, 0x34, 0x44, 0x56), hours = 1),
            EntityList("Autres données moteur", listOf(
                0x43, 0x45, 0x47, 0x48, 0x4C, 0x4A, 0x4B, 0x5D, 0x50, 0x2C, 0x2D, 0x2E, 0x5E, 0x61, 0x63, 0xA4, 0x51, 0x52
            )),
        )),
        View("Batterie", "batterie", "mdi:car-battery", listOf(
            GaugeGrid(null, listOf(PidRegistry.ADAPTER_VOLTAGE, 0x42), columns = 2),
            Graph("Tension (24 h)", listOf(PidRegistry.ADAPTER_VOLTAGE, 0x42), hours = 24),
            Stats("Aujourd'hui", listOf(
                Stat(PidRegistry.ADAPTER_VOLTAGE, "Tension min", "min", "day"),
                Stat(PidRegistry.ADAPTER_VOLTAGE, "Tension max", "max", "day"),
            )),
        )),
        View("Diagnostic", "diagnostic", "mdi:car-wrench", listOf(
            DiagnosticsDetail,
            EntityList("Depuis l'effacement des défauts", listOf(0x31, 0x4E, 0x30, 0x21, 0x4D, 0x1F, 0xA6)),
        )),
        View("Historique", "historique", "mdi:chart-line", listOf(
            Graph("Vitesse (24 h)", listOf(0x0D), hours = 24),
            Graph("Régime (24 h)", listOf(0x0C), hours = 24),
            Graph("Température moteur (24 h)", listOf(0x05), hours = 24),
            Stats("Records de la semaine", listOf(
                Stat(0x0D, "Vitesse max", "max", "week"),
                Stat(0x0C, "Régime max", "max", "week"),
                Stat(0x05, "Température max", "max", "week"),
            )),
        )),
    )

    fun entityId(config: AppConfig, pid: Int): String = HaEntities(config).pidEntityId(pid)

    fun generate(config: AppConfig, pids: Set<Int>, style: DashboardStyle = DashboardStyle.STANDARD): String = buildString {
        val r = Renderer(this, config, pids, style == DashboardStyle.HACS)
        appendLine("# Tableau de bord ${config.deviceName} — un onglet par thème")
        if (r.hacs) {
            appendLine("# Version avancée : installez d'abord ces cartes dans HACS (Interface / Frontend),")
            appendLine("# puis rechargez la page :")
            HACS_CARDS.forEach { appendLine("#   - $it") }
        }
        appendLine("# Paramètres → Tableaux de bord → Ajouter → Nouveau tableau de bord,")
        appendLine("# puis ⋮ → Modifier → ⋮ → Éditeur de configuration brute, et coller ce contenu.")
        appendLine("title: ${q(config.deviceName)}")
        appendLine("views:")
        VIEWS.forEach { r.view(it) }
        if (pids.isNotEmpty()) {
            r.viewHeader(View("Toutes les mesures", "mesures", "mdi:format-list-bulleted", emptyList()))
            r.entities("Toutes les mesures", pids.sortedBy { PidRegistry.getOrUnknown(it).name })
        }
    }

    private class Renderer(
        private val out: StringBuilder,
        private val config: AppConfig,
        private val supported: Set<Int>,
        val hacs: Boolean
    ) {
        private val ha = HaEntities(config)

        private fun line(text: String) { out.appendLine(text) }

        private fun present(pids: List<Int>) = pids.filter { it in supported }.distinct()

        /** Pedal D and relative pedal are the same reading: keep one gauge. */
        private fun gaugePids(pids: List<Int>) = present(pids).filter { it in GAUGES }
            .let { if (0x49 in it && 0x5A in it) it - 0x49 else it }

        private fun isEmpty(card: Card): Boolean = when (card) {
            is GaugeGrid -> gaugePids(card.pids).isEmpty()
            is Graph -> present(card.pids).isEmpty()
            is Stats -> card.stats.none { it.pid in supported }
            is EntityList -> present(card.pids).isEmpty()
            DiagnosticsSummary, DiagnosticsDetail -> false
        }

        fun view(view: View) {
            val cards = view.cards.filterNot { isEmpty(it) }
            if (cards.isEmpty() || cards.all { it == DiagnosticsSummary }) return
            viewHeader(view)
            cards.forEach { card ->
                when (card) {
                    is GaugeGrid -> gauges(card)
                    is Graph -> graph(card)
                    is Stats -> stats(card)
                    is EntityList -> entities(card.title, present(card.pids))
                    DiagnosticsSummary -> diagnosticsSummary()
                    DiagnosticsDetail -> diagnosticsDetail()
                }
            }
        }

        fun viewHeader(view: View) {
            line("  - title: ${q(view.title)}")
            line("    path: ${view.path}")
            line("    icon: ${view.icon}")
            line("    cards:")
        }

        private fun gauges(card: GaugeGrid) {
            val pids = gaugePids(card.pids)
            line("      - type: grid")
            card.title?.let { line("        title: ${q(it)}") }
            line("        columns: ${minOf(card.columns, pids.size)}")
            line("        square: false")
            line("        cards:")
            pids.forEach { pid ->
                val g = GAUGES.getValue(pid)
                line("          - type: ${if (hacs) "custom:modern-circular-gauge" else "gauge"}")
                line("            entity: ${ha.pidEntityId(pid)}")
                line("            name: ${q(g.title)}")
                line("            min: ${g.min}")
                line("            max: ${g.max}")
                line("            needle: true")
                if (g.segments.isNotEmpty()) {
                    if (hacs) line("            smooth_segments: true")
                    line("            segments:")
                    g.segments.forEach { (from, colour) ->
                        line("              - from: $from")
                        line("                color: ${q(colour)}")
                    }
                }
            }
        }

        /** [Graph.hours] = 0 means the last 15 minutes (live driving). */
        private fun graph(card: Graph) {
            val pids = present(card.pids)
            if (hacs) {
                val firstUnit = PidRegistry.getOrUnknown(pids.first()).unit
                line("      - type: custom:mini-graph-card")
                line("        name: ${q(card.title)}")
                line("        hours_to_show: ${if (card.hours == 0) 0.25 else card.hours}")
                line("        points_per_hour: ${if (card.hours >= 24) 12 else 120}")
                line("        line_width: 2")
                line("        smoothing: true")
                line("        animate: true")
                line("        show:")
                line("          fill: fade")
                line("          legend: ${pids.size > 1}")
                line("        entities:")
                pids.forEach { pid ->
                    val def = PidRegistry.getOrUnknown(pid)
                    line("          - entity: ${ha.pidEntityId(pid)}")
                    line("            name: ${q(def.name)}")
                    if (def.unit != firstUnit) line("            y_axis: secondary")
                }
            } else {
                line("      - type: history-graph")
                line("        title: ${q(card.title)}")
                // The built-in graph shows whole hours: live graphs get the last hour
                line("        hours_to_show: ${maxOf(card.hours, 1)}")
                line("        entities:")
                pids.forEach { line("          - entity: ${ha.pidEntityId(it)}") }
            }
        }

        /** Built-in statistic cards (long-term statistics, needs state_class measurement). */
        private fun stats(card: Stats) {
            val stats = card.stats.filter { it.pid in supported }
            line("      - type: grid")
            line("        title: ${q(card.title)}")
            line("        columns: ${stats.size}")
            line("        square: false")
            line("        cards:")
            stats.forEach { s ->
                line("          - type: statistic")
                line("            entity: ${ha.pidEntityId(s.pid)}")
                line("            name: ${q(s.name)}")
                line("            stat_type: ${s.type}")
                line("            period:")
                line("              calendar:")
                line("                period: ${s.period}")
            }
        }

        fun entities(title: String, pids: List<Int>) {
            line("      - type: entities")
            line("        title: ${q(title)}")
            line("        show_header_toggle: false")
            line("        entities:")
            pids.forEach { pid ->
                line("          - entity: ${ha.pidEntityId(pid)}")
                line("            name: ${q(PidRegistry.getOrUnknown(pid).name)}")
            }
        }

        private fun diagnosticsSummary() {
            line("      - type: grid")
            line("        columns: 2")
            line("        square: false")
            line("        cards:")
            line("          - type: tile")
            line("            entity: ${ha.milEntityId}")
            line("            name: Voyant moteur")
            line("          - type: tile")
            line("            entity: ${ha.faultCodesEntityId}")
            line("            name: Codes défaut")
        }

        private fun diagnosticsDetail() {
            line("      - type: entities")
            line("        title: Diagnostic moteur")
            line("        show_header_toggle: false")
            line("        entities:")
            line("          - entity: ${ha.milEntityId}")
            line("            name: Voyant moteur")
            line("          - entity: ${ha.faultCodesEntityId}")
            line("            name: Codes défaut")
            line("          - entity: ${ha.pendingEntityId}")
            line("            name: Défauts en attente")
            line("          - entity: ${ha.readinessEntityId}")
            line("            name: Tests antipollution incomplets")
            line("      - type: markdown")
            line("        title: Détail des défauts")
            // Literal block: one markdown list item per line
            line("        content: |")
            line("          {% set codes = state_attr('${ha.faultCountEntityId}', 'codes') or [] %}")
            line("          {% set pending = state_attr('${ha.pendingEntityId}', 'codes') or [] %}")
            line("          {% for c in codes %}")
            line("          - **{{ c.code }}** — {{ c.description }}")
            line("          {% endfor %}")
            line("          {% for c in pending %}")
            line("          - *{{ c.code }} (en attente)* — {{ c.description }}")
            line("          {% endfor %}")
            line("          {% if not codes and not pending %}Aucun défaut mémorisé ✅{% endif %}")
            line("          {% set clear = state_attr('${ha.faultCountEntityId}', 'dernier_effacement') %}")
            line("          {% if clear is mapping %}")
            line("")
            line("          _Dernier effacement : {{ clear.message }}_")
            line("          {% endif %}")
            line("      - type: markdown")
            line("        title: Tests antipollution")
            line("        content: |")
            line("          {% set tests = state_attr('${ha.readinessEntityId}', 'tests') or [] %}")
            line("          {% for t in tests %}")
            line("          - {{ '✅' if t.complete else '⏳' }} {{ t.name }}")
            line("          {% endfor %}")
            line("          {% if not tests %}Pas encore lu{% endif %}")
            if (config.haToken.isNotBlank()) {
                line("      - type: button")
                line("        name: Effacer les défauts")
                line("        icon: mdi:broom")
                line("        show_state: false")
                line("        tap_action:")
                line("          action: perform-action")
                line("          perform_action: input_button.press")
                line("          target:")
                line("            entity_id: ${ha.clearButtonEntityId}")
                line("          confirmation:")
                line("            text: >-")
                line("              Effacer les codes défaut ? Mettez le contact (moteur arrêté). Les tests")
                line("              antipollution seront remis à zéro et le voyant se rallumera si le défaut persiste.")
            }
        }
    }

    private fun q(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
