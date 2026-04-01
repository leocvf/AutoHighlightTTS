package com.app.autohighlighttts

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.app.autohighlighttts.ui.theme.MITextToSpeechTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MITextToSpeechTheme {
                UsableAppShell()
            }
        }
    }
}

private enum class HomeDestination(val label: String) {
    Reader("Reader"),
    Devices("Devices")
}

@Composable
private fun UsableAppShell() {
    var destination by remember { mutableStateOf(HomeDestination.Reader) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar {
                HomeDestination.entries.forEach { item ->
                    NavigationBarItem(
                        selected = destination == item,
                        onClick = { destination = item },
                        label = { Text(item.label) },
                        icon = {}
                    )
                }
            }
        }
    ) { innerPadding ->
        when (destination) {
            HomeDestination.Reader -> TTSScreen(modifier = Modifier.padding(innerPadding))
            HomeDestination.Devices -> BluetoothDiscoveryScreen(modifier = Modifier.padding(innerPadding))
        }
    }
}
