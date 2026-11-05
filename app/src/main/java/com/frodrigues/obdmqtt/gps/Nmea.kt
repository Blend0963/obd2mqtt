package com.frodrigues.obdmqtt.gps

import java.util.Calendar
import java.util.TimeZone

/** One position read from the receiver. */
data class GpsFix(
    /** UTC time of the fix (from the receiver), or null when it gave none. */
    val timeMs: Long?,
    val latitude: Double,
    val longitude: Double,
    /** Metres per second, null when unknown. */
    val speedMps: Double?,
    /** Degrees from north, null when unknown (standing still). */
    val bearingDeg: Double?,
    val altitudeM: Double?,
    val hdop: Double?,
    val satellites: Int?
) {
    /** Horizontal accuracy as Android expects it (metres, 68 %): HDOP × a typical 5 m. */
    val accuracyM: Float get() = ((hdop ?: 2.0) * 5.0).coerceIn(2.0, 200.0).toFloat()
}

/**
 * NMEA 0183 sentences from a GPS receiver (any talker: GP, GN, GL, GA, BD…).
 * Only RMC (position, speed, course, date) and GGA (altitude, satellites, HDOP)
 * are read: enough for a car's navigation. A sentence with a wrong checksum is
 * ignored, never half-used.
 */
object Nmea {

    /** « $GPRMC,…*hh » → its fields without the « $ » and the checksum, or null if damaged. */
    fun fields(line: String): List<String>? {
        val s = line.trim()
        if (!s.startsWith("$") || s.length < 7) return null
        val star = s.lastIndexOf('*')
        val body = if (star > 0) s.substring(1, star) else s.substring(1)
        if (star > 0) {
            val given = s.substring(star + 1).take(2).toIntOrNull(16) ?: return null
            var sum = 0
            for (c in body) sum = sum xor c.code
            if (sum != given) return null
        }
        return body.split(',')
    }

    /** « 4807.038 » + « N » → 48.1173 ; « 01131.000 » + « W » → -11.5166… */
    fun coordinate(value: String, hemisphere: String): Double? {
        if (value.isBlank()) return null
        val dot = value.indexOf('.').let { if (it < 0) value.length else it }
        if (dot < 3) return null
        val degrees = value.substring(0, dot - 2).toIntOrNull() ?: return null
        val minutes = value.substring(dot - 2).toDoubleOrNull() ?: return null
        val v = degrees + minutes / 60.0
        return when (hemisphere) {
            "S", "W" -> -v
            "N", "E" -> v
            else -> null
        }
    }

    /** « 230394 » (ddmmyy) + « 123519.00 » (hhmmss) → epoch ms UTC. */
    fun utcMillis(date: String, time: String): Long? {
        if (date.length < 6 || time.length < 6) return null
        val d = date.substring(0, 2).toIntOrNull() ?: return null
        val mo = date.substring(2, 4).toIntOrNull() ?: return null
        val y = date.substring(4, 6).toIntOrNull() ?: return null
        val h = time.substring(0, 2).toIntOrNull() ?: return null
        val mi = time.substring(2, 4).toIntOrNull() ?: return null
        val sec = time.substring(4).toDoubleOrNull() ?: return null
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(2000 + y, mo - 1, d, h, mi, sec.toInt())
            set(Calendar.MILLISECOND, ((sec - sec.toInt()) * 1000).toInt())
        }
        return cal.timeInMillis
    }
}

/**
 * Turns the receiver's lines into fixes: GGA's altitude, satellites and HDOP are
 * kept, and each valid RMC gives a fix with them. Not thread-safe: one reader.
 */
class NmeaAccumulator {
    private var altitude: Double? = null
    private var hdop: Double? = null
    private var satellites: Int? = null

    /** Number of well-formed sentences seen (any kind): tells a working baud rate. */
    var sentences = 0
        private set

    fun feed(line: String): GpsFix? {
        val f = Nmea.fields(line) ?: return null
        sentences++
        val type = f[0].takeLast(3)
        when (type) {
            "GGA" -> if (f.size > 9) {
                val quality = f[6].toIntOrNull() ?: 0
                satellites = f[7].toIntOrNull()
                hdop = f[8].toDoubleOrNull()
                altitude = if (quality > 0) f[9].toDoubleOrNull() else null
            }
            "RMC" -> if (f.size > 9 && f[2] == "A") {
                val lat = Nmea.coordinate(f[3], f[4]) ?: return null
                val lon = Nmea.coordinate(f[5], f[6]) ?: return null
                val knots = f[7].toDoubleOrNull()
                val course = f[8].toDoubleOrNull()
                return GpsFix(
                    timeMs = Nmea.utcMillis(f[9], f[1]),
                    latitude = lat,
                    longitude = lon,
                    speedMps = knots?.let { it * 0.514444 },
                    bearingDeg = course?.takeIf { (knots ?: 0.0) > 1.0 },
                    altitudeM = altitude,
                    hdop = hdop,
                    satellites = satellites
                )
            }
        }
        return null
    }
}

/** Bytes from the receiver → whole lines, whatever the USB packets cut. */
class LineSplitter(private val onLine: (String) -> Unit) {
    private val buf = StringBuilder()

    fun feed(bytes: ByteArray, count: Int) {
        for (i in 0 until count) {
            val c = (bytes[i].toInt() and 0xFF).toChar()
            when (c) {
                '\n' -> { if (buf.isNotEmpty()) onLine(buf.toString()); buf.setLength(0) }
                '\r' -> {}
                else -> if (buf.length < 200) buf.append(c) else buf.setLength(0)  // no line: drop the noise
            }
        }
    }
}
