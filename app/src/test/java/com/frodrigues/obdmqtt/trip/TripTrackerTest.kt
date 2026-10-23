package com.frodrigues.obdmqtt.trip

import com.frodrigues.obdmqtt.obd.PidRegistry
import org.junit.Assert.*
import org.junit.Test

class TripTrackerTest {

    private val readings = mutableMapOf<Int, Double>()
    private val tracker = TripTracker()

    private fun feed(pid: Int, value: Double, t: Long) {
        readings[pid] = value
        tracker.onReading(pid, value, t, readings)
    }

    /** One reading of RPM, speed and air flow every 500 ms, speed given per step. */
    private fun drive(fromMs: Long, speeds: List<Double>, rpm: Double = 1800.0, maf: Double = 20.0): Long {
        var t = fromMs
        for (v in speeds) {
            feed(0x0C, if (v > 0 || rpm < TripTracker.RUNNING_RPM) rpm else 800.0, t)
            feed(0x0D, v, t)
            feed(0x10, maf, t)
            t += 500
        }
        return t
    }

    @Test
    fun `distance is integrated from the speed`() {
        drive(0, List(7201) { 72.0 })  // 72 km/h for one hour

        val trip = tracker.snapshot(3_600_000)!!
        assertTrue(trip.active)
        assertEquals(72.0, trip.distanceKm, 0.1)
        assertEquals(72.0, trip.maxSpeedKmh, 0.0)
    }

    @Test
    fun `trip ends after a minute with the engine stopped`() {
        var t = drive(0, List(600) { 50.0 })
        t = drive(t, List(130) { 0.0 }, rpm = 0.0)

        val trip = tracker.snapshot(t)!!
        assertFalse(trip.active)
        assertEquals(300_000, trip.endMs)
        assertEquals(trip, tracker.lastFinished)
    }

    @Test
    fun `zero to hundred is timed from standstill`() {
        // Standstill, then +10 km/h every 500 ms: 100 km/h is reached 5 s after leaving 0
        var t = drive(0, List(4) { 0.0 })
        t = drive(t, (1..12).map { it * 10.0 })

        val trip = tracker.snapshot(t)!!
        assertEquals(4.85, trip.zeroTo100Seconds!!, 0.05)
        assertEquals(2.0, trip.eightyTo120Seconds!!, 0.05)
        assertTrue(trip.harshAccelerations >= 1)
    }

    @Test
    fun `cranking dip is the lowest voltage just before the engine starts`() {
        feed(PidRegistry.ADAPTER_VOLTAGE, 12.5, 0)
        feed(0x0C, 0.0, 0)
        feed(PidRegistry.ADAPTER_VOLTAGE, 10.1, 1_000)
        feed(PidRegistry.ADAPTER_VOLTAGE, 11.6, 1_500)
        drive(2_000, List(10) { 0.0 }, rpm = 850.0)

        assertEquals(10.1, tracker.snapshot(7_000)!!.crankVoltage!!, 0.0)
    }

    @Test
    fun `idle fuel rate is plausible for a diesel`() {
        drive(0, List(20) { 0.0 }, rpm = 800.0, maf = 11.0)

        val rate = tracker.snapshot(10_000)!!.fuelRateLh!!
        assertTrue("idle $rate L/h", rate in 0.4..1.0)
    }
}
