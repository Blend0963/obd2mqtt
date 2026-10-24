package com.frodrigues.obdmqtt.trip

import com.frodrigues.obdmqtt.obd.PidRegistry
import kotlin.math.max
import kotlin.math.min

/** A trip so far (or finished): everything is cumulative, so HA can keep the last one received. */
data class Trip(
    /** Start time (epoch ms), unique per trip: HA counts each trip once. */
    val id: Long,
    val active: Boolean,
    val startMs: Long,
    val endMs: Long,
    val distanceKm: Double,
    val drivingSeconds: Double,
    val idleSeconds: Double,
    val maxSpeedKmh: Double,
    val maxRpm: Double,
    val maxCoolantC: Double?,
    /** Estimated from the air flow; HA applies the correction learned from fill-ups. */
    val fuelLitres: Double,
    val harshAccelerations: Int,
    val harshBrakings: Int,
    val overRevs: Int,
    val coldOverRevs: Int,
    /** Lowest battery voltage while cranking; null when the app wasn't reading during the start. */
    val crankVoltage: Double?,
    val zeroTo100Seconds: Double?,
    val eightyTo120Seconds: Double?,
    /** Instantaneous consumption, only while the trip is active. */
    val fuelRateLh: Double?,
    val fuelL100: Double?,
    /** Engine stopped (fuel stop, restaurant) but the trip may still go on: see [TripTracker.PAUSE_MS]. */
    val paused: Boolean = false,
) {
    /** Start to end, stops included. */
    val durationSeconds: Double get() = (endMs - startMs) / 1000.0
    /** Time with the engine running: what a trip "lasted" once stops are left out. */
    val engineSeconds: Double get() = drivingSeconds + idleSeconds
    /** Over the time spent moving: idling at a light or in a jam doesn't pull it down. */
    val averageSpeedKmh: Double get() = if (drivingSeconds > 0) distanceKm / (drivingSeconds / 3600) else 0.0
    val litresPer100Km: Double? get() = if (distanceKm >= 1) fuelLitres / distanceKm * 100 else null
    val idlePercent: Double get() = if (engineSeconds > 0) idleSeconds / engineSeconds * 100 else 0.0

    /**
     * Eco-driving score out of 100: events are weighted per 10 km so one hard brake on a short
     * trip doesn't sink it, and idling only counts beyond 20 % of the trip.
     */
    val ecoScore: Int
        get() {
            val per10Km = max(distanceKm / 10, 1.0)
            val penalty = (harshAccelerations * 6 + harshBrakings * 8 + overRevs * 4 + coldOverRevs * 6) / per10Km +
                max(0.0, idlePercent - 20) / 2
            return (100 - penalty).coerceIn(0.0, 100.0).toInt()
        }
}

/**
 * Builds trips from the OBD readings: a trip starts when the engine runs (RPM above
 * [RUNNING_RPM]) and ends after [PAUSE_MS] with the engine stopped, or without readings
 * (car units often power off with the ignition, before the engine-off can be seen; HA also
 * closes a trip that stops updating). A shorter stop, at a fuel station or a motorway
 * restaurant, is a pause: the trip goes on when the engine restarts. The running trip is
 * saved ([TripStore]) so a pause survives the app being killed with the ignition.
 *
 * Pure logic, fed with readings and their timestamps, so it is unit-testable.
 */
class TripTracker {

    private var current: Builder? = null
    /** The last finished trip, sent until the next one starts. */
    var lastFinished: Trip? = null
        private set

    private var lastReadingMs = 0L
    private var engineStoppedSinceMs: Long? = null
    /** Battery voltages of the last seconds before the engine started, for the cranking dip. */
    private val voltageHistory = ArrayDeque<Pair<Long, Double>>()

    /** The trip to report: the running one, else the last finished one. */
    fun snapshot(now: Long): Trip? =
        current?.toTrip(now, active = true, paused = engineStoppedSinceMs != null) ?: lastFinished

    /**
     * Picks up a trip saved before the app was killed (ignition off on car units that
     * force-stop apps), if it is still within the pause. Returns true when resumed.
     */
    fun resume(saved: Trip, now: Long): Boolean {
        if (current != null || !saved.active || now - saved.endMs !in 0..PAUSE_MS) return false
        current = Builder.from(saved)
        lastReadingMs = saved.endMs
        engineStoppedSinceMs = saved.endMs
        return true
    }

    fun onReading(pid: Int, value: Double, timestampMs: Long, readings: Map<Int, Double>) {
        // No reading for a long time: that trip ended when the data stopped
        if (current != null && timestampMs - lastReadingMs > PAUSE_MS) finish(lastReadingMs)
        lastReadingMs = timestampMs

        if (pid == PidRegistry.ADAPTER_VOLTAGE) {
            voltageHistory.addLast(timestampMs to value)
            while (voltageHistory.first().first < timestampMs - CRANK_WINDOW_MS) voltageHistory.removeFirst()
        }

        if (pid == RPM) {
            if (value >= RUNNING_RPM) {
                engineStoppedSinceMs = null
                if (current == null) start(timestampMs)
            } else {
                val since = engineStoppedSinceMs ?: timestampMs.also { engineStoppedSinceMs = it }
                if (current != null && timestampMs - since >= PAUSE_MS) finish(since)
            }
        }
        current?.onReading(pid, value, timestampMs, readings)
    }

