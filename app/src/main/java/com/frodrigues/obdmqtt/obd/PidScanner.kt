package com.frodrigues.obdmqtt.obd

import android.util.Log
import com.frodrigues.obdmqtt.settings.AppSettings
import kotlinx.coroutines.flow.first

class PidScanner(
    private val executor: ObdCommandExecutor,
    private val settings: AppSettings,
    private val onProgress: suspend (scanned: Int, total: Int) -> Unit = { _, _ -> }
) {
    // Support bitmask PIDs — not real data PIDs
    private val supportPids = setOf(0x00, 0x20, 0x40, 0x60, 0x80, 0xA0, 0xC0)
    private val scanRange = (0x01..0xFF).filter { it !in supportPids }

    /**
     * Returns cached PIDs if available, otherwise runs a full brute-force scan.
     * Cache persists across app restarts. Clear via AppSettings.clearPidCache().
     */
    suspend fun scan(): Set<Int> {
        val cached = settings.cachedPids.first()
        if (cached.isNotEmpty()) {
            Log.d(TAG, "Cache hit: ${cached.size} PIDs (use 'Rescan PIDs' in Settings to refresh)")
            return cached
        }
        return bruteForce()
    }

    private suspend fun bruteForce(): Set<Int> {
        val supported = mutableSetOf<Int>()
        val total = scanRange.size
        Log.d(TAG, "Brute-force scan: probing $total PIDs (0x01–0xFF)...")

        scanRange.forEachIndexed { index, pid ->
            onProgress(index + 1, total)

            val pidHex = pid.toString(16).padStart(2, '0').uppercase()
            val response = executor.sendCommand("01$pidHex")
            val bytes = ObdResponseParser.extractDataBytes(response, pid)

            if (bytes != null && bytes.isNotEmpty()) {
                supported.add(pid)
                val name = PidRegistry.definitions[pid]?.name ?: "Unknown"
                Log.d(TAG, "✓ 0x$pidHex $name  [${bytes.size}B raw=${bytes.take(4).joinToString(" ") { "%02X".format(it) }}]")
            } else {
                Log.v(TAG, "✗ 0x$pidHex: ${response.trim().take(20)}")
            }
        }

        Log.d(TAG, "Brute-force done: ${supported.size}/$total PIDs respond")
        Log.d(TAG, "Known in registry: ${supported.count { PidRegistry.definitions.containsKey(it) }}")
        Log.d(TAG, "Unknown (raw): ${supported.count { !PidRegistry.definitions.containsKey(it) }}")

        // Battery voltage read by the adapter itself — available on any car
        if (PidParser.parse(PidRegistry.ADAPTER_VOLTAGE, executor.sendCommand("ATRV")) != null) {
            supported.add(PidRegistry.ADAPTER_VOLTAGE)
        }

        settings.savePidCache(supported)
        return supported
    }

    companion object {
        private const val TAG = "PidScanner"
    }
}
