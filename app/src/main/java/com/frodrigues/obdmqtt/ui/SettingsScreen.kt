package com.frodrigues.obdmqtt.ui

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
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
import com.frodrigues.obdmqtt.mqtt.testMqttConnection
import com.frodrigues.obdmqtt.settings.AppConfig
import com.frodrigues.obdmqtt.settings.AppSettings
import com.frodrigues.obdmqtt.settings.PublishMode
import com.frodrigues.obdmqtt.settings.syncAutoStartToDeStorage
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
    val autoStart by settings.autoStart.collectAsState(initial = false)
    val publishMode by settings.publishMode.collectAsState(initial = PublishMode.MQTT)

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
            localWebhookId = snap.webhookId
            localWebhookInterval = snap.webhookIntervalSeconds.toString()
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

    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refreshDevices()
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
            ?: AppSettings.DEFAULT_WEBHOOK_INTERVAL_SECONDS
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

    // BT device dialog
    if (showDeviceDialog) {
        AlertDialog(
            onDismissRequest = { showDeviceDialog = false },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Bluetooth Device")
                    IconButton(onClick = { refreshDevices() }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            text = {
                if (pairedDevices.isEmpty()) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "No paired devices found.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = {
                            context.startActivity(Intent(AndroidSettings.ACTION_BLUETOOTH_SETTINGS))
                            showDeviceDialog = false
                        }) {
                            Text("Open Bluetooth Settings")
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 400.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        pairedDevices.forEachIndexed { index, device ->
                            val name = runCatching { device.name }.getOrDefault(device.address)
                            val selected = device.address == btMac

                            if (index > 0) HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                            ListItem(
                                headlineContent = { Text(name) },
                                supportingContent = {
                                    Text(
                                        device.address,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                leadingContent = {
                                    RadioButton(selected = selected, onClick = null)
                                },
                                modifier = Modifier.clickable(
                                    role = Role.RadioButton,
                                    onClickLabel = "Select $name"
                                ) {
                                    scope.launch {
                                        settings.update {
                                            this[AppSettings.BT_DEVICE_MAC] = device.address
                                        }
                                    }
                                    showDeviceDialog = false
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDeviceDialog = false }) { Text("Done") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
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
            SettingsSection(header = { SectionLabel("Bluetooth Device") }) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val selectedDevice = pairedDevices.find { it.address == btMac }
                    val selectedName = selectedDevice
                        ?.let { runCatching { it.name }.getOrDefault(it.address) }

                    OutlinedButton(
                        onClick = { showDeviceDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = selectedName ?: "Tap to select a device…",
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
            SettingsSection(header = { SectionLabel("Home Assistant Connection") }) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val modes = listOf(PublishMode.MQTT to "MQTT (LAN)", PublishMode.WEBHOOK to "Webhook HTTPS")
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
                            "Readings are POSTed over HTTPS to a Home Assistant webhook — suited to access over the internet."
                        else
                            "Readings are published to an MQTT broker with HA auto-discovery — best on the local network.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ── Webhook ───────────────────────────────────────────────────────
            if (publishMode == PublishMode.WEBHOOK) SettingsSection(header = { SectionLabel("Webhook (HTTPS)") }) {
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
                        label = { Text("Home Assistant URL") },
                        placeholder = { Text("https://ha.example.com") },
                        supportingText = { Text(urlError ?: "External HTTPS URL (valid certificate required)") },
                        isError = urlError != null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = localWebhookId,
                        onValueChange = { saveWebhookId(it.trim()) },
                        label = { Text("Webhook ID (secret)") },
                        supportingText = { Text("Random 256-bit ID — acts as the password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { saveWebhookId(generateWebhookId()) }) {
                            Text("New ID")
                        }
                        OutlinedButton(
                            onClick = { copyToClipboard(context, "Webhook ID", localWebhookId) },
                            enabled = localWebhookId.isNotBlank()
                        ) {
                            Text("Copy ID")
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
                        label = { Text("Send interval (s)") },
                        supportingText = { Text("Latest values are sent at most this often (default: 5)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
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
                                Text("Testing…")
                            } else {
                                Text("Test Connection")
                            }
                        }

                        webhookTestResult?.let { result ->
                            if (result.isSuccess) {
                                Text(
                                    text = "✓ Reachable",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Text(
                                    text = "✗ Failed: ${result.exceptionOrNull()?.message?.take(40)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        text = "Home Assistant needs a matching configuration. Copy the generated YAML " +
                               "into configuration.yaml, then reload template entities. Regenerate it " +
                               "after changing the PID selection or the webhook ID.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = {
                            val polled = selectedFastPids + selectedSlowPids
                            val pids = if (cachedPids.isEmpty()) polled else polled.intersect(cachedPids)
                            copyToClipboard(context, "HA YAML", HaWebhookYaml.generate(currentConfig(), pids))
                        },
                        enabled = localWebhookId.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Copy Home Assistant YAML")
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
                            label = { Text("Host") },
                            supportingText = { Text("IP or hostname") },
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
                            supportingText = { Text("Default: 1883") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Text(
                        text = "Credentials (optional)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = localUser,
                        onValueChange = { localUser = it
                            scope.launch { settings.update { this[AppSettings.MQTT_USER] = it } }
                        },
                        label = { Text("Username") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = localPassword,
                        onValueChange = { localPassword = it
                            scope.launch { settings.update { this[AppSettings.MQTT_PASSWORD] = it } }
                        },
                        label = { Text("Password") },
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
                                Text("Testing…")
                            } else {
                                Text("Test Connection")
                            }
                        }

                        mqttTestResult?.let { result ->
                            if (result.isSuccess) {
                                Text(
                                    text = "✓ Connected",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Text(
                                    text = "✗ Failed: ${result.exceptionOrNull()?.message?.take(40)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }

            // ── Device Info ───────────────────────────────────────────────────
            SettingsSection(header = { SectionLabel("Device (Home Assistant)") }) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "These values appear in HA entity names and device info.",
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
                            label = { Text("Name") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = localDeviceModel,
                            onValueChange = { localDeviceModel = it
                                scope.launch { settings.update { this[AppSettings.DEVICE_MODEL] = it } }
                            },
                            label = { Text("Model") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    OutlinedTextField(
                        value = localDeviceManufacturer,
                        onValueChange = { localDeviceManufacturer = it
                            scope.launch { settings.update { this[AppSettings.DEVICE_MANUFACTURER] = it } }
                        },
                        label = { Text("Manufacturer") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // ── Comportamento ─────────────────────────────────────────────────
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SectionLabel("Comportamento")

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                ListItem(
                    headlineContent = { Text("Iniciar automaticamente") },
                    supportingContent = {
                        Text(
                            text = "Inicia o serviço ao ligar o dispositivo e mantém ativo em segundo plano.",
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
                        onClickLabel = "Iniciar automaticamente"
                    ) {
                        scope.launch {
                            val newValue = !autoStart
                            settings.update { this[AppSettings.AUTO_START] = newValue }
                            syncAutoStartToDeStorage(context, newValue)
                        }
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
                    headlineContent = { Text("PID Selection") },
                    supportingContent = {
                        Text(
                            text = if (totalCount == 0) "No PIDs discovered yet"
                                   else "${fastPids.size} rápidos · ${slowPids.size} lentos · $totalCount descobertos",
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
            SectionLabel("OBD2 Scanner")

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
                    Text(
                        text = "Discovered PIDs are cached to avoid rescanning on every start. " +
                               "Clear cache and restart the service to probe all PIDs again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = { scope.launch { settings.clearPidCache() } },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Clear Mode 01 Cache")
                    }
                    OutlinedButton(
                        onClick = { scope.launch { settings.clearMode22Cache() } },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Clear Mode 22 Cache")
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Copies [text] flagged as sensitive, so Android hides it from the clipboard preview (API 33+ shows its own confirmation). */
private fun copyToClipboard(context: Context, label: String, text: String) {
    val clip = ClipData.newPlainText(label, text).apply {
        description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
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
