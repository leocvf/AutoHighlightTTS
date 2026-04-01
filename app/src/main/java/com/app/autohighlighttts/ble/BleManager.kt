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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.util.ArrayDeque

class BleManager(private val context: Context) {

    companion object {
        private const val TAG = "BleManager"
        private const val TARGET_HINT = "X4"
        private const val DEFAULT_ATT_MTU = 23
        private const val ATT_WRITE_OVERHEAD = 3
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val RECONNECT_DELAY_MS = 2_000L
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

    @Volatile
    private var isScanning: Boolean = false

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange status=$status newState=$newState")
            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    reconnectAttempts = 0
                    _connectionState.value = "CONNECTED"
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
                return
            }
            val service: BluetoothGattService? = gatt.getService(X4BleUuids.X4_SERVICE_UUID)
            commandCharacteristic =
                service?.getCharacteristic(X4BleUuids.X4_COMMAND_CHARACTERISTIC_UUID)

            if (commandCharacteristic != null) {
                _connectionState.value = "READY"
            } else {
                _connectionState.value = "CONNECTED_NO_CHAR"
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
            isOperationInFlight = false
            flushNextWrite()
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: result.scanRecord?.deviceName.orEmpty()
            Log.d(TAG, "onScanResult name=$name address=${result.device.address}")
            if (name.contains(TARGET_HINT, ignoreCase = true)) {
                stopScan()
                connect(result.device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "onScanFailed errorCode=$errorCode")
            _connectionState.value = "SCAN_FAILED_$errorCode"
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
    fun scanAndConnect() {
        if (!hasRequiredPermissions()) {
            _connectionState.value = "MISSING_PERMISSION"
            Log.w(TAG, "scanAndConnect blocked: missing permissions")
            return
        }
        if (isScanning) return

        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            _connectionState.value = "BT_DISABLED"
            Log.w(TAG, "scanAndConnect blocked: bluetooth disabled")
            return
        }

        _connectionState.value = "SCANNING"
        isScanning = true
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(X4BleUuids.X4_SERVICE_UUID))
                .build()
        )
        scanner?.startScan(filters, settings, scanCallback)
        Log.d(TAG, "BLE scan started")
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!isScanning) return
        scanner?.stopScan(scanCallback)
        isScanning = false
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
        Log.d(TAG, "BLE disconnected")
    }
}
