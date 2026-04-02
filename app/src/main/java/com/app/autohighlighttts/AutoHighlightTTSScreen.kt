package com.app.autohighlighttts

import android.util.Log
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.app.autohighlighttts.composable.AutoHighlightTTSBuilder
import com.app.autohighlighttts.ble.BleManager.ScannedDevice
import com.app.autohighlighttts.ui.theme.Amaranth
import com.app.autohighlighttts.ui.theme.fontFamily
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import com.app.autohighlightttssample.R


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TTSScreen(
    modifier: Modifier = Modifier,
    viewModel: AutoHighlightTTSViewModel = hiltViewModel()
) {

    var instanceOfTTS by remember { mutableStateOf<AutoHighlightTTSEngine?>(null) }
    LaunchedEffect(instanceOfTTS == null) {
        instanceOfTTS = viewModel.instanceOfTTS
    }

    instanceOfTTS?.let { tts ->
        ComposableLifecycle { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    tts.apply {
                        if (autoHighlightTTS.isSpeaking) {
                            pauseTextToSpeech()
                        }
                    }
                }

                else -> {}
            }
        }

        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val connectionState by viewModel.connectionState.collectAsState()
        val bleStatusDetail by viewModel.bleStatusDetail.collectAsState()
        val scannedDevices by viewModel.scannedDevices.collectAsState()
        val bleDebugState by viewModel.bleStreamDebugState.collectAsState()
        val streamingModeEnabled by viewModel.streamingModeEnabled.collectAsState()
        var pendingBleScanAfterPermission by remember { mutableStateOf(false) }
        val permissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions()
        ) { granted ->
            val allGranted = granted.values.all { it }
            if (pendingBleScanAfterPermission && allGranted) {
                viewModel.scanBleDevices(includeAllDevices = true)
            } else if (pendingBleScanAfterPermission && !allGranted) {
                scope.launch {
                    Toast.makeText(
                        context,
                        "Bluetooth permissions are required to scan devices.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            pendingBleScanAfterPermission = false
        }

        tts.setOnCompletionListener {
            Log.e("TAG", "TTSScreen: Completed From Callback")
            scope.launch {
                Toast.makeText(context, "Completed", Toast.LENGTH_LONG).show()
            }
        }.setOnErrorListener {
            scope.launch {
                Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            }
        }.setOnEachSentenceStartListener {
            Log.e("TAG", "TTSScreen: onEachSentenceStart is called")
        }

        var editableText by remember(tts.mainText) { mutableStateOf(tts.mainText) }
        var pitch by remember { mutableFloatStateOf(1f) }
        var speed by remember { mutableFloatStateOf(1f) }
        var fontSize by remember { mutableFloatStateOf(20f) }
        var textAlign by remember { mutableStateOf(TextAlign.Center) }
        var engineMenuExpanded by remember { mutableStateOf(false) }
        var voiceMenuExpanded by remember { mutableStateOf(false) }
        var alignmentMenuExpanded by remember { mutableStateOf(false) }
        var selectedEngine by remember { mutableStateOf<String?>(null) }
        var selectedVoice by remember { mutableStateOf<String?>(null) }
        var engines by remember { mutableStateOf(viewModel.availableEngines()) }
        var voices by remember { mutableStateOf(viewModel.availableVoices()) }
        var showConfigurationScreen by remember { mutableStateOf(false) }
        val epubPickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument()
        ) { uri ->
            uri?.let {
                val loaded = viewModel.loadEpub(it)
                if (loaded.isNotBlank()) {
                    editableText = loaded
                    scope.launch {
                        Toast.makeText(context, "EPUB loaded", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        Column(
            modifier
                .fillMaxSize()
                .fillMaxWidth()
                .background(Color.White)
                .padding(horizontal = 20.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 40.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(id = R.string.text_to_speech),
                    color = Amaranth,
                    fontWeight = FontWeight.Medium,
                    fontSize = 30.sp,
                    fontFamily = fontFamily,
                    textAlign = TextAlign.Start
                )
                TextButton(onClick = { showConfigurationScreen = !showConfigurationScreen }) {
                    Text(if (showConfigurationScreen) "Back to Reader" else "Configuration")
                }
            }

            if (showConfigurationScreen) {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        OutlinedTextField(
                            value = editableText,
                            onValueChange = { editableText = it },
                            label = { Text("Text to read") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Streaming protocol (non-legacy)")
                                Text(
                                    text = if (streamingModeEnabled) {
                                        "Using stream_start/stream_chunk/stream_commit"
                                    } else {
                                        "Using legacy load_text/position mode"
                                    },
                                    fontSize = 12.sp,
                                    color = Color.Gray
                                )
                            }
                            Switch(
                                checked = streamingModeEnabled,
                                onCheckedChange = viewModel::setStreamingModeEnabled
                            )
                        }
                    }
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { viewModel.updateNarrationText(editableText) },
                                modifier = Modifier.weight(1f)
                            ) { Text("Apply Text") }
                            Button(
                                onClick = { viewModel.loadSampleText() },
                                modifier = Modifier.weight(1f)
                            ) { Text("Sync to Device") }
                        }
                    }
                    item {
                        Button(
                            onClick = { epubPickerLauncher.launch(arrayOf("application/epub+zip")) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Load EPUB")
                        }
                    }
                    item {
                        ExposedDropdownMenuBox(
                            expanded = engineMenuExpanded,
                            onExpandedChange = { engineMenuExpanded = !engineMenuExpanded }
                        ) {
                OutlinedTextField(
                    value = selectedEngine ?: "Select TTS engine",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("TTS Engine") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = engineMenuExpanded) },
                    modifier = Modifier
                        .menuAnchor(type = MenuAnchorType.PrimaryNotEditable, enabled = true)
                        .fillMaxWidth()
                )
                ExposedDropdownMenu(expanded = engineMenuExpanded, onDismissRequest = { engineMenuExpanded = false }) {
                    engines.forEach { engine ->
                        DropdownMenuItem(
                            text = { Text(engine.label ?: engine.name) },
                            onClick = {
                                selectedEngine = engine.label ?: engine.name
                                engineMenuExpanded = false
                                viewModel.selectEngine(engine.name)
                                scope.launch {
                                    delay(500)
                                    voices = viewModel.availableVoices()
                                }
                            }
                        )
                    }
                }
                        }
                    }
                    item {
                        ExposedDropdownMenuBox(
                expanded = voiceMenuExpanded,
                onExpandedChange = { voiceMenuExpanded = !voiceMenuExpanded },
            ) {
                OutlinedTextField(
                    value = selectedVoice ?: "Select voice",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Voice") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = voiceMenuExpanded) },
                    modifier = Modifier
                        .menuAnchor(type = MenuAnchorType.PrimaryNotEditable, enabled = true)
                        .fillMaxWidth()
                )
                ExposedDropdownMenu(expanded = voiceMenuExpanded, onDismissRequest = { voiceMenuExpanded = false }) {
                    voices.forEach { voice ->
                        val voiceLabel = "${voice.locale.displayName} • ${voice.name}"
                        DropdownMenuItem(
                            text = { Text(voiceLabel) },
                            onClick = {
                                selectedVoice = voiceLabel
                                voiceMenuExpanded = false
                                viewModel.selectVoice(voice.name)
                            }
                        )
                    }
                }
                        }
                    }
                    item {
                        ExposedDropdownMenuBox(
                expanded = alignmentMenuExpanded,
                onExpandedChange = { alignmentMenuExpanded = !alignmentMenuExpanded },
            ) {
                OutlinedTextField(
                    value = textAlign.toDisplayName(),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Text alignment") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = alignmentMenuExpanded) },
                    modifier = Modifier
                        .menuAnchor(type = MenuAnchorType.PrimaryNotEditable, enabled = true)
                        .fillMaxWidth()
                )
                ExposedDropdownMenu(expanded = alignmentMenuExpanded, onDismissRequest = { alignmentMenuExpanded = false }) {
                    listOf(TextAlign.Start, TextAlign.Center, TextAlign.End, TextAlign.Justify).forEach { align ->
                        DropdownMenuItem(
                            text = { Text(align.toDisplayName()) },
                            onClick = {
                                textAlign = align
                                alignmentMenuExpanded = false
                            }
                        )
                    }
                }
                        }
                    }
                    item { Text(text = "Text Size: ${fontSize.roundToInt()}sp") }
                    item {
                        Slider(
                            value = fontSize,
                            valueRange = 14f..36f,
                            onValueChange = { fontSize = it }
                        )
                    }
                    item { Text(text = "Voice Pitch: ${"%.1f".format(pitch)}") }
                    item {
                        Slider(
                            value = pitch,
                            valueRange = 0.5f..2f,
                            onValueChange = {
                                pitch = it
                                viewModel.updatePitchAndSpeed(pitch, speed)
                            }
                        )
                    }
                    item { Text(text = "Voice Speed: ${"%.1f".format(speed)}") }
                    item {
                        Slider(
                            value = speed,
                            valueRange = 0.5f..2f,
                            onValueChange = {
                                speed = it
                                viewModel.updatePitchAndSpeed(pitch, speed)
                            }
                        )
                    }
                    item {
                        BleTestPanel(
                            connectionState = connectionState,
                            statusDetail = bleStatusDetail,
                            onConnect = {
                                if (viewModel.hasRequiredBlePermissions()) {
                                    viewModel.scanBleDevices(includeAllDevices = true)
                                } else {
                                    pendingBleScanAfterPermission = true
                                    permissionLauncher.launch(viewModel.requiredBlePermissions())
                                }
                            },
                            devices = scannedDevices,
                            onConnectDevice = viewModel::connectBle,
                            onDisconnect = viewModel::disconnectBle,
                            onPing = viewModel::sendPing,
                            onClear = viewModel::sendClear,
                            onLoadSample = viewModel::loadSampleText,
                            onPosition = viewModel::sendPosition,
                            legacySummary = bleDebugState.legacySummary
                        )
                    }
                }
            } else {
                Text(
                    text = "Reader mode",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(end = 10.dp)
                ) {
                AutoHighlightTTSComposable(
                    tts = tts,
                    textAlign = textAlign,
                    fontFamily = fontFamily,
                    fontWeight = FontWeight.ExtraLight,
                    autoHighlightTTSBuilder = AutoHighlightTTSBuilder(
                        text = tts.mainText,
                        tts.highlightTextPair.value,
                        style = SpanStyle(
                            fontFamily = fontFamily,
                            color = Amaranth,
                            fontWeight = FontWeight.Bold,
                        )
                    ),
                    style = TextStyle(
                        fontSize = fontSize.sp, color = Color.Black,
                        lineHeight = 35.sp
                    ),
                )
                }
                Text(
                    text = "Connection: $connectionState",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            BottomStorySection(tts)
        }
    } ?: Box(contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

private fun TextAlign.toDisplayName(): String = when (this) {
    TextAlign.Start -> "Start"
    TextAlign.Center -> "Center"
    TextAlign.End -> "End"
    TextAlign.Justify -> "Justify"
    else -> "Start"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BleTestPanel(
    connectionState: String,
    statusDetail: String,
    onConnect: () -> Unit,
    devices: List<ScannedDevice>,
    onConnectDevice: (ScannedDevice) -> Unit,
    onDisconnect: () -> Unit,
    onPing: () -> Unit,
    onClear: () -> Unit,
    onLoadSample: () -> Unit,
    onPosition: (Int, Int) -> Unit,
    legacySummary: String
) {
    var slider by remember { mutableFloatStateOf(0f) }
    var deviceMenuExpanded by remember { mutableStateOf(false) }
    var selectedDeviceAddress by remember { mutableStateOf<String?>(null) }
    val selectedDevice = devices.firstOrNull { it.address == selectedDeviceAddress }

    LaunchedEffect(devices) {
        if (selectedDeviceAddress == null && devices.isNotEmpty()) {
            selectedDeviceAddress = devices.first().address
        } else if (selectedDeviceAddress != null && devices.none { it.address == selectedDeviceAddress }) {
            selectedDeviceAddress = devices.firstOrNull()?.address
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Text(text = "BLE Test Panel: $connectionState")
        Text(
            text = "Details: $statusDetail",
            fontSize = 12.sp,
            color = Color.Gray
        )
        Text(
            text = "Legacy telemetry: $legacySummary",
            fontSize = 11.sp,
            color = Color.Gray
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onConnect) { Text("Scan Devices") }
            Button(onClick = onDisconnect) { Text("Disconnect") }
        }
        if (devices.isNotEmpty()) {
            Text(
                text = "Choose a device to connect:",
                fontSize = 12.sp,
                color = Color.Gray,
                modifier = Modifier.padding(top = 8.dp)
            )
            ExposedDropdownMenuBox(
                expanded = deviceMenuExpanded,
                onExpandedChange = { deviceMenuExpanded = !deviceMenuExpanded },
                modifier = Modifier.padding(top = 4.dp)
            ) {
                OutlinedTextField(
                    value = selectedDevice?.let { "${it.name} (${it.address})" } ?: "Select a device",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Available devices (${devices.size})") },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = deviceMenuExpanded)
                    },
                    modifier = Modifier
                        .menuAnchor(type = MenuAnchorType.PrimaryNotEditable, enabled = true)
                        .fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = deviceMenuExpanded,
                    onDismissRequest = { deviceMenuExpanded = false }
                ) {
                    devices.forEach { device ->
                        DropdownMenuItem(
                            text = { Text("${device.name} (${device.address})") },
                            onClick = {
                                selectedDeviceAddress = device.address
                                deviceMenuExpanded = false
                            }
                        )
                    }
                }
            }
            Button(
                onClick = { selectedDevice?.let(onConnectDevice) },
                enabled = selectedDevice != null,
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Text("Connect Selected Device")
            }
            LazyColumn(modifier = Modifier.height(80.dp)) {
                items(devices, key = { it.address }) { device ->
                    Text(
                        text = "${device.name} • ${device.rssi} dBm",
                        fontSize = 11.sp,
                        color = if (device.address == selectedDeviceAddress) Amaranth else Color.Gray,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onPing) { Text("Send Ping") }
            Button(onClick = onClear) { Text("Send Clear") }
            Button(onClick = onLoadSample) { Text("Load Sample Text") }
        }
        Slider(
            value = slider,
            valueRange = 0f..200f,
            onValueChange = {
                slider = it
                val start = it.toInt()
                onPosition(start, (start + 8).coerceAtMost(200))
            }
        )
    }
}

/**
 * This Composable Is Used For Track and Control the Progress.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomStorySection(instanceOfTTS: AutoHighlightTTSEngine) {
    var test by remember {
        mutableFloatStateOf(0f)
    }
    val sliderValue = animateFloatAsState(
        targetValue = instanceOfTTS.sliderPosition,
        animationSpec = tween(durationMillis = 100),
        label = ""
    )

    Column(
        modifier = Modifier.padding(vertical = 20.dp, horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Slider(
            value = sliderValue.value,
            onValueChange = {
                instanceOfTTS.sliderPosition = it
                test = it
            },
            valueRange = 0f..(instanceOfTTS.totalWords + 1).toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = Color.Transparent, // Make the default thumb transparent
                activeTrackColor = Amaranth,
            ),
            onValueChangeFinished = {
                instanceOfTTS.sliderToUpdate((test.roundToInt()))
            },
            track = {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp) // Match slider height
                ) {
                    val trackHeight = 8.dp.toPx() // Track height
                    val activeTrackWidth =
                        (sliderValue.value / instanceOfTTS.totalWords) * size.width

                    // Draw inactive track (gray)
                    drawRoundRect(
                        color = Color.LightGray,
                        size = Size(width = size.width, height = trackHeight),
                        cornerRadius = CornerRadius(trackHeight / 2),
                        topLeft = Offset(0f, (size.height - trackHeight) / 2)
                    )

                    // Draw active track (blue)
                    drawRoundRect(
                        color = Amaranth,
                        size = Size(width = activeTrackWidth, height = trackHeight),
                        cornerRadius = CornerRadius(trackHeight / 2),
                        topLeft = Offset(0f, (size.height - trackHeight) / 2)
                    )
                }
            },
            thumb = {
                // Draw the custom thumb
                Canvas(
                    modifier = Modifier
                        .size(24.dp)
                ) {
                    val thumbRadius = 12.dp.toPx() // Thumb radius, proportional to slider height

                    val thumbCenterX = (sliderValue.value * size.width).coerceIn(
                        thumbRadius,
                        size.width - thumbRadius
                    ) // Constrain thumb within bounds
                    val thumbCenterY = size.height / 2 // Thumb Y position, centered

                    // Draw the outer transparent circle (thumb)
                    drawCircle(
                        color = Amaranth, // Semi-transparent white
                        center = Offset(thumbCenterX, thumbCenterY),
                        radius = thumbRadius
                    )
                }
            },
        )

        StoryReadingController(instanceOfTTS)
    }
}

/**
 * Control the Reading Like Moving to Next , Skip or Forward.
 */
@Composable
fun StoryReadingController(instanceOfTTS: AutoHighlightTTSEngine) {
    Row(
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
    ) {
        CommonImageButton(
            modifier = Modifier,
            image = R.drawable.ic_skip_previous,
            enabled = (instanceOfTTS.currentCount.intValue == 0).not(),
            color = if ((instanceOfTTS.currentCount.intValue == 0)) Color.LightGray else Amaranth
        ) {
            instanceOfTTS.backwardText()
        }
        Box(
            Modifier
                .clip(CircleShape)
                .background(Amaranth)
                .size(64.dp)
                .clickable {
                    if (instanceOfTTS.playOrPauseTTS.value) {
                        instanceOfTTS.pauseTextToSpeech()
                    } else {
                        instanceOfTTS.playTextToSpeech()
                    }
                }, contentAlignment = Alignment.Center
        ) {
            Image(
                painterResource(id = if (instanceOfTTS.playOrPauseTTS.value) R.drawable.ic_pause else R.drawable.ic_play_white),
                contentDescription = "",
                modifier = Modifier.size(24.dp)
            )
        }
        CommonImageButton(
            modifier = Modifier, image = R.drawable.ic_skip_next,
            enabled = (instanceOfTTS.currentCount.intValue >= instanceOfTTS.listOfStringOfParagraph.size - 1).not(),
            color = if ((instanceOfTTS.currentCount.intValue >= instanceOfTTS.listOfStringOfParagraph.size - 1)) Color.LightGray else Amaranth
        ) {
            instanceOfTTS.forwardText()
        }
    }
}


@Composable
fun CommonImageButton(
    @DrawableRes image: Int,
    modifier: Modifier = Modifier,
    color: Color = Amaranth, enabled: Boolean = true,
    onClick: () -> Unit = {},
) {
    IconButton(
        enabled = enabled,
        onClick = { onClick() },
    ) {
        Icon(
            painter = painterResource(id = image),
            contentDescription = "",
            modifier = modifier.size(32.dp),
            tint = color
        )
    }
}

@Composable
fun ComposableLifecycle(
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
    onEvent: (LifecycleOwner, Lifecycle.Event) -> Unit
) {

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { source, event ->
            onEvent(source, event)
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
}
