package com.sensorstream.ui.screens

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.sensorstream.ui.signal.Fmt
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sensorstream.ui.components.SectionHeader
import com.sensorstream.ui.components.SsCard
import com.sensorstream.ui.settings.BatchMode
import com.sensorstream.ui.settings.ThemeMode
import com.sensorstream.ui.theme.Ss
import com.sensorstream.ui.theme.SsDims
import com.sensorstream.ui.theme.SsType
import com.sensorstream.vm.StreamViewModel

@Composable
fun SettingsScreen(vm: StreamViewModel) {
    val c = Ss.colors
    val settings by vm.settings.collectAsState()
    val context = LocalContext.current
    val versionName = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: "—"
    }
    val state by vm.engineState.collectAsState()
    val streamingLike = state.streaming || state.connecting || state.connected
    val scope = rememberCoroutineScope()
    var recInfo by remember { mutableStateOf(vm.recordingInfo()) }
    var exporting by remember { mutableStateOf(false) }
    var exportMsg by remember { mutableStateOf<String?>(null) }
    var batteryExempt by remember { mutableStateOf(isBatteryExempt(context)) }

    // Refresh on return to the app (after the battery dialog / share sheet) and when streaming stops.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) { batteryExempt = isBatteryExempt(context); recInfo = vm.recordingInfo() }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(streamingLike) { recInfo = vm.recordingInfo() }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = SsDims.screenPad, vertical = SsDims.gap),
        verticalArrangement = Arrangement.spacedBy(SsDims.gap),
    ) {
        Text("Settings", color = c.fg, fontSize = 24.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 2.dp))

        // --- Appearance -------------------------------------------------------------------------
        SectionHeader("Appearance")
        SsCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Theme", color = c.fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("Choose light, dark, or follow the system.", color = c.muted, fontSize = 12.sp)
                SegmentedControl(
                    options = listOf("System" to ThemeMode.SYSTEM, "Light" to ThemeMode.LIGHT, "Dark" to ThemeMode.DARK),
                    selected = settings.themeMode,
                    onSelect = { vm.setThemeMode(it) },
                )
            }
        }

        // --- 3D Visualization -------------------------------------------------------------------
        SectionHeader("3D Visualization")
        SsCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ToggleRow(
                    title = "World frame by default",
                    subtitle = "Show the fixed up / north / east reference on Orientation.",
                    checked = settings.default3dWorldFrame,
                    onToggle = { vm.setDefault3dWorldFrame(it) },
                )
                Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp).background(c.line))
                ToggleRow(
                    title = "Axis labels by default",
                    subtitle = "Label the X / Y / Z axes on the 3D views.",
                    checked = settings.default3dLabels,
                    onToggle = { vm.setDefault3dLabels(it) },
                )
            }
        }

        // --- Filtering ----------------------------------------------------------------------------
        SectionHeader("Filtering")
        SsCard(Modifier.fillMaxWidth()) {
            ToggleRow(
                title = "Show filtered signals",
                subtitle = "Filters are set on the laptop dashboard; the phone remembers them and filters its own samples, even offline.",
                checked = settings.showFiltered,
                onToggle = { vm.setShowFiltered(it) },
            )
        }

        // --- Streaming --------------------------------------------------------------------------
        SectionHeader("Streaming")
        SsCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Packet batching", color = c.fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    when (settings.batchMode) {
                        BatchMode.LOW_LATENCY -> "Send each sample immediately — lowest latency."
                        BatchMode.BALANCED -> "Pack samples for up to 2 ms — fewer packets, +2 ms latency."
                        BatchMode.BATTERY -> "Pack samples for up to 5 ms — fewest packets and radio wake-ups, +5 ms latency."
                    },
                    color = c.muted, fontSize = 12.sp,
                )
                SegmentedControl(
                    options = listOf("Low latency" to BatchMode.LOW_LATENCY, "Balanced" to BatchMode.BALANCED, "Battery" to BatchMode.BATTERY),
                    selected = settings.batchMode,
                    onSelect = { vm.setBatchMode(it) },
                )
                Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp).background(c.line))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Background streaming", color = c.fg, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text(
                            if (batteryExempt) "Unrestricted — keeps streaming reliably with the screen off."
                            else "Battery-optimized — Android may throttle streaming when the screen is off.",
                            color = if (batteryExempt) c.muted else c.warn, fontSize = 12.sp,
                        )
                    }
                    if (!batteryExempt) {
                        OutlinedButton(
                            onClick = { com.sensorstream.MainActivity.requestBatteryExemption(context) },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = c.accent),
                        ) { Text("Allow") }
                    }
                }
            }
        }

        // --- On-phone recording -----------------------------------------------------------------
        SectionHeader("On-phone recording")
        SsCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (recInfo.segments == 0) "Nothing buffered yet. The phone keeps a rolling recording (up to 20 min) while streaming."
                    else "${Fmt.value(recInfo.bytes / 1_048_576f, 1)} MB buffered on the phone (${recInfo.segments} segments).",
                    color = c.muted, fontSize = 12.sp,
                )
                OutlinedButton(
                    onClick = {
                        exportMsg = null
                        exporting = true
                        scope.launch {
                            val r = vm.exportRecording()
                            exporting = false
                            if (r.error != null) exportMsg = r.error
                            else {
                                exportMsg = "Exported ${r.frames} samples."
                                shareFiles(context, r.files)
                            }
                        }
                    },
                    enabled = !streamingLike && recInfo.segments > 0 && !exporting,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = c.accent),
                ) { Text(if (exporting) "Preparing…" else "Share recording") }
                Text(
                    when {
                        exportMsg != null -> exportMsg!!
                        streamingLike -> "Stop streaming to export."
                        else -> "Shares a .ssbin + .meta.json. Put both in the laptop's recordings/ folder to open them in the dashboard's Recordings view."
                    },
                    color = c.faint, fontSize = 11.sp,
                )
            }
        }

        // --- About ------------------------------------------------------------------------------
        SectionHeader("About")
        SsCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoLine("App version", versionName)
                InfoLine("Device", "${Build.MANUFACTURER} ${Build.MODEL}")
                InfoLine("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                InfoLine("Sensors detected", vm.catalog.size.toString())
            }
        }

        Text(
            "SensorStream streams multi-sensor telemetry over UDP to your laptop. " +
                "Appearance and 3D preferences affect display only; packet batching changes how samples " +
                "are grouped into packets, never the values sent.",
            color = c.faint, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )
    }
}



private fun isBatteryExempt(context: Context): Boolean =
    (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)

/** Hand the exported files to the Android share sheet (email, Drive, Nearby Share, …). */
private fun shareFiles(context: Context, files: List<java.io.File>) {
    val uris = ArrayList(files.map { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it) })
    val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "application/octet-stream"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share recording"))
}

@Composable
private fun <T> SegmentedControl(
    options: List<Pair<String, T>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    val c = Ss.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(SsDims.radiusSm))
            .background(c.surface2).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { (label, value) ->
            val isSel = value == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(SsDims.radiusSm - 3.dp))
                    .background(if (isSel) c.accent else Color.Transparent)
                    .clickable { onSelect(value) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (isSel) Color.White else c.muted,
                    fontSize = 13.sp,
                    fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    val c = Ss.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = c.fg, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = c.muted, fontSize = 12.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = c.accent,
                uncheckedThumbColor = c.muted,
                uncheckedTrackColor = c.surface2,
                uncheckedBorderColor = c.line,
            ),
        )
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Ss.colors.muted, fontSize = 13.sp)
        Text(value, style = SsType.mono, color = Ss.colors.fg, fontSize = 13.sp)
    }
}
