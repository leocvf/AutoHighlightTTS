# X4 BLE + TTS Sync (MVP) — Phase 2/3 + Phase 4 Hook

## What was added
- BLE transport layer (`BleManager`) for scan/connect/discover/write JSON.
- UUID placeholder constants in one file (`X4BleUuids`).
- TTS sync packet builder (`TtsSyncBridge`) for `ping`, `load_text`, and debounced `position` packets.
- Phase 4 hook in `AutoHighlightTTSEngine` + `AutoHighlightTTSViewModel`:
  - emits sentence-level spoken range on `onStart` (safe default)
  - logs `onRangeStart` and only emits range-level events when sentence-preferred mode is disabled

## Safest fallback path
- `setPreferSentenceLevelSync(true)` is used by default.
- This means sentence-level sync is always emitted and range-level callbacks are treated as optional diagnostics unless explicitly enabled.

## File list
- `app/src/main/java/com/app/autohighlighttts/ble/X4BleUuids.kt`
- `app/src/main/java/com/app/autohighlighttts/ble/BleManager.kt`
- `app/src/main/java/com/app/autohighlighttts/sync/TtsSyncBridge.kt`
- `app/src/main/AndroidManifest.xml`
- `AutoHighlightTTS/src/main/java/com/app/autohighlighttts/AutoHighlightTTSEngine.kt`
- `app/src/main/java/com/app/autohighlighttts/AutoHighlightTTSViewModel.kt`

## BLE UUID placeholders to replace
- `X4_SERVICE_UUID`
- `X4_COMMAND_CHARACTERISTIC_UUID`

## Android permission choices
- `BLUETOOTH` + `BLUETOOTH_ADMIN` (with `maxSdkVersion=30`) for legacy BLE behavior.
- `ACCESS_FINE_LOCATION` (with `maxSdkVersion=30`) for scan visibility on pre-Android 12.
- `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT` for Android 12+ runtime permission model.

## Current limitations
- No BLE control UI in this phase; transport and sync hooks are in code-level wiring.
- Firmware UUID/protocol values are placeholders until provided by X4 firmware bridge.
