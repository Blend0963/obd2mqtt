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
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.frodrigues.obdmqtt.MainActivity
import com.frodrigues.obdmqtt.bluetooth.BluetoothTransportFactory
import android.util.Log
import com.frodrigues.obdmqtt.obd.ClearResult
import com.frodrigues.obdmqtt.obd.DiagnosticReport
import com.frodrigues.obdmqtt.obd.DtcReader
import com.frodrigues.obdmqtt.obd.Mode22Registry
import com.frodrigues.obdmqtt.obd.Mode22Scanner
import com.frodrigues.obdmqtt.obd.ObdCommandExecutor
import com.frodrigues.obdmqtt.obd.PidPoller
import com.frodrigues.obdmqtt.obd.PidScanner
import com.frodrigues.obdmqtt.settings.AppSettings
import com.frodrigues.obdmqtt.settings.PublishMode
import com.frodrigues.obdmqtt.settings.dataStore
import com.frodrigues.obdmqtt.settings.AppConfig
import com.frodrigues.obdmqtt.webhook.ClearButtonWatcher
import com.frodrigues.obdmqtt.webhook.HaApiClient
import com.frodrigues.obdmqtt.webhook.HaEntities
import com.frodrigues.obdmqtt.webhook.buildWebhookUrl
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

enum class ServiceStatus { IDLE, CONNECTING, CONNECTED, RECONNECTING, WAITING_ENGINE }

/** Battery below the charging voltage: the engine is off, so the ECU is left alone. */
class EngineOffException(val voltage: Double) :
    Exception("Moteur arrêté (%.1f V)".format(java.util.Locale.FRANCE, voltage))

sealed interface DiagRequest {
    data object Read : DiagRequest
    data class Clear(val requestedAt: Long = System.currentTimeMillis()) : DiagRequest
}
enum class ConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED }

class OBDCollectorService : LifecycleService() {

    inner class LocalBinder : Binder() {
        fun getService(): OBDCollectorService = this@OBDCollectorService
    }

    private val binder = LocalBinder()

    private lateinit var settings: AppSettings
    private var wakeLock: PowerManager.WakeLock? = null
    private var collectorJob: Job? = null
    /** Set once the ECU answered during the current attempt. */
    @Volatile private var ecuConnected = false
    private var lastClear: ClearResult? = null
    private val clearButtonWatcher = ClearButtonWatcher()

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
        // ServiceCompat drops the service type on Android < 10, where it doesn't exist
        ServiceCompat.startForeground(
            this,
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
        var engineOff = false
        var bypassEngineGate = false
        while (true) {
            ecuConnected = false
            try {
                collect(bypassEngineGate)
                attempt = 0
            } catch (e: CancellationException) {
                throw e  // job cancelled externally — stop
            } catch (e: EngineOffException) {
                engineOff = true
                engineOffVoltage.value = e.voltage
            } catch (e: Exception) {
                Log.w(TAG, "Collect failed: ${e.message}")
                if (bypassEngineGate) failPendingRequests("Le calculateur ne répond pas : mettez le contact puis réessayez")
                if (ecuConnected) { engineOff = false; attempt = 0 }
                updateNotification("Erreur : ${e.message?.take(80)}")
            }
            btStatus.value = ConnectionStatus.DISCONNECTED
            serverStatus.value = ConnectionStatus.DISCONNECTED

            val config = settings.snapshot()
            bypassEngineGate = if (engineOff && config.engineRunningOnly) {
                status.value = ServiceStatus.WAITING_ENGINE
                val volts = engineOffVoltage.value?.let { "%.1f V".format(java.util.Locale.FRANCE, it) } ?: "?"
                updateNotification("Moteur arrêté ($volts) — en veille, vérification toutes les ${ENGINE_OFF_RECHECK_MS / 60_000} min")
                waitForWakeup(config, ENGINE_OFF_RECHECK_MS)
            } else {
                status.value = ServiceStatus.RECONNECTING
                val delay = backoffMs.getOrElse(attempt) { 60_000L }
                attempt = minOf(attempt + 1, backoffMs.lastIndex)
                updateNotification("Nouvelle tentative dans ${delay / 1000} s…")
                waitForWakeup(config, delay)
            }
        }
    }

