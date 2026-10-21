package com.frodrigues.obdmqtt.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.frodrigues.obdmqtt.bluetooth.BluetoothScanner
import com.frodrigues.obdmqtt.bluetooth.BondState
import com.frodrigues.obdmqtt.bluetooth.ScannedDevice
import com.frodrigues.obdmqtt.settings.BtTransport

/**
 * Chooses the OBD adapter: already-paired devices, plus an in-app search and pairing
 * (PIN 1234) for car tablets whose Bluetooth screen only lists phones.
 */
@Composable
fun BluetoothDeviceDialog(
    currentAddress: String,
    onSelect: (address: String, name: String, transport: BtTransport) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scanner = remember { BluetoothScanner(context.applicationContext) }
    val devices by scanner.devices.collectAsState()
    val scanning by scanner.scanning.collectAsState()
    val bondState by scanner.bondState.collectAsState()
    var message by remember { mutableStateOf<String?>(null) }
    var btEnabled by remember { mutableStateOf(scanner.isEnabled) }

    DisposableEffect(Unit) {
        scanner.loadBonded()
        onDispose { scanner.release() }
    }

    val scanPermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    fun hasScanPermissions() = scanPermissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
    fun locationOff(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
        context.getSystemService(LocationManager::class.java)
            ?.let { !LocationManagerCompat.isLocationEnabled(it) } == true

    fun startScan() {
        message = null
        btEnabled = scanner.isEnabled
        when {
            !scanner.isAvailable -> message = "Aucun Bluetooth utilisable par les applications sur cet appareil."
            !btEnabled -> { scanner.requestEnable(); message = "Activez le Bluetooth puis relancez la recherche." }
            else -> {
                if (locationOff()) message = "Activez la localisation : sur cette version d'Android, la recherche Bluetooth en a besoin."
                scanner.start()
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) startScan()
        else message = "Autorisation refusée : la recherche Bluetooth est impossible."
    }

    // Pairing finished → select the adapter
    LaunchedEffect(bondState) {
        when (val state = bondState) {
            is BondState.Bonded -> {
                val device = devices.find { it.address == state.address }
                onSelect(state.address, device?.name ?: state.address, BtTransport.CLASSIC)
                onDismiss()
            }
            is BondState.Failed -> message = "Appairage refusé ou expiré. Vérifiez que l'adaptateur est branché, contact mis, puis réessayez (code 1234)."
            else -> Unit
        }
    }

    fun choose(device: ScannedDevice) {
        when {
            device.isBle -> { onSelect(device.address, device.name ?: device.address, BtTransport.BLE); onDismiss() }
            device.bonded -> { onSelect(device.address, device.name ?: device.address, BtTransport.CLASSIC); onDismiss() }
            else -> if (!scanner.bond(device.address)) message = "Impossible de lancer l'appairage."
        }
    }

    val sorted = remember(devices) {
        devices.sortedWith(compareByDescending<ScannedDevice> { it.looksLikeObd }
            .thenByDescending { it.name != null }
            .thenBy { it.name ?: it.address })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Adaptateur OBD2") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { if (hasScanPermissions()) startScan() else permissionLauncher.launch(scanPermissions) },
                    enabled = !scanning && bondState !is BondState.Bonding,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (scanning) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Recherche en cours…")
                    } else {
                        Text(if (btEnabled) "Rechercher les adaptateurs" else "Activer le Bluetooth et rechercher")
                    }
                }
                Text(
                    "Branchez l'adaptateur et mettez le contact. Un adaptateur non appairé l'est " +
                    "automatiquement avec le code 1234.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                message?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (bondState is BondState.Bonding) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Appairage en cours…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (sorted.isEmpty()) {
                        Text(
                            if (scanning) "Recherche…" else "Aucun appareil. Lancez une recherche.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    sorted.forEach { device ->
                        val label = device.name ?: "Appareil sans nom"
                        ListItem(
                            headlineContent = {
                                Text(label, fontWeight = if (device.looksLikeObd) FontWeight.SemiBold else null)
                            },
                            supportingContent = {
                                Text(
                                    buildString {
                                        append(device.address)
                                        append(if (device.isBle) " · BLE" else " · Bluetooth classique")
                                        if (device.bonded) append(" · appairé")
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            leadingContent = { RadioButton(selected = device.address == currentAddress, onClick = null) },
                            modifier = Modifier.clickable(
                                enabled = bondState !is BondState.Bonding,
                                role = Role.RadioButton,
                                onClickLabel = "Sélectionner $label"
                            ) { choose(device) }
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
        dismissButton = {
            TextButton(onClick = {
                runCatching { context.startActivity(Intent(AndroidSettings.ACTION_BLUETOOTH_SETTINGS)) }
            }) { Text("Réglages Bluetooth") }
        }
    )
}
