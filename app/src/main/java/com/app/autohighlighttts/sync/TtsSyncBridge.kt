package com.app.autohighlighttts.sync

import android.os.SystemClock
import android.util.Log
import com.app.autohighlighttts.ble.BleManager
import org.json.JSONObject

class TtsSyncBridge(
    private val bleManager: BleManager,
    private val docId: String = "local-demo"
) {

    companion object {
        private const val TAG = "TtsSyncBridge"
        private const val POSITION_DEBOUNCE_MS = 80L
    }

    private var lastPosition: Pair<Int, Int>? = null
    private var lastPositionAt = 0L

    fun sendPing() {
        val packet = JSONObject().put("type", "ping")
        sendPacket(packet)
    }

    fun sendLoadText(text: String) {
        val packet = JSONObject()
            .put("type", "load_text")
            .put("docId", docId)
            .put("text", text)
        sendPacket(packet)
    }

    fun onSpokenRangeChanged(start: Int, end: Int) {
        val now = SystemClock.elapsedRealtime()
        val current = Pair(start, end)
        if (current == lastPosition && now - lastPositionAt < POSITION_DEBOUNCE_MS) {
            return
        }
        lastPosition = current
        lastPositionAt = now

        val packet = JSONObject()
            .put("type", "position")
            .put("docId", docId)
            .put("start", start)
            .put("end", end)
        sendPacket(packet)
    }

    private fun sendPacket(packet: JSONObject) {
        val sent = bleManager.writeJson(packet)
        Log.d(TAG, "packetSent=$sent packet=$packet")
    }
}
