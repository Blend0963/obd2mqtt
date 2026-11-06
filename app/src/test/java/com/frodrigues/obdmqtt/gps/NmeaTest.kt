package com.frodrigues.obdmqtt.gps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NmeaTest {

    private fun withChecksum(body: String): String {
        var sum = 0
        for (c in body) sum = sum xor c.code
        return "$" + body + "*" + "%02X".format(sum)
    }

    @Test fun checksum() {
        assertNotNull(Nmea.fields("\$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*6A"))
        assertNull(Nmea.fields("\$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*6B"))
        assertNull(Nmea.fields("garbage"))
    }

    @Test fun coordinates() {
        assertEquals(48.1173, Nmea.coordinate("4807.038", "N")!!, 1e-4)
        assertEquals(-11.516667, Nmea.coordinate("01131.000", "W")!!, 1e-5)
        assertNull(Nmea.coordinate("", "N"))
    }

    @Test fun fixFromGgaAndRmc() {
        val acc = NmeaAccumulator()
        assertNull(acc.feed(withChecksum("GNGGA,092750.000,4851.000,N,00221.000,E,1,9,0.9,48.5,M,47.0,M,,")))
        val fix = acc.feed(withChecksum("GNRMC,092750.000,A,4851.000,N,00221.000,E,27.0,90.5,061126,,,A"))!!
        assertEquals(48.85, fix.latitude, 1e-6)
        assertEquals(2.35, fix.longitude, 1e-6)
        assertEquals(13.89, fix.speedMps!!, 0.01)
        assertEquals(90.5, fix.bearingDeg!!, 1e-6)
        assertEquals(48.5, fix.altitudeM!!, 1e-6)
        assertEquals(9, fix.satellites)
        assertEquals(4.5f, fix.accuracyM, 0.01f)
        assertEquals(1793957270000L, fix.timeMs)  // 2026-11-06 09:27:50 UTC
        assertEquals(2, acc.sentences)
    }

    @Test fun noFixWhileSearching() {
        val acc = NmeaAccumulator()
        assertNull(acc.feed(withChecksum("GPRMC,092750.000,V,,,,,,,061126,,,N")))
        assertEquals(1, acc.sentences)
    }

    @Test fun satellitesInViewWhileSearching() {
        val acc = NmeaAccumulator()
        assertNull(acc.inView)
        acc.feed(withChecksum("GPGSV,2,1,07,02,45,120,30,05,30,060,25,13,10,300,,15,70,200,35"))
        acc.feed(withChecksum("GPGSV,2,2,07,18,12,040,,24,55,250,28,29,20,330,"))
        acc.feed(withChecksum("GLGSV,1,1,03,65,40,100,22,66,15,180,,72,60,270,30"))
        acc.feed(withChecksum("GNGGA,092750.000,,,,,0,00,99.9,,M,,M,,"))
        assertEquals(10, acc.inView)
        assertEquals(0, acc.used)
        assertNull(acc.feed(withChecksum("GNRMC,092750.000,V,,,,,,,061126,,,N")))
    }

    @Test fun standingStillHasNoBearing() {
        val fix = NmeaAccumulator().feed(withChecksum("GPRMC,092750,A,4851.000,N,00221.000,E,0.3,211.0,061126,,"))!!
        assertNull(fix.bearingDeg)
    }

    @Test fun linesAcrossPackets() {
        val lines = mutableListOf<String>()
        val split = LineSplitter { lines.add(it) }
        val a = "\$GPGGA,1*00\r\n\$GPR".toByteArray()
        val b = "MC,2*00\r\n".toByteArray()
        split.feed(a, a.size)
        split.feed(b, b.size)
        assertEquals(listOf("\$GPGGA,1*00", "\$GPRMC,2*00"), lines)
    }

    @Test fun ch34xDivisorMatchesLinux() {
        assertEquals(0xB202, UsbSerialPort.ch34xDivisor(9600))
        assertEquals(0x6402, UsbSerialPort.ch34xDivisor(4800))
    }
}
