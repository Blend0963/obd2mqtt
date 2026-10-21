package com.frodrigues.obdmqtt.bluetooth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeTransport(
    private val responses: Map<String, String> = emptyMap()
) : BluetoothTransport {

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    val sentCommands = mutableListOf<String>()

    override suspend fun connect() {
        _isConnected.value = true
    }

    override suspend fun disconnect() {
        _isConnected.value = false
    }

    val timeouts = mutableMapOf<String, Long>()

    override suspend fun sendCommand(command: String, timeoutMs: Long): String {
        timeouts[command.trim()] = timeoutMs
        sentCommands.add(command.trim())
        return responses[command.trim()] ?: "NO DATA"
    }
}
