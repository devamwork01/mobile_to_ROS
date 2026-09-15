package com.sensorstream.ui.screens

import android.hardware.Sensor
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.sensorstream.ui.viz.MiniSignalGraph
import com.sensorstream.ui.viz.Phone3DView
import com.sensorstream.ui.viz.Projection
import com.sensorstream.ui.viz.Vec3
import com.sensorstream.vm.StreamViewModel

@Composable
fun SensorDetailScreen(vm: StreamViewModel, nav: AppNav, handle: Int) {
    val info = vm.catalog.firstOrNull { it.handle == handle } ?: run { nav.back(); return }
    val type = info.type
    val sig = SignalCatalog.of(type)
    val c = Ss.colors
    val state by vm.engineState.collectAsState()
    val previews by vm.preview.collectAsState()

    DisposableEffect(type) {
        vm.startPreview(type, Sensor.TYPE_ROTATION_VECTOR)
        onDispose { vm.stopPreview(type, Sensor.TYPE_ROTATION_VECTOR) }
    }

    val values = previews[type]
    val orientation = previews[Sensor.TYPE_ROTATION_VECTOR]
    val axisColors = listOf(c.axisX, c.axisY, c.axisZ)
    val isVector = sig.category == SensorCategory.MOTION || sig.category == SensorCategory.MAGNETIC
    val sensorVec = if (isVector && values != null && values.size >= 3) Vec3(values[0], values[1], values[2]) else null

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

        // Hero 3D
        Phone3DView(
            rotationVector = orientation,
            sensorVector = sensorVec,
            sensorVectorColor = c.accent,
            modifier = Modifier.fillMaxWidth().aspectRatio(1.05f),
        )

        // Values
        if (sig.category == SensorCategory.ORIENTATION) {
            val r = Projection.rotationVectorToMatrix(orientation ?: floatArrayOf(0f, 0f, 0f))
            val (roll, pitch, yaw) = Projection.eulerDeg(r)
            SsCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SignalValue("Roll", Fmt.signed(roll, 1), "°", c.axisX)
                    SignalValue("Pitch", Fmt.signed(pitch, 1), "°", c.axisY)
                    SignalValue("Yaw", Fmt.signed(yaw, 1), "°", c.axisZ)
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

        // Mini graph (vector sensors)
        if (isVector) {
            SectionHeader("Real-time")
            SsCard(Modifier.fillMaxWidth()) {
                MiniSignalGraph(values = values, colors = axisColors, modifier = Modifier.fillMaxWidth().aspectRatio(2.2f))
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
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Ss.colors.muted, fontSize = 13.sp)
        Text(value, style = SsType.mono, color = Ss.colors.fg, fontSize = 13.sp)
    }
}