    /** Service stopped: close the running trip at the last reading. */
    fun finishNow() {
        if (current != null) finish(lastReadingMs)
    }

    private fun start(now: Long) {
        // The starter motor pulls the battery down for a second or two just before the engine runs
        val crank = voltageHistory.minOfOrNull { it.second }?.takeIf { it < CRANK_DIP_MAX_V }
        current = Builder(now, crank)
    }

    private fun finish(endMs: Long) {
        val trip = current ?: return
        current = null
        // A few seconds of idling isn't a trip (engine started and stopped, adapter glitch)
        val result = trip.toTrip(max(endMs, trip.startMs), active = false)
        if (result.distanceKm >= MIN_TRIP_KM || result.durationSeconds >= MIN_TRIP_SECONDS) lastFinished = result
    }

    private class Builder(val startMs: Long, val crankVoltage: Double?) {
        var distanceKm = 0.0
        var drivingSeconds = 0.0
        var idleSeconds = 0.0
        var maxSpeed = 0.0
        var maxRpm = 0.0
        var maxCoolant: Double? = null
        var fuelLitres = 0.0
        var harshAccelerations = 0
        var harshBrakings = 0
        var overRevs = 0
        var coldOverRevs = 0
        var zeroTo100: Double? = null
        var eightyTo120: Double? = null
        var fuelRateLh: Double? = null

        private var lastSpeed: Pair<Long, Double>? = null
        private var lastMafMs: Long? = null
        private var lastAccelEventMs = 0L
        private var lastBrakeEventMs = 0L
        private var overRevActive = false
        private var coldOverRevActive = false
        /** 0-100: time the car left standstill, cleared if it slows down on the way. */
        private var launchMs: Double? = null
        private var launchPeak = 0.0
        private var from80Ms: Double? = null
        /** Speed one second ago or more, for accelerations over a stable window. */
        private val speedWindow = ArrayDeque<Pair<Long, Double>>()

        fun onReading(pid: Int, value: Double, t: Long, readings: Map<Int, Double>) {
            when (pid) {
                SPEED -> onSpeed(value, t, readings)
                RPM -> {
                    maxRpm = max(maxRpm, value)
                    val coolant = readings[COOLANT]
                    val cold = coolant != null && coolant < COLD_ENGINE_C
                    // Count each excursion once, not every reading above the threshold
                    if (value > OVER_REV_RPM) { if (!overRevActive) overRevs++; overRevActive = true }
                    else if (value < OVER_REV_RPM - 300) overRevActive = false
                    if (cold && value > COLD_OVER_REV_RPM) { if (!coldOverRevActive) coldOverRevs++; coldOverRevActive = true }
                    else if (value < COLD_OVER_REV_RPM - 300) coldOverRevActive = false
                }
                COOLANT -> maxCoolant = max(maxCoolant ?: value, value)
                MAF -> onAirFlow(value, t, readings)
            }
        }

        private fun onSpeed(v: Double, t: Long, readings: Map<Int, Double>) {
            val previous = lastSpeed
            lastSpeed = t to v
            maxSpeed = max(maxSpeed, v)
            if (previous != null) {
                val dt = (t - previous.first) / 1000.0
                if (dt in 0.0..MAX_GAP_S) {
                    distanceKm += (previous.second + v) / 2 * dt / 3600
                    val engineRunning = (readings[RPM] ?: 0.0) >= RUNNING_RPM
                    if (v < 1 && previous.second < 1) { if (engineRunning) idleSeconds += dt } else drivingSeconds += dt
                    timers(previous.first, previous.second, t, v)
                }
            }
            accelerationEvents(t, v)
        }

        private fun accelerationEvents(t: Long, v: Double) {
            speedWindow.addLast(t to v)
            while (speedWindow.size > 2 && t - speedWindow[1].first >= 1000) speedWindow.removeFirst()
            val (t0, v0) = speedWindow.first()
            val dt = (t - t0) / 1000.0
            if (dt < 0.8 || dt > 3) return
            val accel = (v - v0) / 3.6 / dt  // m/s²
            if (accel > HARSH_ACCEL && t - lastAccelEventMs > EVENT_DEBOUNCE_MS) { harshAccelerations++; lastAccelEventMs = t }
            if (accel < -HARSH_BRAKE && t - lastBrakeEventMs > EVENT_DEBOUNCE_MS) { harshBrakings++; lastBrakeEventMs = t }
        }

