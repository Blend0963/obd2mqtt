package com.frodrigues.obdmqtt.bluetooth

import android.bluetooth.BluetoothDevice
import android.content.Context
import kotlinx.coroutines.flow.StateFlow

interface BluetoothTransport {
    suspend fun connect()
    suspend fun disconnect()
    suspend fun sendCommand(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): String
    val isConnected: StateFlow<Boolean>
}

const val DEFAULT_TIMEOUT_MS = 5_000L

object BluetoothTransportFactory {
    fun create(device: BluetoothDevice, context: Context): BluetoothTransport {
        return when (device.type) {
            BluetoothDevice.DEVICE_TYPE_LE -> BleTransport(context, device)
            else -> ClassicTransport(device)
        }
    }
}
