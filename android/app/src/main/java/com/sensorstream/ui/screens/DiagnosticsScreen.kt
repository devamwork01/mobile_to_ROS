package com.sensorstream.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sensorstream.ui.components.MetricCard
import com.sensorstream.ui.components.SectionHeader
import com.sensorstream.ui.components.SsCard
import com.sensorstream.ui.components.StatusBadge
import com.sensorstream.ui.components.StreamPhase
import com.sensorstream.ui.components.phase
import com.sensorstream.ui.nav.AppNav
import com.sensorstream.ui.signal.Fmt
import com.sensorstream.ui.theme.Ss
import com.sensorstream.ui.theme.SsDims
import com.sensorstream.ui.theme.SsType

/**
 * Live link + pipeline health. Read-only view over [com.sensorstream.stream.EngineState];
 * surfaces latency, throughput, loss/backfill reliability, and the on-phone recording buffer.
 */
@Composable
fun DiagnosticsScreen(vm: com.sensorstream.vm.StreamViewModel, nav: AppNav) {
    val state by vm.engineState.collectAsState()
    val sel by vm.sel.collectAsState()
    val c = Ss.colors
    val phase = state.phase()
    val live = state.connected || state.streaming

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
                Text("Diagnostics", color = c.fg, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Live link & pipeline health", color = c.muted, fontSize = 12.sp)
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            StatusBadge(phase)
            Text(if (live) "Wi-Fi · ${sel.host}" else "Not connected", color = c.faint, fontSize = 12.sp)
        }

        state.error?.let { err ->
            SsCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Last error", color = c.err, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(err, color = c.muted, fontSize = 12.sp)
                }
            }
        }

        if (!live) {
            SsCard(Modifier.fillMaxWidth()) {
                Text(
                    "Connect and stream to see live latency and throughput. " +
                        "The on-phone buffer below persists across reconnects.",
                    color = c.muted, fontSize = 13.sp,
                )
            }
        }

        // Top-line metrics
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SsDims.gap)) {
            MetricCard(
                label = "Latency",
                value = if (live && state.rttMs > 0f) "${Fmt.value(state.rttMs, 1)} ms" else "—",
                modifier = Modifier.weight(1f),
            )
            MetricCard(
                label = "Send Rate",
                value = if (live) "${Fmt.value(state.sendBps / 1024f, 1)} KB/s" else "—",
                modifier = Modifier.weight(1f),
            )
        }

        // Link details
        SectionHeader("Link")
        SsCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DiagRow("Target", "${sel.host}:${sel.port}")
                DiagRow("Device ID", if (state.deviceId != 0) "0x%08X".format(state.deviceId) else "—")
                DiagRow("UDP port", if (state.udpPort != 0) state.udpPort.toString() else "—")
                DiagRow("Packets sent", state.sentPackets.toString())
            }
        }

        // Reliability — problem counters turn red/amber when non-zero
        SectionHeader("Reliability")
        SsCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DiagRow(
                    "Dropped (recoverable)", state.droppedSamples.toString(),
                    valueColor = if (state.droppedSamples > 0) c.warn else null,
                )
                DiagRow("Backfill served", state.backfillServed.toString(),
                    valueColor = if (state.backfillServed > 0) c.ok else null)
                DiagRow(
                    "Fan-out drops", state.recFanoutDropped.toString(),
                    valueColor = if (state.recFanoutDropped > 0) c.err else null,
                )
                DiagRow(
                    "Write errors", state.recWriteErrors.toString(),
                    valueColor = if (state.recWriteErrors > 0) c.err else null,
                )
            }
        }

        // On-phone recording buffer
        SectionHeader("On-Phone Buffer")
        SsCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DiagRow("Buffered", "${Fmt.value(state.recBytes / 1_048_576f, 1)} MB")
                DiagRow("Oldest record", if (state.recOldestAgeMs > 0) "${Fmt.value(state.recOldestAgeMs / 1000f, 0)} s ago" else "—")
                DiagRow(
                    "Permanent gaps (pruned)", state.recDropped.toString(),
                    valueColor = if (state.recDropped > 0) c.warn else null,
                )
            }
        }

        Text(
            "\"Dropped (recoverable)\" are network-channel samples the laptop re-requests via backfill. " +
                "Fan-out drops and write errors should stay at zero.",
            color = c.faint, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun DiagRow(label: String, value: String, valueColor: Color? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Ss.colors.muted, fontSize = 13.sp)
        Text(value, style = SsType.mono, color = valueColor ?: Ss.colors.fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}
