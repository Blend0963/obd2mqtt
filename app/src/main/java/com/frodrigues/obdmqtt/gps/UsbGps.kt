package com.frodrigues.obdmqtt.gps

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.frodrigues.obdmqtt.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The GPS receiver plugged into the car unit's USB port, read here and given to
 * Android as its GPS ([MockLocation]): what UsbGps4Droid did, without a second
 * app and without pressing « Start » — it starts and stops with the collection,
 * and comes back by itself when the receiver is plugged in again.
 *
 * Baud rate: the last one that worked, else 4800 (NMEA's own), 9600, 38400,
 * 115200, 19200, 57600, each tried for a few seconds until sentences arrive.
 */
class UsbGps(private val context: Context, private val scope: CoroutineScope) {

    private val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val mock = MockLocation(context)
    private var job: Job? = null
    private var receiverOn = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED, ACTION_PERMISSION -> restart()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    status.value = "Récepteur GPS débranché"
                    job?.cancel()
                }
            }
        }
    }

    fun start() {
        if (!receiverOn) {
            ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                addAction(ACTION_PERMISSION)
            }, ContextCompat.RECEIVER_EXPORTED)  // USB broadcasts come from the system
            receiverOn = true
        }
        restart()
    }

    fun stop() {
        job?.cancel()
        job = null
        if (receiverOn) { runCatching { context.unregisterReceiver(receiver) }; receiverOn = false }
        mock.stop()
        running.value = false
        status.value = "GPS USB arrêté"
    }

    private fun restart() {
        job?.cancel()
        job = scope.launch(Dispatchers.IO) { run() }
    }

    private fun findDevice(): UsbDevice? =
        usb.deviceList.values.firstOrNull { UsbSerialPort.looksLikeSerial(it) }

    private suspend fun run() {
        val device = findDevice()
        if (device == null) { status.value = "Aucun récepteur GPS USB branché"; running.value = false; return }
        if (!usb.hasPermission(device)) {
            status.value = "Autoriser l'accès au récepteur GPS (fenêtre Android)"
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(context, 0, Intent(ACTION_PERMISSION).setPackage(context.packageName), flags)
            usb.requestPermission(device, pi)  // the answer comes back as ACTION_PERMISSION → restart
            return
        }
        mock.start()?.let { why -> status.value = why; DebugLog.add("GPS USB : $why") }
        val conn = usb.openDevice(device) ?: run { status.value = "Récepteur GPS impossible à ouvrir"; return }
        val port = UsbSerialPort(device, conn)
        try {
            val bauds = (listOfNotNull(lastBaud) + BAUDS).distinct()
            if (!port.open(bauds.first())) { status.value = "Récepteur GPS sans liaison série"; return }
            running.value = true
            DebugLog.add("GPS USB : ${device.productName ?: device.deviceName} (${port.chip})")
            var baudIndex = 0
            var acc = NmeaAccumulator()
            var since = System.currentTimeMillis()
            val split = LineSplitter { line ->
                acc.feed(line)?.let { fix ->
                    if (!mock.active) mock.start()
                    mock.publish(fix)
                    lastFix.value = fix
                    status.value = "GPS USB : ${fix.satellites ?: "?"} satellites · ±${fix.accuracyM.toInt()} m"
                }
            }
            val buf = ByteArray(512)
            status.value = "GPS USB : recherche du signal…"
            while (currentCoroutineContext().isActive) {
                val n = port.read(buf, 1000)
                if (n < 0 && findDevice() == null) break
                if (n > 0) split.feed(buf, n)
                // No sentence at this speed after a while: try the next one
                if (acc.sentences == 0 && System.currentTimeMillis() - since > BAUD_TRY_MS) {
                    baudIndex = (baudIndex + 1) % bauds.size
                    port.setBaud(bauds[baudIndex])
                    acc = NmeaAccumulator()
                    since = System.currentTimeMillis()
                    status.value = "GPS USB : essai à ${bauds[baudIndex]} bauds…"
                } else if (acc.sentences > 0 && lastBaud != bauds[baudIndex]) {
                    lastBaud = bauds[baudIndex]
                    DebugLog.add("GPS USB : ${bauds[baudIndex]} bauds")
                }
                if (n <= 0) delay(50)
            }
        } finally {
            port.close()
            running.value = false
        }
    }

    companion object {
        const val ACTION_PERMISSION = "com.frodrigues.obdmqtt.USB_GPS_PERMISSION"
        private val BAUDS = listOf(4800, 9600, 38400, 115200, 19200, 57600)
        private const val BAUD_TRY_MS = 4_000L

        /** For the main screen: what the receiver is doing. */
        val status = MutableStateFlow("")
        val running = MutableStateFlow(false)
        val lastFix = MutableStateFlow<GpsFix?>(null)
        /** The speed that worked, kept for the next start of this process. */
        @Volatile var lastBaud: Int? = null
    }
}
