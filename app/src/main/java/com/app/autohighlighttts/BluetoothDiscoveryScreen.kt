package com.app.autohighlighttts

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun BluetoothDiscoveryScreen(
    modifier: Modifier = Modifier,
    viewModel: AutoHighlightTTSViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val connectionState by viewModel.connectionState.collectAsState()
    val statusDetail by viewModel.bleStatusDetail.collectAsState()
    val devices by viewModel.scannedDevices.collectAsState()
    var pendingScan by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val allGranted = granted.values.all { it }
        if (pendingScan && allGranted) {
            viewModel.scanBleDevices(includeAllDevices = true)
        } else if (pendingScan) {
            Toast.makeText(
                context,
                "Bluetooth + Location permissions are required to discover devices.",
                Toast.LENGTH_LONG
            ).show()
        }
        pendingScan = false
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Bluetooth Device Discovery", style = MaterialTheme.typography.headlineSmall)
        Text(
            "State: $connectionState",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text("Details: $statusDetail", style = MaterialTheme.typography.bodySmall)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (viewModel.hasRequiredBlePermissions()) {
                    viewModel.scanBleDevices(includeAllDevices = true)
                } else {
                    pendingScan = true
                    permissionLauncher.launch(viewModel.requiredBlePermissions())
                }
            }) {
                Text("Scan Any Device")
            }
            Button(onClick = viewModel::disconnectBle) {
                Text("Stop/Disconnect")
            }
        }

        Text(
            text = "Nearby devices (${devices.size})",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp)
        )

        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(devices, key = { it.address }) { device ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { viewModel.connectBle(device) }
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(device.name)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(device.address, style = MaterialTheme.typography.bodySmall)
                        Text("RSSI: ${device.rssi}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
