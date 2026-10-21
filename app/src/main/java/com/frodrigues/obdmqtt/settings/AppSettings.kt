package com.frodrigues.obdmqtt.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.frodrigues.obdmqtt.obd.ObdProtocol
import com.frodrigues.obdmqtt.obd.PidRegistry
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

/** Bluetooth link to the adapter: AUTO follows Android's device type (bonded devices). */
enum class BtTransport { AUTO, CLASSIC, BLE }

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
    val publishMode: PublishMode = PublishMode.WEBHOOK,
    val haUrl: String = "",
    val webhookId: String = "",
    val webhookIntervalSeconds: Int = AppSettings.DEFAULT_WEBHOOK_INTERVAL_SECONDS,
    val obdProtocol: ObdProtocol = ObdProtocol.AUTO,
    /** Only query the ECU when the alternator is charging, so a parked car is never woken up. */
    val engineRunningOnly: Boolean = false,
    /** Optional HA long-lived token: lets the app read the "clear faults" button state from HA. */
    val haToken: String = "",
    val btTransport: BtTransport = BtTransport.AUTO
) {
    val mqttClientId: String get() = "obd2_android_${btDeviceMac.replace(":", "")}"
}

class AppSettings(private val dataStore: DataStore<Preferences>) {

    companion object {
        const val DE_PREFS_NAME = "boot_prefs"
        const val DE_KEY_AUTO_START = "autoStart"

        val BT_DEVICE_MAC = stringPreferencesKey("btDeviceMac")
        val BT_DEVICE_NAME = stringPreferencesKey("btDeviceName")
        val BT_TRANSPORT = stringPreferencesKey("btTransport")
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

        const val DEFAULT_WEBHOOK_INTERVAL_SECONDS = 1
        val OBD_PROTOCOL = stringPreferencesKey("obdProtocol")
        /** Protocol found in AUTO mode, tried first next time. */
        val LAST_OBD_PROTOCOL = stringPreferencesKey("lastObdProtocol")
        val ENGINE_RUNNING_ONLY = booleanPreferencesKey("engineRunningOnly")
        val HA_TOKEN = stringPreferencesKey("haToken")

        // Default fast PIDs (1 s) — live driving data; only those the car supports are polled.
        // RPM, Speed, Load, Throttle, Coolant, MAP, MAF, battery voltage (AT RV)
        val DEFAULT_FAST_PIDS: Set<Int> =
            setOf(0x0C, 0x0D, 0x04, 0x11, 0x05, 0x0B, 0x10, PidRegistry.ADAPTER_VOLTAGE)
    }

    /** On by default: the app is meant to run unattended in the car. */
    val autoStart: Flow<Boolean> = dataStore.data.map { it[AUTO_START] ?: true }

    val fastPids: Flow<Set<Int>> = dataStore.data.map { prefs ->
        parsePidHexSet(prefs[FAST_PIDS] ?: return@map DEFAULT_FAST_PIDS)
    }

    /** Until the user edits the selection, every other discovered PID is polled slowly ("all data"). */
    val slowPids: Flow<Set<Int>> = dataStore.data.map { prefs ->
        prefs[SLOW_PIDS]?.let { parsePidHexSet(it) }
            ?: (parsePidHexSet(prefs[CACHED_PIDS] ?: "") - (prefs[FAST_PIDS]?.let { parsePidHexSet(it) } ?: DEFAULT_FAST_PIDS))
    }

    suspend fun savePidModes(fast: Set<Int>, slow: Set<Int>) {
        update {
            this[FAST_PIDS] = fast.toPidHexString()
            this[SLOW_PIDS] = slow.toPidHexString()
        }
    }

    val btDeviceMac: Flow<String> = dataStore.data.map { it[BT_DEVICE_MAC] ?: "" }
    val btDeviceName: Flow<String> = dataStore.data.map { it[BT_DEVICE_NAME] ?: "" }
    val btTransport: Flow<BtTransport> = dataStore.data.map { prefs ->
        prefs[BT_TRANSPORT]?.let { runCatching { BtTransport.valueOf(it) }.getOrNull() } ?: BtTransport.AUTO
    }

    /** Remembers the adapter chosen in the app (bonded list or in-app scan). */
    suspend fun selectBtDevice(address: String, name: String, transport: BtTransport) {
        update {
            this[BT_DEVICE_MAC] = address
            this[BT_DEVICE_NAME] = name
            this[BT_TRANSPORT] = transport.name
        }
    }
    val mqttHost: Flow<String> = dataStore.data.map { it[MQTT_HOST] ?: "" }
    val mqttPort: Flow<Int> = dataStore.data.map { it[MQTT_PORT] ?: 1883 }
    val mqttUser: Flow<String> = dataStore.data.map { it[MQTT_USER] ?: "" }
    val mqttPassword: Flow<String> = dataStore.data.map { it[MQTT_PASSWORD] ?: "" }
    val deviceName: Flow<String> = dataStore.data.map { it[DEVICE_NAME] ?: "Peugeot 407" }
    val deviceModel: Flow<String> = dataStore.data.map { it[DEVICE_MODEL] ?: "407" }
    val deviceManufacturer: Flow<String> = dataStore.data.map { it[DEVICE_MANUFACTURER] ?: "Peugeot" }
    val publishMode: Flow<PublishMode> = dataStore.data.map { prefs ->
        prefs[PUBLISH_MODE]?.let { runCatching { PublishMode.valueOf(it) }.getOrNull() } ?: PublishMode.WEBHOOK
    }
    /** Off by default: car units power off with the ignition, so the car is never queried while parked. */
    val engineRunningOnly: Flow<Boolean> = dataStore.data.map { it[ENGINE_RUNNING_ONLY] ?: false }
    val haToken: Flow<String> = dataStore.data.map { it[HA_TOKEN] ?: "" }
    val lastObdProtocol: Flow<ObdProtocol?> = dataStore.data.map { prefs ->
        prefs[LAST_OBD_PROTOCOL]?.let { runCatching { ObdProtocol.valueOf(it) }.getOrNull() }
    }

    suspend fun saveLastObdProtocol(protocol: ObdProtocol) {
        update { this[LAST_OBD_PROTOCOL] = protocol.name }
    }

    val obdProtocol: Flow<ObdProtocol> = dataStore.data.map { prefs ->
        prefs[OBD_PROTOCOL]?.let { runCatching { ObdProtocol.valueOf(it) }.getOrNull() } ?: ObdProtocol.AUTO
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
        webhookIntervalSeconds = webhookIntervalSeconds.first(),
        obdProtocol = obdProtocol.first(),
        engineRunningOnly = engineRunningOnly.first(),
        haToken = haToken.first(),
        btTransport = btTransport.first()
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
