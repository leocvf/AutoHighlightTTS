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
    private val debounceMs: Long = 150L,
    private val sendPositionPackets: Boolean = true
) {

    companion object {
        private const val TAG = "TtsSyncBridge"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var pendingPosition: Pair<Int, Int>? = null
    private var positionJob: Job? = null
    private var lastPositionSentAt = 0L
    private var activeSegments: List<TextSegment> = emptyList()
    private var activeSegmentIndex: Int = -1
    private var sourceDocumentText: String = ""
    private var lastHighlightedPayload: String? = null

    private data class TextSegment(
        val docId: String,
        val text: String,
        val startOffset: Int,
        val endOffsetExclusive: Int
    )

    fun sendPing() = sendPacket(JSONObject().put("type", "ping"))

    fun sendClear() {
        activeSegments = emptyList()
        activeSegmentIndex = -1
        sourceDocumentText = ""
        lastHighlightedPayload = null
        sendPacket(JSONObject().put("type", "clear"))
    }

    fun setDocId(newDocId: String) {
        if (newDocId.isNotBlank()) {
            docId = newDocId
            activeSegments = emptyList()
            activeSegmentIndex = -1
            sourceDocumentText = ""
            lastHighlightedPayload = null
        }
    }

    fun loadDocumentTextOnce(text: String): Boolean {
        if (text.isBlank()) {
            Log.w(TAG, "loadDocumentTextOnce ignored (blank text)")
            return false
        }
        sourceDocumentText = text
        lastHighlightedPayload = null
        val segments = buildLoadTextSegments(text)
        if (segments.isEmpty()) {
            Log.w(TAG, "loadDocumentTextOnce produced no chunks")
            return false
        }
        activeSegments = segments
        activeSegmentIndex = -1
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

            if (!sendPositionPackets) {
                sendHighlightedRangeAsLoadText(range.first, range.second)
                lastPositionSentAt = SystemClock.elapsedRealtime()
                return@launch
            }

            val targetChunkIndex = findSegmentIndex(range.first)
            if (targetChunkIndex >= 0 && targetChunkIndex != activeSegmentIndex) {
                val switched = sendLoadTextSegment(targetChunkIndex)
                if (!switched) {
                    Log.w(TAG, "Failed to switch chunk for position range=$range index=$targetChunkIndex")
                }
            }

            val activeChunk = activeSegments.getOrNull(activeSegmentIndex)
            val startOffset = activeChunk?.startOffset ?: 0
            val endOffset = activeChunk?.endOffsetExclusive ?: Int.MAX_VALUE
            val localStart = (range.first - startOffset).coerceAtLeast(0)
            val localEnd = (range.second - startOffset).coerceIn(localStart, endOffset - startOffset)

            val packet = JSONObject()
                .put("type", "position")
                .put("docId", activeChunk?.docId ?: docId)
                .put("start", localStart)
                .put("end", localEnd)
            sendPacket(packet)
            lastPositionSentAt = SystemClock.elapsedRealtime()
        }
    }

    fun close() {
        scope.coroutineContext.cancel()
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

        val packet = JSONObject()
            .put("type", "load_text")
            .put("docId", "$docId#live")
            .put("text", highlightedText)

        sendPacket(packet)
        lastHighlightedPayload = payloadKey
    }

    private fun sendPacket(packet: JSONObject) {
        val sent = bleManager.writeJson(packet)
        Log.d(TAG, "outboundCommand sent=$sent payload=$packet")
    }

    private fun sendLoadTextSegment(index: Int): Boolean {
        val chunk = activeSegments.getOrNull(index) ?: return false
        val sent = bleManager.writeJson(
            JSONObject()
                .put("type", "load_text")
                .put("docId", chunk.docId)
                .put("text", chunk.text)
        )
        if (sent) {
            activeSegmentIndex = index
        }
        return sent
    }

    private fun findSegmentIndex(globalOffset: Int): Int {
        if (activeSegments.isEmpty()) return -1
        return activeSegments.indexOfFirst { chunk ->
            globalOffset >= chunk.startOffset && globalOffset < chunk.endOffsetExclusive
        }.takeIf { it >= 0 } ?: (activeSegments.size - 1)
    }

    private fun buildLoadTextSegments(text: String): List<TextSegment> {
        val paragraphRanges = buildParagraphRanges(text)
        val maxPayloadBytes = 220
        val chunks = mutableListOf<TextSegment>()
        var chunkIndex = 0
        paragraphRanges.forEach { range ->
            var start = range.first
            val paragraphEnd = range.second
            while (start < paragraphEnd) {
                val chunkDocId = "$docId#$chunkIndex"
                val end = findChunkEndIndex(text, start, paragraphEnd, maxPayloadBytes, chunkDocId)
                if (end <= start) {
                    break
                }
                val candidate = text.substring(start, end)
                chunks.add(
                    TextSegment(
                        docId = chunkDocId,
                        text = candidate,
                        startOffset = start,
                        endOffsetExclusive = end
                    )
                )
                start = end
                chunkIndex++
            }
        }
        return chunks
    }

    private fun buildParagraphRanges(text: String): List<Pair<Int, Int>> {
        val ranges = mutableListOf<Pair<Int, Int>>()
        var start = 0
        while (start < text.length) {
            val paragraphBreak = text.indexOf("\n\n", start)
            val end = if (paragraphBreak >= 0) paragraphBreak + 2 else text.length
            if (end > start) {
                ranges.add(start to end)
            }
            start = end
        }
        return ranges
    }

    private fun findChunkEndIndex(
        text: String,
        start: Int,
        maxEnd: Int,
        maxPayloadBytes: Int,
        chunkDocId: String
    ): Int {
        var low = start + 1
        var high = maxEnd
        var best = -1

        while (low <= high) {
            val mid = (low + high) / 2
            val candidate = text.substring(start, mid)
            val payloadBytes = JSONObject()
                .put("type", "load_text")
                .put("docId", chunkDocId)
                .put("text", candidate)
                .toString()
                .toByteArray(Charsets.UTF_8)
                .size
            if (payloadBytes <= maxPayloadBytes) {
                best = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }

        if (best <= start) return start
        val preferredSplit = text.substring(start, best).indexOfLast { it == ' ' || it == '\n' }
        return if (preferredSplit > 20) {
            start + preferredSplit + 1
        } else {
            best
        }
    }
}
