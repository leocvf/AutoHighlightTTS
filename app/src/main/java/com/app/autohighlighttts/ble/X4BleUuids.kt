package com.app.autohighlighttts.ble

import java.util.UUID

object X4BleUuids {
    // TODO(X4 firmware): replace placeholder UUID with the real custom bridge service UUID.
    val X4_SERVICE_UUID: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")

    // TODO(X4 firmware): replace placeholder UUID with the real writable command characteristic UUID.
    val X4_COMMAND_CHARACTERISTIC_UUID: UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
}
