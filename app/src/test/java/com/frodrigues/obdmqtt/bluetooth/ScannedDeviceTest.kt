package com.frodrigues.obdmqtt.bluetooth

import org.junit.Assert.*
import org.junit.Test

class ScannedDeviceTest {

    private fun device(name: String?) = ScannedDevice("AA:BB:CC:DD:EE:FF", name, isBle = false, bonded = false)

    @Test
    fun `recognises common OBD adapter names`() {
        listOf("Vgate iCar Pro", "V-LINK", "vLinker MC+", "OBDII", "ELM327 v1.5", "IOS-Vlink", "KONNWEI")
            .forEach { assertTrue(it, device(it).looksLikeObd) }
    }

    @Test
    fun `phones and unnamed devices are not adapters`() {
        assertFalse(device("Pixel 8").looksLikeObd)
        assertFalse(device(null).looksLikeObd)
    }
}
