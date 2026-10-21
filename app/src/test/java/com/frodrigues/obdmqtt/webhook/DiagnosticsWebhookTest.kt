package com.frodrigues.obdmqtt.webhook

import com.frodrigues.obdmqtt.obd.ClearResult
import com.frodrigues.obdmqtt.obd.DiagnosticReport
import com.frodrigues.obdmqtt.obd.ReadinessMonitor
import com.frodrigues.obdmqtt.settings.AppConfig
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class DiagnosticsWebhookTest {

    private val config = AppConfig(
        btDeviceMac = "AA:BB:CC:DD:EE:FF",
        mqttHost = "",
        mqttPort = 1883,
        mqttUser = "",
        mqttPassword = "",
        deviceName = "Peugeot 407",
        deviceModel = "407",
        deviceManufacturer = "Peugeot",
        webhookId = "abcdefghijklmnopqrstuvwxyz",
        haToken = "token"
    )

    private val report = DiagnosticReport(
        milOn = true,
        codes = listOf("P0301"),
        pending = listOf("P0420"),
        monitors = listOf(ReadinessMonitor("Catalyseur", false), ReadinessMonitor("Sonde lambda", true)),
        timestamp = 42L,
        lastClear = ClearResult(true, "Défauts effacés", 40L)
    )

    @Test
    fun `diagnostics json carries codes with descriptions`() {
        assertEquals(
            "{\"mil\":true,\"codes\":[{\"code\":\"P0301\",\"description\":\"Ratés d'allumage cylindre 1\"}]" +
            ",\"pending\":[{\"code\":\"P0420\",\"description\":\"Efficacité du catalyseur sous le seuil (banc 1)\"}]" +
            ",\"incomplete_monitors\":[\"Catalyseur\"]" +
            ",\"monitors\":[{\"name\":\"Catalyseur\",\"complete\":false},{\"name\":\"Sonde lambda\",\"complete\":true}]" +
            ",\"ts\":42,\"last_clear\":{\"success\":true,\"message\":\"Défauts effacés\",\"ts\":40}}",
            WebhookPayload.diagnostics(report)
        )
    }

    @Test
    fun `readings and unavailable payloads embed diagnostics when known`() {
        assertTrue(WebhookPayload.readings(mapOf(0x0C to 800.0), "Car", 1L, report).contains(",\"diag\":{\"mil\":true"))
        assertFalse(WebhookPayload.readings(mapOf(0x0C to 800.0), "Car", 1L).contains("diag"))
        val parked = WebhookPayload.unavailable("Car", 1L, report)
        assertTrue(parked.startsWith("{\"available\":false"))
        assertTrue(parked.contains("\"P0301\""))
    }

    @Test
    fun `unknown MIL state is null`() {
        assertTrue(WebhookPayload.diagnostics(report.copy(milOn = null)).startsWith("{\"mil\":null"))
    }

    @Test
    fun `package yaml declares diagnostics, button and automation`() {
        val yaml = HaWebhookYaml.generate(config, setOf(0x0C))
        val ha = HaEntities(config)
        assertTrue(yaml.contains("binary_sensor:"))
        assertTrue(yaml.contains("name: \"Peugeot 407 Voyant moteur\""))
        assertTrue(yaml.contains("name: \"Peugeot 407 Nombre de défauts\""))
        assertTrue(yaml.contains("input_button:\n  peugeot_407_effacer_les_defauts:"))
        assertTrue(yaml.contains("entity_id: ${ha.faultCountEntityId}"))
        assertEquals("sensor.peugeot_407_nombre_de_defauts", ha.faultCountEntityId)
        assertEquals("input_button.peugeot_407_effacer_les_defauts", ha.clearButtonEntityId)
        assertEquals("binary_sensor.peugeot_407_voyant_moteur", ha.milEntityId)
    }

    @Test
    fun `dashboard has diagnostics and the clear button only with a token`() {
        val withToken = HaDashboardYaml.generate(config, setOf(0x0C))
        assertTrue(withToken.contains("title: Diagnostic moteur"))
        assertTrue(withToken.contains("perform_action: input_button.press"))
        val withoutToken = HaDashboardYaml.generate(config.copy(haToken = ""), setOf(0x0C))
        assertTrue(withoutToken.contains("title: Diagnostic moteur"))
        assertFalse(withoutToken.contains("input_button.press"))
    }

    @Test
    fun `parses input_button state from HA API json`() {
        val json = "{\"entity_id\":\"input_button.x\",\"state\":\"2026-10-21T09:42:00.123456+00:00\",\"attributes\":{}}"
        assertEquals("2026-10-21T09:42:00.123456+00:00", HaApiClient.parseState(json))
        assertNull(HaApiClient.parseState("{}"))
    }

    @Test
    fun `button watcher only reacts to fresh new presses`() {
        val now = Instant.parse("2026-10-21T10:00:00Z")
        val watcher = ClearButtonWatcher()
        assertFalse("old press is ignored", watcher.onState("2026-10-21T08:00:00+00:00", now))
        assertFalse("unknown state", watcher.onState("unknown", now))
        assertTrue(watcher.onState("2026-10-21T09:59:30+00:00", now))
        assertFalse("same press only once", watcher.onState("2026-10-21T09:59:30+00:00", now))
        assertTrue(watcher.onState("2026-10-21T10:00:05+00:00", now))
    }
}
