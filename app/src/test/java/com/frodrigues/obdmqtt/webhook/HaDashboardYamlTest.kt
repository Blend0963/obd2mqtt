package com.frodrigues.obdmqtt.webhook

import com.frodrigues.obdmqtt.obd.PidRegistry
import com.frodrigues.obdmqtt.settings.AppConfig
import org.junit.Assert.*
import org.junit.Test

class HaDashboardYamlTest {

    private val config = AppConfig(
        btDeviceMac = "AA:BB:CC:DD:EE:FF",
        mqttHost = "",
        mqttPort = 1883,
        mqttUser = "",
        mqttPassword = "",
        deviceName = "Peugeot 407",
        deviceModel = "407",
        deviceManufacturer = "Peugeot",
        webhookId = "abcdefghijklmnopqrstuvwxyz"
    )

    @Test
    fun `slugify matches Home Assistant entity ids`() {
        assertEquals("peugeot_407_regime_moteur", HaEntities.slugify("Peugeot 407 Régime moteur"))
        assertEquals("peugeot_407_temperature_liquide_de_refroidissement",
            HaEntities.slugify("Peugeot 407 Température liquide de refroidissement"))
        assertEquals("peugeot_407_pid_0x9a", HaEntities.slugify("Peugeot 407 PID 0x9A"))
    }

    @Test
    fun `entity ids follow sensor names`() {
        assertEquals("sensor.peugeot_407_vitesse", HaDashboardYaml.entityId(config, 0x0D))
        assertEquals("sensor.peugeot_407_tension_batterie",
            HaDashboardYaml.entityId(config, PidRegistry.ADAPTER_VOLTAGE))
    }

    @Test
    fun `dashboard only references polled sensors`() {
        val yaml = HaDashboardYaml.generate(config, setOf(0x0C, 0x05, PidRegistry.ADAPTER_VOLTAGE))
        assertTrue(yaml.contains("entity: sensor.peugeot_407_regime_moteur"))
        assertTrue(yaml.contains("entity: sensor.peugeot_407_tension_batterie"))
        assertFalse(yaml.contains("sensor.peugeot_407_vitesse"))
        assertTrue(yaml.contains("type: gauge"))
        assertTrue(yaml.contains("type: history-graph"))
        assertTrue(yaml.contains("title: \"Toutes les mesures\""))
        assertTrue(yaml.contains("              - from: 4500"))
        assertTrue(yaml.contains("path: apercu"))
    }

    @Test
    fun `webhook yaml and dashboard agree on entity names`() {
        val pids = setOf(0x0C, 0x0D, 0x05)
        val sensors = HaWebhookYaml.generate(config, pids)
        pids.forEach { pid ->
            val name = "${config.deviceName} ${PidRegistry.getOrUnknown(pid).name}"
            assertTrue(sensors.contains("name: \"$name\""))
            assertEquals("sensor." + HaEntities.slugify(name), HaDashboardYaml.entityId(config, pid))
        }
    }

    @Test
    fun `hacs dashboard uses modern gauges and mini graphs`() {
        val yaml = HaDashboardYaml.generate(
            config, setOf(0x0C, 0x0D, 0x05, PidRegistry.ADAPTER_VOLTAGE), DashboardStyle.HACS
        )
        assertTrue(yaml.contains("type: custom:modern-circular-gauge"))
        assertFalse(yaml.contains("type: gauge"))
        assertTrue(yaml.contains("type: custom:mini-graph-card"))
        assertFalse(yaml.contains("type: history-graph"))
        assertTrue(yaml.contains("Modern Circular Gauge"))
        // Battery segments sorted by threshold: red 10, yellow 11.8, green 12.4
        val battery = yaml.substringAfter("name: \"Batterie\"")
        assertTrue(battery.indexOf("from: 10") < battery.indexOf("from: 11.8"))
        assertTrue(battery.indexOf("from: 11.8") < battery.indexOf("from: 12.4"))
        // Speed (km/h) shares the "Conduite" graph with RPM: secondary axis
        val driving = yaml.substringAfter("name: \"Conduite\"").substringBefore("- type:")
        assertTrue(driving.contains("y_axis: secondary"))
        // Diagnostics stay on built-in cards
        assertTrue(yaml.contains("title: Diagnostic moteur"))
    }

    @Test
    fun `standard dashboard needs no custom card`() {
        val yaml = HaDashboardYaml.generate(config, setOf(0x0C, 0x0D, 0x05))
        assertFalse(yaml.contains("custom:"))
    }

    @Test
    fun `one view per theme, empty views left out`() {
        val yaml = HaDashboardYaml.generate(config, setOf(0x0C, 0x0D, 0x05, 0x06, 0x0B))
        listOf("Aperçu", "Conduite", "Moteur", "Diagnostic", "Historique", "Toutes les mesures").forEach {
            assertTrue(it, yaml.contains("  - title: \"$it\""))
        }
        // No voltage PID: no battery view
        assertFalse(yaml.contains("  - title: \"Batterie\""))
        assertTrue(yaml.contains("type: statistic"))
        assertTrue(yaml.contains("stat_type: max"))
        assertTrue(yaml.contains("title: \"Correction de richesse\""))
    }

    @Test
    fun `cards without supported pids are left out`() {
        val yaml = HaDashboardYaml.generate(config, setOf(0x0C))
        assertFalse(yaml.contains("Températures"))
        assertFalse(yaml.contains("Pressions"))
        // Diagnostic view is always there
        assertTrue(yaml.contains("  - title: \"Diagnostic\""))
    }
}
