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
    private var activeChunks: List<TextChunk> = emptyList()
    private var activeChunkIndex: Int = -1

    private data class TextChunk(
        val docId: String,
        val text: String,
        val startOffset: Int,
        val endOffsetExclusive: Int
    )

    fun sendPing() = sendPacket(JSONObject().put("type", "ping"))

    fun sendClear() {
        activeChunks = emptyList()
        activeChunkIndex = -1
        sendPacket(JSONObject().put("type", "clear"))
    }

    fun setDocId(newDocId: String) {
        if (newDocId.isNotBlank()) {
            docId = newDocId
            activeChunks = emptyList()
            activeChunkIndex = -1
        }
    }

    fun loadDocumentTextOnce(text: String): Boolean {
        if (text.isBlank()) {
            Log.w(TAG, "loadDocumentTextOnce ignored (blank text)")
            return false
        }
        val chunks = buildLoadTextChunks(text)
        if (chunks.isEmpty()) {
            Log.w(TAG, "loadDocumentTextOnce produced no chunks")
            return false
        }
        activeChunks = chunks
        activeChunkIndex = 0
        return sendLoadTextChunk(activeChunkIndex)
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

            val targetChunkIndex = findChunkIndex(range.first)
            if (targetChunkIndex >= 0 && targetChunkIndex != activeChunkIndex) {
                val switched = sendLoadTextChunk(targetChunkIndex)
                if (!switched) {
                    Log.w(TAG, "Failed to switch chunk for position range=$range index=$targetChunkIndex")
                }
            }

            val activeChunk = activeChunks.getOrNull(activeChunkIndex)
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

    private fun sendPacket(packet: JSONObject) {
        val sent = bleManager.writeJson(packet)
        Log.d(TAG, "outboundCommand sent=$sent payload=$packet")
    }

    private fun sendLoadTextChunk(index: Int): Boolean {
        val chunk = activeChunks.getOrNull(index) ?: return false
        val sent = bleManager.writeJson(
            JSONObject()
                .put("type", "load_text")
                .put("docId", chunk.docId)
                .put("text", chunk.text)
        )
        if (sent) {
            activeChunkIndex = index
        }
        return sent
    }

    private fun findChunkIndex(globalOffset: Int): Int {
        if (activeChunks.isEmpty()) return -1
        return activeChunks.indexOfFirst { chunk ->
            globalOffset >= chunk.startOffset && globalOffset < chunk.endOffsetExclusive
        }.takeIf { it >= 0 } ?: (activeChunks.size - 1)
    }

    private fun buildLoadTextChunks(text: String): List<TextChunk> {
        val maxPayloadBytes = 220
        val chunks = mutableListOf<TextChunk>()
        var start = 0
        var chunkIndex = 0
        while (start < text.length) {
            val chunkDocId = "$docId#$chunkIndex"
            val end = findChunkEndIndex(text, start, maxPayloadBytes, chunkDocId)
            if (end <= start) {
                break
            }
            val candidate = text.substring(start, end)
            chunks.add(
                TextChunk(
                    docId = chunkDocId,
                    text = candidate,
                    startOffset = start,
                    endOffsetExclusive = end
                )
            )
            start = end
            chunkIndex++
        }
        return chunks
    }

    private fun findChunkEndIndex(text: String, start: Int, maxPayloadBytes: Int, chunkDocId: String): Int {
        var low = start + 1
        var high = text.length
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
