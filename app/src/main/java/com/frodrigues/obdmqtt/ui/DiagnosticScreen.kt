package com.frodrigues.obdmqtt.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.frodrigues.obdmqtt.obd.DtcDescriptions
import com.frodrigues.obdmqtt.service.ServiceStatus
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val report by viewModel.diagnostics.collectAsState()
    val clearResult by viewModel.clearResult.collectAsState()
    val clearPending by viewModel.clearPending.collectAsState()
    val serviceStatus by viewModel.serviceStatus.collectAsState()
    val serviceRunning = serviceStatus != ServiceStatus.IDLE
    var showClearDialog by remember { mutableStateOf(false) }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Effacer les défauts ?") },
            text = {
                Text(
                    "Mettez le contact, moteur arrêté.\n\n" +
                    "• Les codes défaut et l'image figée sont effacés et le voyant moteur s'éteint.\n" +
                    "• Les tests antipollution repartent à zéro : à éviter juste avant un contrôle technique.\n" +
                    "• Rien n'est réparé : le voyant se rallumera si le défaut est toujours présent."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showClearDialog = false
                    viewModel.clearFaults()
                }) { Text("Effacer", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Annuler") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnostic moteur") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.readDiagnostics() }, enabled = serviceRunning) {
                        Icon(Icons.Default.Refresh, contentDescription = "Relire les défauts")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            if (!serviceRunning) {
                InfoText("Démarrez la collecte pour lire ou effacer les défauts.")
            }

            val r = report
            if (r == null) {
                InfoText("Les défauts n'ont pas encore été lus. Ils le sont au démarrage puis chaque minute.")
            } else {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = when (r.milOn) {
                                true -> "Voyant moteur allumé"
                                false -> "Voyant moteur éteint"
                                null -> "État du voyant inconnu"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (r.milOn == true) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Lu à ${SimpleDateFormat("HH:mm:ss", Locale.FRANCE).format(Date(r.timestamp))}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                CodeSection("Défauts mémorisés", r.codes, emptyText = "Aucun défaut mémorisé")
                CodeSection("Défauts en attente", r.pending, emptyText = "Aucun défaut en attente")

                if (r.monitors.isNotEmpty()) {
                    SectionTitle("Tests antipollution")
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            r.monitors.forEach { m ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(m.name, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        if (m.complete) "Terminé" else "Incomplet",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (m.complete) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.tertiary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Button(
                onClick = { showClearDialog = true },
                enabled = serviceRunning && !clearPending,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (clearPending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onError
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Effacement en cours…")
                } else {
                    Text("Effacer les défauts")
                }
            }
            clearResult?.let { result ->
                Text(
                    text = (if (result.success) "✓ " else "✗ ") + result.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (result.success) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                )
            }
            InfoText(
                "Seuls les défauts du calculateur moteur (codes OBD2 normalisés) sont accessibles. " +
                "ABS, airbag, boîte automatique ou BSI nécessitent l'outil Peugeot (Diagbox)."
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CodeSection(title: String, codes: List<String>, emptyText: String) {
    SectionTitle(title)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (codes.isEmpty()) {
                Text(
                    emptyText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
            }
            codes.forEachIndexed { index, code ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.padding(vertical = 8.dp)) {
                    Text(code, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        DtcDescriptions.describe(code),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}

@Composable
private fun InfoText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
