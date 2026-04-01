package com.app.autohighlighttts.sync

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsSyncBridgeTest {

    private class FakeTransport(
        private val maxBytes: Int = 220
    ) : TtsSyncBridge.CommandTransport {
        val packets = mutableListOf<JSONObject>()
        var queueDepth = 0
        var meanLatencyMs = 8

        override fun writeJson(packet: JSONObject): Boolean {
            packets += JSONObject(packet.toString())
            return true
        }

        override fun maxPayloadBytes(): Int = maxBytes

        override fun pendingWriteCount(): Int = queueDepth

        override fun meanWriteLatencyMs(): Int = meanLatencyMs
    }

    @Test
    fun streamChunking_respectsPayloadBudget() {
        val transport = FakeTransport(maxBytes = 210)
        val bridge = TtsSyncBridge(transport = transport)
        val text = "Sentence one is short. Sentence two is a bit longer and should still fit. Sentence three finishes."

        val chunks = bridge.buildStreamChunks(text, maxJsonBytes = 200, softTargetTextBytes = 120)

        assertTrue(chunks.isNotEmpty())
        chunks.forEach { chunk ->
            val packet = JSONObject()
                .put("type", "stream_chunk")
                .put("sessionId", "s")
                .put("docId", "d")
                .put("sequenceId", chunk.sequenceId)
                .put("chunkId", chunk.chunkId)
                .put("start", chunk.startOffset)
                .put("end", chunk.endOffsetExclusive)
                .put("checksum", chunk.checksum)
                .put("text", chunk.text)
            assertTrue(packet.toString().toByteArray(Charsets.UTF_8).size <= 200)
        }
    }

    @Test
    fun streamProtocol_emitsStartAndSeekAndChunks() = runBlocking {
        val transport = FakeTransport()
        val bridge = TtsSyncBridge(
            transport = transport,
            debounceMs = 1,
            streamingEnabled = true
        )
        val text = "First sentence. Second sentence. Third sentence. Fourth sentence."

        assertTrue(bridge.loadDocumentTextOnce(text))
        Thread.sleep(30)
        bridge.onSpokenRangeChanged(0, 5)
        Thread.sleep(30)
        bridge.onSpokenRangeChanged(500, 510)
        Thread.sleep(80)

        val types = transport.packets.map { it.optString("type") }
        assertTrue(types.contains("stream_start"))
        assertTrue(types.contains("stream_chunk"))
        assertTrue(types.contains("stream_commit"))
        assertTrue(types.contains("stream_seek"))
    }

    @Test
    fun fallbackMode_usesLoadText() {
        val transport = FakeTransport()
        val bridge = TtsSyncBridge(
            transport = transport,
            streamingEnabled = false,
            sendPositionPackets = false
        )

        val loaded = bridge.loadDocumentTextOnce("Alpha beta gamma")
        assertTrue(loaded)
        bridge.onSpokenRangeChanged(0, 5)
        Thread.sleep(40)

        val types = transport.packets.map { it.optString("type") }
        assertTrue(types.contains("load_text"))
        assertFalse(types.contains("stream_chunk"))
    }

    @Test
    fun fallbackMode_reloadsChunkAndSendsLocalOffsets() {
        val transport = FakeTransport(maxBytes = 170)
        val bridge = TtsSyncBridge(
            transport = transport,
            debounceMs = 1,
            streamingEnabled = false,
            sendPositionPackets = true
        )
        val text = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. ".repeat(14)

        assertTrue(bridge.loadDocumentTextOnce(text))
        Thread.sleep(30)
        bridge.onSpokenRangeChanged(320, 340)
        Thread.sleep(50)

        val loadPackets = transport.packets.filter { it.optString("type") == "load_text" }
        val positionPackets = transport.packets.filter { it.optString("type") == "position" }

        assertTrue(loadPackets.size >= 2)
        assertTrue(positionPackets.isNotEmpty())
        val lastPosition = positionPackets.last()
        assertTrue(lastPosition.getInt("start") < 320)
        assertTrue(lastPosition.getInt("end") <= loadPackets.last().getString("text").length)
    }

    @Test
    fun fallbackRangeMode_keepsDocIdStable() {
        val transport = FakeTransport()
        val bridge = TtsSyncBridge(
            transport = transport,
            debounceMs = 1,
            streamingEnabled = false,
            sendPositionPackets = false
        )

        assertTrue(bridge.loadDocumentTextOnce("Alpha beta gamma"))
        Thread.sleep(20)
        bridge.onSpokenRangeChanged(0, 5)
        Thread.sleep(40)

        val lastLoad = transport.packets.last { it.optString("type") == "load_text" }
        assertEquals("demo-001", lastLoad.getString("docId"))
    }
}
