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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

class TtsSyncBridge(
    private val transport: CommandTransport,
    private var docId: String = "demo-001",
    private val debounceMs: Long = 150L,
    private val sendPositionPackets: Boolean = true,
    private val streamingEnabled: Boolean = true,
    private val streamVersion: Int = 2
) {
    constructor(
        bleManager: BleManager,
        docId: String = "demo-001",
        debounceMs: Long = 150L,
        sendPositionPackets: Boolean = true,
        streamingEnabled: Boolean = true,
        streamVersion: Int = 2
    ) : this(
        transport = BleCommandTransport(bleManager),
        docId = docId,
        debounceMs = debounceMs,
        sendPositionPackets = sendPositionPackets,
        streamingEnabled = streamingEnabled,
        streamVersion = streamVersion
    )

    interface CommandTransport {
        fun writeJson(packet: JSONObject): Boolean
        fun maxPayloadBytes(): Int
        fun pendingWriteCount(): Int
        fun meanWriteLatencyMs(): Int
    }

    private class BleCommandTransport(private val bleManager: BleManager) : CommandTransport {
        override fun writeJson(packet: JSONObject): Boolean = bleManager.writeJson(packet)
        override fun maxPayloadBytes(): Int = bleManager.maxPayloadBytes()
        override fun pendingWriteCount(): Int = bleManager.pendingWriteCount()
        override fun meanWriteLatencyMs(): Int = bleManager.meanWriteLatencyMs()
    }

    companion object {
        private const val TAG = "TtsSyncBridge"
        private const val MIN_LOOKAHEAD_CHUNKS = 2
        private const val MAX_LOOKAHEAD_CHUNKS = 5
        private const val MIN_BUFFERED_CHARS = 360
        private const val ACK_TIMEOUT_MS = 900L
        private const val MAX_RETRY = 2
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var pendingPosition: Pair<Int, Int>? = null
    private var positionJob: Job? = null
    private var senderJob: Job? = null
    private var ackMonitorJob: Job? = null
    private var lastPositionSentAt = 0L
    private var sourceDocumentText: String = ""
    private var lastHighlightedPayload: String? = null
    private var legacyChunks: List<LegacyChunk> = emptyList()
    private var activeLegacyChunkIndex: Int = -1

    private var session: StreamSession? = null
    private var ackModeEnabled: Boolean = false
    private var lastSeekOffset: Int = 0

    private val outboundQueue = ArrayDeque<JSONObject>()
    private val ackWaiting = ConcurrentHashMap<Int, InFlightChunk>()
    private val sentChunkSeqs = mutableSetOf<Int>()
    private var committedSeq = -1

    private var tokenBucket = 4.0
    private var lastRefillMs = SystemClock.elapsedRealtime()

    private data class StreamSession(
        val sessionId: String,
        val docId: String,
        val streamVersion: Int,
        val totalChars: Int,
        val chunks: List<StreamChunk>
    )

    internal data class StreamChunk(
        val chunkId: Int,
        val sequenceId: Int,
        val startOffset: Int,
        val endOffsetExclusive: Int,
        val text: String,
        val checksum: Int,
        val sentenceTail: Boolean
    )

    private data class InFlightChunk(
        val chunk: StreamChunk,
        var lastSentAtMs: Long,
        var retryCount: Int
    )

    private data class LegacyChunk(
        val startOffset: Int,
        val endOffsetExclusive: Int,
        val text: String
    )

    internal data class StreamMetrics(
        var chunksSent: Int = 0,
        var chunksRetried: Int = 0,
        var droppedChunks: Int = 0,
        var resyncCount: Int = 0,
        var seekCount: Int = 0
    )

    private val metrics = StreamMetrics()

    fun sendPing() = enqueuePacket(JSONObject().put("type", "ping"))

    fun sendClear() {
        resetStreamingState()
        enqueuePacket(JSONObject().put("type", "clear"))
    }

    fun setDocId(newDocId: String) {
        if (newDocId.isNotBlank()) {
            docId = newDocId
            resetStreamingState()
        }
    }

    fun setAckModeEnabled(enabled: Boolean) {
        ackModeEnabled = enabled
    }

    fun onAckReceived(sequenceId: Int) {
        if (!ackModeEnabled) return
        ackWaiting.remove(sequenceId)
        while (ackWaiting[committedSeq + 1] == null && committedSeq + 1 in sentChunkSeqs) {
            committedSeq += 1
        }
    }

    fun loadDocumentTextOnce(text: String): Boolean {
        if (text.isBlank()) {
            Log.w(TAG, "loadDocumentTextOnce ignored (blank text)")
            return false
        }
        sourceDocumentText = text
        lastHighlightedPayload = null

        if (!streamingEnabled) {
            return buildLegacyChunks(text).isNotEmpty()
        }

        val maxJsonBytes = (transport.maxPayloadBytes() - 8).coerceAtLeast(90)
        val chunks = buildStreamChunks(
            text = text,
            maxJsonBytes = maxJsonBytes,
            softTargetTextBytes = 150
        )

        if (chunks.isEmpty()) {
            Log.w(TAG, "loadDocumentTextOnce produced no chunks")
            return false
        }

        session = StreamSession(
            sessionId = UUID.randomUUID().toString(),
            docId = docId,
            streamVersion = streamVersion,
            totalChars = text.length,
            chunks = chunks
        )
        committedSeq = -1
        sentChunkSeqs.clear()
        ackWaiting.clear()
        sendStreamStart()
        ensureBufferAround(0)
        startAckMonitorIfNeeded()
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

            if (!streamingEnabled) {
                if (!sendPositionPackets) {
                    sendHighlightedRangeAsLoadText(range.first, range.second)
                } else {
                    sendFallbackPosition(range.first, range.second)
                }
                lastPositionSentAt = SystemClock.elapsedRealtime()
                return@launch
            }

            handleStreamingRange(range.first, range.second)
            lastPositionSentAt = SystemClock.elapsedRealtime()
        }
    }

    fun close() {
        scope.coroutineContext.cancel()
    }

    private fun handleStreamingRange(start: Int, end: Int) {
        val currentSession = session ?: return
        val safeStart = start.coerceIn(0, currentSession.totalChars)
        val safeEnd = end.coerceIn(safeStart, currentSession.totalChars)
        if (kotlin.math.abs(safeStart - lastSeekOffset) > 420) {
            metrics.seekCount += 1
            metrics.resyncCount += 1
            sendStreamSeek(safeStart, safeEnd)
        }
        lastSeekOffset = safeStart
        ensureBufferAround(safeStart)
        sendStreamCommit()
    }

    private fun sendStreamStart() {
        val s = session ?: return
        enqueuePacket(
            JSONObject()
                .put("type", "stream_start")
                .put("sessionId", s.sessionId)
                .put("docId", s.docId)
                .put("streamVersion", s.streamVersion)
                .put("totalChars", s.totalChars)
                .put("chunkCount", s.chunks.size)
        )
    }

    private fun sendStreamSeek(start: Int, end: Int) {
        val s = session ?: return
        enqueuePacket(
            JSONObject()
                .put("type", "stream_seek")
                .put("sessionId", s.sessionId)
                .put("start", start)
                .put("end", end)
        )
    }

    private fun sendStreamCommit() {
        val s = session ?: return
        enqueuePacket(
            JSONObject()
                .put("type", "stream_commit")
                .put("sessionId", s.sessionId)
                .put("committedSeq", committedSeq)
        )
    }

    private fun sendStreamChunk(chunk: StreamChunk, reason: String) {
        val s = session ?: return
        val packet = JSONObject()
            .put("type", "stream_chunk")
            .put("sessionId", s.sessionId)
            .put("docId", s.docId)
            .put("sequenceId", chunk.sequenceId)
            .put("chunkId", chunk.chunkId)
            .put("start", chunk.startOffset)
            .put("end", chunk.endOffsetExclusive)
            .put("checksum", chunk.checksum)
            .put("text", chunk.text)
        enqueuePacket(packet)

        sentChunkSeqs += chunk.sequenceId
        if (ackModeEnabled) {
            ackWaiting[chunk.sequenceId] = InFlightChunk(chunk, SystemClock.elapsedRealtime(), 0)
        } else {
            committedSeq = max(committedSeq, chunk.sequenceId)
        }
        metrics.chunksSent += 1
        Log.d(TAG, "stream_chunk queued seq=${chunk.sequenceId} reason=$reason")
    }

    private fun sendStreamEnd() {
        val s = session ?: return
        enqueuePacket(
            JSONObject()
                .put("type", "stream_end")
                .put("sessionId", s.sessionId)
        )
    }

    private fun ensureBufferAround(globalOffset: Int) {
        val s = session ?: return
        val centerIndex = findChunkIndexForOffset(s.chunks, globalOffset)
        if (centerIndex < 0) return

        val lookahead = (MIN_LOOKAHEAD_CHUNKS + transport.pendingWriteCount()).coerceIn(
            MIN_LOOKAHEAD_CHUNKS,
            MAX_LOOKAHEAD_CHUNKS
        )
        val startIndex = (centerIndex - 1).coerceAtLeast(0)
        val endIndex = (centerIndex + lookahead).coerceAtMost(s.chunks.lastIndex)

        var bufferedChars = 0
        for (i in startIndex..endIndex) {
            val chunk = s.chunks[i]
            val alreadyQueued = sentChunkSeqs.contains(chunk.sequenceId)
            if (!alreadyQueued) {
                sendStreamChunk(chunk, reason = if (i == centerIndex) "focus" else "window")
            }
            if (chunk.endOffsetExclusive > globalOffset) {
                bufferedChars += chunk.endOffsetExclusive - max(globalOffset, chunk.startOffset)
            }
        }

        if (bufferedChars < MIN_BUFFERED_CHARS && endIndex < s.chunks.lastIndex) {
            val refillEnd = (endIndex + 2).coerceAtMost(s.chunks.lastIndex)
            for (i in (endIndex + 1)..refillEnd) {
                val chunk = s.chunks[i]
                if (!sentChunkSeqs.contains(chunk.sequenceId)) {
                    sendStreamChunk(chunk, reason = "refill")
                }
            }
        }
    }

    private fun findChunkIndexForOffset(chunks: List<StreamChunk>, globalOffset: Int): Int {
        return chunks.indexOfFirst { globalOffset in it.startOffset until it.endOffsetExclusive }
            .takeIf { it >= 0 }
            ?: chunks.lastIndex
    }

    private fun enqueuePacket(packet: JSONObject) {
        outboundQueue.add(packet)
        if (senderJob?.isActive != true) {
            senderJob = scope.launch { drainOutboundQueue() }
        }
    }

    private suspend fun drainOutboundQueue() {
        while (scope.isActive && outboundQueue.isNotEmpty()) {
            applyTokenRefill()
            if (tokenBucket < 1.0) {
                delay(18)
                continue
            }

            val packet = outboundQueue.removeFirstOrNull() ?: continue
            val sent = transport.writeJson(packet)
            tokenBucket -= 1.0
            val depth = transport.pendingWriteCount()
            val latency = transport.meanWriteLatencyMs()
            Log.d(
                TAG,
                "stream_tx sent=$sent depth=$depth latencyMs=$latency payload=$packet metrics=$metrics"
            )
            if (!sent) {
                metrics.droppedChunks += 1
                delay(35)
            } else if (depth > 6 || latency > 65) {
                delay(30)
            } else {
                delay(8)
            }
        }
    }

    private fun applyTokenRefill() {
        val now = SystemClock.elapsedRealtime()
        val elapsedMs = (now - lastRefillMs).coerceAtLeast(0)
        lastRefillMs = now
        val queueDepth = transport.pendingWriteCount()
        val meanLatency = transport.meanWriteLatencyMs()

        val fillRatePerSec = when {
            queueDepth > 6 || meanLatency > 70 -> 14.0
            queueDepth > 3 || meanLatency > 40 -> 22.0
            else -> 30.0
        }
        tokenBucket = (tokenBucket + (elapsedMs / 1000.0) * fillRatePerSec).coerceAtMost(8.0)
    }

    private fun startAckMonitorIfNeeded() {
        if (!ackModeEnabled || ackMonitorJob?.isActive == true) return
        ackMonitorJob = scope.launch {
            while (isActive) {
                delay(200)
                val now = SystemClock.elapsedRealtime()
                ackWaiting.values.forEach { inFlight ->
                    if (now - inFlight.lastSentAtMs >= ACK_TIMEOUT_MS && inFlight.retryCount < MAX_RETRY) {
                        inFlight.retryCount += 1
                        inFlight.lastSentAtMs = now
                        metrics.chunksRetried += 1
                        sendStreamChunk(inFlight.chunk, reason = "retry")
                    }
                }
            }
        }
    }

    private fun sendFallbackPosition(start: Int, end: Int) {
        val chunk = ensureLegacyChunkForOffset(start)
        val localStart = if (chunk != null) {
            (start - chunk.startOffset).coerceIn(0, chunk.text.length)
        } else {
            start
        }
        val localEnd = if (chunk != null) {
            (end - chunk.startOffset).coerceIn(localStart, chunk.text.length)
        } else {
            end.coerceAtLeast(localStart)
        }
        val packet = JSONObject()
            .put("type", "position")
            .put("docId", docId)
            .put("start", localStart)
            .put("end", localEnd)
        enqueuePacket(packet)
    }

    private fun buildLegacyChunks(text: String): List<LegacyChunk> {
        if (text.isBlank()) return emptyList()
        val maxJsonBytes = (transport.maxPayloadBytes() - 8).coerceAtLeast(90)
        val chunks = buildLegacyChunkRanges(text, maxJsonBytes)
        legacyChunks = chunks
        activeLegacyChunkIndex = -1
        ensureLegacyChunkForOffset(0)
        return chunks
    }

    private fun buildLegacyChunkRanges(text: String, maxJsonBytes: Int): List<LegacyChunk> {
        val ranges = mutableListOf<LegacyChunk>()
        var cursor = 0
        while (cursor < text.length) {
            var probeEnd = (cursor + 420).coerceAtMost(text.length)
            var accepted = -1
            while (probeEnd > cursor) {
                val candidate = text.substring(cursor, probeEnd)
                if (fitsLegacyLoadTextPayload(candidate, maxJsonBytes)) {
                    accepted = probeEnd
                    break
                }
                probeEnd--
            }
            if (accepted <= cursor) {
                break
            }
            val boundary = preferredBoundary(text, cursor, accepted)
            val resolvedBoundary = boundary.coerceAtLeast(cursor + 1)
            ranges += LegacyChunk(
                startOffset = cursor,
                endOffsetExclusive = resolvedBoundary,
                text = text.substring(cursor, resolvedBoundary)
            )
            cursor = resolvedBoundary
        }
        return ranges
    }

    private fun ensureLegacyChunkForOffset(globalOffset: Int): LegacyChunk? {
        if (legacyChunks.isEmpty()) return null
        val targetIndex = legacyChunks.indexOfFirst {
            globalOffset in it.startOffset until it.endOffsetExclusive
        }.takeIf { it >= 0 } ?: legacyChunks.lastIndex

        if (targetIndex != activeLegacyChunkIndex) {
            val chunk = legacyChunks[targetIndex]
            enqueuePacket(
                JSONObject()
                    .put("type", "load_text")
                    .put("docId", docId)
                    .put("text", chunk.text)
            )
            activeLegacyChunkIndex = targetIndex
        }
        return legacyChunks.getOrNull(activeLegacyChunkIndex)
    }

    private fun sendHighlightedRangeAsLoadText(start: Int, end: Int) {
        if (sourceDocumentText.isBlank()) return

        val safeStart = start.coerceIn(0, sourceDocumentText.length)
        val safeEnd = end.coerceIn(safeStart, sourceDocumentText.length)
        if (safeEnd <= safeStart) return

        val highlightedText = sourceDocumentText.substring(safeStart, safeEnd)
        val payloadKey = "$safeStart:$safeEnd:$highlightedText"
        if (payloadKey == lastHighlightedPayload) {
            return
        }

        enqueuePacket(
            JSONObject()
                .put("type", "load_text")
                .put("docId", docId)
                .put("text", highlightedText)
        )
        lastHighlightedPayload = payloadKey
    }

    private fun resetStreamingState() {
        sendStreamEnd()
        session = null
        sourceDocumentText = ""
        legacyChunks = emptyList()
        activeLegacyChunkIndex = -1
        outboundQueue.clear()
        ackWaiting.clear()
        sentChunkSeqs.clear()
        committedSeq = -1
        lastSeekOffset = 0
        lastHighlightedPayload = null
    }

    internal fun buildStreamChunks(
        text: String,
        maxJsonBytes: Int,
        softTargetTextBytes: Int
    ): List<StreamChunk> {
        if (text.isBlank()) return emptyList()
        val paragraphRanges = splitParagraphRanges(text)
        val sentenceRanges = paragraphRanges.flatMap { paragraph ->
            splitSentenceRanges(text, paragraph.first, paragraph.second)
        }

        val chunks = mutableListOf<StreamChunk>()
        var sequence = 0
        var chunkId = 0
        var currentStart = -1
        var currentEnd = -1

        fun flush(sentenceTail: Boolean) {
            if (currentStart < 0 || currentEnd <= currentStart) return
            val payloadText = text.substring(currentStart, currentEnd)
            chunks += StreamChunk(
                chunkId = chunkId++,
                sequenceId = sequence++,
                startOffset = currentStart,
                endOffsetExclusive = currentEnd,
                text = payloadText,
                checksum = payloadText.hashCode(),
                sentenceTail = sentenceTail
            )
            currentStart = -1
            currentEnd = -1
        }

        sentenceRanges.forEach { sentence ->
            if (currentStart < 0) {
                currentStart = sentence.first
                currentEnd = sentence.second
                return@forEach
            }

            val candidateEnd = sentence.second
            val candidateText = text.substring(currentStart, candidateEnd)
            val overSoftTarget = candidateText.toByteArray(Charsets.UTF_8).size > softTargetTextBytes
            val overHardTarget = !fitsStreamChunkPayload(text.substring(currentStart, candidateEnd), maxJsonBytes)

            if (overSoftTarget || overHardTarget) {
                flush(sentenceTail = true)
                currentStart = sentence.first
                currentEnd = sentence.second
            } else {
                currentEnd = sentence.second
            }
        }
        flush(sentenceTail = true)

        return chunks.flatMap { splitChunkIfNeeded(it, maxJsonBytes) }
    }

    private fun splitChunkIfNeeded(chunk: StreamChunk, maxJsonBytes: Int): List<StreamChunk> {
        if (fitsStreamChunkPayload(chunk.text, maxJsonBytes)) return listOf(chunk)

        val split = mutableListOf<StreamChunk>()
        var cursor = 0
        var seq = chunk.sequenceId
        var cid = chunk.chunkId * 100

        while (cursor < chunk.text.length) {
            var end = (cursor + 140).coerceAtMost(chunk.text.length)
            var accepted = -1
            while (end > cursor) {
                val candidate = chunk.text.substring(cursor, end)
                if (fitsStreamChunkPayload(candidate, maxJsonBytes)) {
                    accepted = end
                    break
                }
                end--
            }
            if (accepted <= cursor) break
            val boundary = preferredBoundary(chunk.text, cursor, accepted)
            val textPart = chunk.text.substring(cursor, boundary)
            split += StreamChunk(
                chunkId = cid++,
                sequenceId = seq++,
                startOffset = chunk.startOffset + cursor,
                endOffsetExclusive = chunk.startOffset + boundary,
                text = textPart,
                checksum = textPart.hashCode(),
                sentenceTail = textPart.lastOrNull() in listOf('.', '!', '?', '\n')
            )
            cursor = boundary
        }

        return if (split.isEmpty()) listOf(chunk) else split
    }

    private fun preferredBoundary(text: String, start: Int, maxEnd: Int): Int {
        val sentenceBoundary = text.substring(start, maxEnd).indexOfLast { it == '.' || it == '!' || it == '?' }
        if (sentenceBoundary > 0) return start + sentenceBoundary + 1

        val wordBoundary = text.substring(start, maxEnd).indexOfLast { it.isWhitespace() }
        if (wordBoundary > 16) return start + wordBoundary + 1

        return maxEnd
    }

    private fun fitsStreamChunkPayload(chunkText: String, maxJsonBytes: Int): Boolean {
        val probe = JSONObject()
            .put("type", "stream_chunk")
            .put("sessionId", "s")
            .put("docId", "d")
            .put("sequenceId", 1)
            .put("chunkId", 1)
            .put("start", 0)
            .put("end", chunkText.length)
            .put("checksum", chunkText.hashCode())
            .put("text", chunkText)
        return probe.toString().toByteArray(Charsets.UTF_8).size <= maxJsonBytes
    }

    private fun fitsLegacyLoadTextPayload(chunkText: String, maxJsonBytes: Int): Boolean {
        val probe = JSONObject()
            .put("type", "load_text")
            .put("docId", "d")
            .put("text", chunkText)
        return probe.toString().toByteArray(Charsets.UTF_8).size <= maxJsonBytes
    }

    private fun splitParagraphRanges(text: String): List<Pair<Int, Int>> {
        val ranges = mutableListOf<Pair<Int, Int>>()
        var start = 0
        while (start < text.length) {
            val paragraphBreak = text.indexOf("\n\n", start)
            val end = if (paragraphBreak >= 0) paragraphBreak + 2 else text.length
            if (end > start) ranges += start to end
            start = end
        }
        return ranges
    }

    private fun splitSentenceRanges(text: String, start: Int, end: Int): List<Pair<Int, Int>> {
        val ranges = mutableListOf<Pair<Int, Int>>()
        var cursor = start
        while (cursor < end) {
            val sentenceEnd = findSentenceEnd(text, cursor, end)
            if (sentenceEnd <= cursor) break
            ranges += cursor to sentenceEnd
            cursor = sentenceEnd
        }
        return if (ranges.isEmpty()) listOf(start to end) else ranges
    }

    private fun findSentenceEnd(text: String, start: Int, maxEnd: Int): Int {
        var i = start
        while (i < maxEnd) {
            val c = text[i]
            if (c == '.' || c == '!' || c == '?') {
                var j = i + 1
                while (j < maxEnd && text[j].isWhitespace()) {
                    j++
                }
                return j
            }
            i++
        }
        return maxEnd
    }
}
