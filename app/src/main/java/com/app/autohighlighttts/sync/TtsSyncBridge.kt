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
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

class TtsSyncBridge(
    private val transport: CommandTransport,
    private var docId: String = "demo-001",
    private val debounceMs: Long = 40L,
    private val sendPositionPackets: Boolean = true,
    private val streamingEnabled: Boolean = true,
    private val streamVersion: Int = 2,
    private val compatibilityProfile: CompatibilityProfile = CompatibilityProfile(),
    private val ackFallbackTimeoutMs: Long = 1_200L,
    private val ackRetryBaseMs: Long = 900L,
    private val maxChunkRetries: Int = 3,
    private val retryQueueDepthCircuitBreaker: Int = 14
) {
    constructor(
        bleManager: BleManager,
        docId: String = "demo-001",
        debounceMs: Long = 40L,
        sendPositionPackets: Boolean = true,
        streamingEnabled: Boolean = true,
        streamVersion: Int = 2,
        compatibilityProfile: CompatibilityProfile = CompatibilityProfile()
    ) : this(
        transport = BleCommandTransport(bleManager),
        docId = docId,
        debounceMs = debounceMs,
        sendPositionPackets = sendPositionPackets,
        streamingEnabled = streamingEnabled,
        streamVersion = streamVersion,
        compatibilityProfile = compatibilityProfile
    )

    interface CommandTransport {
        fun writeJson(packet: JSONObject): Boolean
        fun maxPayloadBytes(): Int
        fun pendingWriteCount(): Int
        fun meanWriteLatencyMs(): Int
        fun isReady(): Boolean
        fun reconnectLightweight(reason: String): Boolean
    }

    data class CompatibilityProfile(
        val useCompatibilityPayloads: Boolean = true,
        val includeAliasFields: Boolean = true,
        val requireAckForCommit: Boolean = false,
        val explicitStartSeq: Boolean = true
    )

    internal class PacketSender(private val compatibilityProfile: CompatibilityProfile) {
        fun streamStart(
            sessionId: String,
            docId: String,
            streamVersion: Int,
            totalChars: Int,
            chunkCount: Int,
            startSeq: Int,
            startOffset: Int = 0
        ): JSONObject {
            val packet = JSONObject()
                .put("type", "stream_start")
                .put("sessionId", sessionId)
                .put("docId", docId)
                .put("streamVersion", streamVersion)
                .put("totalChars", totalChars)
                .put("chunkCount", chunkCount)
                .put("startOffset", startOffset)
            if (compatibilityProfile.explicitStartSeq) {
                packet.put("startSeq", startSeq)
            }
            return packet
        }

        fun streamChunk(sessionId: String, docId: String, chunk: StreamChunk): JSONObject {
            val packet = JSONObject()
                .put("type", "stream_chunk")
                .put("sessionId", sessionId)
                .put("docId", docId)
                .put("seq", chunk.sequenceId)
                .put("offset", chunk.startOffset)
                .put("text", chunk.text)
                .put("chunkId", chunk.chunkId)
                .put("checksum", chunk.checksum)
            if (compatibilityProfile.useCompatibilityPayloads && compatibilityProfile.includeAliasFields) {
                packet.put("sequenceId", chunk.sequenceId)
                packet.put("start", chunk.startOffset)
                packet.put("end", chunk.endOffsetExclusive)
            }
            return packet
        }

        fun streamCommit(sessionId: String, committedSeq: Int): JSONObject {
            val packet = JSONObject()
                .put("type", "stream_commit")
                .put("sessionId", sessionId)
                .put("uptoSeq", committedSeq)
            if (compatibilityProfile.useCompatibilityPayloads && compatibilityProfile.includeAliasFields) {
                packet.put("committedSeq", committedSeq)
            }
            return packet
        }

        fun streamSeek(sessionId: String, startOffset: Int, endOffset: Int): JSONObject {
            val packet = JSONObject()
                .put("type", "stream_seek")
                .put("sessionId", sessionId)
                .put("offset", startOffset)
            if (compatibilityProfile.useCompatibilityPayloads && compatibilityProfile.includeAliasFields) {
                packet.put("start", startOffset)
                packet.put("end", endOffset)
            }
            return packet
        }

        fun position(docId: String?, start: Int, end: Int): JSONObject {
            val packet = JSONObject()
                .put("type", "position")
                .put("start", start)
                .put("end", end)
            if (!docId.isNullOrBlank()) {
                packet.put("docId", docId)
            }
            return packet
        }
    }

    data class DebugState(
        val ackModeEnabled: Boolean,
        val lastAckSeq: Int,
        val committedSeq: Int,
        val pendingQueue: Int,
        val inFlightCount: Int,
        val retriesCount: Int,
        val legacySummary: String = ""
    )

    data class LegacyTransportProfile(
        val maxPayloadBytes: Int,
        val writeIntervalMs: Long,
        val positionDebounceMs: Long,
        val maxInFlightWrites: Int,
        val reconnectBackoffMs: List<Long>,
        val queueCapacity: Int = 96,
        val meaningfulRangeDeltaChars: Int = 2,
        val loadQuietPeriodMs: Long = 220L,
        val writeRetryLimit: Int = 3,
        val writeTimeoutMs: Long = 300L
    )

    private class BleCommandTransport(private val bleManager: BleManager) : CommandTransport {
        override fun writeJson(packet: JSONObject): Boolean = bleManager.writeJson(packet)
        override fun maxPayloadBytes(): Int = bleManager.maxPayloadBytes()
        override fun pendingWriteCount(): Int = bleManager.pendingWriteCount()
        override fun meanWriteLatencyMs(): Int = bleManager.meanWriteLatencyMs()
        override fun isReady(): Boolean = bleManager.isReady()
        override fun reconnectLightweight(reason: String): Boolean = bleManager.requestLightweightReconnect(reason)
    }

    companion object {
        private const val TAG = "TtsSyncBridge"
        // Some receiver builds treat seq=0 as "no chunks committed yet".
        // Starting at 1 keeps the first chunk commit-eligible across those implementations.
        private const val STREAM_SEQUENCE_BASE = 1
        private const val MIN_LOOKAHEAD_CHUNKS = 2
        private const val MAX_LOOKAHEAD_CHUNKS = 5
        private const val MIN_BUFFERED_CHARS = 360
        private const val TELEMETRY_INTERVAL_MS = 500L
        private const val POSITION_MAX_HZ_INTERVAL_MS = 40L
    }

    private fun activeLegacyProfile(): LegacyTransportProfile {
        val maxPayload = (transport.maxPayloadBytes() - 8).coerceAtLeast(90)
        val writeInterval = when {
            transport.meanWriteLatencyMs() > 70 -> 34L
            transport.meanWriteLatencyMs() > 35 -> 22L
            else -> 12L
        }
        return LegacyTransportProfile(
            maxPayloadBytes = maxPayload,
            writeIntervalMs = writeInterval,
            positionDebounceMs = debounceMs.coerceAtLeast(120L),
            maxInFlightWrites = 1,
            reconnectBackoffMs = listOf(250L, 600L, 1_200L, 2_000L)
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var pendingPosition: Pair<Int, Int>? = null
    private var positionJob: Job? = null
    private var senderJob: Job? = null
    private var ackMonitorJob: Job? = null
    private var telemetryJob: Job? = null
    private var lastPositionSentAt = 0L
    private var sourceDocumentText: String = ""
    private var lastHighlightedPayload: String? = null
    private var legacyChunks: List<LegacyChunk> = emptyList()
    private var activeLegacyChunkIndex: Int = -1
    private var lastSentPosition: Pair<Int, Int>? = null
    private var deferredLegacySeekDuringLoad: Pair<Int, Int>? = null
    private var legacyLoadInProgress: Boolean = false
    private var legacyLoadJob: Job? = null
    private var legacyReconnectCount = 0

    private var session: StreamSession? = null
    private var ackModeEnabled: Boolean = false
    private var feedbackChannelReady: Boolean = false
    private var ackObservedThisSession: Boolean = false
    private var streamStartAtMs: Long = 0L
    private var lastAckSeq: Int = -1
    private var lastSeekOffset: Int = 0

    private enum class PacketKind { LOAD_TEXT, POSITION, OTHER }

    private data class OutboundPacket(
        val packet: JSONObject,
        val kind: PacketKind,
        var retries: Int = 0
    )

    private val outboundQueue = ArrayDeque<OutboundPacket>()
    private val ackWaiting = ConcurrentHashMap<Int, InFlightChunk>()
    private val sentChunkSeqs = mutableSetOf<Int>()
    private val ackedChunkSeqs = mutableSetOf<Int>()
    private val invalidatedChunkSeqs = mutableSetOf<Int>()
    private var committedSeq = -1
    private var highestSentSeq = -1
    private var highestContiguousSentSeq = -1
    private var highestContiguousAckedSeq = -1

    private var tokenBucket = 4.0
    private var lastRefillMs = SystemClock.elapsedRealtime()
    private val packetSender = PacketSender(compatibilityProfile)

    internal enum class SessionState {
        DISCONNECTED, CONNECTED_PAIRED, STREAM_START_SENT, STREAMING, STREAM_ENDED
    }
    private var sessionState: SessionState = SessionState.DISCONNECTED

    private data class StreamSession(
        val sessionId: String,
        val docId: String,
        val streamVersion: Int,
        val totalChars: Int,
        val chunks: List<StreamChunk>,
        val startSeq: Int
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
        var retryCount: Int,
        var nextRetryAtMs: Long
    )

    private data class LegacyChunk(
        val startOffset: Int,
        val endOffsetExclusive: Int,
        val text: String
    )

    internal data class StreamMetrics(
        var chunksSent: Int = 0,
        var retries: Int = 0,
        var dropped: Int = 0,
        var queueDepth: Int = 0,
        var meanWriteLatency: Int = 0,
        var ackGap: Int = 0,
        var seekCount: Int = 0
    )

    internal data class LegacyMetrics(
        var writesAttempted: Int = 0,
        var writesSucceeded: Int = 0,
        var writesFailed: Int = 0,
        var retries: Int = 0,
        var reconnects: Int = 0,
        var loadTextChunksSent: Int = 0,
        var positionSent: Int = 0,
        var positionDropped: Int = 0,
        var positionCoalesced: Int = 0,
        var avgWriteRttMs: Int = 0
    )

    private val metrics = StreamMetrics()
    private val legacyMetrics = LegacyMetrics()
    private var onDebugStateChanged: ((DebugState) -> Unit)? = null

    fun setDebugStateListener(listener: ((DebugState) -> Unit)?) {
        onDebugStateChanged = listener
        publishDebugState()
    }

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

    fun setFeedbackChannelReady(ready: Boolean) {
        val wasReady = feedbackChannelReady
        feedbackChannelReady = ready
        if (!ready) {
            ackModeEnabled = false
            ackObservedThisSession = false
            sessionState = SessionState.DISCONNECTED
        } else {
            sessionState = SessionState.CONNECTED_PAIRED
            if (!wasReady && streamingEnabled && sourceDocumentText.isNotBlank()) {
                Log.i(TAG, "feedback channel restored; starting fresh streaming session")
                loadDocumentTextOnce(sourceDocumentText)
            }
        }
        publishDebugState()
    }

    fun onAckReceived(sequenceId: Int) {
        if (sequenceId < 0) return
        lastAckSeq = max(lastAckSeq, sequenceId)
        ackObservedThisSession = true
        if (feedbackChannelReady) {
            ackModeEnabled = true
        }
        ackWaiting.remove(sequenceId)
        ackedChunkSeqs += sequenceId
        recalculateContiguousState()
        sendStreamCommit()
        publishDebugState()
    }

    fun onAckPacketReceived(packet: JSONObject) {
        if (packet.optString("type") != "ack") return
        val highestSeq = when {
            packet.has("highestContiguousSeq") -> packet.optInt("highestContiguousSeq", -1)
            packet.has("sequenceId") -> packet.optInt("sequenceId", -1)
            else -> -1
        }
        val missing = when (val raw = packet.opt("missing")) {
            is JSONArray -> (0 until raw.length()).mapNotNull { idx ->
                raw.optInt(idx, -1).takeIf { it >= 0 }
            }
            else -> emptyList()
        }
        val bufferFillPct = packet.optInt("bufferFillPct", -1)
        Log.d(TAG, "ack payload highest=$highestSeq missing=$missing bufferFillPct=$bufferFillPct")
        if (highestSeq >= 0) onAckReceived(highestSeq)
        if (missing.isNotEmpty()) {
            retryMissingChunks(missing)
        }
    }

    private fun retryMissingChunks(missingSeqs: List<Int>) {
        val activeSession = session ?: return
        val chunksBySeq = activeSession.chunks.associateBy { it.sequenceId }
        missingSeqs.distinct().sorted().forEach { seq ->
            val chunk = chunksBySeq[seq] ?: return@forEach
            Log.w(TAG, "ack missing seq=$seq retrying")
            val now = SystemClock.elapsedRealtime()
            ackWaiting[seq] = InFlightChunk(
                chunk = chunk,
                lastSentAtMs = now,
                retryCount = (ackWaiting[seq]?.retryCount ?: 0) + 1,
                nextRetryAtMs = now + ackRetryBaseMs
            )
            sentChunkSeqs.remove(seq)
            sendStreamChunk(chunk, reason = "ack_missing_retry")
        }
    }

    fun loadDocumentTextOnce(text: String): Boolean {
        if (text.isBlank()) {
            Log.w(TAG, "loadDocumentTextOnce ignored (blank text)")
            return false
        }
        sourceDocumentText = text
        lastHighlightedPayload = null
        lastSentPosition = null

        if (!streamingEnabled) {
            val chunks = buildLegacyChunks(text)
            if (chunks.isEmpty()) return false
            val profile = activeLegacyProfile()
            Log.i(
                TAG,
                "legacy_profile active {maxPayloadBytes=${profile.maxPayloadBytes},writeIntervalMs=${profile.writeIntervalMs},positionDebounceMs=${profile.positionDebounceMs},maxInFlightWrites=${profile.maxInFlightWrites},reconnectBackoffMs=${profile.reconnectBackoffMs}}"
            )
            startLegacyDocumentLoad(chunks, profile)
            return true
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

        val firstSeq = chunks.first().sequenceId
        session = StreamSession(
            sessionId = UUID.randomUUID().toString(),
            docId = docId,
            streamVersion = streamVersion,
            totalChars = text.length,
            chunks = chunks,
            startSeq = if (compatibilityProfile.explicitStartSeq) firstSeq else 0
        )
        streamStartAtMs = SystemClock.elapsedRealtime()
        committedSeq = -1
        highestSentSeq = -1
        highestContiguousSentSeq = -1
        highestContiguousAckedSeq = -1
        lastAckSeq = -1
        ackObservedThisSession = false
        ackModeEnabled = false
        sentChunkSeqs.clear()
        ackedChunkSeqs.clear()
        invalidatedChunkSeqs.clear()
        ackWaiting.clear()
        sessionState = if (feedbackChannelReady) SessionState.CONNECTED_PAIRED else SessionState.DISCONNECTED
        sendStreamStart()
        ensureBufferAround(0)
        startAckMonitorIfNeeded()
        startTelemetryIfNeeded()
        return true
    }

    fun onSpokenRangeChanged(start: Int, end: Int) {
        pendingPosition = normalizeHighlightBounds(start, end, sourceDocumentText.length)
        if (positionJob?.isActive == true) return

        positionJob = scope.launch {
            while (isActive && pendingPosition != null) {
                val now = SystemClock.elapsedRealtime()
                val elapsed = now - lastPositionSentAt
                if (elapsed < POSITION_MAX_HZ_INTERVAL_MS) {
                    delay(POSITION_MAX_HZ_INTERVAL_MS - elapsed)
                }
                val range = pendingPosition ?: break
                pendingPosition = null
                if (!streamingEnabled) {
                    if (!sendPositionPackets) {
                        sendHighlightedRangeAsLoadText(range.first, range.second)
                    } else if (legacyLoadInProgress) {
                        deferredLegacySeekDuringLoad = range
                        legacyMetrics.positionCoalesced += 1
                    } else {
                        sendFallbackPosition(range.first, range.second)
                    }
                } else {
                    handleStreamingRange(range.first, range.second)
                }
                lastPositionSentAt = SystemClock.elapsedRealtime()
            }
        }
    }

    fun close() {
        scope.coroutineContext.cancel()
    }

    private fun handleStreamingRange(start: Int, end: Int) {
        val currentSession = session ?: return
        val (safeStart, safeEnd) = normalizeHighlightBounds(start, end, currentSession.totalChars)
        if (kotlin.math.abs(safeStart - lastSeekOffset) > 420) {
            metrics.seekCount += 1
            sendStreamSeek(safeStart, safeEnd)
            invalidateChunkWindowAround(safeStart)
        }
        lastSeekOffset = safeStart
        ensureBufferAround(safeStart)
        sendStreamCommit()
    }

    private fun sendStreamStart() {
        val s = session ?: return
        val packet = packetSender.streamStart(
            sessionId = s.sessionId,
            docId = s.docId,
            streamVersion = s.streamVersion,
            totalChars = s.totalChars,
            chunkCount = s.chunks.size,
            startSeq = s.startSeq
        )
        sessionState = SessionState.STREAM_START_SENT
        enqueuePacket(packet)
    }

    private fun sendStreamSeek(start: Int, end: Int) {
        val s = session ?: return
        val packet = packetSender.streamSeek(s.sessionId, start, end)
        enqueuePacket(packet)
    }

    private fun sendStreamCommit() {
        val s = session ?: return
        val effectiveCommitted = if (shouldUseAckForCommit()) highestContiguousAckedSeq else highestContiguousSentSeq
        committedSeq = max(committedSeq, effectiveCommitted)
        if (committedSeq < 0) {
            Log.d(
                TAG,
                "stream_commit skip mode=${if (shouldUseAckForCommit()) "ack" else "non-ack"} committedSeq=$committedSeq highestSentSeq=$highestSentSeq ackWaiting=${ackWaiting.size}"
            )
            return
        }

        val packet = packetSender.streamCommit(s.sessionId, committedSeq)
        enqueuePacket(packet)
        Log.d(
            TAG,
            "stream_commit mode=${if (shouldUseAckForCommit()) "ack" else "non-ack"} committedSeq=$committedSeq highestSentSeq=$highestSentSeq ackWaiting=${ackWaiting.size}"
        )
        publishDebugState()
    }

    private fun shouldUseAckForCommit(): Boolean {
        return ackModeEnabled && (feedbackChannelReady || compatibilityProfile.requireAckForCommit)
    }

    private fun sendStreamChunk(chunk: StreamChunk, reason: String) {
        val s = session ?: return
        val packet = packetSender.streamChunk(s.sessionId, s.docId, chunk)
        enqueuePacket(packet)
        sessionState = SessionState.STREAMING

        sentChunkSeqs += chunk.sequenceId
        invalidatedChunkSeqs.remove(chunk.sequenceId)
        highestSentSeq = max(highestSentSeq, chunk.sequenceId)

        if (shouldUseAckForCommit()) {
            val now = SystemClock.elapsedRealtime()
            ackWaiting[chunk.sequenceId] = InFlightChunk(
                chunk = chunk,
                lastSentAtMs = now,
                retryCount = 0,
                nextRetryAtMs = now + ackRetryBaseMs
            )
        }
        recalculateContiguousState()
        if (!shouldUseAckForCommit()) {
            sendStreamCommit()
        }
        metrics.chunksSent += 1
        publishDebugState()
        Log.v(TAG, "stream_chunk queued seq=${chunk.sequenceId} reason=$reason")
    }

    private fun sendStreamEnd() {
        val s = session ?: return
        sessionState = SessionState.STREAM_ENDED
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
            val isInflight = ackWaiting.containsKey(chunk.sequenceId)
            val alreadySent = sentChunkSeqs.contains(chunk.sequenceId)
            val isAcked = ackedChunkSeqs.contains(chunk.sequenceId)
            val forceResend = invalidatedChunkSeqs.contains(chunk.sequenceId) && !isInflight && !isAcked
            if ((!alreadySent && !isInflight) || forceResend) {
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
                if (!sentChunkSeqs.contains(chunk.sequenceId) && !ackWaiting.containsKey(chunk.sequenceId)) {
                    sendStreamChunk(chunk, reason = "refill")
                }
            }
        }
    }

    private fun invalidateChunkWindowAround(globalOffset: Int) {
        val s = session ?: return
        val centerIndex = findChunkIndexForOffset(s.chunks, globalOffset)
        val start = (centerIndex - 1).coerceAtLeast(0)
        val end = (centerIndex + 2).coerceAtMost(s.chunks.lastIndex)
        for (idx in start..end) {
            val seq = s.chunks[idx].sequenceId
            if (!ackedChunkSeqs.contains(seq) && !ackWaiting.containsKey(seq)) {
                invalidatedChunkSeqs += seq
                sentChunkSeqs.remove(seq)
            }
        }
    }

    private fun recalculateContiguousState() {
        val baselineSeq = session?.startSeq ?: STREAM_SEQUENCE_BASE
        var seq = baselineSeq
        while (sentChunkSeqs.contains(seq)) {
            seq++
        }
        highestContiguousSentSeq = seq - 1

        seq = baselineSeq
        while (ackedChunkSeqs.contains(seq)) {
            seq++
        }
        highestContiguousAckedSeq = seq - 1
        metrics.ackGap = (highestContiguousSentSeq - highestContiguousAckedSeq).coerceAtLeast(0)
    }

    private fun findChunkIndexForOffset(chunks: List<StreamChunk>, globalOffset: Int): Int {
        return chunks.indexOfFirst { globalOffset in it.startOffset until it.endOffsetExclusive }
            .takeIf { it >= 0 }
            ?: chunks.lastIndex
    }

    private fun enqueuePacket(packet: JSONObject, kind: PacketKind = PacketKind.OTHER) {
        val profile = activeLegacyProfile()
        if (!streamingEnabled && kind == PacketKind.POSITION) {
            val iterator = outboundQueue.iterator()
            var removed = false
            while (iterator.hasNext()) {
                if (iterator.next().kind == PacketKind.POSITION) {
                    iterator.remove()
                    removed = true
                }
            }
            if (removed) legacyMetrics.positionCoalesced += 1
        }
        if (!streamingEnabled && outboundQueue.size >= profile.queueCapacity) {
            val iterator = outboundQueue.iterator()
            var dropped = false
            while (iterator.hasNext()) {
                if (iterator.next().kind == PacketKind.POSITION) {
                    iterator.remove()
                    dropped = true
                    legacyMetrics.positionDropped += 1
                    break
                }
            }
            if (!dropped) {
                outboundQueue.removeFirstOrNull()
            }
        }
        outboundQueue.add(OutboundPacket(packet = packet, kind = kind))
        Log.d(TAG, "enqueue_json packet=$packet")
        publishDebugState()
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

            val queued = outboundQueue.removeFirstOrNull() ?: continue
            val packet = queued.packet
            legacyMetrics.writesAttempted += 1
            val startedAt = SystemClock.elapsedRealtime()
            val sent = transport.writeJson(packet)
            Log.d(TAG, "write_json sent=$sent packet=$packet")
            val elapsed = (SystemClock.elapsedRealtime() - startedAt).toInt()
            if (elapsed > 0) {
                legacyMetrics.avgWriteRttMs =
                    if (legacyMetrics.avgWriteRttMs == 0) elapsed else ((legacyMetrics.avgWriteRttMs * 4) + elapsed) / 5
            }
            tokenBucket -= 1.0
            metrics.queueDepth = transport.pendingWriteCount()
            metrics.meanWriteLatency = transport.meanWriteLatencyMs()
            if (!sent) {
                legacyMetrics.writesFailed += 1
                metrics.dropped += 1
                val profile = activeLegacyProfile()
                if (queued.retries < profile.writeRetryLimit) {
                    queued.retries += 1
                    legacyMetrics.retries += 1
                    val jitter = Random.nextLong(8, 26)
                    delay(profile.writeTimeoutMs + jitter)
                    outboundQueue.addFirst(queued)
                } else if (!streamingEnabled) {
                    attemptLegacyReconnectAndResume("bounded_write_retry_exhausted")
                }
                delay(35)
            } else {
                legacyMetrics.writesSucceeded += 1
                if (queued.kind == PacketKind.LOAD_TEXT) legacyMetrics.loadTextChunksSent += 1
                if (queued.kind == PacketKind.POSITION) legacyMetrics.positionSent += 1
            }
            if (metrics.queueDepth > 6 || metrics.meanWriteLatency > 65) delay(30) else delay(8)
            publishDebugState()
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

    private fun attemptLegacyReconnectAndResume(reason: String) {
        val profile = activeLegacyProfile()
        val backoff = profile.reconnectBackoffMs.getOrElse(legacyReconnectCount) { profile.reconnectBackoffMs.last() }
        legacyReconnectCount += 1
        legacyMetrics.reconnects += 1
        scope.launch {
            Log.w(TAG, "legacy_reconnect start reason=$reason backoffMs=$backoff")
            transport.reconnectLightweight(reason)
            delay(backoff)
            val latest = deferredLegacySeekDuringLoad ?: lastSentPosition
            if (latest != null) {
                ensureLegacyChunkForOffset(latest.first)
                sendFallbackPosition(latest.first, latest.second)
            }
        }
    }

    private fun startAckMonitorIfNeeded() {
        if (ackMonitorJob?.isActive == true) return
        ackMonitorJob = scope.launch {
            while (isActive) {
                delay(80)
                if (session == null) continue

                val now = SystemClock.elapsedRealtime()
                if (!ackObservedThisSession && now - streamStartAtMs >= ackFallbackTimeoutMs) {
                    ackModeEnabled = false
                }

                val retryBlocked = transport.pendingWriteCount() >= retryQueueDepthCircuitBreaker
                ackWaiting.entries.forEach { entry ->
                    val inFlight = entry.value
                    if (retryBlocked || now < inFlight.nextRetryAtMs) return@forEach

                    if (inFlight.retryCount >= maxChunkRetries) {
                        ackWaiting.remove(entry.key)
                        metrics.dropped += 1
                        return@forEach
                    }

                    inFlight.retryCount += 1
                    inFlight.lastSentAtMs = now
                    val multiplier = 1L shl (inFlight.retryCount - 1)
                    inFlight.nextRetryAtMs = now + ackRetryBaseMs * multiplier
                    metrics.retries += 1
                    sendStreamChunk(inFlight.chunk, reason = "retry")
                }
                recalculateContiguousState()
                if (!shouldUseAckForCommit()) {
                    sendStreamCommit()
                }
                publishDebugState()
            }
        }
    }

    private fun startTelemetryIfNeeded() {
        if (telemetryJob?.isActive == true) return
        telemetryJob = scope.launch {
            while (isActive) {
                delay(TELEMETRY_INTERVAL_MS)
                if (session == null) continue
                Log.d(
                    TAG,
                    "stream_telemetry {ackModeEnabled=$ackModeEnabled,lastAckSeq=$lastAckSeq,committedSeq=$committedSeq,chunksSent=${metrics.chunksSent},retries=${metrics.retries},dropped=${metrics.dropped},queueDepth=${metrics.queueDepth},meanWriteLatency=${metrics.meanWriteLatency},ackGap=${metrics.ackGap},pending=${outboundQueue.size},inFlight=${ackWaiting.size}}"
                )
            }
        }
    }

    private fun publishDebugState() {
        val legacySummary =
            "writes=${legacyMetrics.writesSucceeded}/${legacyMetrics.writesAttempted} " +
                "failed=${legacyMetrics.writesFailed} retry=${legacyMetrics.retries} " +
                "reconnect=${legacyMetrics.reconnects} load=${legacyMetrics.loadTextChunksSent} " +
                "pos=${legacyMetrics.positionSent} drop=${legacyMetrics.positionDropped} " +
                "coal=${legacyMetrics.positionCoalesced} rtt=${legacyMetrics.avgWriteRttMs}ms"
        onDebugStateChanged?.invoke(
            DebugState(
                ackModeEnabled = ackModeEnabled,
                lastAckSeq = lastAckSeq,
                committedSeq = committedSeq,
                pendingQueue = outboundQueue.size,
                inFlightCount = ackWaiting.size,
                retriesCount = metrics.retries,
                legacySummary = legacySummary
            )
        )
    }

    private fun sendFallbackPosition(start: Int, end: Int) {
        val docLength = sourceDocumentText.length.coerceAtLeast(0)
        val (clampedStart, clampedEnd) = normalizeHighlightBounds(start, end, docLength)
        val previous = lastSentPosition
        if (previous != null &&
            abs(previous.first - clampedStart) < activeLegacyProfile().meaningfulRangeDeltaChars &&
            abs(previous.second - clampedEnd) < activeLegacyProfile().meaningfulRangeDeltaChars
        ) {
            legacyMetrics.positionDropped += 1
            return
        }

        val chunk = ensureLegacyChunkForOffset(start)
        val localStart = if (chunk != null) {
            (clampedStart - chunk.startOffset).coerceIn(0, chunk.text.length)
        } else {
            clampedStart
        }
        val localEnd = if (chunk != null) {
            (clampedEnd - chunk.startOffset).coerceIn(localStart, chunk.text.length)
        } else {
            clampedEnd.coerceAtLeast(localStart)
        }
        if (lastSentPosition?.first == clampedStart && lastSentPosition?.second == clampedEnd) {
            legacyMetrics.positionDropped += 1
            return
        }
        val packet = packetSender.position(
            docId = docId.ifBlank { null },
            start = localStart,
            end = localEnd.coerceAtLeast(localStart)
        )
        enqueuePacket(packet, kind = PacketKind.POSITION)
        lastSentPosition = clampedStart to clampedEnd
    }

    internal fun normalizeHighlightBounds(start: Int, end: Int, docLength: Int): Pair<Int, Int> {
        val safeLength = docLength.coerceAtLeast(0)
        if (safeLength == 0) return 0 to 0
        val clampedStart = start.coerceIn(0, safeLength - 1)
        val clampedEndRaw = end.coerceIn(0, safeLength)
        val orderedStart = minOf(clampedStart, clampedEndRaw)
        val orderedEnd = max(orderedStart, clampedEndRaw)
        val normalizedEnd = if (orderedEnd == orderedStart) {
            (orderedStart + 1).coerceAtMost(safeLength)
        } else {
            orderedEnd.coerceAtMost(safeLength)
        }
        return orderedStart to normalizedEnd
    }

    private fun buildLegacyChunks(text: String): List<LegacyChunk> {
        if (text.isBlank()) return emptyList()
        val maxJsonBytes = activeLegacyProfile().maxPayloadBytes
        val chunks = buildLegacyChunkRanges(text, maxJsonBytes)
        legacyChunks = chunks
        activeLegacyChunkIndex = -1
        return chunks
    }

    private fun startLegacyDocumentLoad(chunks: List<LegacyChunk>, profile: LegacyTransportProfile) {
        legacyLoadJob?.cancel()
        legacyLoadInProgress = true
        deferredLegacySeekDuringLoad = null
        activeLegacyChunkIndex = -1
        legacyLoadJob = scope.launch {
            chunks.forEach { chunk ->
                enqueuePacket(
                    JSONObject()
                        .put("type", "load_text")
                        .put("docId", docId)
                        .put("text", chunk.text),
                    kind = PacketKind.LOAD_TEXT
                )
                delay(profile.writeIntervalMs)
            }
            delay(profile.loadQuietPeriodMs)
            legacyLoadInProgress = false
            val deferred = deferredLegacySeekDuringLoad
            deferredLegacySeekDuringLoad = null
            if (deferred != null) {
                sendFallbackPosition(deferred.first, deferred.second)
            } else {
                sendFallbackPosition(0, 0)
            }
        }
    }

    private fun buildLegacyChunkRanges(text: String, maxJsonBytes: Int): List<LegacyChunk> {
        val ranges = mutableListOf<LegacyChunk>()
        val paragraphs = splitParagraphRanges(text)
        paragraphs.forEach { paragraph ->
            var cursor = paragraph.first
            while (cursor < paragraph.second) {
                var probeEnd = (cursor + 420).coerceAtMost(paragraph.second)
                var accepted = -1
                while (probeEnd > cursor) {
                    val candidate = text.substring(cursor, probeEnd)
                    if (fitsLegacyLoadTextPayload(candidate, maxJsonBytes)) {
                        accepted = probeEnd
                        break
                    }
                    probeEnd--
                }
                if (accepted <= cursor) break
                val boundary = preferredBoundary(text, cursor, accepted)
                    .coerceAtLeast(cursor + 1)
                    .coerceAtMost(paragraph.second)
                ranges += LegacyChunk(
                    startOffset = cursor,
                    endOffsetExclusive = boundary,
                    text = text.substring(cursor, boundary)
                )
                cursor = boundary
            }
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
                    .put("text", chunk.text),
                kind = PacketKind.LOAD_TEXT
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
        legacyLoadJob?.cancel()
        legacyLoadInProgress = false
        deferredLegacySeekDuringLoad = null
        outboundQueue.clear()
        ackWaiting.clear()
        sentChunkSeqs.clear()
        ackedChunkSeqs.clear()
        invalidatedChunkSeqs.clear()
        committedSeq = -1
        highestSentSeq = -1
        highestContiguousSentSeq = -1
        highestContiguousAckedSeq = -1
        lastAckSeq = -1
        lastSeekOffset = 0
        lastHighlightedPayload = null
        lastSentPosition = null
        ackModeEnabled = false
        ackObservedThisSession = false
        sessionState = SessionState.STREAM_ENDED
        publishDebugState()
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
        var sequence = STREAM_SEQUENCE_BASE
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

        val splitChunks = chunks.flatMap { splitChunkIfNeeded(it, maxJsonBytes) }
        // Reindex after split expansion so emitted seq values are strictly increasing with no duplicates.
        return splitChunks.mapIndexed { index, chunk ->
            chunk.copy(sequenceId = STREAM_SEQUENCE_BASE + index)
        }
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
            .put("seq", 1)
            .put("offset", 0)
            .put("chunkId", 1)
            .put("checksum", chunkText.hashCode())
            .put("text", chunkText)
        if (compatibilityProfile.useCompatibilityPayloads && compatibilityProfile.includeAliasFields) {
            probe.put("sequenceId", 1)
            probe.put("start", 0)
            probe.put("end", chunkText.length)
        }
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