        /** 0-100 and 80-120 km/h, with the crossing instants interpolated between two readings. */
        private fun timers(t0: Long, v0: Double, t1: Long, v1: Double) {
            fun crossing(target: Double) = t0 + (t1 - t0) * ((target - v0) / (v1 - v0))

            if (v1 <= STANDSTILL_KMH) { launchMs = null; launchPeak = 0.0 }
            else if (v0 <= STANDSTILL_KMH) { launchMs = crossing(STANDSTILL_KMH); launchPeak = v1 }
            launchMs?.let { start ->
                if (v1 < launchPeak - 5) launchMs = null  // lifted off: not a full-throttle run
                else {
                    launchPeak = max(launchPeak, v1)
                    if (v0 < 100 && v1 >= 100) {
                        val s = (crossing(100.0) - start) / 1000
                        zeroTo100 = min(zeroTo100 ?: s, s)
                        launchMs = null
                    }
                }
            }

            if (v0 < 80 && v1 >= 80) from80Ms = crossing(80.0)
            if (v1 < 78) from80Ms = null
            from80Ms?.let { start ->
                if (v0 < 120 && v1 >= 120) {
                    val s = (crossing(120.0) - start) / 1000
                    eightyTo120 = min(eightyTo120 ?: s, s)
                    from80Ms = null
                }
            }
        }

        /**
         * Diesel fuel from the air flow: fuel = air / air-fuel ratio. The wide-band lambda gives
         * the ratio when it isn't saturated (λ < 1.95); otherwise the ratio is estimated from the
         * engine load (very lean at idle, ~20:1 at full load). About ±15 %, corrected in HA from
         * the fill-ups.
         */
        private fun onAirFlow(mafGs: Double, t: Long, readings: Map<Int, Double>) {
            val lambda = readings[PidRegistry.LAMBDA]
            val load = readings[LOAD] ?: 30.0
            val afr = if (lambda != null && lambda in 0.7..1.95) STOICHIOMETRIC_DIESEL * lambda
            else max(18.0, 75 - 0.55 * load)
            val fuelGs = if ((readings[RPM] ?: 0.0) >= RUNNING_RPM) mafGs / afr else 0.0
            fuelRateLh = fuelGs * 3600 / DIESEL_G_PER_L
            lastMafMs?.let { previous ->
                val dt = (t - previous) / 1000.0
                if (dt in 0.0..MAX_GAP_S) fuelLitres += fuelGs * dt / DIESEL_G_PER_L
            }
            lastMafMs = t
        }

        companion object {
            fun from(t: Trip) = Builder(t.startMs, t.crankVoltage).apply {
                distanceKm = t.distanceKm
                drivingSeconds = t.drivingSeconds
                idleSeconds = t.idleSeconds
                maxSpeed = t.maxSpeedKmh
                maxRpm = t.maxRpm
                maxCoolant = t.maxCoolantC
                fuelLitres = t.fuelLitres
                harshAccelerations = t.harshAccelerations
                harshBrakings = t.harshBrakings
                overRevs = t.overRevs
                coldOverRevs = t.coldOverRevs
                zeroTo100 = t.zeroTo100Seconds
                eightyTo120 = t.eightyTo120Seconds
            }
        }

        fun toTrip(now: Long, active: Boolean, paused: Boolean = false): Trip {
            val speed = lastSpeed?.second ?: 0.0
            val rate = if (active) fuelRateLh else null
            return Trip(
                id = startMs, active = active, startMs = startMs, endMs = now,
                distanceKm = distanceKm, drivingSeconds = drivingSeconds, idleSeconds = idleSeconds,
                maxSpeedKmh = maxSpeed, maxRpm = maxRpm, maxCoolantC = maxCoolant, fuelLitres = fuelLitres,
                harshAccelerations = harshAccelerations, harshBrakings = harshBrakings,
                overRevs = overRevs, coldOverRevs = coldOverRevs, crankVoltage = crankVoltage,
                zeroTo100Seconds = zeroTo100, eightyTo120Seconds = eightyTo120,
                fuelRateLh = rate,
                fuelL100 = if (rate != null && speed >= 5) rate / speed * 100 else null,
                paused = paused,
            )
        }
    }

    companion object {
        private const val SPEED = 0x0D
        private const val RPM = 0x0C
        private const val LOAD = 0x04
        private const val COOLANT = 0x05
        private const val MAF = 0x10

        const val RUNNING_RPM = 300.0
        /** Longest stop (engine off or no data) that doesn't end the trip; same value in HA. */
        const val PAUSE_MS = 30 * 60_000L
        private const val CRANK_WINDOW_MS = 20_000L
        /** Above this the battery never dipped: the start wasn't seen (app connected afterwards). */
        private const val CRANK_DIP_MAX_V = 11.8
        private const val MIN_TRIP_KM = 0.3
        private const val MIN_TRIP_SECONDS = 120.0
        /** Longer gaps between two readings aren't integrated (connection lost meanwhile). */
        private const val MAX_GAP_S = 5.0

        const val HARSH_ACCEL = 2.7   // m/s²: a 2.0 HDi flat out is ~3
        const val HARSH_BRAKE = 3.5   // m/s²
        private const val EVENT_DEBOUNCE_MS = 3_000L
        const val OVER_REV_RPM = 3_800.0
        const val COLD_OVER_REV_RPM = 2_500.0
        const val COLD_ENGINE_C = 60.0
        private const val STANDSTILL_KMH = 3.0

        private const val STOICHIOMETRIC_DIESEL = 14.5
        private const val DIESEL_G_PER_L = 832.0
    }
}
