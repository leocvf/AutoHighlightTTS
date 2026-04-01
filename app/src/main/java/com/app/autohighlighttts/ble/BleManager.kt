package com.app.autohighlighttts.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.util.ArrayDeque

class BleManager(private val context: Context) {

    data class ScannedDevice(
        val bluetoothDevice: BluetoothDevice,
        val name: String,
        val address: String,
        val rssi: Int
    )

    companion object {
        private const val TAG = "BleManager"
        private val TARGET_NAME_HINTS = listOf("CrossPoint-X4-TTS", "X4")
        private const val DEFAULT_ATT_MTU = 23
        private const val ATT_WRITE_OVERHEAD = 3
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val RECONNECT_DELAY_MS = 2_000L
        private const val SCAN_TIMEOUT_MS = 12_000L
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        manager.adapter
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var bluetoothGatt: BluetoothGatt? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    private var lastConnectedDevice: BluetoothDevice? = null
    private var reconnectAttempts = 0
    private var currentMtu: Int = DEFAULT_ATT_MTU

    private var isOperationInFlight = false
    private val writeQueue = ArrayDeque<ByteArray>()

    private val scanner get() = bluetoothAdapter?.bluetoothLeScanner

    private val _connectionState = MutableStateFlow("DISCONNECTED")
    val connectionState: StateFlow<String> = _connectionState
    private val _statusDetail = MutableStateFlow("Idle")
    val statusDetail: StateFlow<String> = _statusDetail
    private val _scannedDevices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val scannedDevices = _scannedDevices.asStateFlow()

    @Volatile
    private var isScanning: Boolean = false
    private var discoveredCount: Int = 0
    private var serviceFilterEnabled: Boolean = true

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange status=$status newState=$newState")
            _statusDetail.value = "GATT state changed: status=$status newState=$newState"
            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    reconnectAttempts = 0
                    _connectionState.value = "CONNECTED"
                    _statusDetail.value = "Connected to ${gatt.device.address}; requesting MTU and services"
                    gatt.requestMtu(247)
                    gatt.discoverServices()
                }

                BluetoothGatt.STATE_CONNECTING -> {
                    _connectionState.value = "CONNECTING"
                }

                else -> {
                    _connectionState.value = "DISCONNECTED"
                    commandCharacteristic = null
                    isOperationInFlight = false
                    writeQueue.clear()
                    bluetoothGatt?.close()
                    bluetoothGatt = null
                    scheduleReconnectIfPossible()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                currentMtu = mtu
            }
            Log.d(TAG, "onMtuChanged status=$status mtu=$mtu currentMtu=$currentMtu")
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.d(TAG, "onServicesDiscovered status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _statusDetail.value = "Service discovery failed: status=$status"
                return
            }
            val service: BluetoothGattService? = gatt.getService(X4BleUuids.X4_SERVICE_UUID)
            commandCharacteristic =
                service?.getCharacteristic(X4BleUuids.X4_COMMAND_CHARACTERISTIC_UUID)

