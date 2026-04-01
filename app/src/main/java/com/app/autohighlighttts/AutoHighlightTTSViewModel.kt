package com.app.autohighlighttts

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import com.app.autohighlighttts.ble.BleManager
import com.app.autohighlighttts.ble.BleManager.ScannedDevice
import com.app.autohighlighttts.sync.TtsSyncBridge
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import javax.inject.Inject
import com.app.autohighlightttssample.R

@HiltViewModel
class AutoHighlightTTSViewModel @Inject constructor(@ApplicationContext context: Context) : ViewModel() {

    companion object {
        private const val TAG = "AutoHighlightTTSVM"
    }

    lateinit var instanceOfTTS: AutoHighlightTTSEngine

    private val bleManager = BleManager(context)
    private val ttsSyncBridge = TtsSyncBridge(bleManager)

    val connectionState: StateFlow<String> = bleManager.connectionState
    val bleStatusDetail: StateFlow<String> = bleManager.statusDetail
    val scannedDevices: StateFlow<List<ScannedDevice>> = bleManager.scannedDevices

    init {
        initTTS(context)
    }

    private fun initTTS(context: Context): AutoHighlightTTSEngine {
        instanceOfTTS = AutoHighlightTTSEngine
            .getInstance()
            .init(context)
            .setLanguage(Locale.ENGLISH)
            .setPitchAndSpeed(1f, 1f)
            .setText(context.getString(R.string.text_to_speech_text))
            .setPreferSentenceLevelSync(true)
            .setOnSpokenRangeListener { utteranceId, start, end, isRangeLevel ->
                Log.d(
                    TAG,
                    "spokenRangeChanged utteranceId=$utteranceId start=$start end=$end isRangeLevel=$isRangeLevel"
                )
                ttsSyncBridge.onSpokenRangeChanged(start, end)
            }
        return instanceOfTTS
    }

    fun requiredBlePermissions(): Array<String> = bleManager.requiredPermissions()

    fun hasRequiredBlePermissions(): Boolean = bleManager.hasRequiredPermissions()

    fun scanBleDevices(includeAllDevices: Boolean = true) = bleManager.scanForDevices(includeAllDevices)

    fun connectBle(device: ScannedDevice) = bleManager.connect(device.bluetoothDevice)

    fun disconnectBle() = bleManager.disconnect()

    fun sendPing() = ttsSyncBridge.sendPing()

    fun sendClear() = ttsSyncBridge.sendClear()

    fun loadSampleText() {
        ttsSyncBridge.loadDocumentTextOnce(instanceOfTTS.mainText)
    }

    fun sendPosition(start: Int, end: Int) {
        ttsSyncBridge.onSpokenRangeChanged(start, end)
    }

    override fun onCleared() {
        ttsSyncBridge.close()
        bleManager.disconnect()
        super.onCleared()
    }
}
