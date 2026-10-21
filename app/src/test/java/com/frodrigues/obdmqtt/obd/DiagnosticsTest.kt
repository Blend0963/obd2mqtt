package com.frodrigues.obdmqtt.obd

import com.frodrigues.obdmqtt.bluetooth.FakeTransport
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DiagnosticsTest {

    @Test
    fun `decodes the four code families`() {
        assertEquals("P0133", DtcParser.decode(0x01, 0x33))
        assertEquals("P0420", DtcParser.decode(0x04, 0x20))
        assertEquals("C0123", DtcParser.decode(0x41, 0x23))
        assertEquals("B1234", DtcParser.decode(0x92, 0x34))
        assertEquals("U0100", DtcParser.decode(0xC1, 0x00))
        assertEquals("P242F", DtcParser.decode(0x24, 0x2F))
    }

    @Test
    fun `parses single frame CAN reply with count byte`() {
        assertEquals(listOf("P0133", "P0420"), DtcParser.parse("43 02 01 33 04 20", 0x03, isCan = true))
    }

    @Test
    fun `parses CAN reply without codes`() {
        assertEquals(emptyList<String>(), DtcParser.parse("43 00", 0x03, isCan = true))
    }

    @Test
    fun `parses multi-frame CAN reply ignoring padding`() {
        val response = "00A\r0: 43 04 01 33 04 20\r1: 01 71 02 99 00 00 00"
        assertEquals(listOf("P0133", "P0420", "P0171", "P0299"), DtcParser.parse(response, 0x03, isCan = true))
    }

    @Test
    fun `parses KWP replies with three codes per line`() {
        val response = "43 01 33 04 20 01 71\r43 03 00 00 00 00 00"
        assertEquals(listOf("P0133", "P0420", "P0171", "P0300"), DtcParser.parse(response, 0x03, isCan = false))
    }

    @Test
    fun `guesses framing when protocol is unknown`() {
        assertEquals(listOf("P0301"), DtcParser.parse("43 01 03 01", 0x03, isCan = null))
        assertEquals(listOf("P0301"), DtcParser.parse("43 03 01 00 00 00 00", 0x03, isCan = null))
    }

    @Test
    fun `pending codes use mode 07 reply`() {
        assertEquals(listOf("P0128"), DtcParser.parse("47 01 01 28", 0x07, isCan = true))
        assertEquals(emptyList<String>(), DtcParser.parse("43 01 01 28", 0x07, isCan = true))
    }

    @Test
    fun `no data gives no codes`() {
        assertEquals(emptyList<String>(), DtcParser.parse("NO DATA", 0x03, isCan = true))
        assertEquals(emptyList<String>(), DtcParser.parse("SEARCHING...\rUNABLE TO CONNECT", 0x03, isCan = null))
    }

    @Test
    fun `readiness for a petrol engine`() {
        // MIL on, 2 codes; misfire+fuel+components supported, components incomplete;
        // catalyst + O2 + O2 heater supported, catalyst incomplete
        val (mil, monitors) = ReadinessParser.parse(byteArrayOf(0x82.toByte(), 0x47, 0x61, 0x01))!!
        assertTrue(mil)
        assertEquals(
            listOf(
                ReadinessMonitor("Ratés d'allumage", true),
                ReadinessMonitor("Système carburant", true),
                ReadinessMonitor("Composants", false),
                ReadinessMonitor("Catalyseur", false),
                ReadinessMonitor("Sonde lambda", true),
                ReadinessMonitor("Chauffage sonde lambda", true)
            ),
            monitors
        )
    }

    @Test
    fun `readiness for a diesel engine uses compression names`() {
        val (mil, monitors) = ReadinessParser.parse(byteArrayOf(0x00, 0x08, 0xC0.toByte(), 0x40))!!
        assertFalse(mil)
        assertEquals(
            listOf(ReadinessMonitor("Filtre à particules", false), ReadinessMonitor("EGR / distribution variable", true)),
            monitors
        )
    }

    @Test
    fun `reader collects MIL, stored and pending codes`() = runTest {
        val transport = FakeTransport(
            mapOf(
                "0101" to "41 01 81 07 65 20",
                "03" to "43 01 03 01 00 00 00 00",
                "07" to "47 00 00 00 00 00 00"
            )
        )
        val report = DtcReader(ObdCommandExecutor(transport)).read()

        assertEquals(true, report.milOn)
        assertEquals(listOf("P0301"), report.codes)
        assertEquals(emptyList<String>(), report.pending)
        assertEquals(listOf("Sonde lambda"), report.incompleteMonitors)
    }

    @Test
    fun `clear reports success and ECU refusal`() = runTest {
        assertTrue(DtcReader(ObdCommandExecutor(FakeTransport(mapOf("04" to "44")))).clear().success)
        val refused = DtcReader(ObdCommandExecutor(FakeTransport(mapOf("04" to "7F 04 22")))).clear()
        assertFalse(refused.success)
        assertTrue(refused.message.contains("coupez le moteur"))
        assertFalse(DtcReader(ObdCommandExecutor(FakeTransport())).clear().success)
    }

    @Test
    fun `describes known codes and falls back by range`() {
        assertEquals("Ratés d'allumage cylindre 1", DtcDescriptions.describe("P0301"))
        assertEquals("Efficacité du catalyseur sous le seuil (banc 1)", DtcDescriptions.describe("P0420"))
        assertEquals("Sonde lambda (banc 1, sonde 2) — chauffage — circuit", DtcDescriptions.describe("P0141"))
        assertEquals("Injecteur cylindre 10 — circuit", DtcDescriptions.describe("P0210"))
        assertEquals("Capteur pression rampe carburant — signal haut", DtcDescriptions.describe("P0193"))
        assertTrue(DtcDescriptions.describe("P1351").contains("Peugeot"))
        assertTrue(DtcDescriptions.describe("U0100").contains("Réseau"))
        assertEquals("Allumage ou ratés d'allumage", DtcDescriptions.describe("P0399"))
    }

    @Test
    fun `battery voltage is read from the adapter only`() = runTest {
        val transport = FakeTransport(mapOf("ATRV" to "12.4V"))
        assertEquals(12.4, ObdCommandExecutor(transport).readBatteryVoltage()!!, 0.001)
        assertEquals(listOf("ATRV"), transport.sentCommands)
    }
}
