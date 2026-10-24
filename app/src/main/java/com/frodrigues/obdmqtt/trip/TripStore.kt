package com.frodrigues.obdmqtt.trip

import android.content.Context

/**
 * Keeps the running trip across process deaths: car units that force-stop every app at
 * ignition off would otherwise lose it at each fuel stop, and the trip would restart from zero.
 */
class TripStore(context: Context) {

    private val prefs = context.getSharedPreferences("trip", Context.MODE_PRIVATE)

    fun save(trip: Trip) {
        prefs.edit().putString(KEY, encode(trip)).apply()
    }

    fun load(): Trip? = prefs.getString(KEY, null)?.let(::decode)

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    companion object {
        private const val KEY = "running"

        /** Plain "key=value;…" so it needs neither org.json (stubbed in unit tests) nor a library. */
        fun encode(t: Trip): String = listOf(
            "start" to t.startMs, "end" to t.endMs, "km" to t.distanceKm,
            "driving" to t.drivingSeconds, "idle" to t.idleSeconds,
            "maxKmh" to t.maxSpeedKmh, "maxRpm" to t.maxRpm, "maxCoolant" to t.maxCoolantC,
            "fuel" to t.fuelLitres, "accel" to t.harshAccelerations, "brake" to t.harshBrakings,
            "overRev" to t.overRevs, "coldOverRev" to t.coldOverRevs, "crank" to t.crankVoltage,
            "t0100" to t.zeroTo100Seconds, "t80120" to t.eightyTo120Seconds,
        ).joinToString(";") { (k, v) -> "$k=${v ?: ""}" }

        /** Null when the text is damaged: losing a trip beats crashing the service. */
        fun decode(text: String): Trip? = runCatching {
            val m = text.split(";").associate { it.substringBefore("=") to it.substringAfter("=") }
            fun d(k: String) = m.getValue(k).toDouble()
            fun dn(k: String) = m[k]?.takeIf { it.isNotEmpty() }?.toDouble()
            fun i(k: String) = m.getValue(k).toInt()
            val start = m.getValue("start").toLong()
            Trip(
                id = start, active = true, startMs = start, endMs = m.getValue("end").toLong(),
                distanceKm = d("km"), drivingSeconds = d("driving"), idleSeconds = d("idle"),
                maxSpeedKmh = d("maxKmh"), maxRpm = d("maxRpm"), maxCoolantC = dn("maxCoolant"),
                fuelLitres = d("fuel"), harshAccelerations = i("accel"), harshBrakings = i("brake"),
                overRevs = i("overRev"), coldOverRevs = i("coldOverRev"), crankVoltage = dn("crank"),
                zeroTo100Seconds = dn("t0100"), eightyTo120Seconds = dn("t80120"),
                fuelRateLh = null, fuelL100 = null,
            )
        }.getOrNull()
    }
}
