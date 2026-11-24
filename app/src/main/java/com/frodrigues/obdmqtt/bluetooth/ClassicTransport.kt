package com.frodrigues.obdmqtt.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import com.frodrigues.obdmqtt.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

@SuppressLint("MissingPermission")
class ClassicTransport(private val device: BluetoothDevice) : BluetoothTransport {

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    /**
     * Tries several RFCOMM socket kinds: some car head units can't pair the adapter
     * (no PIN dialog), and many ELM327 adapters accept an insecure link without pairing.
     * Unpaired adapters try the insecure socket first, so no pairing request is triggered.
     */
    override suspend fun connect() = withContext(Dispatchers.IO) {
        @Suppress("DEPRECATION")
        runCatching { BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery() }  // discovery slows RFCOMM down

        val secure = SocketFactory("secure") { it.createRfcommSocketToServiceRecord(SPP_UUID) }
        val insecure = SocketFactory("insecure") { it.createInsecureRfcommSocketToServiceRecord(SPP_UUID) }
        // Hidden API used by most OBD apps: raw RFCOMM channel 1, no SDP lookup
        val channel1 = SocketFactory("channel 1") {
            it.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType).invoke(it, 1) as BluetoothSocket
        }
        val base = if (device.bondState == BluetoothDevice.BOND_BONDED) listOf(secure, insecure, channel1)
                   else listOf(insecure, channel1, secure)
        // The kind that worked last time first: one short try instead of up to three
        // long ones (each failed one holds the head unit's Bluetooth radio).
        val order = base.sortedBy { if (it.name == lastGood) 0 else 1 }

        var lastError: Exception? = null
        for (factory in order) {
            val s = try { factory.create(device) } catch (e: Exception) { lastError = e; continue }
            // connect() ignores thread interrupts: closing the socket is the only way to abort it
            val watchdog = launch { delay(CONNECT_TIMEOUT_MS); runCatching { s.close() } }
            try {
                try { s.connect() } finally { watchdog.cancel() }
                Log.d(TAG, "Connected with ${factory.name} socket")
                lastGood = factory.name
                DebugLog.add("Bluetooth connecté (socket ${factory.name})")
                socket = s
                inputStream = s.inputStream
                outputStream = s.outputStream
                _isConnected.value = true
                return@withContext
            } catch (e: IOException) {
                runCatching { s.close() }
                Log.w(TAG, "${factory.name} socket failed: ${e.message}")
                DebugLog.add("Socket ${factory.name} : échec (${e.message})")
                lastError = e
            }
        }
        throw IOException("Connexion Bluetooth impossible : ${lastError?.message}", lastError)
    }

    private class SocketFactory(val name: String, val create: (BluetoothDevice) -> BluetoothSocket)

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        _isConnected.value = false
        runCatching { socket?.close() }
        socket = null
        inputStream = null
        outputStream = null
    }

    override suspend fun sendCommand(command: String, timeoutMs: Long): String {
        try {
            return withTimeout(timeoutMs) {
                withContext(Dispatchers.IO) {
                    val out = outputStream ?: throw IOException("Not connected")
                    val inp = inputStream ?: throw IOException("Not connected")
                    // Drop a late reply left over from a previous timed-out command
                    if (inp.available() > 0) inp.skip(inp.available().toLong())
                    out.write("$command\r".toByteArray(Charsets.UTF_8))
                    out.flush()
                    readUntilPrompt(inp)
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw IOException("Command timed out: $command")
        }
    }

    // Read by blocks, not byte by byte: one system call per chunk the adapter sends.
    // The ELM327 ends each answer with '>' and sends nothing after it until the next command.
    private suspend fun readUntilPrompt(inp: InputStream): String = runInterruptible {
        val buffer = StringBuilder()
        val chunk = ByteArray(256)
        while (true) {
            val read = inp.read(chunk)
            if (read == -1) throw IOException("Stream closed")
            for (i in 0 until read) {
                val ch = (chunk[i].toInt() and 0xFF).toChar()
                if (ch == '>') return@runInterruptible buffer.toString().trim()
                buffer.append(ch)
            }
        }
        @Suppress("UNREACHABLE_CODE")
        buffer.toString().trim()
    }

    companion object {
        private const val TAG = "ClassicTransport"
        private const val CONNECT_TIMEOUT_MS = 8_000L
        /** The socket kind that connected last (kept while the app runs). */
        @Volatile private var lastGood: String? = null
        private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
