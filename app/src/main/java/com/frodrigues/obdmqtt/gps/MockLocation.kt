package com.frodrigues.obdmqtt.gps

import android.annotation.SuppressLint
import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.SystemClock
import com.frodrigues.obdmqtt.util.DebugLog
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices

/**
 * The receiver's position given to Android as the GPS: every app (Home Assistant's
 * companion app, navigation) then sees it as the phone's own GPS. Android allows
 * this only to the app chosen in Developer options → « Sélectionner une
 * application de position fictive »: until then [start] says so and nothing is sent.
 *
 * Two ways in, both kept up while the receiver gives fixes:
 * - Android's own providers (GPS and network), as test providers;
 * - Google's fused location (Play services), in mock mode: Home Assistant's
 *   companion app reads its position there, and an app started before this one
 *   would otherwise keep the fused location's own (network) idea of where it is.
 * [ensure] checks every few seconds that both are still ours and puts them back
 * if not (another app, a provider reset, an app started earlier).
 */
class MockLocation(context: Context) {

    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
    private val fused: FusedLocationProviderClient? =
        runCatching { LocationServices.getFusedLocationProviderClient(context) }.getOrNull()
    private var fusedOn = false
    private var lastEnsure = 0L
    private var lastFusedMode = 0L
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
            fusedMode(true)
            null
        } catch (e: SecurityException) {
            "Choisir cette appli comme « position fictive » dans les options pour les développeurs"
        } catch (e: IllegalArgumentException) {
            "Position fictive refusée par Android : ${e.message}"
        }
    }

    @SuppressLint("MissingPermission")
    private fun fusedMode(on: Boolean) {
        val f = fused ?: return
        try {
            f.setMockMode(on)
                .addOnSuccessListener { fusedOn = on }
                .addOnFailureListener { fusedOn = false; if (on) DebugLog.add("GPS USB : position Google refusée (${it.message})") }
            lastFusedMode = SystemClock.elapsedRealtime()
        } catch (e: Exception) {  // no Play services, no permission: Android's providers only
            fusedOn = false
        }
    }

    @SuppressLint("MissingPermission")
    fun publish(fix: GpsFix) {
        if (!active) return
        ensure()
        for (p in providers) runCatching { lm.setTestProviderLocation(p, location(p, fix)) }
        if (fusedOn) runCatching { fused?.setMockLocation(location("fused", fix)) }
    }

    /**
     * Every 10 s: our providers still there and enabled, still giving our
     * position; the fused location still in mock mode (set again every minute:
     * Play services may leave it when another app asks).
     */
    @SuppressLint("MissingPermission")
    private fun ensure() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastEnsure < ENSURE_MS) return
        lastEnsure = now
        val ours = runCatching {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) &&
                lm.getLastKnownLocation(LocationManager.GPS_PROVIDER).let { it == null || isMock(it) }
        }.getOrDefault(true)
        if (!ours) {
            DebugLog.add("GPS USB : le GPS d'Android n'était plus le récepteur USB, remis")
            active = false
            start()
            return
        }
        if (fused != null && (!fusedOn || now - lastFusedMode > FUSED_MODE_MS)) fusedMode(true)
    }

    private fun isMock(l: Location): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) l.isMock else @Suppress("DEPRECATION") l.isFromMockProvider

    private fun location(provider: String, fix: GpsFix) = Location(provider).apply {
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

    fun stop() {
        if (!active) return
        for (p in providers) runCatching { lm.removeTestProvider(p) }
        fusedMode(false)
        active = false
    }

    private companion object {
        const val ENSURE_MS = 10_000L
        const val FUSED_MODE_MS = 60_000L
    }
}
