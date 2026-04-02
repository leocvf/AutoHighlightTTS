package com.app.autohighlighttts

import android.content.Context
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import androidx.lifecycle.ViewModel
import com.app.autohighlighttts.ble.BleManager
import com.app.autohighlighttts.ble.BleManager.ScannedDevice
import com.app.autohighlighttts.sync.TtsSyncBridge
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import javax.inject.Inject
import com.app.autohighlightttssample.R

@HiltViewModel
class AutoHighlightTTSViewModel @Inject constructor(@ApplicationContext context: Context) : ViewModel() {

    companion object {
        private const val TAG = "AutoHighlightTTSVM"
    }

    lateinit var instanceOfTTS: AutoHighlightTTSEngine
    private val appContext: Context = context

    private val bleManager = BleManager(context)
    private val _streamingModeEnabled = MutableStateFlow(false)
    val streamingModeEnabled: StateFlow<Boolean> = _streamingModeEnabled.asStateFlow()
    private var currentDocId: String = "doc-default"
    private var ttsSyncBridge: TtsSyncBridge = createSyncBridge(_streamingModeEnabled.value)
    private val _bleStreamDebugState = MutableStateFlow(TtsSyncBridge.DebugState(false, -1, -1, 0, 0, 0, ""))
    val bleStreamDebugState: StateFlow<TtsSyncBridge.DebugState> = _bleStreamDebugState.asStateFlow()

    val connectionState: StateFlow<String> = bleManager.connectionState
    val bleStatusDetail: StateFlow<String> = bleManager.statusDetail
    val scannedDevices: StateFlow<List<ScannedDevice>> = bleManager.scannedDevices

    init {
        ttsSyncBridge.setDebugStateListener { _bleStreamDebugState.value = it }
        bleManager.onFeedbackChannelReady = { ready ->
            ttsSyncBridge.setFeedbackChannelReady(ready)
        }
        bleManager.onFeedbackPacket = { packet ->
            if (packet.optString("type") == "ack") {
                val sequenceId = when {
                    packet.has("sequenceId") -> packet.optInt("sequenceId", -1)
                    packet.has("highestContiguousSeq") -> packet.optInt("highestContiguousSeq", -1)
                    else -> -1
                }
                if (sequenceId >= 0) {
                    ttsSyncBridge.onAckReceived(sequenceId)
                }
            }
        }
        initTTS(context)
        ttsSyncBridge.setDocId(currentDocId)
        ttsSyncBridge.loadDocumentTextOnce(instanceOfTTS.mainText)
    }

    private fun createSyncBridge(streamingEnabled: Boolean): TtsSyncBridge {
        return TtsSyncBridge(
            bleManager = bleManager,
            sendPositionPackets = true,
            streamingEnabled = streamingEnabled
        )
    }

    fun setStreamingModeEnabled(enabled: Boolean) {
        if (_streamingModeEnabled.value == enabled) return

        ttsSyncBridge.sendClear()
        ttsSyncBridge.close()
        _streamingModeEnabled.value = enabled
        ttsSyncBridge = createSyncBridge(enabled)
        ttsSyncBridge.setDebugStateListener { _bleStreamDebugState.value = it }
        bleManager.onFeedbackChannelReady = { ready -> ttsSyncBridge.setFeedbackChannelReady(ready) }
        ttsSyncBridge.setDocId(currentDocId)
        ttsSyncBridge.loadDocumentTextOnce(instanceOfTTS.mainText)
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

    fun updateNarrationText(text: String) {
        currentDocId = "doc-${text.hashCode()}"
        ttsSyncBridge.setDocId(currentDocId)
        instanceOfTTS.setText(text)
        ttsSyncBridge.loadDocumentTextOnce(instanceOfTTS.mainText)
    }

    fun updatePitchAndSpeed(pitch: Float, speed: Float) {
        instanceOfTTS.setPitchAndSpeed(pitch, speed)
    }

    fun availableEngines(): List<TextToSpeech.EngineInfo> = instanceOfTTS.getAvailableEngines()

    fun selectEngine(enginePackageName: String) {
        instanceOfTTS.setEngine(enginePackageName)
    }

    fun availableVoices(): List<Voice> = instanceOfTTS.getAvailableVoices()

    fun selectVoice(voiceName: String) {
        instanceOfTTS.setVoiceByName(voiceName)
    }

    fun loadEpub(uri: Uri): String {
        currentDocId = uri.lastPathSegment?.substringAfterLast('/') ?: "epub-${System.currentTimeMillis()}"
        ttsSyncBridge.setDocId(currentDocId)
        val text = EpubParser.readText(appContext, uri)
        if (text.isNotBlank()) {
            updateNarrationText(text)
        }
        return text
    }

    override fun onCleared() {
        ttsSyncBridge.close()
        bleManager.disconnect()
        super.onCleared()
    }
}
