package com.frodrigues.obdmqtt

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Android's battery optimisation (Doze, app standby, vendor "battery limits" on car
 * head units) can stop the collector in the background. The app asks to be exempted.
 */
object BatteryOptimization {

    private const val PREFS = "battery_prefs"
    private const val KEY_ASKED = "askedOnce"

    fun isExempt(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true

    /** Shows the system "allow to run in background" dialog, falling back to the settings list. */
    @SuppressLint("BatteryLife")  // sideloaded app whose job is to run in the background
    fun requestExemption(context: Context) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        for (intent in listOf(direct, fallback)) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
                // Some head units strip these screens: try the next one
            } catch (_: SecurityException) {
            }
        }
    }

    /** Asks once on first launch; afterwards only from Settings, so a refusal isn't nagged. */
    fun requestOnFirstLaunch(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (isExempt(context) || prefs.getBoolean(KEY_ASKED, false)) return
        prefs.edit().putBoolean(KEY_ASKED, true).apply()
        requestExemption(context)
    }
}
