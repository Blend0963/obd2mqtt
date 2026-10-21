package com.frodrigues.obdmqtt.obd

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.IOException

data class PidReading(
    val pid: Int,
    val value: Double,
    val timestamp: Long = System.currentTimeMillis()
)

class PidPoller(
    private val executor: ObdCommandExecutor,
    private val fastPids: Set<Int>,
    private val slowPids: Set<Int>
) {
    private val slowEvery = SLOW_INTERVAL_SECONDS / FAST_INTERVAL_SECONDS

    /**
     * Every cycle reads all fast PIDs plus a slice of the slow ones, so each slow PID
     * is still read once per [SLOW_INTERVAL_SECONDS] without a burst that would
     * delay the fast ones (matters on slow KWP2000 ECUs).
     */
    fun readings(): Flow<PidReading> = flow {
        var consecutiveFailures = 0
        val slowList = slowPids.toList()
        val slowPerCycle = if (slowList.isEmpty()) 0 else (slowList.size + slowEvery - 1) / slowEvery
        var slowIndex = 0
        while (true) {
            val cycleStart = System.currentTimeMillis()

            val batch = fastPids.toList() + List(slowPerCycle) { slowList[(slowIndex + it) % slowList.size] }
            slowIndex = if (slowList.isEmpty()) 0 else (slowIndex + slowPerCycle) % slowList.size

            for (pid in batch) {
                try {
                    val response = executor.sendCommand(PidRegistry.command(pid))
                    PidParser.parse(pid, response)?.let {
                        consecutiveFailures = 0
                        emit(PidReading(pid, it))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    consecutiveFailures++
                    Log.w(TAG, "PID 0x${pid.toString(16).uppercase()} failed ($consecutiveFailures): ${e.message}")
                    if (consecutiveFailures >= MAX_FAILURES)
                        throw IOException("$consecutiveFailures échecs consécutifs — connexion perdue", e)
                }
            }

            val remaining = FAST_INTERVAL_SECONDS * 1000L - (System.currentTimeMillis() - cycleStart)
            if (remaining > 0) delay(remaining)
        }
    }

    companion object {
        private const val TAG = "PidPoller"
        const val FAST_INTERVAL_SECONDS = 1
        const val SLOW_INTERVAL_SECONDS = 10
        const val FAST_PID_LIMIT = 10
        private const val MAX_FAILURES = 5
    }
}
