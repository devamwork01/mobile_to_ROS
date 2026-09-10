package com.sensorstream.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sensorstream.core.SensorInfo
import com.sensorstream.vm.StreamViewModel

@Composable
fun StreamScreen(vm: StreamViewModel) {
    val engine by vm.engineState.collectAsState()
    val sel by vm.sel.collectAsState()
    val live by vm.live.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("SENSOR STREAMER", style = MaterialTheme.typography.titleLarge)

        ConnectionCard(vm, engine, sel)

        Text(
            "Sensors (${sel.enabled.size}/${vm.catalog.size} enabled)",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(vm.catalog, key = { it.handle }) { info ->
                SensorRow(
                    info = info,
                    enabled = info.handle in sel.enabled,
                    periodUs = sel.periodByHandle[info.handle] ?: 10_000,
                    live = live[info.handle],
                    streaming = engine.streaming,
                    presets = vm.presets,
                    onToggle = { vm.toggle(info.handle) },
                    onPeriod = { vm.setPeriod(info.handle, it) },
                )
            }
        }
    }
}

@Composable
private fun ConnectionCard(vm: StreamViewModel, engine: com.sensorstream.stream.EngineState, sel: StreamViewModel.Selections) {
    val active = engine.connecting || engine.connected || engine.streaming
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = sel.host,
                    onValueChange = vm::setHost,
                    label = { Text("Laptop IP") },
                    singleLine = true,
                    enabled = !active,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = sel.port,
                    onValueChange = vm::setPort,
                    label = { Text("Ctrl port") },
                    singleLine = true,
                    enabled = !active,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(110.dp),
                )
            }
            val discovering by vm.discovering.collectAsState()
            TextButton(onClick = vm::discover, enabled = !active && !discovering) {
                Text(if (discovering) "Searching for laptop…" else "Find laptop automatically")
            }
            Button(onClick = vm::toggleStreaming, modifier = Modifier.fillMaxWidth()) {
                Text(if (active) "Stop" else "Connect & Stream")
            }
            val status = when {
                engine.streaming -> "● Streaming  •  id=0x%08x  •  RTT %.0f ms".format(engine.deviceId, engine.rttMs)
                engine.connected -> "Connected (id=0x%08x) — starting telemetry…".format(engine.deviceId)
                engine.connecting -> "Connecting…"
                else -> "○ Idle"
            }
            Text(
                status,
                fontFamily = FontFamily.Monospace,
                color = if (engine.streaming) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "packets %d   dropped %d   udp :%d".format(engine.sentPackets, engine.droppedSamples, engine.udpPort),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
            engine.error?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun SensorRow(
    info: SensorInfo,
    enabled: Boolean,
    periodUs: Int,
    live: StreamViewModel.Live?,
    streaming: Boolean,
    presets: List<Pair<String, Int>>,
    onToggle: () -> Unit,
    onPeriod: (Int) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = enabled, onCheckedChange = { onToggle() })
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(info.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "type ${info.type} • ${info.units.ifBlank { "—" }}" +
                            if (info.maxFrequencyHz > 0) " • ≤${info.maxFrequencyHz.toInt()} Hz" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                RateSelector(periodUs, presets, onPeriod)
            }
            if (streaming && enabled && live != null) {
                val vals = live.values.joinToString("  ") { "%+.2f".format(it) }
                Text(
                    "%.0f Hz   %s".format(live.hz, vals),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun RateSelector(periodUs: Int, presets: List<Pair<String, Int>>, onPeriod: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = presets.firstOrNull { it.second == periodUs }?.first ?: "custom"
    Box {
        Text(
            label,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { expanded = true }.padding(8.dp),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            presets.forEach { (name, period) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { onPeriod(period); expanded = false })
            }
        }
    }
}
