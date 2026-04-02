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

    private class FakeAckSource(private val bridge: TtsSyncBridge) {
        fun emitAck(seq: Int) = bridge.onAckReceived(seq)
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
                .put("seq", chunk.sequenceId)
                .put("offset", chunk.startOffset)
                .put("chunkId", chunk.chunkId)
                .put("checksum", chunk.checksum)
                .put("text", chunk.text)
                .put("sequenceId", chunk.sequenceId)
                .put("start", chunk.startOffset)
                .put("end", chunk.endOffsetExclusive)
            assertTrue(packet.toString().toByteArray(Charsets.UTF_8).size <= 200)
        }
    }

    @Test
    fun noAckPath_commitsStillProgress() = runBlocking {
        val transport = FakeTransport()
        val bridge = TtsSyncBridge(
            transport = transport,
            debounceMs = 1,
            streamingEnabled = true,
            ackFallbackTimeoutMs = 10
        )
        val text = "First sentence. Second sentence. Third sentence. Fourth sentence."

        assertTrue(bridge.loadDocumentTextOnce(text))
        Thread.sleep(40)
        bridge.onSpokenRangeChanged(0, 12)
        Thread.sleep(80)

        val commits = transport.packets.filter { it.optString("type") == "stream_commit" }
        assertTrue(commits.isNotEmpty())
        assertTrue(commits.last().optInt("uptoSeq", -1) >= 0)
    }

    @Test
    fun ackPath_commitProgressesOnAck() {
        val transport = FakeTransport()
        val bridge = TtsSyncBridge(
            transport = transport,
            debounceMs = 1,
            streamingEnabled = true,
            compatibilityProfile = TtsSyncBridge.CompatibilityProfile(requireAckForCommit = true)
        )
        bridge.setFeedbackChannelReady(true)
        assertTrue(bridge.loadDocumentTextOnce("First sentence. Second sentence."))
        Thread.sleep(40)

        FakeAckSource(bridge).emitAck(1)
        Thread.sleep(40)

        val commits = transport.packets.filter { it.optString("type") == "stream_commit" }
        assertTrue(commits.isNotEmpty())
        assertEquals(1, commits.last().optInt("uptoSeq", -1))
    }

    @Test
    fun negativeCommittedSeq_neverSent() {
        val transport = FakeTransport()
        val bridge = TtsSyncBridge(
            transport = transport,
            debounceMs = 1,
            streamingEnabled = true,
            compatibilityProfile = TtsSyncBridge.CompatibilityProfile(requireAckForCommit = true),
            ackFallbackTimeoutMs = 2_000
        )
        bridge.setFeedbackChannelReady(true)
        assertTrue(bridge.loadDocumentTextOnce("One. Two."))
        Thread.sleep(40)

        val commits = transport.packets.filter { it.optString("type") == "stream_commit" }
        assertTrue(commits.isEmpty())
    }

    @Test
    fun startSeq_alignsWithFirstChunkSequence() {
        val transport = FakeTransport()
        val bridge = TtsSyncBridge(transport = transport)

        assertTrue(bridge.loadDocumentTextOnce("Sentence one. Sentence two."))
        Thread.sleep(40)

        val start = transport.packets.first { it.optString("type") == "stream_start" }
        val firstChunk = transport.packets.first { it.optString("type") == "stream_chunk" }
        assertEquals(firstChunk.optInt("seq", -1), start.optInt("startSeq", -2))
        assertEquals(1, firstChunk.optInt("seq", -1))
    }

    @Test
    fun streamChunking_sequencesAreStrictlyIncreasingAfterSplits() {
        val transport = FakeTransport(maxBytes = 170)
        val bridge = TtsSyncBridge(transport = transport)
        val text =
            "Sentence one is intentionally elongated to trigger additional splitting across payload limits. " +
                "Sentence two is also long enough to require payload-aware split behavior."

        val chunks = bridge.buildStreamChunks(text, maxJsonBytes = 160, softTargetTextBytes = 80)

        assertTrue(chunks.isNotEmpty())
        val seqs = chunks.map { it.sequenceId }
        assertEquals((1 until (1 + chunks.size)).toList(), seqs)
    }

    @Test
    fun retryBackoff_andMaxRetryBehavior() {
        val transport = FakeTransport()
        val bridge = TtsSyncBridge(
            transport = transport,
            streamingEnabled = true,
            compatibilityProfile = TtsSyncBridge.CompatibilityProfile(requireAckForCommit = true),
            ackRetryBaseMs = 20,
            maxChunkRetries = 1
        )
        bridge.setFeedbackChannelReady(true)
        assertTrue(bridge.loadDocumentTextOnce("A. B. C. D."))

        Thread.sleep(180)

        val chunkPackets = transport.packets.filter { it.optString("type") == "stream_chunk" }
        val seqOneAttempts = chunkPackets.count { it.optInt("seq", -1) == 1 }
        assertTrue(seqOneAttempts <= 2)
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
}
