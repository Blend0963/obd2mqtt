package com.frodrigues.obdmqtt.obd

import com.frodrigues.obdmqtt.bluetooth.FakeTransport
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PidPollerTest {

    private fun hex(pid: Int) = pid.toString(16).padStart(2, '0').uppercase()

    @Test
    fun `reads fast pids every cycle and spreads slow pids`() = runTest {
        val slow = (0x01..0x14).toSet()  // 20 slow PIDs → 2 per 1 s cycle over 10 s
        val responses = (slow + 0x0C).associate { "01${hex(it)}" to "41 ${hex(it)} 10 20" }
        val transport = FakeTransport(responses)
        val poller = PidPoller(ObdCommandExecutor(transport), fastPids = setOf(0x0C), slowPids = slow)

        val readings = poller.readings().take(3 * 3).toList()

        assertEquals(9, readings.size)
        assertEquals(
            listOf("010C", "0101", "0102", "010C", "0103", "0104", "010C", "0105", "0106"),
            transport.sentCommands
        )
    }

    @Test
    fun `every slow pid is read within the slow interval`() = runTest {
        val slow = (0x01..0x0D).toSet()  // 13 slow PIDs → 2 per cycle
        val responses = slow.associate { "01${hex(it)}" to "41 ${hex(it)} 10 20" }
        val transport = FakeTransport(responses)
        val poller = PidPoller(ObdCommandExecutor(transport), fastPids = emptySet(), slowPids = slow)

        val cycles = PidPoller.SLOW_INTERVAL_SECONDS / PidPoller.FAST_INTERVAL_SECONDS
        poller.readings().take(cycles * 2).toList()

        assertEquals(slow, transport.sentCommands.map { it.removePrefix("01").toInt(16) }.toSet())
    }
}
