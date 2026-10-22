package com.frodrigues.obdmqtt.obd

import com.frodrigues.obdmqtt.bluetooth.FakeTransport
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PidPollerTest {

    private fun hex(pid: Int) = pid.toString(16).padStart(2, '0').uppercase()

    private fun transportFor(pids: Set<Int>) =
        FakeTransport(pids.associate { "01${hex(it)}" to "41 ${hex(it)} 10 20" })

    @Test
    fun `reads fast pids back to back and spreads slow pids over time`() = runTest {
        val slow = (0x01..0x04).toSet()  // 4 slow PIDs → one every 2.5 s
        val transport = transportFor(slow + 0x0C)
        val poller = PidPoller(
            ObdCommandExecutor(transport), fastPids = setOf(0x0C), slowPids = slow,
            clock = { testScheduler.currentTime }
        )

        // Instant adapter → 100 ms cycles: 0C + first slow, then 24 cycles of 0C, then 0C + second slow
        val readings = poller.readings().take(2 + 24 + 2).toList()

        assertEquals(28, readings.size)
        assertEquals(listOf("010C", "0101"), transport.sentCommands.take(2))
        assertEquals(List(24) { "010C" }, transport.sentCommands.subList(2, 26))
        assertEquals(listOf("010C", "0102"), transport.sentCommands.subList(26, 28))
    }

    @Test
    fun `every slow pid is read within the slow interval`() = runTest {
        val slow = (0x01..0x0D).toSet()
        val transport = transportFor(slow)
        val poller = PidPoller(
            ObdCommandExecutor(transport), fastPids = emptySet(), slowPids = slow,
            clock = { testScheduler.currentTime }
        )

        poller.readings().take(slow.size).toList()

        assertEquals(slow, transport.sentCommands.map { it.removePrefix("01").toInt(16) }.toSet())
        assertTrue(testScheduler.currentTime < PidPoller.SLOW_INTERVAL_MS)
    }

    @Test
    fun `reports each cycle duration`() = runTest {
        val cycles = mutableListOf<Long>()
        val poller = PidPoller(
            ObdCommandExecutor(transportFor(setOf(0x0C, 0x0D))), fastPids = setOf(0x0C, 0x0D), slowPids = emptySet(),
            clock = { testScheduler.currentTime }, onCycle = { cycles += it }
        )

        poller.readings().take(2 * 3).toList()

        assertEquals(2, cycles.size)  // the third cycle is cut off by take() before it ends
    }
}
