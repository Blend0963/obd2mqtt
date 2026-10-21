package com.frodrigues.obdmqtt.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.frodrigues.obdmqtt.obd.PidRegistry
import com.frodrigues.obdmqtt.service.ConnectionStatus
import com.frodrigues.obdmqtt.service.ServiceStatus
import com.frodrigues.obdmqtt.settings.PublishMode
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onNavigateToSettings: () -> Unit
) {
    val serviceStatus by viewModel.serviceStatus.collectAsState()
    val pidCount by viewModel.activePidCount.collectAsState()
    val lastUpdate by viewModel.lastUpdateTime.collectAsState()
    val btStatus by viewModel.btStatus.collectAsState()
    val serverStatus by viewModel.serverStatus.collectAsState()
    val publishMode by viewModel.settings.publishMode.collectAsState(initial = PublishMode.WEBHOOK)
    val pidReadings by viewModel.pidReadings.collectAsState()
    val obdProtocol by viewModel.obdProtocolLabel.collectAsState()

    val isRunning = serviceStatus == ServiceStatus.CONNECTED ||
                    serviceStatus == ServiceStatus.CONNECTING ||
                    serviceStatus == ServiceStatus.RECONNECTING

    val autoStart by viewModel.settings.autoStart.collectAsState(initial = false)

    LaunchedEffect(autoStart, serviceStatus) {
        if (autoStart && serviceStatus == ServiceStatus.IDLE) {
            viewModel.startService()
        }
    }

    val sortedPidEntries = remember(pidReadings) {
        pidReadings.entries.sortedWith(compareBy {
            PidRegistry.definitions[it.key]?.name ?: "zz_${it.key}"
        })
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "OBD2 → Home Assistant",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Réglages",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors()
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ── Scrollable content ────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(top = 24.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // ── Connection status cards ───────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ConnectionCard(
                        label = "OBD2",
                        status = btStatus,
                        modifier = Modifier.weight(1f)
                    )
                    ConnectionCard(
                        label = if (publishMode == PublishMode.WEBHOOK) "Home Assistant" else "Broker MQTT",
                        status = serverStatus,
                        modifier = Modifier.weight(1f)
                    )
                }

                // ── Active PIDs summary card ──────────────────────────────────
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.elevatedCardColors()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "PIDs actifs",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "$pidCount",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        if (obdProtocol.isNotBlank()) {
                            Text(
                                text = obdProtocol,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
                            )
                        }
                        if (lastUpdate > 0L) {
                            Text(
                                text = "Mis à jour à ${SimpleDateFormat("HH:mm:ss", Locale.FRANCE).format(Date(lastUpdate))}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // ── PID readings list ─────────────────────────────────────────
                if (sortedPidEntries.isNotEmpty()) {
                    Text(
                        text = "Mesures en direct",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                            sortedPidEntries.forEachIndexed { index, (pid, value) ->
                                val def = PidRegistry.definitions[pid]
                                val name = def?.name ?: "PID 0x${pid.toString(16).uppercase()}"
                                val unit = def?.unit ?: ""
                                val formatted = formatValue(value, unit)

                                if (index > 0) HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = formatted,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── Start / Stop (fixo no rodapé) ─────────────────────────────────
            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
                Button(
                    onClick = { if (isRunning) viewModel.stopService() else viewModel.startService() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = if (isRunning)
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    else
                        ButtonDefaults.buttonColors()
                ) {
                    Text(
                        text = if (isRunning) "Arrêter" else "Démarrer",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionCard(
    label: String,
    status: ConnectionStatus,
    modifier: Modifier = Modifier
) {
    ElevatedCard(
        modifier = modifier,
        colors = CardDefaults.elevatedCardColors()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    color = connectionColor(status),
                    modifier = Modifier.size(8.dp)
                ) {}
                Text(
                    text = connectionLabel(status),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = connectionColor(status)
                )
            }
        }
    }
}

@Composable
private fun connectionLabel(status: ConnectionStatus): String = when (status) {
    ConnectionStatus.DISCONNECTED -> "Hors ligne"
    ConnectionStatus.CONNECTING -> "Connexion…"
    ConnectionStatus.CONNECTED -> "En ligne"
}

@Composable
private fun connectionColor(status: ConnectionStatus): Color = when (status) {
    ConnectionStatus.CONNECTED -> MaterialTheme.colorScheme.primary
    ConnectionStatus.CONNECTING -> MaterialTheme.colorScheme.tertiary
    ConnectionStatus.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun formatValue(value: Double, unit: String): String {
    val formatted = if (value == value.toLong().toDouble()) {
        value.toLong().toString()
    } else {
        "%.2f".format(Locale.FRANCE, value).trimEnd('0').trimEnd(',')
    }
    return if (unit.isBlank()) formatted else "$formatted $unit"
}
