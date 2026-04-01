# X4 BLE Remote TTS MVP (Android)

This app includes an MVP BLE client that sends Remote TTS JSON commands to CrossPoint X4.

## Streaming protocol (v2)
The Android app now supports a production-style stream session protocol with deterministic
ordering and prebuffering.

### Session lifecycle
1. `stream_start` initializes a stream session (`sessionId`, `docId`, `streamVersion`,
   `totalChars`, `chunkCount`).
2. `stream_chunk` sends sentence/paragraph-aware chunks with `sequenceId`, offsets,
   and checksum.
3. `stream_commit` marks the highest contiguous sequence currently committed.
4. `stream_seek` re-centers stream delivery around a new spoken offset after jumps/seeks.
5. `stream_end` closes the active session.

### Stream packet examples
```json
{"type":"stream_start","sessionId":"2f7...","docId":"chapter-12","streamVersion":2,"totalChars":14329,"chunkCount":107}
{"type":"stream_chunk","sessionId":"2f7...","docId":"chapter-12","sequenceId":18,"chunkId":18,"start":2190,"end":2358,"checksum":-1581904,"text":"..."}
{"type":"stream_commit","sessionId":"2f7...","committedSeq":18}
{"type":"stream_seek","sessionId":"2f7...","start":7120,"end":7154}
{"type":"stream_end","sessionId":"2f7..."}
```

### Chunking and pacing
- Chunks are sentence-aware first, then word-boundary aware.
- Payload sizing uses runtime MTU budget (`MTU - ATT overhead - JSON headroom`).
- Sending uses paced queue draining with dynamic token refill based on observed BLE queue
  depth and write latency.
- Sliding window keeps a center chunk + lookahead/refill chunks buffered.

### Reliability modes
- **ACK mode (optional):** enable retransmit on timeout using sequence IDs.
- **Best-effort mode:** stream remains idempotent; key window chunks are resent on seek/resync.
- Android now listens for feedback notify packets such as
  `{"type":"ack","sequenceId":<int>}` and forwards ACK sequence IDs to the stream bridge.

### Migration and compatibility
- Legacy `load_text` + `position` behavior remains available behind a bridge flag
  (`streamingEnabled=false`).
- Existing `ping` and `clear` commands are unchanged for compatibility with older firmware.
- To reduce parser ambiguity, keep one active mode per session (`stream_*` or legacy commands),
  and reset (`clear`/`stream_end`) before switching.

## Implemented behavior
- Scans and connects to a peripheral advertising the placeholder X4 service UUID.
- Discovers writable command characteristic.
- Sends UTF-8 JSON commands:
  - `{"type":"ping"}`
  - `{"type":"clear"}`
  - `{"type":"stream_start", ...}`
  - `{"type":"stream_chunk", ...}`
  - `{"type":"stream_commit", ...}`
  - `{"type":"stream_seek", ...}`
  - `{"type":"stream_end", ...}`
  - `{"type":"load_text","docId":"demo-001","text":"..."}`
  - `{"type":"position","docId":"demo-001","start":N,"end":M}`
- Supports write queue + reconnect attempts.
- Splits large payloads into MTU-aware chunks.
- For large documents, the app sends paragraph-scoped MTU-safe `load_text` chunks.
- During live narration sync, the app switches paragraphs by sending `load_text` for the
  active paragraph/chunk and does not send `position` updates (default behavior).
- Logs BLE lifecycle + every outbound command.

## CrossPoint enhanced-reading-mod compatibility
Target firmware: `leocvf/crosspoint-enhanced-reading-mod` Remote TTS Reader mode.

### 1) BLE identity you must match
Defined in `app/src/main/java/com/app/autohighlighttts/ble/X4BleUuids.kt`.

- Service UUID: `0000fff0-0000-1000-8000-00805f9b34fb`
- Command characteristic UUID: `0000fff1-0000-1000-8000-00805f9b34fb`

> The firmware docs still call these "placeholder" names (`X4_TTS_SERVICE_UUID` and
> `X4_TTS_COMMAND_CHARACTERISTIC_UUID`), but this Android app and the current firmware
> branch are aligned on the values above.

### 2) Security requirement
- The command characteristic requires encrypted writes.
- Pair/bond first (if required by current firmware settings), then write commands.
- On Android, pairing prompt is usually OS-driven when attempting the first secured write.
- If firmware enforces a fixed passkey, use `123456` during pairing.

### 3) JSON packets to send
All packets are UTF-8 JSON written to the command characteristic:

- `{"type":"ping"}`
- `{"type":"clear"}`
- `{"type":"load_text","docId":"<string>","text":"<full document text>"}`
- `{"type":"position","docId":"<same string as load_text>","start":<int>,"end":<int>}`

### 4) Required field rules
- `type` is always required.
- `load_text` requires both `docId` and `text`.
- `position` requires `docId`, `start`, and `end`.
- `position` with a different `docId` than the currently loaded document is ignored by firmware.
- `start`/`end` should be document character offsets in the same `text` sent in `load_text`.

## Test steps (with X4 serial logs)
1. Flash/run X4 firmware with BLE Remote TTS bridge enabled.
2. Open serial monitor for X4 logs.
3. Launch Android app and grant Bluetooth permissions.
4. In **BLE Test Panel**:
   - Tap **Connect**.
   - Wait until state becomes `READY`.
5. Tap **Send Ping** and confirm X4 serial log receives ping JSON.
6. Tap **Send Clear** and confirm clear command appears in X4 logs.
7. Tap **Load Sample Text** and verify `load_text` arrives (possibly chunked).
8. Move slider; confirm active paragraph/chunk is re-sent with `load_text` updates.
9. Tap **Disconnect** and confirm disconnection logs on both Android/X4 sides.

## Notes
- Minimum Android API is 26.
- For Android 12+, `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` runtime permissions are required.
