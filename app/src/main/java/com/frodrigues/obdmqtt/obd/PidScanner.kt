package com.frodrigues.obdmqtt.obd

import android.util.Log
import com.frodrigues.obdmqtt.settings.AppSettings
import com.frodrigues.obdmqtt.util.DebugLog
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
        // Standard support bitmasks answer in a few requests; probing 254 PIDs one by one
        // takes minutes on a slow KWP2000 ECU, so it is only the fallback
        val fromBitmask = bitmask()
        if (fromBitmask.isNotEmpty()) {
            DebugLog.add("PIDs annoncés par le calculateur : ${fromBitmask.size}")
            return save(fromBitmask)
        }
        DebugLog.add("Pas de liste de PIDs : test un par un")
        return bruteForce()
    }

    /** Reads PIDs 00, 20, 40… (32-bit support masks) while the next mask is announced. */
    private suspend fun bitmask(): Set<Int> {
        val supported = mutableSetOf<Int>()
        var base = 0x00
        while (base <= 0xC0) {
            val bytes = ObdResponseParser.extractDataBytes(
                executor.sendCommand("01" + base.toString(16).padStart(2, '0').uppercase()), base
            )
            if (bytes == null || bytes.size < 4) break
            for (i in 0 until 32) {
                if (bytes[i / 8].toInt() and (0x80 shr (i % 8)) != 0) supported += base + i + 1
            }
            if (base + 0x20 !in supported) break
            base += 0x20
        }
        return supported - supportPids
    }

    private suspend fun save(pids: Set<Int>): Set<Int> {
        val supported = pids.toMutableSet()
        // Battery voltage read by the adapter itself — available on any car
        if (PidParser.parse(PidRegistry.ADAPTER_VOLTAGE, executor.sendCommand("ATRV")) != null) {
            supported.add(PidRegistry.ADAPTER_VOLTAGE)
        }
        settings.savePidCache(supported)
        return supported
    }

    private suspend fun bruteForce(): Set<Int> {
        val supported = mutableSetOf<Int>()
        val total = scanRange.size
        Log.d(TAG, "Brute-force scan: probing $total PIDs (0x01–0xFF)...")

        var consecutiveErrors = 0
        scanRange.forEachIndexed { index, pid ->
            onProgress(index + 1, total)

            val pidHex = pid.toString(16).padStart(2, '0').uppercase()
            // An unsupported PID may time out instead of answering NO DATA: skip it, but
            // give up if the link itself looks dead
            val response = try {
                executor.sendCommand("01$pidHex").also { consecutiveErrors = 0 }
            } catch (e: java.io.IOException) {
                if (++consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) throw e
                ""
            }
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

        return save(supported)
    }

    companion object {
        private const val TAG = "PidScanner"
        private const val MAX_CONSECUTIVE_ERRORS = 5
    }
}
