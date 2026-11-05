package com.frodrigues.obdmqtt.gps

import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.SystemClock

/**
 * The receiver's position given to Android as the GPS: every app (Home Assistant's
 * companion app, navigation) then sees it as the phone's own GPS. Android allows
 * this only to the app chosen in Developer options → « Sélectionner une
 * application de position fictive »: until then [start] says so and nothing is sent.
 */
class MockLocation(context: Context) {

    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
    var active = false
        private set

    /** Null when it works, else why not (in French, for the status line). */
    fun start(): String? {
        if (active) return null
        return try {
            for (p in providers) {
                runCatching { lm.removeTestProvider(p) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    lm.addTestProvider(p, false, false, false, false, true, true, true,
                        ProviderProperties.POWER_USAGE_LOW, ProviderProperties.ACCURACY_FINE)
                } else {
                    @Suppress("DEPRECATION")
                    lm.addTestProvider(p, false, false, false, false, true, true, true,
                        Criteria.POWER_LOW, Criteria.ACCURACY_FINE)
                }
                lm.setTestProviderEnabled(p, true)
            }
            active = true
            null
        } catch (e: SecurityException) {
            "Choisir cette appli comme « position fictive » dans les options pour les développeurs"
        } catch (e: IllegalArgumentException) {
            "Position fictive refusée par Android : ${e.message}"
        }
    }

    fun publish(fix: GpsFix) {
        if (!active) return
        for (p in providers) {
            val loc = Location(p).apply {
                latitude = fix.latitude
                longitude = fix.longitude
                accuracy = fix.accuracyM
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                fix.altitudeM?.let { altitude = it }
                fix.speedMps?.let { speed = it.toFloat() }
                fix.bearingDeg?.let { bearing = it.toFloat() }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    fix.speedMps?.let { speedAccuracyMetersPerSecond = 0.5f }
                    fix.bearingDeg?.let { bearingAccuracyDegrees = 5f }
                    verticalAccuracyMeters = fix.accuracyM * 1.5f
                }
            }
            runCatching { lm.setTestProviderLocation(p, loc) }
        }
    }

    fun stop() {
        if (!active) return
        for (p in providers) runCatching { lm.removeTestProvider(p) }
        active = false
    }
}
