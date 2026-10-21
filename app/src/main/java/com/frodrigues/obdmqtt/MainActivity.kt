package com.frodrigues.obdmqtt

import android.Manifest
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
import com.frodrigues.obdmqtt.ui.MainScreen
import com.frodrigues.obdmqtt.ui.MainViewModel
import com.frodrigues.obdmqtt.ui.PidSelectionScreen
import com.frodrigues.obdmqtt.ui.SettingsScreen
import com.frodrigues.obdmqtt.ui.theme.OdbMqttTheme

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* handle result: service will handle missing permissions gracefully */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.POST_NOTIFICATIONS
            )
        )

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
                onNavigateToDiagnostics = { navController.navigate("diagnostics") }
            )
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
