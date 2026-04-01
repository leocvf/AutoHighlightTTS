package com.app.autohighlighttts.sync

import android.os.SystemClock
import android.util.Log
import com.app.autohighlighttts.ble.BleManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

class TtsSyncBridge(
    private val bleManager: BleManager,
    private var docId: String = "demo-001",
    private val debounceMs: Long = 150L
) {

    companion object {
        private const val TAG = "TtsSyncBridge"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var pendingPosition: Pair<Int, Int>? = null
    private var positionJob: Job? = null
    private var lastPositionSentAt = 0L

    fun sendPing() = sendPacket(JSONObject().put("type", "ping"))

    fun sendClear() = sendPacket(JSONObject().put("type", "clear"))

    fun setDocId(newDocId: String) {
        if (newDocId.isNotBlank()) {
            docId = newDocId
        }
    }

    fun loadDocumentTextOnce(text: String): Boolean {
        if (text.isBlank()) {
            Log.w(TAG, "loadDocumentTextOnce ignored (blank text)")
            return false
        }
        val packet = JSONObject()
            .put("type", "load_text")
            .put("docId", docId)
            .put("text", text)
        sendPacket(packet)
        return true
    }

    fun onSpokenRangeChanged(start: Int, end: Int) {
        pendingPosition = start to end
        if (positionJob?.isActive == true) return

        positionJob = scope.launch {
            delay(debounceMs)
            val range = pendingPosition ?: return@launch
            pendingPosition = null

            val now = SystemClock.elapsedRealtime()
            if (now - lastPositionSentAt < debounceMs) {
                delay(debounceMs)
            }

            val packet = JSONObject()
                .put("type", "position")
                .put("docId", docId)
                .put("start", range.first)
                .put("end", range.second)
            sendPacket(packet)
            lastPositionSentAt = SystemClock.elapsedRealtime()
        }
    }

    fun close() {
        scope.coroutineContext.cancel()
    }

    private fun sendPacket(packet: JSONObject) {
        val sent = bleManager.writeJson(packet)
        Log.d(TAG, "outboundCommand sent=$sent payload=$packet")
    }
}
