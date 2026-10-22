package com.frodrigues.obdmqtt.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.frodrigues.obdmqtt.obd.PidPoller
import com.frodrigues.obdmqtt.obd.PidRegistry
import com.frodrigues.obdmqtt.settings.AppSettings
import com.frodrigues.obdmqtt.settings.PidMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PidSelectionScreen(
    settings: AppSettings,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val cachedPids by settings.cachedPids.collectAsState(initial = emptySet())
    var modes by remember { mutableStateOf<Map<Int, PidMode>>(emptyMap()) }
    var initialized by remember { mutableStateOf(false) }
    var showInfoDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val fast = settings.fastPids.first()
        val slow = settings.slowPids.first()
        val allPids = settings.cachedPids.first()
        modes = allPids.associateWith { pid ->
            when (pid) {
                in fast -> PidMode.FAST
                in slow -> PidMode.SLOW
                else -> PidMode.OFF
            }
        }
        initialized = true
    }

    val fastCount by remember { derivedStateOf { modes.values.count { it == PidMode.FAST } } }
    val slowCount by remember { derivedStateOf { modes.values.count { it == PidMode.SLOW } } }
    val fastAtLimit by remember { derivedStateOf { fastCount >= PidPoller.FAST_PID_LIMIT } }

    fun setMode(pid: Int, newMode: PidMode) {
        val currentMode = modes[pid] ?: PidMode.OFF
        if (newMode == PidMode.FAST && currentMode != PidMode.FAST && fastAtLimit) return
        modes = modes + (pid to newMode)
        scope.launch {
            settings.savePidModes(
                fast = modes.filterValues { it == PidMode.FAST }.keys,
                slow = modes.filterValues { it == PidMode.SLOW }.keys
            )
        }
    }

    if (showInfoDialog) {
        AlertDialog(
            onDismissRequest = { showInfoDialog = false },
            title = { Text("Fréquence de lecture") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Rapide (en continu) — ${PidPoller.FAST_PID_LIMIT} PIDs maximum\n" +
                        "Lus l'un après l'autre, aussi vite que la voiture répond (≈ 80 ms par PID en KWP2000) : " +
                        "moins il y en a, plus ils se rafraîchissent vite. Pour les données de conduite : régime, vitesse, charge…",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "Lent (${PidPoller.SLOW_INTERVAL_SECONDS} s) — Sans limite\n" +
                        "Lu toutes les ${PidPoller.SLOW_INTERVAL_SECONDS} secondes. Pour les valeurs qui évoluent lentement : températures, carburant…",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "Off\n" +
                        "Le PID n'est ni lu ni envoyé à Home Assistant.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showInfoDialog = false }) { Text("OK") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sélection des PIDs") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    IconButton(onClick = { showInfoDialog = true }) {
                        Icon(Icons.Outlined.Info, contentDescription = "À propos des fréquences")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors()
            )
        }
    ) { padding ->
        if (!initialized) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        if (cachedPids.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Aucun PID détecté. Lancez d'abord la collecte avec le contact mis.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Scaffold
        }

        val sortedPids = remember(cachedPids, modes) {
            cachedPids.sortedWith(
                compareBy<Int> {
                    when (modes[it]) {
                        PidMode.FAST -> 0
                        PidMode.SLOW -> 1
                        else -> 2
                    }
                }.thenBy { PidRegistry.getOrUnknown(it).name }
            )
        }

        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                elevation = CardDefaults.cardElevation(0.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(
                                "Rapides : $fastCount/${PidPoller.FAST_PID_LIMIT}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = if (fastAtLimit) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                "Lents : $slowCount",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "Off : ${cachedPids.size - fastCount - slowCount}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (fastAtLimit) {
                            Text(
                                "Limite de ${PidPoller.FAST_PID_LIMIT} PIDs rapides atteinte",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                elevation = CardDefaults.cardElevation(0.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                sortedPids.forEachIndexed { index, pid ->
                    if (index > 0) HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    PidRow(
                        pid = pid,
                        mode = modes[pid] ?: PidMode.OFF,
                        fastAtLimit = fastAtLimit,
                        onModeChange = { setMode(pid, it) }
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PidRow(
    pid: Int,
    mode: PidMode,
    fastAtLimit: Boolean,
    onModeChange: (PidMode) -> Unit
) {
    val def = PidRegistry.getOrUnknown(pid)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(def.name, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "0x${pid.toString(16).padStart(2, '0').uppercase()}" +
                       (if (def.unit.isNotBlank()) " · ${def.unit}" else ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        SingleChoiceSegmentedButtonRow(modifier = Modifier.width(210.dp)) {
            SegmentedButton(
                selected = mode == PidMode.FAST,
                onClick = { onModeChange(PidMode.FAST) },
                shape = SegmentedButtonDefaults.itemShape(0, 3),
                enabled = mode == PidMode.FAST || !fastAtLimit
            ) { Text("Rapide", maxLines = 1, style = MaterialTheme.typography.labelSmall) }
            SegmentedButton(
                selected = mode == PidMode.SLOW,
                onClick = { onModeChange(PidMode.SLOW) },
                shape = SegmentedButtonDefaults.itemShape(1, 3)
            ) { Text("Lent", maxLines = 1, style = MaterialTheme.typography.labelSmall) }
            SegmentedButton(
                selected = mode == PidMode.OFF,
                onClick = { onModeChange(PidMode.OFF) },
                shape = SegmentedButtonDefaults.itemShape(2, 3)
            ) { Text("Off", maxLines = 1, style = MaterialTheme.typography.labelSmall) }
        }
    }
}
