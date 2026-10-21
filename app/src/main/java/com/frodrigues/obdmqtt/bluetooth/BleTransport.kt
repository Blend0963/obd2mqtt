package com.frodrigues.obdmqtt.bluetooth

import android.bluetooth.*
import android.content.Context
import android.os.Build
import com.frodrigues.obdmqtt.util.DebugLog
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.util.UUID

/** GATT layout of a BLE ELM327 clone: one service, a write characteristic and a notify one (may be the same). */
data class BleProfile(val service: UUID, val write: UUID, val notify: UUID) {
    constructor(service: String, write: String, notify: String = write) :
        this(UUID.fromString(service), UUID.fromString(write), UUID.fromString(notify))
}

class BleTransport(
    private val context: Context,
    private val device: BluetoothDevice,
    private val profiles: List<BleProfile> = KNOWN_PROFILES
) : BluetoothTransport {

    private val cccdUUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private val responseChannel = Channel<String>(Channel.BUFFERED)
    private val responseBuffer = StringBuilder()
    private var connectLatch = CompletableDeferred<Unit>()

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.discoverServices()
            } else {
                _isConnected.value = false
                if (!connectLatch.isCompleted) {
                    connectLatch.completeExceptionally(IOException("BLE déconnecté, status=$status"))
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                connectLatch.completeExceptionally(IOException("Découverte des services échouée : $status"))
                return
            }
            val match = profiles.firstNotNullOfOrNull { p ->
                val service = gatt.getService(p.service) ?: return@firstNotNullOfOrNull null
                val w = service.getCharacteristic(p.write) ?: return@firstNotNullOfOrNull null
                val n = service.getCharacteristic(p.notify) ?: return@firstNotNullOfOrNull null
                w to n
            }
            if (match == null) {
                DebugLog.add("BLE : aucun service OBD connu (${gatt.services.joinToString { it.uuid.toString().take(8) }})")
                connectLatch.completeExceptionally(IOException("Service OBD BLE inconnu sur cet adaptateur"))
                return
            }
            val (w, notifyChar) = match
            DebugLog.add("BLE : service ${w.service.uuid} trouvé")
            writeChar = w
            gatt.setCharacteristicNotification(notifyChar, true)
            val descriptor = notifyChar.getDescriptor(cccdUUID)
            if (descriptor == null) {
                connectLatch.completeExceptionally(IOException("Descripteur CCCD introuvable"))
                return
            }
            val useIndication = notifyChar.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0 &&
                notifyChar.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
            val enable = if (useIndication) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                         else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, enable)
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = enable
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _isConnected.value = true
                connectLatch.complete(Unit)
            } else {
                connectLatch.completeExceptionally(IOException("Écriture CCCD échouée : $status"))
            }
        }

        // Android 13+
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) = onData(value)

        // Android 8–12 only call this one
        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            characteristic.value?.let { onData(it) }
        }

        private fun onData(value: ByteArray) {
            val chunk = value.toString(Charsets.UTF_8)
            responseBuffer.append(chunk)
            if (responseBuffer.contains('>')) {
                val response = responseBuffer.toString().substringBefore('>').trim()
                responseBuffer.clear()
                val sent = responseChannel.trySend(response)
                if (sent.isFailure) {
                    // channel full — response dropped, sendCommand will timeout
                    android.util.Log.w("BleTransport", "Response dropped (channel full): $response")
                }
            }
        }
    }

    override suspend fun connect() {
        responseBuffer.clear()
        connectLatch = CompletableDeferred()
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        withTimeout(10_000) { connectLatch.await() }
    }

    override suspend fun disconnect() {
        _isConnected.value = false
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        writeChar = null
    }

    override suspend fun sendCommand(command: String, timeoutMs: Long): String {
        val g = gatt ?: throw IOException("Not connected")
        val w = writeChar ?: throw IOException("Write char not found")
        // Drop a late reply left over from a previous timed-out command
        while (responseChannel.tryReceive().isSuccess) Unit
        val writeType =
            if (w.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0 &&
                w.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val bytes = "$command\r".toByteArray(Charsets.UTF_8)
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(w, bytes, writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            w.writeType = writeType
            @Suppress("DEPRECATION")
            w.value = bytes
            @Suppress("DEPRECATION")
            g.writeCharacteristic(w)
        }
        if (!ok) throw IOException("BLE write failed")
        return withTimeout(timeoutMs) { responseChannel.receive() }
    }

    companion object {
        val KNOWN_PROFILES = listOf(
            // Vgate iCar Pro BLE 4.0 (and other Vgate BLE adapters)
            BleProfile("e7810a71-73ae-499d-8c15-faa9aef0c3f2", "bef8d6c9-9c21-4c9e-b632-bd58c1009f9f"),
            // Generic ELM327 BLE clones
            BleProfile(
                "0000fff0-0000-1000-8000-00805f9b34fb",
                "0000fff1-0000-1000-8000-00805f9b34fb",
                "0000fff2-0000-1000-8000-00805f9b34fb"
            ),
            // HM-10 style modules
            BleProfile("0000ffe0-0000-1000-8000-00805f9b34fb", "0000ffe1-0000-1000-8000-00805f9b34fb"),
        )
    }
}
