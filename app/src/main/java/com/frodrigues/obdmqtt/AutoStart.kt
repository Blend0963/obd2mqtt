package com.frodrigues.obdmqtt

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.frodrigues.obdmqtt.service.OBDCollectorService
import com.frodrigues.obdmqtt.service.ServiceStatus
import com.frodrigues.obdmqtt.settings.AppSettings
import com.frodrigues.obdmqtt.settings.dataStore
import com.frodrigues.obdmqtt.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Starts the collector when auto-start is on, and keeps a safety-net job scheduled. */
object AutoStart {

    private const val WATCHDOG_JOB_ID = 4071
    private const val WATCHDOG_PERIOD_MS = 15 * 60_000L  // JobScheduler minimum

    /** Starts collection; [reason] is shown in the connection log. Never throws. */
    fun startCollection(context: Context, reason: String) {
        if (OBDCollectorService.stoppedByUser) return
        if (OBDCollectorService.status.value != ServiceStatus.IDLE) return
        DebugLog.add("Démarrage automatique : $reason")
        // Android 12+ may refuse a background start outside boot broadcasts
        runCatching {
            context.startForegroundService(
                Intent(context, OBDCollectorService::class.java).setAction(OBDCollectorService.ACTION_START)
            )
        }.onFailure { DebugLog.add("Démarrage automatique refusé : ${it.message}") }
    }

    /**
     * Safety net for car units that don't deliver BOOT_COMPLETED to apps (or very late):
     * a persisted periodic job survives reboots and restarts collection if it isn't running.
     */
    fun scheduleWatchdog(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        if (scheduler.getPendingJob(WATCHDOG_JOB_ID) != null) return
        runCatching {
            scheduler.schedule(
                JobInfo.Builder(WATCHDOG_JOB_ID, ComponentName(context, AutoStartJobService::class.java))
                    .setPeriodic(WATCHDOG_PERIOD_MS)
                    .setPersisted(true)
                    .build()
            )
        }
    }
}

class AutoStartJobService : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (AppSettings(applicationContext.dataStore).autoStart.first()) {
                    AutoStart.startCollection(applicationContext, "tâche de secours")
                }
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = false
}
