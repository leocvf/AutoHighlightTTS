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
import android.bluetooth.BluetoothStatusCodes
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.UUID

class BleManager(private val context: Context) {

    data class ScannedDevice(
        val bluetoothDevice: BluetoothDevice,
        val name: String,
        val address: String,
        val rssi: Int
    )

    companion object {
        private const val TAG = "BleManager"
        private val TARGET_NAME_HINTS = listOf("CrossPoint", "CrossPoint-X4", "X4")
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
    private var commandServiceUuid: UUID? = null
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
                    commandServiceUuid = null
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

            val (service, characteristic, selectedByFallback) = resolveCommandTarget(gatt.services)
            commandServiceUuid = service?.uuid
            commandCharacteristic = characteristic

            if (commandCharacteristic != null) {
                _connectionState.value = "READY"
                _statusDetail.value = if (selectedByFallback) {
                    "Connected using fallback writable characteristic ${characteristic?.uuid}"
                } else {
                    "Connected using expected CrossPoint command characteristic"
                }
            } else {
                _connectionState.value = "CONNECTED_NO_CHAR"
                _statusDetail.value =
                    "Connected but no writable characteristic was found; verify firmware BLE service"
            }
            Log.d(
                TAG,
                "commandCharacteristicFound=${commandCharacteristic != null} service=$commandServiceUuid characteristic=${commandCharacteristic?.uuid} fallback=$selectedByFallback"
            )
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

    private fun resolveCommandTarget(services: List<BluetoothGattService>): Triple<BluetoothGattService?, BluetoothGattCharacteristic?, Boolean> {
        val expectedService = services.firstOrNull { it.uuid == X4BleUuids.X4_SERVICE_UUID }
        val expectedCharacteristic = expectedService
            ?.characteristics
            ?.firstOrNull { it.uuid == X4BleUuids.X4_COMMAND_CHARACTERISTIC_UUID }
        if (expectedService != null && expectedCharacteristic != null) {
            return Triple(expectedService, expectedCharacteristic, false)
        }

        val bestHintService = services.firstOrNull { service ->
            service.characteristics.any { isWritable(it) } &&
                TARGET_NAME_HINTS.any { hint -> service.uuid.toString().contains(hint, ignoreCase = true) }
        }
        val bestHintCharacteristic = bestHintService?.characteristics?.firstOrNull { isWritable(it) }
        if (bestHintService != null && bestHintCharacteristic != null) {
            return Triple(bestHintService, bestHintCharacteristic, true)
        }

        val firstWritable = services.asSequence()
            .flatMap { service -> service.characteristics.asSequence().map { service to it } }
            .firstOrNull { (_, characteristic) -> isWritable(characteristic) }

        return Triple(firstWritable?.first, firstWritable?.second, firstWritable != null)
    }

    private fun isWritable(characteristic: BluetoothGattCharacteristic): Boolean {
        val props = characteristic.properties
        return (props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) ||
            (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleScanResult(result)
        }

        @SuppressLint("MissingPermission")
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach(::handleScanResult)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "onScanFailed errorCode=$errorCode")
            _connectionState.value = "SCAN_FAILED_$errorCode"
            val detail = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && errorCode == BluetoothStatusCodes.ERROR_MISSING_BLUETOOTH_SCAN_PERMISSION) {
                "Scan failed due to missing BLUETOOTH_SCAN permission"
            } else {
                "Scan failed with code=$errorCode"
            }
            _statusDetail.value = detail
            isScanning = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleScanResult(result: ScanResult) {
        val name = result.device.name ?: result.scanRecord?.deviceName.orEmpty()
        discoveredCount += 1
        Log.d(TAG, "onScanResult name=$name address=${result.device.address}")
        _statusDetail.value =
            "Found ${result.device.address} name='${name.ifBlank { "unknown" }}' rssi=${result.rssi} count=$discoveredCount filterByService=$serviceFilterEnabled"
        upsertScannedDevice(result.device, name, result.rssi)
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
    fun scanForDevices(includeAllDevices: Boolean = false) {
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

        val bleScanner = scanner
        if (bleScanner == null) {
            _connectionState.value = "SCANNER_UNAVAILABLE"
            _statusDetail.value = "Bluetooth LE scanner unavailable on this device"
            Log.w(TAG, "scanForDevices blocked: scanner unavailable")
            return
        }

        serviceFilterEnabled = !includeAllDevices
        _connectionState.value = "SCANNING"
        isScanning = true
        discoveredCount = 0
        _scannedDevices.value = emptyList()
        _statusDetail.value = if (serviceFilterEnabled) {
            "Starting filtered scan for service ${X4BleUuids.X4_SERVICE_UUID}"
        } else {
            "Starting broad scan to discover any nearby BLE device"
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
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
        bleScanner.startScan(filters, settings, scanCallback)
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
        stopScan()
        lastConnectedDevice = device
        _connectionState.value = "CONNECTING"
        _statusDetail.value = "Connecting to ${device.address} (${device.name ?: "unknown"})"
        bluetoothGatt?.close()
        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
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
        commandServiceUuid = null
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
                "No matching result with service UUID after ${SCAN_TIMEOUT_MS / 1000}s; retrying broad BLE scan"
            scanForDevices(includeAllDevices = true)
        } else {
            _connectionState.value = "SCAN_TIMEOUT"
            _statusDetail.value =
                "No BLE device found. Verify the peripheral is advertising BLE (not only classic BT), and permissions are granted."
        }
    }
}
