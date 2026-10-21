package com.frodrigues.obdmqtt

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.frodrigues.obdmqtt.ui.DiagnosticScreen
import com.frodrigues.obdmqtt.ui.LogScreen
import com.frodrigues.obdmqtt.ui.MainScreen
import com.frodrigues.obdmqtt.ui.MainViewModel
import com.frodrigues.obdmqtt.ui.PidSelectionScreen
import com.frodrigues.obdmqtt.ui.SettingsScreen
import com.frodrigues.obdmqtt.ui.theme.OdbMqttTheme

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Service handles missing permissions gracefully; ask for the battery exemption next
        BatteryOptimization.requestOnFirstLaunch(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Before Android 12 Bluetooth access is an install-time permission; notifications need asking from 13
        val runtimePermissions = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (runtimePermissions.isNotEmpty()) permissionLauncher.launch(runtimePermissions.toTypedArray())
        else BatteryOptimization.requestOnFirstLaunch(this)

        setContent {
            OdbMqttTheme {
                AppNavigation()
            }
        }
    }
}

@Composable
private fun AppNavigation() {
    val navController = rememberNavController()
    val viewModel: MainViewModel = viewModel()

    NavHost(navController = navController, startDestination = "main") {
        composable("main") {
            MainScreen(
                viewModel = viewModel,
                onNavigateToSettings = { navController.navigate("settings") },
                onNavigateToDiagnostics = { navController.navigate("diagnostics") },
                onNavigateToLog = { navController.navigate("log") }
            )
        }
        composable("log") {
            LogScreen(onBack = { navController.popBackStack() })
        }
        composable("diagnostics") {
            DiagnosticScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }
        composable("settings") {
            SettingsScreen(
                settings = viewModel.settings,
                onBack = { navController.popBackStack() },
                onNavigateToPidSelection = { navController.navigate("pid_selection") }
            )
        }
        composable("pid_selection") {
            PidSelectionScreen(
                settings = viewModel.settings,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
