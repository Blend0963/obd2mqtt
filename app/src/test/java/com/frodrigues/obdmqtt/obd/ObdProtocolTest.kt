package com.frodrigues.obdmqtt.obd

import org.junit.Assert.*
import org.junit.Test

class ObdProtocolTest {

    @Test
    fun `parses auto-detected and fixed DPN replies`() {
        assertEquals(ObdProtocol.ISO_15765_CAN_11_500, ObdProtocol.fromDpn("A6"))
        assertEquals(ObdProtocol.ISO_14230_KWP_FAST, ObdProtocol.fromDpn("5\r"))
        assertEquals(ObdProtocol.ISO_9141_2, ObdProtocol.fromDpn(" a3 "))
    }

    @Test
    fun `unknown DPN reply gives null`() {
        assertNull(ObdProtocol.fromDpn("?"))
        assertNull(ObdProtocol.fromDpn("A0"))
    }
}
