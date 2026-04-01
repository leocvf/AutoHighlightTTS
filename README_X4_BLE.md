# X4 BLE Remote TTS MVP (Android)

This app includes an MVP BLE client that sends Remote TTS JSON commands to CrossPoint X4.

## Implemented behavior
- Scans and connects to a peripheral advertising the placeholder X4 service UUID.
- Discovers writable command characteristic.
- Sends UTF-8 JSON commands:
  - `{"type":"ping"}`
  - `{"type":"clear"}`
  - `{"type":"load_text","docId":"demo-001","text":"..."}`
  - `{"type":"position","docId":"demo-001","start":N,"end":M}`
- Supports write queue + reconnect attempts.
- Splits large payloads into MTU-aware chunks.
- Debounces position updates (~150ms) to avoid flooding.
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
8. Move slider; confirm `position` updates arrive at debounced cadence.
9. Tap **Disconnect** and confirm disconnection logs on both Android/X4 sides.

## Notes
- Minimum Android API is 26.
- For Android 12+, `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` runtime permissions are required.
