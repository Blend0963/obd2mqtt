package com.frodrigues.obdmqtt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.frodrigues.obdmqtt.service.OBDCollectorService
import com.frodrigues.obdmqtt.settings.AppSettings
import com.frodrigues.obdmqtt.settings.dataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                // CE storage unavailable — read autoStart from DE SharedPreferences
                val autoStart = context.createDeviceProtectedStorageContext()
                    .getSharedPreferences(AppSettings.DE_PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean(AppSettings.DE_KEY_AUTO_START, false)
                if (autoStart) startService(context)
            }
            // Full boot, "quick boot" of some car units / OEMs, or the app was just updated
            Intent.ACTION_BOOT_COMPLETED, ACTION_QUICKBOOT, ACTION_QUICKBOOT_HTC, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val autoStart = AppSettings(context.dataStore).autoStart.first()
                        if (autoStart) startService(context)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    companion object {
        private const val ACTION_QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON"
        private const val ACTION_QUICKBOOT_HTC = "com.htc.intent.action.QUICKBOOT_POWERON"
    }

    private fun startService(context: Context) {
        // Android 12+ may refuse a background start for non-exempt broadcasts (quick boot)
        runCatching {
            context.startForegroundService(
                Intent(context, OBDCollectorService::class.java).apply {
                    action = OBDCollectorService.ACTION_START
                }
            )
        }
    }
}
