package com.frodrigues.obdmqtt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.frodrigues.obdmqtt.service.OBDCollectorService
import com.frodrigues.obdmqtt.service.ServiceStatus
import com.frodrigues.obdmqtt.util.DebugLog

/**
 * Starts or stops collection without showing the app, for Home Assistant
 * (`command_broadcast_intent`), Tasker or MacroDroid: car units that block the app's
 * own boot receiver can still launch it from an app they do start.
 */
class StartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_START -> {
                // An explicit request overrides an earlier "Arrêter" in the app
                OBDCollectorService.stoppedByUser = false
                AutoStart.startCollection(context, "commande externe")
            }
            ACTION_STOP -> {
                if (OBDCollectorService.status.value == ServiceStatus.IDLE) return
                DebugLog.add("Arrêt demandé par commande externe")
                runCatching {
                    context.startService(
                        Intent(context, OBDCollectorService::class.java).setAction(OBDCollectorService.ACTION_STOP)
                    )
                }.onFailure { DebugLog.add("Arrêt refusé : ${it.message}") }
            }
        }
    }

    companion object {
        const val ACTION_START = "com.frodrigues.obdmqtt.action.START"
        const val ACTION_STOP = "com.frodrigues.obdmqtt.action.STOP"
    }
}
