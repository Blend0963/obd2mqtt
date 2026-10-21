package com.frodrigues.obdmqtt.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.frodrigues.obdmqtt.MainActivity
import com.frodrigues.obdmqtt.bluetooth.BluetoothTransportFactory
import com.frodrigues.obdmqtt.obd.Mode22Registry
import com.frodrigues.obdmqtt.obd.Mode22Scanner
import com.frodrigues.obdmqtt.obd.ObdCommandExecutor
import com.frodrigues.obdmqtt.obd.PidPoller
import com.frodrigues.obdmqtt.obd.PidScanner
import com.frodrigues.obdmqtt.settings.AppSettings
import com.frodrigues.obdmqtt.settings.PublishMode
import com.frodrigues.obdmqtt.settings.dataStore
import com.frodrigues.obdmqtt.webhook.buildWebhookUrl
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

enum class ServiceStatus { IDLE, CONNECTING, CONNECTED, RECONNECTING }
enum class ConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED }

class OBDCollectorService : LifecycleService() {

    inner class LocalBinder : Binder() {
        fun getService(): OBDCollectorService = this@OBDCollectorService
    }

    private val binder = LocalBinder()

    private lateinit var settings: AppSettings
    private var wakeLock: PowerManager.WakeLock? = null
    private var collectorJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        status.value = ServiceStatus.IDLE
        activePidCount.value = 0
        lastUpdateTime.value = 0L
        btStatus.value = ConnectionStatus.DISCONNECTED
        serverStatus.value = ConnectionStatus.DISCONNECTED
        pidReadings.value = emptyMap()
        obdProtocolLabel.value = ""
        settings = AppSettings(applicationContext.dataStore)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START, null -> startCollection()
            ACTION_STOP -> stopCollection()
        }
        return START_STICKY
    }

    private fun startCollection() {
        startForeground(
            NOTIFICATION_ID,
            buildNotification("Démarrage…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OBDCollector::polling")
            .also { it.acquire() }
        val oldJob = collectorJob
        collectorJob = lifecycleScope.launch(Dispatchers.Default) {
            oldJob?.cancelAndJoin()
            collectWithRetry()
        }
    }

    private suspend fun collectWithRetry() {
        val backoffMs = listOf(5_000L, 10_000L, 30_000L, 60_000L)
        var attempt = 0
        while (true) {
            try {
                collect()
                attempt = 0
            } catch (e: CancellationException) {
                throw e  // job cancelled externally — stop
            } catch (e: Exception) {
                status.value = ServiceStatus.RECONNECTING
                btStatus.value = ConnectionStatus.DISCONNECTED
                serverStatus.value = ConnectionStatus.DISCONNECTED
                val delay = backoffMs.getOrElse(attempt) { 60_000L }
                attempt = minOf(attempt + 1, backoffMs.lastIndex)
                updateNotification("Nouvelle tentative dans ${delay / 1000} s… (${e.message?.take(60)})")
                delay(delay)
            }
        }
    }

    private suspend fun collect() {
        val config = settings.snapshot()
        if (config.btDeviceMac.isBlank()) throw IllegalStateException("Aucun adaptateur Bluetooth configuré")
        when (config.publishMode) {
            PublishMode.MQTT ->
                if (config.mqttHost.isBlank()) throw IllegalStateException("Aucun serveur MQTT configuré")
            PublishMode.WEBHOOK -> buildWebhookUrl(config.haUrl, config.webhookId)  // throws if invalid
        }

        status.value = ServiceStatus.CONNECTING
        btStatus.value = ConnectionStatus.CONNECTING
        updateNotification("Connexion Bluetooth…")

        val bluetoothAdapter = getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw IllegalStateException("Bluetooth indisponible")

        val device = bluetoothAdapter.getRemoteDevice(config.btDeviceMac)
        val transport = BluetoothTransportFactory.create(device, applicationContext)
        transport.connect()

        val sink = TelemetrySink.create(config) { serverStatus.value = it }
        try {
            updateNotification("Initialisation ELM327 et recherche du protocole…")
            val executor = ObdCommandExecutor(transport, config.obdProtocol)
            executor.initialize()
            obdProtocolLabel.value = executor.detectedProtocol?.label ?: ""

            updateNotification("Recherche des PIDs…")
            val supportedPids = PidScanner(
                executor = executor,
                settings = settings,
                onProgress = { scanned, total ->
                    updateNotification("Recherche des PIDs ($scanned/$total)…")
                }
            ).scan()
            activePidCount.value = supportedPids.size
            btStatus.value = ConnectionStatus.CONNECTED

            // Mode 22 is manufacturer-specific and nothing is decoded yet: skip the
            // 256-request scan (several minutes on a KWP2000 ECU) until definitions exist
            val mode22Pids = if (Mode22Registry.definitions.isEmpty()) emptyMap() else {
                updateNotification("Recherche des PIDs Mode 22…")
                Mode22Scanner(
                    executor = executor,
                    settings = settings,
                    onProgress = { scanned, total ->
                        updateNotification("Recherche Mode 22 ($scanned/$total)…")
                    }
                ).scan()
            }

            val mac = device.address.replace(":", "")
            coroutineScope {
                updateNotification(
                    if (config.publishMode == PublishMode.WEBHOOK) "Démarrage du webhook…" else "Connexion MQTT…"
                )
                sink.start(this, mac, supportedPids, mode22Pids.keys)

                status.value = ServiceStatus.CONNECTED

                val activeFast = supportedPids.intersect(settings.fastPids.first())
                val activeSlow = supportedPids.intersect(settings.slowPids.first())

                updateNotification("Connecté — ${activeFast.size} rapides (${PidPoller.FAST_INTERVAL_SECONDS} s) + ${activeSlow.size} lents (${PidPoller.SLOW_INTERVAL_SECONDS} s)")

                val currentReadings = mutableMapOf<Int, Double>()

                PidPoller(executor = executor, fastPids = activeFast, slowPids = activeSlow).readings().collect { reading ->
                    currentReadings[reading.pid] = reading.value
                    val snapshot = currentReadings.toMap()
                    pidReadings.value = snapshot
                    sink.publish(reading, snapshot)
                    lastUpdateTime.value = reading.timestamp
                }
                coroutineContext.cancelChildren()  // stop sink background work if polling ever ends
            }
        } finally {
            withContext(NonCancellable) {
                sink.stop()
                transport.disconnect()
            }
            btStatus.value = ConnectionStatus.DISCONNECTED
            serverStatus.value = ConnectionStatus.DISCONNECTED
            pidReadings.value = emptyMap()
            obdProtocolLabel.value = ""
        }
    }

    private fun stopCollection() {
        val job = collectorJob ?: return
        collectorJob = null
        job.invokeOnCompletion {
            status.value = ServiceStatus.IDLE
            btStatus.value = ConnectionStatus.DISCONNECTED
            serverStatus.value = ConnectionStatus.DISCONNECTED
            pidReadings.value = emptyMap()
        }
        job.cancel()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Collecte OBD2",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OBD2 → Home Assistant")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        const val ACTION_START = "com.frodrigues.obdmqtt.START"
        const val ACTION_STOP = "com.frodrigues.obdmqtt.STOP"
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "obd_collector"

        val status = MutableStateFlow(ServiceStatus.IDLE)
        val activePidCount = MutableStateFlow(0)
        val lastUpdateTime = MutableStateFlow(0L)
        val btStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
        val serverStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
        val pidReadings = MutableStateFlow<Map<Int, Double>>(emptyMap())
        /** OBD protocol detected by the ELM327, e.g. "ISO 15765-4 CAN 11 bits 500 kbit/s". */
        val obdProtocolLabel = MutableStateFlow("")
    }
}
