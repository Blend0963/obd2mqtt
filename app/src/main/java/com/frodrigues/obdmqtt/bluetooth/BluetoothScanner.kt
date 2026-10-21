package com.frodrigues.obdmqtt.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ScannedDevice(
    val address: String,
    val name: String?,
    val isBle: Boolean,
    val bonded: Boolean
) {
    /** ELM327 adapters advertise names like "Vgate iCar Pro", "V-LINK", "OBDII", "ELM327". */
    val looksLikeObd: Boolean
        get() = name?.let { OBD_NAME.containsMatchIn(it) } == true

    companion object {
        private val OBD_NAME = Regex("obd|elm|vgate|v-?link|icar|vlink|konnwei|vepro", RegexOption.IGNORE_CASE)
    }
}

sealed interface BondState {
    data object Idle : BondState
    data class Bonding(val address: String) : BondState
    data class Bonded(val address: String) : BondState
    data class Failed(val address: String) : BondState
}

/**
 * In-app Bluetooth search and pairing, for car head units whose own Bluetooth screen
 * only lists phones: classic discovery (Vgate iCar Pro Bluetooth 3.0) and a BLE scan
 * (BLE 4.0 version) run together, and pairing answers the PIN itself (1234, then 0000).
 * Permissions are checked by the caller.
 */
@SuppressLint("MissingPermission")
class BluetoothScanner(private val context: Context) {

    private val adapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter
    private val handler = Handler(Looper.getMainLooper())

    private val _devices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val devices: StateFlow<List<ScannedDevice>> = _devices.asStateFlow()
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()
    private val _bondState = MutableStateFlow<BondState>(BondState.Idle)
    val bondState: StateFlow<BondState> = _bondState.asStateFlow()

    /** PINs still to try for the device being paired (Vgate: 1234, some clones: 0000). */
    private var pins: List<String> = emptyList()
    private var bondingAddress: String? = null
    private var registered = false
    private val stopScan = Runnable { stop() }

    val isAvailable: Boolean get() = adapter != null
    val isEnabled: Boolean get() = adapter?.isEnabled == true

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device: BluetoothDevice? = intent.bluetoothDevice()
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> device?.let {
                    val name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME) ?: runCatching { it.name }.getOrNull()
                    add(ScannedDevice(it.address, name, isBle = it.type == BluetoothDevice.DEVICE_TYPE_LE,
                        bonded = it.bondState == BluetoothDevice.BOND_BONDED))
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> Log.d(TAG, "Classic discovery finished")
                BluetoothDevice.ACTION_PAIRING_REQUEST -> device?.let {
                    // Head units may have no pairing dialog: answer the PIN ourselves
                    val variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)
                    Log.d(TAG, "Pairing request ${it.address} variant=$variant")
                    if (variant == BluetoothDevice.PAIRING_VARIANT_PIN && it.address == bondingAddress) {
                        val pin = pins.firstOrNull() ?: DEFAULT_PINS.first()
                        runCatching { it.setPin(pin.toByteArray()) }
                            .onSuccess { _ ->
                                Log.d(TAG, "Answered PIN $pin")
                                // Stop the head unit's own handler from answering a wrong default PIN
                                if (isOrderedBroadcast) abortBroadcast()
                            }
                            .onFailure { e -> Log.w(TAG, "setPin failed: ${e.message}") }
                    }
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> device?.let {
                    val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                    val previous = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE)
                    when {
                        state == BluetoothDevice.BOND_BONDED -> {
                            bondingAddress = null
                            _bondState.value = BondState.Bonded(it.address)
                        }
                        state == BluetoothDevice.BOND_BONDING -> _bondState.value = BondState.Bonding(it.address)
                        previous == BluetoothDevice.BOND_BONDING && it.address == bondingAddress -> {
                            pins = pins.drop(1)
                            if (pins.isNotEmpty()) {
                                Log.d(TAG, "Pairing failed, retrying with PIN ${pins.first()}")
                                handler.postDelayed({ startBond(it.address) }, RETRY_DELAY_MS)
                            } else {
                                bondingAddress = null
                                _bondState.value = BondState.Failed(it.address)
                            }
                        }
                    }
                }
            }
        }
    }

    private val bleCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull()
            add(ScannedDevice(result.device.address, name, isBle = true,
                bonded = result.device.bondState == BluetoothDevice.BOND_BONDED))
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "BLE scan failed: $errorCode")
        }
    }

    /** Asks the user to turn Bluetooth on (system dialog). */
    fun requestEnable(): Boolean = try {
        context.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        Log.w(TAG, "Enable request failed: ${e.message}")
        false
    }

    /** Lists already-paired devices without scanning. */
    fun loadBonded() {
        val a = adapter ?: return
        _devices.value = runCatching { a.bondedDevices.orEmpty() }.getOrDefault(emptySet()).map {
            ScannedDevice(it.address, runCatching { it.name }.getOrNull(),
                isBle = it.type == BluetoothDevice.DEVICE_TYPE_LE, bonded = true)
        }
    }

    fun start(durationMs: Long = SCAN_DURATION_MS) {
        val a = adapter ?: return
        register()
        stop()
        loadBonded()
        _scanning.value = true
        val classic = runCatching { a.startDiscovery() }.getOrDefault(false)
        val ble = runCatching { a.bluetoothLeScanner?.startScan(bleCallback) != null }.getOrDefault(false)
        Log.d(TAG, "Scan started: classic=$classic ble=$ble")
        handler.postDelayed(stopScan, durationMs)
    }

    fun stop() {
        handler.removeCallbacks(stopScan)
        val a = adapter ?: return
        runCatching { if (a.isDiscovering) a.cancelDiscovery() }
        runCatching { a.bluetoothLeScanner?.stopScan(bleCallback) }
        _scanning.value = false
    }

    /** Starts pairing a classic adapter, trying each PIN in turn; progress in [bondState]. */
    fun bond(address: String, pins: List<String> = DEFAULT_PINS): Boolean {
        val a = adapter ?: return false
        register()
        stop()  // discovery slows pairing down
        if (a.getRemoteDevice(address).bondState == BluetoothDevice.BOND_BONDED) {
            _bondState.value = BondState.Bonded(address)
            return true
        }
        this.pins = pins
        bondingAddress = address
        return startBond(address)
    }

    private fun startBond(address: String): Boolean {
        _bondState.value = BondState.Bonding(address)
        val started = adapter?.let { a -> runCatching { a.getRemoteDevice(address).createBond() }.getOrDefault(false) } == true
        if (!started) {
            bondingAddress = null
            _bondState.value = BondState.Failed(address)
        }
        return started
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        stop()
        if (registered) {
            runCatching { context.unregisterReceiver(receiver) }
            registered = false
        }
    }

    private fun register() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            priority = IntentFilter.SYSTEM_HIGH_PRIORITY - 1
        }
        // Sent by the Bluetooth process, not system_server: the receiver must be exported
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        registered = true
    }

    private fun add(device: ScannedDevice) {
        val current = _devices.value
        val existing = current.find { it.address == device.address }
        _devices.value = if (existing == null) current + device else current.map {
            if (it.address != device.address) it
            else it.copy(name = it.name ?: device.name, bonded = it.bonded || device.bonded)
        }
    }

    private fun Intent.bluetoothDevice(): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else
            @Suppress("DEPRECATION") getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    companion object {
        private const val TAG = "BluetoothScanner"
        val DEFAULT_PINS = listOf("1234", "0000")
        private const val RETRY_DELAY_MS = 1_500L
        const val SCAN_DURATION_MS = 15_000L
    }
}
