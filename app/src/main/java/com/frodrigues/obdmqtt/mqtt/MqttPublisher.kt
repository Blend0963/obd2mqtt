package com.frodrigues.obdmqtt.mqtt

import com.frodrigues.obdmqtt.settings.AppConfig
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MqttPublisher(private val config: AppConfig) {

    @Volatile private var client: Mqtt3AsyncClient? = null

    suspend fun connect() = withContext(Dispatchers.IO) {
        val newClient = MqttClient.builder()
            .useMqttVersion3()
            .identifier(config.mqttClientId)
            .serverHost(config.mqttHost)
            .serverPort(config.mqttPort)
            .buildAsync()

        val connectBuilder = newClient.connectWith()
        if (config.mqttUser.isNotBlank()) {
            connectBuilder
                .simpleAuth()
                .username(config.mqttUser)
                .password(config.mqttPassword.toByteArray(Charsets.UTF_8))
                .applySimpleAuth()
        }
        connectBuilder.send().get()  // throws if connection fails
        client = newClient           // only assigned on success
    }

    suspend fun publish(topic: String, payload: String, retain: Boolean = false) =
        withContext(Dispatchers.IO) {
            val c = client ?: throw IllegalStateException("Not connected")
            c.publishWith()
                .topic(topic)
                .payload(payload.toByteArray(Charsets.UTF_8))
                .qos(MqttQos.AT_LEAST_ONCE)
                .retain(retain)
                .send()
                .get()
        }

    suspend fun publishEmpty(topic: String) = withContext(Dispatchers.IO) {
        val c = client ?: return@withContext
        c.publishWith()
            .topic(topic)
            .qos(MqttQos.AT_LEAST_ONCE)
            .retain(true)
            .send()
            .get()
    }

    /** Calls [onMessage] for every message received on [topic]. */
    suspend fun subscribe(topic: String, onMessage: (String) -> Unit) = withContext(Dispatchers.IO) {
        val c = client ?: throw IllegalStateException("Not connected")
        c.subscribeWith()
            .topicFilter(topic)
            .qos(MqttQos.AT_LEAST_ONCE)
            .callback { publish -> onMessage(Charsets.UTF_8.decode(publish.payload.orElse(java.nio.ByteBuffer.allocate(0))).toString()) }
            .send()
            .get()
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        runCatching { client?.disconnect()?.get() }
        client = null
    }
}

suspend fun testMqttConnection(config: AppConfig): Result<Unit> = runCatching {
    withContext(Dispatchers.IO) {
        val testClient = MqttClient.builder()
            .useMqttVersion3()
            .identifier("obd2_test_${System.currentTimeMillis()}")
            .serverHost(config.mqttHost)
            .serverPort(config.mqttPort)
            .buildAsync()

        val connectBuilder = testClient.connectWith()
        if (config.mqttUser.isNotBlank()) {
            connectBuilder
                .simpleAuth()
                .username(config.mqttUser)
                .password(config.mqttPassword.toByteArray(Charsets.UTF_8))
                .applySimpleAuth()
        }
        connectBuilder.send().get(5, java.util.concurrent.TimeUnit.SECONDS)
        runCatching { testClient.disconnect().get(2, java.util.concurrent.TimeUnit.SECONDS) }
    }
}
