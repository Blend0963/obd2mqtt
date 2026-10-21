package com.frodrigues.obdmqtt.ui

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.frodrigues.obdmqtt.BatteryOptimization
import com.frodrigues.obdmqtt.mqtt.testMqttConnection
import com.frodrigues.obdmqtt.obd.ObdProtocol
import com.frodrigues.obdmqtt.settings.AppConfig
import com.frodrigues.obdmqtt.settings.AppSettings
import com.frodrigues.obdmqtt.settings.PublishMode
import com.frodrigues.obdmqtt.settings.syncAutoStartToDeStorage
import com.frodrigues.obdmqtt.webhook.HaDashboardYaml
import com.frodrigues.obdmqtt.webhook.HaWebhookYaml
import com.frodrigues.obdmqtt.webhook.buildWebhookUrl
import com.frodrigues.obdmqtt.webhook.generateWebhookId
import com.frodrigues.obdmqtt.webhook.testWebhookConnection
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onNavigateToPidSelection: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    val btMac by settings.btDeviceMac.collectAsState(initial = "")
    val btName by settings.btDeviceName.collectAsState(initial = "")
    val autoStart by settings.autoStart.collectAsState(initial = true)
    val engineRunningOnly by settings.engineRunningOnly.collectAsState(initial = false)
    val publishMode by settings.publishMode.collectAsState(initial = PublishMode.WEBHOOK)

    // Local text field state — avoids cursor-jump bug from DataStore re-emission
    var localHost by rememberSaveable { mutableStateOf("") }
    var localPort by rememberSaveable { mutableStateOf("1883") }
    var localUser by rememberSaveable { mutableStateOf("") }
    var localPassword by rememberSaveable { mutableStateOf("") }
    var localDeviceName by rememberSaveable { mutableStateOf("") }
    var localDeviceModel by rememberSaveable { mutableStateOf("") }
    var localDeviceManufacturer by rememberSaveable { mutableStateOf("") }
    var localHaUrl by rememberSaveable { mutableStateOf("") }
    var localWebhookId by rememberSaveable { mutableStateOf("") }
    var localHaToken by rememberSaveable { mutableStateOf("") }
    var localWebhookInterval by rememberSaveable {
        mutableStateOf(AppSettings.DEFAULT_WEBHOOK_INTERVAL_SECONDS.toString())
    }
    var fieldsInitialized by rememberSaveable { mutableStateOf(false) }

    // Initialise once from DataStore
    LaunchedEffect(Unit) {
        if (!fieldsInitialized) {
            val snap = settings.snapshot()
            localHost = snap.mqttHost
            localPort = snap.mqttPort.toString()
            localUser = snap.mqttUser
            localPassword = snap.mqttPassword
            localDeviceName = snap.deviceName
            localDeviceModel = snap.deviceModel
            localDeviceManufacturer = snap.deviceManufacturer
            localHaUrl = snap.haUrl
            localWebhookId = snap.webhookId.ifBlank {
                // Webhook is the default mode: give a fresh install its secret right away
                generateWebhookId().also { id -> settings.update { this[AppSettings.WEBHOOK_ID] = id } }
            }
            localWebhookInterval = snap.webhookIntervalSeconds.toString()
            localHaToken = snap.haToken
            fieldsInitialized = true
        }
    }

    // BT devices
    var pairedDevices by remember { mutableStateOf(emptyList<BluetoothDevice>()) }
    var showDeviceDialog by remember { mutableStateOf(false) }

    fun refreshDevices() {
        pairedDevices = try {
            context.getSystemService(BluetoothManager::class.java)
                ?.adapter?.bondedDevices?.toList() ?: emptyList()
        } catch (_: SecurityException) { emptyList() }
    }

    var batteryExempt by remember { mutableStateOf(BatteryOptimization.isExempt(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                refreshDevices()
                batteryExempt = BatteryOptimization.isExempt(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun currentConfig() = AppConfig(
        btDeviceMac = btMac,
        mqttHost = localHost,
        mqttPort = localPort.toIntOrNull() ?: 1883,
        mqttUser = localUser,
        mqttPassword = localPassword,
        deviceName = localDeviceName,
        deviceModel = localDeviceModel,
        deviceManufacturer = localDeviceManufacturer,
        publishMode = publishMode,
        haUrl = localHaUrl,
        webhookId = localWebhookId,
        webhookIntervalSeconds = localWebhookInterval.toIntOrNull()
            ?: AppSettings.DEFAULT_WEBHOOK_INTERVAL_SECONDS,
        haToken = localHaToken
    )

    fun saveWebhookId(id: String) {
        localWebhookId = id
        scope.launch { settings.update { this[AppSettings.WEBHOOK_ID] = id } }
    }

    // MQTT / webhook test state
    var mqttTesting by remember { mutableStateOf(false) }
    var mqttTestResult by remember { mutableStateOf<Result<Unit>?>(null) }
    var webhookTesting by remember { mutableStateOf(false) }
    var webhookTestResult by remember { mutableStateOf<Result<Unit>?>(null) }

    // PIDs the service actually polls — these get a sensor in the generated HA YAML
    val cachedPids by settings.cachedPids.collectAsState(initial = emptySet())
    val selectedFastPids by settings.fastPids.collectAsState(initial = emptySet())
    val selectedSlowPids by settings.slowPids.collectAsState(initial = emptySet())
    fun polledPids(): Set<Int> {
        val polled = selectedFastPids + selectedSlowPids
        return if (cachedPids.isEmpty()) polled else polled.intersect(cachedPids)
    }

    val obdProtocol by settings.obdProtocol.collectAsState(initial = ObdProtocol.AUTO)
    var showProtocolDialog by remember { mutableStateOf(false) }

    if (showProtocolDialog) {
        AlertDialog(
            onDismissRequest = { showProtocolDialog = false },
            title = { Text("Protocole OBD") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "Laissez « Automatique » : selon la motorisation, la 407 utilise le CAN ou le KWP2000.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ObdProtocol.entries.forEach { protocol ->
                        ListItem(
                            headlineContent = { Text(protocol.label) },
                            leadingContent = { RadioButton(selected = protocol == obdProtocol, onClick = null) },
                            modifier = Modifier.clickable(role = Role.RadioButton) {
                                scope.launch { settings.update { this[AppSettings.OBD_PROTOCOL] = protocol.name } }
                                showProtocolDialog = false
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showProtocolDialog = false }) { Text("Fermer") }
            }
        )
    }

    // BT device dialog
    if (showDeviceDialog) {
        BluetoothDeviceDialog(
            currentAddress = btMac,
            onSelect = { address, name, transport ->
                scope.launch { settings.selectBtDevice(address, name, transport) }
            },
            onDismiss = {
                showDeviceDialog = false
                refreshDevices()
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Réglages") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Retour"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors()
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            // ── Bluetooth ─────────────────────────────────────────────────────
            SettingsSection(header = { SectionLabel("Adaptateur OBD2 (Bluetooth)") }) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Devices found by the in-app BLE search aren't bonded: fall back to the saved name
                    val selectedName = pairedDevices.find { it.address == btMac }
                        ?.let { runCatching { it.name }.getOrNull() }
                        ?: btName.takeIf { btMac.isNotBlank() }?.ifBlank { btMac }

                    OutlinedButton(
                        onClick = { showDeviceDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = selectedName ?: "Choisir l'adaptateur…",
                            modifier = Modifier.weight(1f),
                            color = if (selectedName != null)
                                MaterialTheme.colorScheme.onSurface
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text("▾", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    if (selectedName != null && btMac.isNotBlank()) {
                        Text(
                            text = btMac,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ── Publish mode ──────────────────────────────────────────────────
            SettingsSection(header = { SectionLabel("Connexion à Home Assistant") }) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val modes = listOf(PublishMode.WEBHOOK to "Webhook HTTPS", PublishMode.MQTT to "MQTT (local)")
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        modes.forEachIndexed { index, (mode, label) ->
                            SegmentedButton(
                                selected = publishMode == mode,
                                onClick = {
                                    scope.launch { settings.update { this[AppSettings.PUBLISH_MODE] = mode.name } }
                                    if (mode == PublishMode.WEBHOOK && localWebhookId.isBlank()) {
                                        saveWebhookId(generateWebhookId())
                                    }
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size)
                            ) { Text(label) }
                        }
                    }
                    Text(
                        text = if (publishMode == PublishMode.WEBHOOK)
                            "Les mesures sont envoyées en direct en HTTPS vers un webhook Home Assistant — adapté à un accès par Internet (4G)."
                        else
                            "Les mesures sont publiées sur un broker MQTT avec découverte automatique — pour le réseau local.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ── Webhook ───────────────────────────────────────────────────────
            if (publishMode == PublishMode.WEBHOOK) SettingsSection(header = { SectionLabel("Webhook HTTPS") }) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val urlError = remember(localHaUrl, localWebhookId) {
                        if (localHaUrl.isBlank()) null
                        else runCatching { buildWebhookUrl(localHaUrl, localWebhookId) }.exceptionOrNull()?.message
                    }
                    OutlinedTextField(
                        value = localHaUrl,
                        onValueChange = { localHaUrl = it
                            scope.launch { settings.update { this[AppSettings.HA_URL] = it.trim() } }
                        },
                        label = { Text("URL de Home Assistant") },
                        placeholder = { Text("https://maison.exemple.fr") },
                        supportingText = { Text(urlError ?: "URL externe en HTTPS (certificat valide requis)") },
                        isError = urlError != null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = localWebhookId,
                        onValueChange = { saveWebhookId(it.trim()) },
                        label = { Text("ID du webhook (secret)") },
                        supportingText = { Text("Identifiant aléatoire de 256 bits — sert de mot de passe") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { saveWebhookId(generateWebhookId()) }) {
                            Text("Nouvel ID")
                        }
                        OutlinedButton(
                            onClick = { copyToClipboard(context, "ID du webhook", localWebhookId) },
                            enabled = localWebhookId.isNotBlank()
                        ) {
                            Text("Copier l'ID")
                        }
                    }
                    OutlinedTextField(
                        value = localWebhookInterval,
                        onValueChange = { v ->
                            localWebhookInterval = v
                            v.toIntOrNull()?.takeIf { it >= 1 }?.let { seconds ->
                                scope.launch { settings.update { this[AppSettings.WEBHOOK_INTERVAL] = seconds } }
                            }
                        },
                        label = { Text("Intervalle d'envoi (s)") },
                        supportingText = { Text("Les dernières valeurs sont envoyées à ce rythme (1 = temps réel)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = localHaToken,
                        onValueChange = { localHaToken = it
                            scope.launch { settings.update { this[AppSettings.HA_TOKEN] = it.trim() } }
                        },
                        label = { Text("Jeton d'accès Home Assistant (facultatif)") },
                        supportingText = {
                            Text(
                                "Uniquement pour le bouton « Effacer les défauts » dans Home Assistant. " +
                                "Créez-le dans HA → Profil → Sécurité → Jetons d'accès longue durée, " +
                                "idéalement avec un utilisateur dédié non administrateur."
                            )
                        },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                webhookTesting = true
                                webhookTestResult = null
                                val config = currentConfig()
                                scope.launch {
                                    webhookTestResult = testWebhookConnection(config)
                                    webhookTesting = false
                                }
                            },
                            enabled = !webhookTesting && localHaUrl.isNotBlank() && urlError == null
                        ) {
                            if (webhookTesting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Test en cours…")
                            } else {
                                Text("Tester la connexion")
                            }
                        }

                        webhookTestResult?.let { result ->
                            if (result.isSuccess) {
                                Text(
                                    text = "✓ Serveur joignable",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Text(
                                    text = "✗ Échec : ${result.exceptionOrNull()?.message?.take(60)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        text = "1. Copiez la configuration dans un fichier du dossier /config/packages/ " +
                               "de Home Assistant (voir l'en-tête du texte copié), puis redémarrez HA. " +
                               "2. Copiez le tableau de bord dans l'éditeur de configuration brute d'un " +
                               "nouveau tableau de bord. À refaire après un changement de PIDs, d'ID ou de jeton.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = {
                            val pids = polledPids()
                            copyToClipboard(context, "Configuration HA", HaWebhookYaml.generate(currentConfig(), pids))
                        },
                        enabled = localWebhookId.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("1. Copier la configuration Home Assistant")
                    }
                    OutlinedButton(
                        onClick = {
                            copyToClipboard(context, "Tableau de bord", HaDashboardYaml.generate(currentConfig(), polledPids()))
                        },
                        enabled = polledPids().isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("2. Copier le tableau de bord")
                    }
                }
            }

            // ── MQTT Broker ───────────────────────────────────────────────────
            if (publishMode == PublishMode.MQTT) SettingsSection(header = { SectionLabel("MQTT Broker") }) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = localHost,
                            onValueChange = { localHost = it
                                scope.launch { settings.update { this[AppSettings.MQTT_HOST] = it } }
                            },
                            label = { Text("Serveur") },
                            supportingText = { Text("IP ou nom d'hôte") },
                            singleLine = true,
                            modifier = Modifier.weight(2f)
                        )
                        OutlinedTextField(
                            value = localPort,
                            onValueChange = { v ->
                                localPort = v
                                v.toIntOrNull()?.let { port ->
                                    scope.launch { settings.update { this[AppSettings.MQTT_PORT] = port } }
                                }
                            },
                            label = { Text("Port") },
                            supportingText = { Text("Par défaut : 1883") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Text(
                        text = "Identifiants (facultatif)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = localUser,
                        onValueChange = { localUser = it
                            scope.launch { settings.update { this[AppSettings.MQTT_USER] = it } }
                        },
                        label = { Text("Utilisateur") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = localPassword,
                        onValueChange = { localPassword = it
                            scope.launch { settings.update { this[AppSettings.MQTT_PASSWORD] = it } }
                        },
                        label = { Text("Mot de passe") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Test connection
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                mqttTesting = true
                                mqttTestResult = null
                                val config = currentConfig()
                                scope.launch {
                                    mqttTestResult = testMqttConnection(config)
                                    mqttTesting = false
                                }
                            },
                            enabled = !mqttTesting && localHost.isNotBlank()
                        ) {
                            if (mqttTesting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Test en cours…")
                            } else {
                                Text("Tester la connexion")
                            }
                        }

                        mqttTestResult?.let { result ->
                            if (result.isSuccess) {
                                Text(
                                    text = "✓ Connecté",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Text(
                                    text = "✗ Échec : ${result.exceptionOrNull()?.message?.take(60)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }

            // ── Device Info ───────────────────────────────────────────────────
            SettingsSection(header = { SectionLabel("Véhicule (Home Assistant)") }) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Ces valeurs apparaissent dans le nom des capteurs Home Assistant.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = localDeviceName,
                            onValueChange = { localDeviceName = it
                                scope.launch { settings.update { this[AppSettings.DEVICE_NAME] = it } }
                            },
                            label = { Text("Nom") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = localDeviceModel,
                            onValueChange = { localDeviceModel = it
                                scope.launch { settings.update { this[AppSettings.DEVICE_MODEL] = it } }
                            },
                            label = { Text("Modèle") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    OutlinedTextField(
                        value = localDeviceManufacturer,
                        onValueChange = { localDeviceManufacturer = it
                            scope.launch { settings.update { this[AppSettings.DEVICE_MANUFACTURER] = it } }
                        },
                        label = { Text("Marque") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // ── Comportamento ─────────────────────────────────────────────────
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SectionLabel("Fonctionnement")

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                ListItem(
                    headlineContent = { Text("Démarrage automatique") },
                    supportingContent = {
                        Text(
                            text = "Lance la collecte au démarrage du téléphone et la garde active en arrière-plan.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = autoStart,
                            onCheckedChange = { enabled ->
                                scope.launch {
                                    settings.update { this[AppSettings.AUTO_START] = enabled }
                                    syncAutoStartToDeStorage(context, enabled)
                                }
                            }
                        )
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    modifier = Modifier.clickable(
                        role = Role.Switch,
                        onClickLabel = "Démarrage automatique"
                    ) {
                        scope.launch {
                            val newValue = !autoStart
                            settings.update { this[AppSettings.AUTO_START] = newValue }
                            syncAutoStartToDeStorage(context, newValue)
                        }
                    }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ListItem(
                    headlineContent = { Text("Restriction batterie d'Android") },
                    supportingContent = {
                        Text(
                            text = if (batteryExempt)
                                "Désactivée ✓ — la collecte peut tourner en arrière-plan."
                            else
                                "Active : Android peut arrêter la collecte en arrière-plan. Appuyez pour la désactiver.",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (batteryExempt) MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.error
                        )
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    modifier = Modifier.clickable(
                        enabled = !batteryExempt,
                        onClickLabel = "Désactiver la restriction batterie"
                    ) { BatteryOptimization.requestExemption(context) }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ListItem(
                    headlineContent = { Text("Lire seulement moteur tournant") },
                    supportingContent = {
                        Text(
                            text = "Ne réveille pas les calculateurs voiture garée : l'application mesure d'abord " +
                                   "la tension batterie et n'interroge la voiture que si l'alternateur charge " +
                                   "(plus de 13,3 V). Recommandé si l'adaptateur reste branché.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = engineRunningOnly,
                            onCheckedChange = { enabled ->
                                scope.launch { settings.update { this[AppSettings.ENGINE_RUNNING_ONLY] = enabled } }
                            }
                        )
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    modifier = Modifier.clickable(
                        role = Role.Switch,
                        onClickLabel = "Lire seulement moteur tournant"
                    ) {
                        scope.launch { settings.update { this[AppSettings.ENGINE_RUNNING_ONLY] = !engineRunningOnly } }
                    }
                )
            }

            // ── PID Selection ─────────────────────────────────────────────────
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SectionLabel("PIDs")

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                val fastPids by settings.fastPids.collectAsState(initial = emptySet())
                val slowPids by settings.slowPids.collectAsState(initial = emptySet())
                val totalCount = cachedPids.size

                ListItem(
                    headlineContent = { Text("Sélection des PIDs") },
                    supportingContent = {
                        Text(
                            text = if (totalCount == 0) "Aucun PID détecté pour l'instant"
                                   else "${fastPids.size} rapides · ${slowPids.size} lents · $totalCount détectés",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingContent = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.rotate(180f)
                        )
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    modifier = Modifier.clickable(
                        enabled = totalCount > 0,
                        onClick = onNavigateToPidSelection
                    )
                )
            }

            // ── PID Cache ─────────────────────────────────────────────────────
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SectionLabel("Scanner OBD2")

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { showProtocolDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Protocole : ${obdProtocol.label}", modifier = Modifier.weight(1f))
                        Text("▾", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        text = "Les PIDs détectés sont mémorisés pour éviter une recherche à chaque démarrage. " +
                               "Videz le cache et relancez la collecte pour tout redétecter.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = { scope.launch { settings.clearPidCache() } },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Vider le cache Mode 01")
                    }
                    OutlinedButton(
                        onClick = { scope.launch { settings.clearMode22Cache() } },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Vider le cache Mode 22")
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Copies [text] flagged as sensitive, so Android 13+ hides it from the clipboard preview. */
private fun copyToClipboard(context: Context, label: String, text: String) {
    val clip = ClipData.newPlainText(label, text).apply {
        description.extras = PersistableBundle().apply {
            // ClipDescription.EXTRA_IS_SENSITIVE (API 33); ignored by older versions
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
    }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    // Android 13+ shows its own confirmation
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, "$label copié", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun SettingsSection(
    header: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) { header() }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(content = content)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary
    )
}
