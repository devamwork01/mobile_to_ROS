package com.sensorstream.ui.screens

import android.hardware.Sensor
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sensorstream.ui.components.PrimaryActionButton
import com.sensorstream.ui.components.SectionHeader
import com.sensorstream.ui.components.SensorStreamHeader
import com.sensorstream.ui.components.SsCard
import com.sensorstream.ui.components.StatusBadge
import com.sensorstream.ui.components.SamplingRateBadge
import com.sensorstream.ui.components.MetricCard
import com.sensorstream.ui.components.StreamPhase
import com.sensorstream.ui.components.phase
import com.sensorstream.ui.nav.AppNav
import com.sensorstream.ui.nav.Screen
import com.sensorstream.ui.signal.Fmt
import com.sensorstream.ui.signal.SignalCatalog
import com.sensorstream.ui.theme.Ss
import com.sensorstream.ui.theme.SsDims
import com.sensorstream.ui.theme.SsType
import com.sensorstream.ui.viz.Phone3DView
import com.sensorstream.vm.StreamViewModel

@Composable
fun HomeScreen(vm: StreamViewModel, nav: AppNav) {
    val state by vm.engineState.collectAsState()
    val sel by vm.sel.collectAsState()
    val orientation by vm.orientationPreview.collectAsState()
    val c = Ss.colors

    // Drive the hero phone from a local orientation preview so it responds to device motion whether
    // or not we're streaming. Registration is ref-counted + released when Home leaves composition.
    DisposableEffect(Unit) {
        vm.startOrientationPreview()
        onDispose { vm.stopOrientationPreview() }
    }

    val phase = state.phase()
    val streamingLike = phase == StreamPhase.STREAMING || state.connecting || state.connected

    // Total requested rate = sum of per-sensor requested Hz (period 0 = "Max", excluded from the sum).
    val totalHz = sel.enabled.sumOf {
        val p = vm.periodOf(it)
        if (p > 0) (1_000_000 / p).toLong() else 0L
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = SsDims.screenPad, vertical = SsDims.gap),
        verticalArrangement = Arrangement.spacedBy(SsDims.gap),
    ) {
        SensorStreamHeader(
            title = "SensorStream",
            subtitle = "Real-Time Mobile Sensor Telemetry",
            onSettings = { nav.go(Screen.Settings) },
        )

        // Hero pseudo-3D phone.
        Phone3DView(
            rotationVector = orientation,
            modifier = Modifier.fillMaxWidth().aspectRatio(1.1f),
        )

        // Connection card.
        SsCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatusBadge(phase)
                    Text(if (state.connected || state.streaming) "Wi-Fi" else "—", color = c.faint, fontSize = 12.sp)
                }
                Text(
                    if (state.connected || state.streaming) "Laptop · ${sel.host}" else "Not connected · ${sel.host}",
                    color = c.muted, fontSize = 13.sp,
                )
                Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Latency", color = c.muted, fontSize = 13.sp)
                    Text(
                        if (state.rttMs > 0f) "${Fmt.value(state.rttMs, 1)} ms" else "—",
                        style = SsType.mono, color = c.fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
                state.error?.let { Text(it, color = c.err, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp)) }
            }
        }

        // Metric cards.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SsDims.gap)) {
            MetricCard("Active Sensors", sel.enabled.size.toString(), Modifier.weight(1f))
            MetricCard("Total Rate", if (totalHz > 0) "$totalHz Hz" else "—", Modifier.weight(1f))
        }

        // Active-sensor summary or empty state.
        if (sel.enabled.isEmpty()) {
            SsCard(Modifier.fillMaxWidth()) {
                Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text("No active sensors", color = c.fg, fontWeight = FontWeight.SemiBold)
                    Text("Select one or more sensors to begin streaming.", color = c.muted, fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 6.dp))
                    Spacer(Modifier.height(4.dp))
                    PrimaryActionButton("Configure Sensors", onClick = { nav.go(Screen.Sensors) })
                }
            }
        } else {
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    SectionHeader("Active Sensors")
                    Text("View all", color = c.accent, fontSize = 12.sp,
                        modifier = Modifier.padding(end = 4.dp).clickableText { nav.go(Screen.Sensors) })
                }
                SsCard(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        val shown = sel.enabled.toList().take(5)
                        shown.forEach { handle ->
                            val info = vm.catalog.firstOrNull { it.handle == handle }
                            val type = info?.type ?: return@forEach
                            val sig = SignalCatalog.of(type)
                            val p = vm.periodOf(handle)
                            val hz = if (p > 0) "${1_000_000 / p} Hz" else "Max"
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text(sig.humanName, color = c.fg, fontSize = 14.sp)
                                SamplingRateBadge(hz)
                            }
                        }
                        if (sel.enabled.size > 5) {
                            Text("+ ${sel.enabled.size - 5} more", color = c.faint, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Primary action.
        PrimaryActionButton(
            text = if (streamingLike) "Stop Streaming" else "Start Streaming",
            onClick = { vm.toggleStreaming() },
            enabled = sel.enabled.isNotEmpty() || streamingLike,
            danger = streamingLike,
        )
        Spacer(Modifier.height(4.dp))
    }
}

// Lightweight tappable text.
private fun Modifier.clickableText(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)