    /**
     * Waits [timeoutMs], polling the HA clear button meanwhile. Returns true when a request
     * (app or HA button) asks to talk to the ECU now, even with the engine off.
     */
    private suspend fun waitForWakeup(config: AppConfig, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) return false
            val woken = withTimeoutOrNull(minOf(remaining, HA_BUTTON_POLL_MS)) { wakeups.receive() } != null
            if (woken) return true
            pollHaClearButton(config)
        }
    }

    private fun failPendingRequests(message: String) {
        while (diagRequests.tryReceive().isSuccess) Unit
        while (wakeups.tryReceive().isSuccess) Unit
        if (clearPending.value) {
            clearPending.value = false
            clearResult.value = ClearResult(false, message)
        }
    }

    /** Reads the HA input_button (webhook mode + token only); a fresh press requests a clear. */
    private suspend fun pollHaClearButton(config: AppConfig) {
        if (config.publishMode != PublishMode.WEBHOOK || config.haToken.isBlank()) return
        try {
            val state = HaApiClient(config.haUrl, config.haToken)
                .getState(HaEntities(config).clearButtonEntityId)
            if (clearButtonWatcher.onState(state)) {
                Log.i(TAG, "Clear requested from Home Assistant")
                requestClearFaults()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "HA button poll failed: ${e.message}")
        }
    }

    private suspend fun collect(bypassEngineGate: Boolean) {
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
        val transport = BluetoothTransportFactory.create(device, applicationContext, config.btTransport)
        transport.connect()

        val sink = TelemetrySink.create(config, onStatus = { serverStatus.value = it }, onClearRequest = ::requestClearFaults)
        try {
            val executor = ObdCommandExecutor(transport, config.obdProtocol)
            executor.resetAdapter()

            // Only the adapter is queried here (AT RV): a parked car's ECUs are never woken up
            if (config.engineRunningOnly && !bypassEngineGate) {
                val volts = executor.readBatteryVoltage()
                Log.d(TAG, "Battery: $volts V")
                if (volts != null && volts < ENGINE_RUNNING_VOLTAGE) throw EngineOffException(volts)
            }

            updateNotification("Connexion au calculateur et recherche du protocole…")
            executor.connectEcu()
            ecuConnected = true
            engineOffVoltage.value = null
            while (wakeups.tryReceive().isSuccess) Unit  // requests are handled by the diagnostics loop below
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

                launch { diagnosticsLoop(DtcReader(executor), sink) }
                launch {
                    while (isActive) {
                        delay(HA_BUTTON_POLL_MS)
                        pollHaClearButton(config)
                    }
                }

                val currentReadings = mutableMapOf<Int, Double>()

                PidPoller(executor = executor, fastPids = activeFast, slowPids = activeSlow).readings().collect { reading ->
                    currentReadings[reading.pid] = reading.value
                    val snapshot = currentReadings.toMap()
                    pidReadings.value = snapshot
                    sink.publish(reading, snapshot)
                    lastUpdateTime.value = reading.timestamp
                }
                coroutineContext.cancelChildren()  // stop background work if polling ever ends
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

    /** Reads fault codes every [DIAG_INTERVAL_MS] and serves read/clear requests in between. */
    private suspend fun diagnosticsLoop(reader: DtcReader, sink: TelemetrySink) {
        suspend fun refresh() {
            try {
                val report = reader.read(lastClear)
                diagnostics.value = report
                sink.publishDiagnostics(report)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Diagnostics read failed: ${e.message}")
            }
        }

        refresh()
        while (true) {
            val request = withTimeoutOrNull(DIAG_INTERVAL_MS) { diagRequests.receive() }
            // The request is served now: its wakeup must not later bypass the engine-off gate
            if (request != null) while (wakeups.tryReceive().isSuccess) Unit
            if (request is DiagRequest.Clear) {
                // Never replay a stale request (e.g. made while the ignition was off)
                if (System.currentTimeMillis() - request.requestedAt <= CLEAR_REQUEST_MAX_AGE_MS) {
                    updateNotification("Effacement des défauts…")
                    val result = try {
                        reader.clear()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ClearResult(false, "Erreur : ${e.message}")
                    }
                    lastClear = result
                    clearResult.value = result
                    updateNotification(result.message)
                }
                clearPending.value = false
            }
            refresh()
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
            engineOffVoltage.value = null
            clearPending.value = false
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
        /** Last fault-code read; kept after disconnect so the UI shows the last known state. */
        val diagnostics = MutableStateFlow<DiagnosticReport?>(null)
        val clearResult = MutableStateFlow<ClearResult?>(null)
        val clearPending = MutableStateFlow(false)
        /** Battery voltage seen when the engine was found off (WAITING_ENGINE). */
        val engineOffVoltage = MutableStateFlow<Double?>(null)

        private const val TAG = "OBDCollectorService"
        /** Alternator charging ⇒ engine running. A rested battery sits at 12.2–12.8 V. */
        const val ENGINE_RUNNING_VOLTAGE = 13.3
        const val ENGINE_OFF_RECHECK_MS = 5 * 60_000L
        const val DIAG_INTERVAL_MS = 60_000L
        const val CLEAR_REQUEST_MAX_AGE_MS = 2 * 60_000L
        private const val HA_BUTTON_POLL_MS = 15_000L

        private val diagRequests = Channel<DiagRequest>(capacity = 8)
        private val wakeups = Channel<Unit>(Channel.CONFLATED)

        /** Reads the fault codes now — wakes the service even if the engine is off (ignition must be on). */
        fun requestDiagnosticsRead() {
            diagRequests.trySend(DiagRequest.Read)
            wakeups.trySend(Unit)
        }

        /** Clears the fault codes (Mode 04) — from the app, the HA button or MQTT. */
        fun requestClearFaults() {
            clearResult.value = null
            clearPending.value = true
            diagRequests.trySend(DiagRequest.Clear())
            wakeups.trySend(Unit)
        }
    }
}
