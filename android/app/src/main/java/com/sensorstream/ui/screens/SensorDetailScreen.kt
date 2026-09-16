package com.sensorstream.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sensorstream.ui.components.SamplingRateBadge
import com.sensorstream.ui.components.SectionHeader
import com.sensorstream.ui.components.SignalValue
import com.sensorstream.ui.components.SsCard
import com.sensorstream.ui.components.StatusBadge
import com.sensorstream.ui.components.phase
import com.sensorstream.ui.nav.AppNav
import com.sensorstream.ui.signal.Fmt
import com.sensorstream.ui.signal.SensorCategory
import com.sensorstream.ui.signal.SignalCatalog
import com.sensorstream.ui.theme.Ss
import com.sensorstream.ui.theme.SsDims
import com.sensorstream.ui.theme.SsType
import com.sensorstream.ui.viz.EnvironmentalHero
import com.sensorstream.ui.viz.MiniSignalGraph
import com.sensorstream.ui.viz.Phone3DView
import com.sensorstream.ui.viz.Projection
import com.sensorstream.ui.viz.Vec3
import com.sensorstream.vm.StreamViewModel

@Composable
fun SensorDetailScreen(vm: StreamViewModel, nav: AppNav, handle: Int) {
    val info = vm.catalog.firstOrNull { it.handle == handle } ?: run { nav.back(); return }
    val type = info.type
    val sig = SignalCatalog.of(type, info.stringType)
    val c = Ss.colors
    val state by vm.engineState.collectAsState()
    val previews by vm.preview.collectAsState()
    val sel by vm.sel.collectAsState()
    val appSettings by vm.settings.collectAsState()

    DisposableEffect(handle) {
        vm.startPreview(handle)
        vm.startOrientationPreview()
        onDispose { vm.stopPreview(handle); vm.stopOrientationPreview() }
    }

    val values = previews[handle]
    val orientation = vm.orientationHandle?.let { previews[it] }
    val axisColors = listOf(c.axisX, c.axisY, c.axisZ)
    val isVector = sig.category == SensorCategory.MOTION || sig.category == SensorCategory.MAGNETIC
    val isOrientation = sig.category == SensorCategory.ORIENTATION
    val isEnv = sig.category == SensorCategory.ENVIRONMENT || sig.category == SensorCategory.PROXIMITY
    val sensorVec = if (isVector && values != null && values.size >= 3) Vec3(values[0], values[1], values[2]) else null

    // Orientation-screen view controls.
    var showBody by remember { mutableStateOf(true) }
    var showWorld by remember { mutableStateOf(appSettings.default3dWorldFrame) }
    var showLabels by remember { mutableStateOf(appSettings.default3dLabels) }
    var quatMode by remember { mutableStateOf(false) } // false = Euler, true = Quaternion
    var windowSec by remember { mutableStateOf(10) } // real-time graph time window

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = SsDims.screenPad, vertical = SsDims.gap),
        verticalArrangement = Arrangement.spacedBy(SsDims.gap),
    ) {
        // Back header
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(c.surface2).clickable { nav.back() },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = c.fg, modifier = Modifier.size(20.dp)) }
            Column(Modifier.padding(start = 12.dp)) {
                Text(sig.humanName, color = c.fg, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(sig.description, color = c.muted, fontSize = 12.sp)
            }
        }

        // Status
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            StatusBadge(state.phase())
            SamplingRateBadge(if (values != null) "live" else "—")
        }

        // Hero: 3D phone for motion/orientation/magnetic; gauge/big-value for environmental.
        if (isEnv) {
            EnvironmentalHero(
                value = values?.getOrNull(0),
                unit = sig.unit,
                kind = sig.icon,
                modifier = Modifier.fillMaxWidth().aspectRatio(1.15f),
            )
        } else {
            Phone3DView(
                rotationVector = orientation,
                sensorVector = sensorVec,
                sensorVectorColor = c.accent,
                showAxes = if (isOrientation) showBody else true,
                showLabels = if (isOrientation) showLabels else true,
                showWorldFrame = isOrientation && showWorld,
                modifier = Modifier.fillMaxWidth().aspectRatio(1.05f),
            )
        }

        // Values
        if (isOrientation) {
            // View controls: body/world/labels toggles.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToggleChip("Body", showBody) { showBody = !showBody }
                ToggleChip("World", showWorld) { showWorld = !showWorld }
                ToggleChip("Labels", showLabels) { showLabels = !showLabels }
            }
            // Euler / Quaternion selector.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToggleChip("Euler Angles", !quatMode) { quatMode = false }
                ToggleChip("Quaternion", quatMode) { quatMode = true }
            }
            SsCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!quatMode) {
                        val r = Projection.rotationVectorToMatrix(orientation ?: floatArrayOf(0f, 0f, 0f))
                        val (roll, pitch, yaw) = Projection.eulerDeg(r)
                        SignalValue("Roll", Fmt.signed(roll, 1), "°", c.axisX)
                        SignalValue("Pitch", Fmt.signed(pitch, 1), "°", c.axisY)
                        SignalValue("Yaw", Fmt.value(yaw, 1), "°", c.axisZ)
                    } else {
                        val q = Projection.quatFromRotationVector(orientation ?: floatArrayOf(0f, 0f, 0f))
                        SignalValue("X", Fmt.signed(q[0], 4), "", c.axisX)
                        SignalValue("Y", Fmt.signed(q[1], 4), "", c.axisY)
                        SignalValue("Z", Fmt.signed(q[2], 4), "", c.axisZ)
                        SignalValue("W", Fmt.signed(q[3], 4), "")
                    }
                }
            }
        } else if (values != null && values.isNotEmpty()) {
            SsCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val labels = sig.componentLabels
                    val shown = minOf(values.size, labels.size, 3)
                    for (i in 0 until shown) {
                        val col = if (isVector) axisColors.getOrNull(i) else null
                        SignalValue("${sig.humanName} ${labels[i]}", Fmt.signed(values[i]), sig.unit, col)
                    }
                    if (isVector && values.size >= 3) {
                        SignalValue("Magnitude", Fmt.magnitude(floatArrayOf(values[0], values[1], values[2])), sig.unit)
                    }
                    if (!isVector && values.size == 1) {
                        SignalValue(sig.humanName, Fmt.value(values[0]), sig.unit)
                    }
                }
            }
        } else {
            SsCard(Modifier.fillMaxWidth()) {
                Text("Waiting for data…", color = c.muted, fontSize = 13.sp)
            }
        }

        // Real-time graph: multi-line for vectors, single line for environmental / single-value
        // sensors. Orientation keeps the 3D + tiles instead. A time-window selector sets how many
        // samples (derived from the selected rate) the graph shows.
        val graphComponents = when {
            isVector && values != null && values.size >= 3 -> 3
            !isOrientation && values != null && values.isNotEmpty() -> 1
            else -> 0
        }
        if (graphComponents > 0) {
            SectionHeader("Real-time")
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(5, 10, 20).forEach { w -> ToggleChip("${w}s", windowSec == w) { windowSec = w } }
            }
            val periodUs = sel.periodByHandle[handle] ?: 10_000
            val rateHz = if (periodUs > 0) 1_000_000f / periodUs else 200f
            val capacity = (windowSec * rateHz).toInt().coerceIn(30, 600)
            val gColors = if (graphComponents == 1) listOf(c.accent) else axisColors
            val gLabels = if (graphComponents == 1) listOf(sig.componentLabels.firstOrNull() ?: "value") else sig.componentLabels
            val gValues = if (graphComponents == 1 && values != null) floatArrayOf(values[0]) else values
            SsCard(Modifier.fillMaxWidth()) {
                MiniSignalGraph(
                    values = gValues,
                    colors = gColors,
                    labels = gLabels,
                    unit = sig.unit,
                    capacity = capacity,
                    modifier = Modifier.fillMaxWidth().height(190.dp),
                )
            }
        }

        // Sampling rate — only rates the device can actually deliver (period >= minDelayUs).
        SectionHeader("Sampling Rate")
        val currentPeriod = sel.periodByHandle[handle] ?: 10_000
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            vm.presets.forEach { (label, periodUs) ->
                val supported = periodUs == 0 || info.minDelayUs <= 0 || periodUs >= info.minDelayUs
                if (supported) {
                    ToggleChip(label, currentPeriod == periodUs) { vm.setPeriod(handle, periodUs) }
                }
            }
        }

        // Technical info
        SectionHeader("Sensor Information")
        SsCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoRow("Android Type", info.stringType.ifBlank { "type ${info.type}" })
                InfoRow("Vendor", info.vendor.ifBlank { "—" })
                InfoRow("Resolution", if (info.resolution > 0f) "${Fmt.value(info.resolution, 4)} ${sig.unit}" else "—")
                InfoRow("Maximum Range", if (info.maximumRange > 0f) "${Fmt.value(info.maximumRange, 2)} ${sig.unit}" else "—")
                InfoRow("Max Rate", if (info.maxFrequencyHz > 0f) Fmt.hz(info.maxFrequencyHz) else "—")
                InfoRow("Power", if (info.power > 0f) "${Fmt.value(info.power, 2)} mA" else "—")
            }
        }
    }
}

@Composable
private fun ToggleChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = Ss.colors
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 13.sp) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = c.surface,
            labelColor = c.muted,
            selectedContainerColor = c.accentSoft,
            selectedLabelColor = c.accent,
        ),
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Ss.colors.muted, fontSize = 13.sp)
        Text(value, style = SsType.mono, color = Ss.colors.fg, fontSize = 13.sp)
    }
}
