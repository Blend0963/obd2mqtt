package com.frodrigues.obdmqtt.webhook

import com.frodrigues.obdmqtt.settings.AppConfig
import com.frodrigues.obdmqtt.settings.PublishMode
import org.junit.Assert.*
import org.junit.Test

class WebhookTest {

    private val id = "abcdefghijklmnop_QRSTUV-0123456789"

    private val config = AppConfig(
        btDeviceMac = "AA:BB:CC:DD:EE:FF",
        mqttHost = "",
        mqttPort = 1883,
        mqttUser = "",
        mqttPassword = "",
        deviceName = "Test \"Car\"",
        deviceModel = "Model X",
        deviceManufacturer = "Maker",
        publishMode = PublishMode.WEBHOOK,
        haUrl = "https://ha.example.com",
        webhookId = id
    )

    @Test
    fun `builds webhook url and strips trailing slash`() {
        assertEquals(
            "https://ha.example.com:8123/api/webhook/$id",
            buildWebhookUrl(" https://ha.example.com:8123/ ", id).toString()
        )
    }

    @Test
    fun `keeps base path for reverse proxies`() {
        assertEquals(
            "https://example.com/ha/api/webhook/$id",
            buildWebhookUrl("https://example.com/ha", id).toString()
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects plain http`() {
        buildWebhookUrl("http://ha.example.com", id)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects short webhook id`() {
        buildWebhookUrl("https://ha.example.com", "short")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects webhook id with path characters`() {
        buildWebhookUrl("https://ha.example.com", "abcdefghijklmnop/../../x")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects query string in base url`() {
        buildWebhookUrl("https://ha.example.com/?x=1", id)
    }

    @Test
    fun `generated id is 256-bit url-safe and accepted`() {
        val generated = generateWebhookId()
        assertEquals(43, generated.length)
        assertTrue(generated.matches(Regex("[A-Za-z0-9_-]+")))
        assertNotEquals(generated, generateWebhookId())
        buildWebhookUrl("https://ha.example.com", generated)
    }

    @Test
    fun `readings payload contains sorted hex pids and escaped device`() {
        val json = WebhookPayload.readings(
            mapOf(0x0D to 42.0, 0x0C to 850.5, 0x05 to Double.NaN),
            "Test \"Car\"",
            1000L
        )
        assertEquals(
            "{\"available\":true,\"ts\":1000,\"device\":\"Test \\\"Car\\\"\",\"pids\":{\"0C\":850.5,\"0D\":42}}",
            json
        )
    }

    @Test
    fun `unavailable payload has empty pids`() {
        val json = WebhookPayload.unavailable("Car", 5L)
        assertTrue(json.contains("\"available\":false"))
        assertTrue(json.contains("\"pids\":{}"))
    }

    @Test
    fun `yaml declares webhook trigger and one sensor per pid`() {
        val yaml = HaWebhookYaml.generate(config, setOf(0x0D, 0x0C, 0xA6))
        assertTrue(yaml.contains("webhook_id: \"$id\""))
        assertTrue(yaml.contains("local_only: false"))
        assertTrue(yaml.contains("allowed_methods: [POST]"))
        assertEquals(3, Regex("trigger\\.json\\.pids\\.get\\(").findAll(yaml).count())
        assertTrue(yaml.contains("unique_id: \"obd2_AABBCCDDEEFF_0C\""))
        assertTrue(yaml.contains("name: \"Test \\\"Car\\\" Régime moteur\""))
        assertTrue(yaml.contains("state: \"{{ trigger.json.pids.get('0D') }}\""))
        assertTrue(yaml.contains("state_class: total_increasing"))
        // sensors are emitted in PID order
        assertTrue(yaml.indexOf("_0C\"") < yaml.indexOf("_0D\""))
    }
}
