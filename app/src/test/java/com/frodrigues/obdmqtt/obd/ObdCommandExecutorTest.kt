package com.frodrigues.obdmqtt.obd

import com.frodrigues.obdmqtt.bluetooth.FakeTransport
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ObdCommandExecutorTest {

    @Test
    fun `initialize uses automatic protocol and detects it`() = runTest {
        val transport = FakeTransport(
            responses = mapOf(
                "ATZ" to "ELM327 v1.5",
                "ATE0" to "OK",
                "ATL0" to "OK",
                "ATH0" to "OK",
                "ATSP0" to "OK",
                "ATAT1" to "OK",
                "0100" to "SEARCHING...\r41 00 BE 3E B8 11",
                "ATDPN" to "A5"
            )
        )
        val executor = ObdCommandExecutor(transport)
        executor.initialize()

        assertEquals(
            listOf("ATZ", "ATE0", "ATL0", "ATH0", "ATSP0", "ATAT1", "0100", "ATDPN"),
            transport.sentCommands
        )
        assertTrue(transport.timeouts.getValue("0100") >= 10_000)
        assertEquals(ObdProtocol.ISO_14230_KWP_FAST, executor.detectedProtocol)
    }

    @Test
    fun `initialize sends chosen protocol`() = runTest {
        val transport = FakeTransport(
            responses = mapOf("ATZ" to "ELM327 v1.5", "0100" to "41 00 BE 3E B8 11", "ATDPN" to "6")
        )
        val executor = ObdCommandExecutor(transport, ObdProtocol.ISO_15765_CAN_11_500)
        executor.initialize()

        assertTrue("ATSP6" in transport.sentCommands)
        assertEquals(ObdProtocol.ISO_15765_CAN_11_500, executor.detectedProtocol)
    }

    @Test(expected = java.io.IOException::class)
    fun `initialize fails when ECU does not answer`() = runTest {
        val transport = FakeTransport(responses = mapOf("ATZ" to "ELM327 v1.5", "0100" to "UNABLE TO CONNECT"))
        ObdCommandExecutor(transport).initialize()
    }

    @Test
    fun `sendCommand delegates to transport`() = runTest {
        val transport = FakeTransport(responses = mapOf("010C" to "7E8 04 41 0C 0F A0"))
        val executor = ObdCommandExecutor(transport)
        transport.connect()

        val response = executor.sendCommand("010C")
        assertEquals("7E8 04 41 0C 0F A0", response)
    }
}