            if (commandCharacteristic != null) {
                _connectionState.value = "READY"
                _statusDetail.value = "Command characteristic discovered"
            } else {
                _connectionState.value = "CONNECTED_NO_CHAR"
                _statusDetail.value = "Connected but command characteristic not found"
            }
            Log.d(TAG, "commandCharacteristicFound=${commandCharacteristic != null}")
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            Log.d(TAG, "onCharacteristicWrite uuid=${characteristic.uuid} status=$status")
            _statusDetail.value = "Last write status=$status queueRemaining=${writeQueue.size}"
            isOperationInFlight = false
            flushNextWrite()
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: result.scanRecord?.deviceName.orEmpty()
            discoveredCount += 1
            Log.d(TAG, "onScanResult name=$name address=${result.device.address}")
            _statusDetail.value =
                "Found ${result.device.address} name='${name.ifBlank { "unknown" }}' rssi=${result.rssi} count=$discoveredCount filterByService=$serviceFilterEnabled"
            upsertScannedDevice(result.device, name, result.rssi)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "onScanFailed errorCode=$errorCode")
            _connectionState.value = "SCAN_FAILED_$errorCode"
            _statusDetail.value = "Scan failed with code=$errorCode"
            isScanning = false
        }
    }

    fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    fun hasRequiredPermissions(): Boolean {
        return requiredPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    fun scanForDevices() {
        if (!hasRequiredPermissions()) {
            _connectionState.value = "MISSING_PERMISSION"
            Log.w(TAG, "scanForDevices blocked: missing permissions")
            return
        }
        if (isScanning) return

        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            _connectionState.value = "BT_DISABLED"
            Log.w(TAG, "scanForDevices blocked: bluetooth disabled")
            return
        }

        _connectionState.value = "SCANNING"
        isScanning = true
        discoveredCount = 0
        _scannedDevices.value = emptyList()
        _statusDetail.value = "Starting scan; expected names include ${TARGET_NAME_HINTS.joinToString()}"
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val filters = if (serviceFilterEnabled) {
            listOf(
                ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(X4BleUuids.X4_SERVICE_UUID))
                    .build()
            )
        } else {
            emptyList()
        }
        scanner?.startScan(filters, settings, scanCallback)
        mainHandler.removeCallbacks(scanTimeoutRunnable)
        mainHandler.postDelayed(scanTimeoutRunnable, SCAN_TIMEOUT_MS)
        Log.d(TAG, "BLE scan started filterByService=$serviceFilterEnabled")
    }

    private fun upsertScannedDevice(device: BluetoothDevice, name: String, rssi: Int) {
        val normalizedName = name.ifBlank { "Unknown" }
        val updated = _scannedDevices.value.toMutableList()
        val index = updated.indexOfFirst { it.address == device.address }
        val record = ScannedDevice(
            bluetoothDevice = device,
            name = normalizedName,
            address = device.address,
            rssi = rssi
        )
        if (index >= 0) {
            updated[index] = record
        } else {
            updated.add(record)
        }
        _scannedDevices.value = updated.sortedByDescending { it.rssi }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!isScanning) return
        scanner?.stopScan(scanCallback)
        isScanning = false
        mainHandler.removeCallbacks(scanTimeoutRunnable)
        Log.d(TAG, "BLE scan stopped")
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        if (!hasRequiredPermissions()) {
            _connectionState.value = "MISSING_PERMISSION"
            return
        }
        lastConnectedDevice = device
        _connectionState.value = "CONNECTING"
        _statusDetail.value = "Connecting to ${device.address} (${device.name ?: "unknown"})"
        bluetoothGatt?.close()
        bluetoothGatt = device.connectGatt(context, false, gattCallback)
        Log.d(TAG, "connect to ${device.address}")
    }

    @SuppressLint("MissingPermission", "DEPRECATION")
    fun writeJson(json: JSONObject): Boolean {
        return queuePayload(json.toString().toByteArray(Charsets.UTF_8), json.toString())
    }

    @SuppressLint("MissingPermission", "DEPRECATION")
    private fun queuePayload(payload: ByteArray, rawMessage: String): Boolean {
        val gatt = bluetoothGatt
        val characteristic = commandCharacteristic
        if (gatt == null || characteristic == null) {
            Log.w(TAG, "writeJson skipped: connection not ready payload=$rawMessage")
            _statusDetail.value = "Write skipped because connection is not READY"
            return false
        }

        val maxChunkBytes = (currentMtu - ATT_WRITE_OVERHEAD).coerceAtLeast(20)
        payload.asList()
            .chunked(maxChunkBytes)
            .map { it.toByteArray() }
            .forEach { chunk -> writeQueue.add(chunk) }

        Log.d(
            TAG,
            "queuePayload queuedChunks=${writeQueue.size} payloadBytes=${payload.size} mtu=$currentMtu raw=$rawMessage"
        )
        flushNextWrite()
        return true
    }

    @SuppressLint("MissingPermission", "DEPRECATION")
    private fun flushNextWrite() {
        if (isOperationInFlight) return

        val gatt = bluetoothGatt
        val characteristic = commandCharacteristic
        if (gatt == null || characteristic == null) {
            writeQueue.clear()
            return
        }

        if (writeQueue.isEmpty()) return
        val nextChunk = writeQueue.removeFirst()
        isOperationInFlight = true

        if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }

        characteristic.value = nextChunk
        val ok = gatt.writeCharacteristic(characteristic)
        Log.d(
            TAG,
            "flushNextWrite ok=$ok bytes=${nextChunk.size} writeType=${characteristic.writeType} queueRemaining=${writeQueue.size}"
        )
        if (!ok) {
            isOperationInFlight = false
            _statusDetail.value = "Write failed to enqueue at GATT layer"
        }
    }

    private fun scheduleReconnectIfPossible() {
        val device = lastConnectedDevice ?: return
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Max reconnect attempts reached")
            return
        }
        reconnectAttempts += 1
        _connectionState.value = "RECONNECTING_$reconnectAttempts"
        _statusDetail.value = "Disconnected; reconnect attempt $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS in ${RECONNECT_DELAY_MS}ms"
        mainHandler.postDelayed(
            {
                Log.d(TAG, "Reconnect attempt $reconnectAttempts")
                connect(device)
            },
            RECONNECT_DELAY_MS
        )
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        stopScan()
        mainHandler.removeCallbacksAndMessages(null)
        reconnectAttempts = MAX_RECONNECT_ATTEMPTS
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        commandCharacteristic = null
        isOperationInFlight = false
        writeQueue.clear()
        _connectionState.value = "DISCONNECTED"
        _statusDetail.value = "Disconnected by user"
        Log.d(TAG, "BLE disconnected")
    }

    private val scanTimeoutRunnable = Runnable {
        if (!isScanning) {
            return@Runnable
        }
        stopScan()
        if (serviceFilterEnabled) {
            serviceFilterEnabled = false
            _connectionState.value = "SCANNING_RETRY_NO_FILTER"
            _statusDetail.value =
                "No matching result with service UUID after ${SCAN_TIMEOUT_MS / 1000}s; retrying unfiltered scan"
            scanForDevices()
        } else {
            _connectionState.value = "SCAN_TIMEOUT"
            _statusDetail.value =
                "No BLE device found. Check advertising name/UUID, bonding, and distance."
        }
    }
}
