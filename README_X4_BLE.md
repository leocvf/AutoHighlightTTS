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

## UUID placeholders
Defined in `app/src/main/java/com/app/autohighlighttts/ble/X4BleUuids.kt`.

> TODO: replace placeholder UUIDs with real CrossPoint X4 service/characteristic UUIDs.

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
