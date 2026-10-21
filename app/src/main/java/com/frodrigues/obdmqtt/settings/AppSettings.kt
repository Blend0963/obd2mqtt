package com.frodrigues.obdmqtt.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

fun syncAutoStartToDeStorage(context: Context, enabled: Boolean) {
    context.createDeviceProtectedStorageContext()
        .getSharedPreferences(AppSettings.DE_PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(AppSettings.DE_KEY_AUTO_START, enabled)
        .apply()
}

/** How readings reach Home Assistant. */
enum class PublishMode { MQTT, WEBHOOK }

data class AppConfig(
    val btDeviceMac: String,
    val mqttHost: String,
    val mqttPort: Int,
    val mqttUser: String,
    val mqttPassword: String,
    val deviceName: String,
    val deviceModel: String,
    val deviceManufacturer: String,
    val publishMode: PublishMode = PublishMode.MQTT,
    val haUrl: String = "",
    val webhookId: String = "",
    val webhookIntervalSeconds: Int = AppSettings.DEFAULT_WEBHOOK_INTERVAL_SECONDS
) {
    val mqttClientId: String get() = "obd2_android_${btDeviceMac.replace(":", "")}"
}

class AppSettings(private val dataStore: DataStore<Preferences>) {

    companion object {
        const val DE_PREFS_NAME = "boot_prefs"
        const val DE_KEY_AUTO_START = "autoStart"

        val BT_DEVICE_MAC = stringPreferencesKey("btDeviceMac")
        val MQTT_HOST = stringPreferencesKey("mqttHost")
        val MQTT_PORT = intPreferencesKey("mqttPort")
        val MQTT_USER = stringPreferencesKey("mqttUser")
        val MQTT_PASSWORD = stringPreferencesKey("mqttPassword")
        val DEVICE_NAME = stringPreferencesKey("deviceName")
        val DEVICE_MODEL = stringPreferencesKey("deviceModel")
        val DEVICE_MANUFACTURER = stringPreferencesKey("deviceManufacturer")
        // Mode 01 PID discovery cache
        val CACHED_PIDS = stringPreferencesKey("cachedPids")
        // PID polling modes — absent = use defaults (auto-migration for old installs)
        val FAST_PIDS = stringPreferencesKey("fastPids")
        val SLOW_PIDS = stringPreferencesKey("slowPids")
        // Mode 22 PID discovery cache — key:hex PID (4 chars), value:hex bytes
        val CACHED_MODE22_PIDS = stringPreferencesKey("cachedMode22Pids")
        val AUTO_START = booleanPreferencesKey("autoStart")
        // Webhook (HTTPS) publishing — alternative to MQTT for WAN setups
        val PUBLISH_MODE = stringPreferencesKey("publishMode")
        val HA_URL = stringPreferencesKey("haUrl")
        val WEBHOOK_ID = stringPreferencesKey("webhookId")
        val WEBHOOK_INTERVAL = intPreferencesKey("webhookIntervalSeconds")

        const val DEFAULT_WEBHOOK_INTERVAL_SECONDS = 5

        // Default PID sets — applied on first launch and after update from old binary model
        val DEFAULT_FAST_PIDS: Set<Int> = setOf(0x0C, 0x0D, 0x42, 0x5E) // RPM, Speed, Voltage, Fuel Rate
        val DEFAULT_SLOW_PIDS: Set<Int> = setOf(0x2F, 0xA6)              // Fuel Level, Odometer
    }

    val autoStart: Flow<Boolean> = dataStore.data.map { it[AUTO_START] ?: false }

    val fastPids: Flow<Set<Int>> = dataStore.data.map { prefs ->
        parsePidHexSet(prefs[FAST_PIDS] ?: return@map DEFAULT_FAST_PIDS)
    }

    val slowPids: Flow<Set<Int>> = dataStore.data.map { prefs ->
        parsePidHexSet(prefs[SLOW_PIDS] ?: return@map DEFAULT_SLOW_PIDS)
    }

    suspend fun savePidModes(fast: Set<Int>, slow: Set<Int>) {
        update {
            this[FAST_PIDS] = fast.toPidHexString()
            this[SLOW_PIDS] = slow.toPidHexString()
        }
    }

    val btDeviceMac: Flow<String> = dataStore.data.map { it[BT_DEVICE_MAC] ?: "" }
    val mqttHost: Flow<String> = dataStore.data.map { it[MQTT_HOST] ?: "" }
    val mqttPort: Flow<Int> = dataStore.data.map { it[MQTT_PORT] ?: 1883 }
    val mqttUser: Flow<String> = dataStore.data.map { it[MQTT_USER] ?: "" }
    val mqttPassword: Flow<String> = dataStore.data.map { it[MQTT_PASSWORD] ?: "" }
    val deviceName: Flow<String> = dataStore.data.map { it[DEVICE_NAME] ?: "Jaecoo 7" }
    val deviceModel: Flow<String> = dataStore.data.map { it[DEVICE_MODEL] ?: "Jaecoo 7" }
    val deviceManufacturer: Flow<String> = dataStore.data.map { it[DEVICE_MANUFACTURER] ?: "Jaecoo" }
    val publishMode: Flow<PublishMode> = dataStore.data.map { prefs ->
        prefs[PUBLISH_MODE]?.let { runCatching { PublishMode.valueOf(it) }.getOrNull() } ?: PublishMode.MQTT
    }
    val haUrl: Flow<String> = dataStore.data.map { it[HA_URL] ?: "" }
    val webhookId: Flow<String> = dataStore.data.map { it[WEBHOOK_ID] ?: "" }
    val webhookIntervalSeconds: Flow<Int> = dataStore.data.map {
        it[WEBHOOK_INTERVAL] ?: DEFAULT_WEBHOOK_INTERVAL_SECONDS
    }

    suspend fun snapshot(): AppConfig = AppConfig(
        btDeviceMac = btDeviceMac.first(),
        mqttHost = mqttHost.first(),
        mqttPort = mqttPort.first(),
        mqttUser = mqttUser.first(),
        mqttPassword = mqttPassword.first(),
        deviceName = deviceName.first(),
        deviceModel = deviceModel.first(),
        deviceManufacturer = deviceManufacturer.first(),
        publishMode = publishMode.first(),
        haUrl = haUrl.first(),
        webhookId = webhookId.first(),
        webhookIntervalSeconds = webhookIntervalSeconds.first()
    )

    val cachedPids: Flow<Set<Int>> = dataStore.data.map { prefs ->
        parsePidHexSet(prefs[CACHED_PIDS] ?: "")
    }

    suspend fun savePidCache(pids: Set<Int>) {
        update { this[CACHED_PIDS] = pids.toPidHexString() }
    }

    suspend fun clearPidCache() {
        update { remove(CACHED_PIDS) }
    }

    // ── Mode 22 cache ────────────────────────────────────────────────────────
    // Format: "XXYY:AABBCC,XXYY:AABB,..." — pid hex : data bytes hex
    val cachedMode22Pids: kotlinx.coroutines.flow.Flow<Map<Int, ByteArray>> = dataStore.data.map { prefs ->
        val raw = prefs[CACHED_MODE22_PIDS] ?: ""
        if (raw.isBlank()) emptyMap()
        else raw.split(",").mapNotNull { entry ->
            val parts = entry.split(":")
            if (parts.size != 2) return@mapNotNull null
            val pid = parts[0].toIntOrNull(16) ?: return@mapNotNull null
            val bytes = parts[1].chunked(2).mapNotNull { it.toIntOrNull(16)?.toByte() }.toByteArray()
            pid to bytes
        }.toMap()
    }

    suspend fun saveMode22Cache(pids: Map<Int, ByteArray>) {
        update {
            this[CACHED_MODE22_PIDS] = pids.entries.joinToString(",") { (pid, bytes) ->
                "${pid.toString(16).padStart(4,'0').uppercase()}:${bytes.joinToString("") { "%02X".format(it) }}"
            }
        }
    }

    suspend fun clearMode22Cache() {
        update { remove(CACHED_MODE22_PIDS) }
    }

    suspend fun update(block: MutablePreferences.() -> Unit) {
        dataStore.edit { block(it) }
    }

    private fun parsePidHexSet(raw: String): Set<Int> =
        if (raw.isBlank()) emptySet()
        else raw.split(",").mapNotNull { it.trim().toIntOrNull(16) }.toSet()

    private fun Set<Int>.toPidHexString(): String =
        joinToString(",") { it.toString(16).uppercase() }
}
