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
        assertEquals("peugeot_407_regime_moteur", HaDashboardYaml.slugify("Peugeot 407 Régime moteur"))
        assertEquals("peugeot_407_temperature_liquide_de_refroidissement",
            HaDashboardYaml.slugify("Peugeot 407 Température liquide de refroidissement"))
        assertEquals("peugeot_407_pid_0x9a", HaDashboardYaml.slugify("Peugeot 407 PID 0x9A"))
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
        assertTrue(yaml.contains("title: Toutes les mesures"))
        assertTrue(yaml.contains("              yellow: 4500"))
        assertTrue(yaml.contains("path: peugeot-407"))
    }

    @Test
    fun `webhook yaml and dashboard agree on entity names`() {
        val pids = setOf(0x0C, 0x0D, 0x05)
        val sensors = HaWebhookYaml.generate(config, pids)
        pids.forEach { pid ->
            val name = "${config.deviceName} ${PidRegistry.getOrUnknown(pid).name}"
            assertTrue(sensors.contains("name: \"$name\""))
            assertEquals("sensor." + HaDashboardYaml.slugify(name), HaDashboardYaml.entityId(config, pid))
        }
    }
}
