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
    private val slowPids: Set<Int>,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Duration of each cycle (fast PIDs + due slow ones), in ms, for the UI. */
    private val onCycle: (Long) -> Unit = {}
) {
    /**
     * Reads the fast PIDs back to back, as fast as the ECU answers (a KWP2000 ECU takes
     * ~80 ms per request), and slips in one slow PID every [SLOW_INTERVAL_MS] / n, so each
     * slow PID is still read once per [SLOW_INTERVAL_MS] without slowing the fast ones.
     */
    fun readings(): Flow<PidReading> = flow {
        var consecutiveFailures = 0
        val fastList = fastPids.toList()
        val slowList = slowPids.toList()
        val slowStepMs = if (slowList.isEmpty()) 0L else SLOW_INTERVAL_MS / slowList.size
        var slowIndex = 0
        var nextSlowAt = clock()
        while (true) {
            val cycleStart = clock()

            val batch = fastList.toMutableList()
            var slowDue = 0
            while (slowList.isNotEmpty() && cycleStart >= nextSlowAt && slowDue < MAX_SLOW_PER_CYCLE) {
                batch += slowList[slowIndex]
                slowIndex = (slowIndex + 1) % slowList.size
                nextSlowAt += slowStepMs
                slowDue++
            }
            // More slow PIDs than the link can read: don't build an ever-growing backlog
            if (slowList.isNotEmpty() && cycleStart - nextSlowAt > SLOW_INTERVAL_MS) nextSlowAt = cycleStart

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

            val elapsed = clock() - cycleStart
            if (fastList.isNotEmpty()) onCycle(elapsed)
            // Without fast PIDs, sleep until the next slow one; otherwise only guard
            // against spinning when the adapter answers instantly
            val wait = if (fastList.isEmpty()) nextSlowAt - clock() else MIN_CYCLE_MS - elapsed
            if (wait > 0) delay(wait)
        }
    }

    companion object {
        private const val TAG = "PidPoller"
        const val SLOW_INTERVAL_SECONDS = 10
        const val SLOW_INTERVAL_MS = SLOW_INTERVAL_SECONDS * 1000L
        const val MIN_CYCLE_MS = 100L
        private const val MAX_SLOW_PER_CYCLE = 2
        const val FAST_PID_LIMIT = 10
        private const val MAX_FAILURES = 5
    }
}
