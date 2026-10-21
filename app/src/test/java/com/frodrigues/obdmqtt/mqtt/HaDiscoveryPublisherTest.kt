package com.frodrigues.obdmqtt.mqtt

import com.frodrigues.obdmqtt.settings.AppConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class HaDiscoveryPublisherTest {

    private val config = AppConfig(
        btDeviceMac = "AA:BB:CC:DD:EE:FF",
        mqttHost = "192.168.1.10",
        mqttPort = 1883,
        mqttUser = "",
        mqttPassword = "",
        deviceName = "Test Car",
        deviceModel = "Model X",
        deviceManufacturer = "Maker"
    )

    @Test
    fun `publishes discovery config for RPM with correct topic`() = runTest {
        val published = mutableListOf<Pair<String, String>>()
        val publisher = HaDiscoveryPublisher(
            publish = { topic, payload -> published.add(topic to payload) },
            config = config
        )

        publisher.publishDiscovery(setOf(0x0C), "AABBCCDDEEFF")

        assertEquals(1, published.size)
        assertEquals(
            "homeassistant/sensor/obd2_AABBCCDDEEFF/0C/config",
            published[0].first
        )
    }

    @Test
    fun `discovery payload contains required HA fields`() = runTest {
        val published = mutableListOf<Pair<String, String>>()
        val publisher = HaDiscoveryPublisher(
            publish = { topic, payload -> published.add(topic to payload) },
            config = config
        )

        publisher.publishDiscovery(setOf(0x0C), "AABBCCDDEEFF")

        val payload = published[0].second
        assertTrue(payload.contains("\"state_topic\""))
        assertTrue(payload.contains("obd2/AABBCCDDEEFF/0C/state"))
        assertTrue(payload.contains("\"unique_id\""))
        assertTrue(payload.contains("\"unit_of_measurement\""))
        assertTrue(payload.contains("Test Car RPM"))
    }

    @Test
    fun `discovery payload includes device block with config values`() = runTest {
        val published = mutableListOf<Pair<String, String>>()
        val publisher = HaDiscoveryPublisher(
            publish = { topic, payload -> published.add(topic to payload) },
            config = config
        )

        publisher.publishDiscovery(setOf(0x0C), "AABBCCDDEEFF")

        val payload = published[0].second
        assertTrue(payload.contains("\"name\":\"Test Car\""))
        assertTrue(payload.contains("\"model\":\"Model X\""))
        assertTrue(payload.contains("\"manufacturer\":\"Maker\""))
    }

    @Test
    fun `skips unknown pids`() = runTest {
        val published = mutableListOf<Pair<String, String>>()
        val publisher = HaDiscoveryPublisher(
            publish = { topic, payload -> published.add(topic to payload) },
            config = config
        )

        publisher.publishDiscovery(setOf(0xFF), "AABBCCDDEEFF")

        assertTrue(published.isEmpty())
    }
}
