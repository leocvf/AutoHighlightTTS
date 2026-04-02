package com.app.autohighlighttts.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacyReconnectInstrumentedTest {

    private class FlakyFakeGattTransport : TtsSyncBridge.CommandTransport {
        private var failuresRemaining = 5
        var reconnectCalls = 0
        val packets = mutableListOf<JSONObject>()

        override fun writeJson(packet: JSONObject): Boolean {
            if (failuresRemaining > 0) {
                failuresRemaining--
                return false
            }
            packets += JSONObject(packet.toString())
            return true
        }

        override fun maxPayloadBytes(): Int = 220

        override fun pendingWriteCount(): Int = 0

        override fun meanWriteLatencyMs(): Int = 12

        override fun isReady(): Boolean = true

        override fun reconnectLightweight(reason: String): Boolean {
            reconnectCalls++
            return true
        }
    }

    @Test
    fun reconnect_resumeFlow_replaysLegacySession() {
        val transport = FlakyFakeGattTransport()
        val bridge = TtsSyncBridge(
            transport = transport,
            streamingEnabled = false,
            debounceMs = 5
        )

        assertTrue(bridge.loadDocumentTextOnce("Paragraph one. Paragraph two. Paragraph three."))
        bridge.onSpokenRangeChanged(10, 20)
        Thread.sleep(1_100)

        assertTrue(transport.reconnectCalls > 0)
        assertTrue(transport.packets.any { it.optString("type") == "load_text" })
        assertTrue(transport.packets.any { it.optString("type") == "position" })
    }
}
