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
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

class BleManager(private val context: Context) {

    companion object {
        private const val TAG = "BleManager"
        private const val TARGET_HINT = "X4"
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        manager.adapter
    }

    private var bluetoothGatt: BluetoothGatt? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    private val scanner get() = bluetoothAdapter?.bluetoothLeScanner

    private val _connectionState = MutableStateFlow("DISCONNECTED")
    val connectionState: StateFlow<String> = _connectionState

    @Volatile
    private var isScanning: Boolean = false

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange status=$status newState=$newState")
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                _connectionState.value = "CONNECTED"
                // TODO(X4 firmware): verify if MTU negotiation is required for payload size.
                gatt.discoverServices()
            } else if (newState == BluetoothGatt.STATE_CONNECTING) {
                _connectionState.value = "CONNECTING"
            } else {
                _connectionState.value = "DISCONNECTED"
                commandCharacteristic = null
                bluetoothGatt?.close()
                bluetoothGatt = null
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.d(TAG, "onServicesDiscovered status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                return
            }
            val service: BluetoothGattService? = gatt.getService(X4BleUuids.X4_SERVICE_UUID)
            commandCharacteristic =
                service?.getCharacteristic(X4BleUuids.X4_COMMAND_CHARACTERISTIC_UUID)

            _connectionState.value = if (commandCharacteristic != null) {
                "READY"
            } else {
                // TODO(X4 firmware): ensure service/characteristic UUID values match firmware bridge.
                "CONNECTED_NO_CHAR"
            }
            Log.d(TAG, "commandCharacteristicFound=${commandCharacteristic != null}")
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            Log.d(TAG, "onCharacteristicWrite uuid=${characteristic.uuid} status=$status")
        }
    }

    private val scanCallback = object : ScanCallback() {
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
        if (isScanning) {
            return
        }
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
        scanner?.startScan(null, settings, scanCallback)
        Log.d(TAG, "BLE scan started")
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!isScanning) {
            return
        }
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
        _connectionState.value = "CONNECTING"
        bluetoothGatt?.close()
        bluetoothGatt = device.connectGatt(context, false, gattCallback)
        Log.d(TAG, "connect to ${device.address}")
    }

    @SuppressLint("MissingPermission", "DEPRECATION")
    fun writeJson(json: JSONObject): Boolean {
        val gatt = bluetoothGatt
        val characteristic = commandCharacteristic
        if (gatt == null || characteristic == null) {
            Log.w(TAG, "writeJson skipped: connection not ready payload=$json")
            return false
        }

        val payload = json.toString().toByteArray(Charsets.UTF_8)
        characteristic.value = payload
        val ok = gatt.writeCharacteristic(characteristic)
        Log.d(TAG, "writeJson sent=$ok payload=$json")
        return ok
    }

    fun disconnect() {
        stopScan()
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        commandCharacteristic = null
        _connectionState.value = "DISCONNECTED"
        Log.d(TAG, "BLE disconnected")
    }
}
