package com.frodrigues.obdmqtt.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import com.frodrigues.obdmqtt.obd.ClearResult
import com.frodrigues.obdmqtt.obd.DiagnosticReport
import com.frodrigues.obdmqtt.service.ConnectionStatus
import com.frodrigues.obdmqtt.service.OBDCollectorService
import com.frodrigues.obdmqtt.service.ServiceStatus
import com.frodrigues.obdmqtt.settings.AppSettings
import com.frodrigues.obdmqtt.settings.dataStore
import kotlinx.coroutines.flow.StateFlow

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val settings = AppSettings(app.dataStore)

    val serviceStatus: StateFlow<ServiceStatus> = OBDCollectorService.status
    val activePidCount: StateFlow<Int> = OBDCollectorService.activePidCount
    val lastUpdateTime: StateFlow<Long> = OBDCollectorService.lastUpdateTime
    val btStatus: StateFlow<ConnectionStatus> = OBDCollectorService.btStatus
    val serverStatus: StateFlow<ConnectionStatus> = OBDCollectorService.serverStatus
    val pidReadings: StateFlow<Map<Int, Double>> = OBDCollectorService.pidReadings
    val obdProtocolLabel: StateFlow<String> = OBDCollectorService.obdProtocolLabel
    val cycleMs: StateFlow<Long> = OBDCollectorService.cycleMs
    val statusText: StateFlow<String> = OBDCollectorService.statusText
    val diagnostics: StateFlow<DiagnosticReport?> = OBDCollectorService.diagnostics
    val clearResult: StateFlow<ClearResult?> = OBDCollectorService.clearResult
    val clearPending: StateFlow<Boolean> = OBDCollectorService.clearPending
    val engineOffVoltage: StateFlow<Double?> = OBDCollectorService.engineOffVoltage

    fun readDiagnostics() = OBDCollectorService.requestDiagnosticsRead()

    fun clearFaults() = OBDCollectorService.requestClearFaults()

    fun startService() {
        val intent = Intent(getApplication(), OBDCollectorService::class.java).apply {
            action = OBDCollectorService.ACTION_START
        }
        getApplication<Application>().startForegroundService(intent)
    }

    fun stopService() {
        val intent = Intent(getApplication(), OBDCollectorService::class.java).apply {
            action = OBDCollectorService.ACTION_STOP
        }
        getApplication<Application>().startService(intent)
    }
}
