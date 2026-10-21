package com.frodrigues.obdmqtt.bluetooth

import android.bluetooth.BluetoothDevice
import android.content.Context
import com.frodrigues.obdmqtt.settings.BtTransport
import kotlinx.coroutines.flow.StateFlow

interface BluetoothTransport {
    suspend fun connect()
    suspend fun disconnect()
    suspend fun sendCommand(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): String
    val isConnected: StateFlow<Boolean>
}

const val DEFAULT_TIMEOUT_MS = 5_000L

object BluetoothTransportFactory {
    /**
     * [transport] comes from how the adapter was chosen: a device found by the in-app BLE
     * scan isn't bonded, so Android may not know its type yet (DEVICE_TYPE_UNKNOWN).
     */
    fun create(device: BluetoothDevice, context: Context, transport: BtTransport = BtTransport.AUTO): BluetoothTransport {
        val ble = when (transport) {
            BtTransport.BLE -> true
            BtTransport.CLASSIC -> false
            BtTransport.AUTO -> device.type == BluetoothDevice.DEVICE_TYPE_LE
        }
        return if (ble) BleTransport(context, device) else ClassicTransport(device)
    }
}
