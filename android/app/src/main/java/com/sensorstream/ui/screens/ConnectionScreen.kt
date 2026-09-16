package com.sensorstream.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import com.sensorstream.ui.components.PrimaryActionButton
import com.sensorstream.ui.components.SectionHeader
import com.sensorstream.ui.components.SsCard
import com.sensorstream.ui.components.SsIcons
import com.sensorstream.ui.components.StatusBadge
import com.sensorstream.ui.components.StreamPhase
import com.sensorstream.ui.components.phase
import com.sensorstream.ui.nav.AppNav
import com.sensorstream.ui.nav.Screen
import com.sensorstream.ui.signal.Fmt
import com.sensorstream.ui.theme.Ss
import com.sensorstream.ui.theme.SsDims
import com.sensorstream.ui.theme.SsType
import com.sensorstream.vm.StreamViewModel

@Composable
fun ConnectionScreen(vm: StreamViewModel, nav: AppNav) {
    val state by vm.engineState.collectAsState()
    val sel by vm.sel.collectAsState()
    val discovering by vm.discovering.collectAsState()
    val c = Ss.colors

    val phase = state.phase()
    val active = phase == StreamPhase.STREAMING || state.connecting || state.connected
    val fieldsEnabled = !active // don't edit target while connected

    val tfColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = c.accent,
        unfocusedBorderColor = c.line,
        focusedTextColor = c.fg,
        unfocusedTextColor = c.fg,
        focusedLabelColor = c.accent,
        unfocusedLabelColor = c.muted,
        cursorColor = c.accent,
        disabledBorderColor = c.line,
        disabledTextColor = c.muted,
        disabledLabelColor = c.faint,
    )

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = SsDims.screenPad, vertical = SsDims.gap),
        verticalArrangement = Arrangement.spacedBy(SsDims.gap),
    ) {
        Text("Connection", color = c.fg, fontSize = 24.sp, fontWeight = FontWeight.Bold)

        // Status card
        SsCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatusBadge(phase)
                    Text(if (state.connected || state.streaming) "Wi-Fi" else "", color = c.faint, fontSize = 12.sp)
                }
                Text(
                    if (state.connected || state.streaming) "Laptop · ${sel.host}:${sel.port}"
                    else "Target · ${sel.host}:${sel.port}",
                    color = c.muted, fontSize = 13.sp,
                )
                if (state.connected || state.streaming) {
                    InfoLine("Latency", if (state.rttMs > 0f) "${Fmt.value(state.rttMs, 1)} ms" else "—")
                    InfoLine("Packets", state.sentPackets.toString())
                    InfoLine("Dropped", state.droppedSamples.toString())
                }
                state.error?.let {
                    Text("Connection failed", color = c.err, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 4.dp))
                    Text(it, color = c.muted, fontSize = 12.sp)
                }
            }
        }

        // Target (editable)
        SectionHeader("Laptop Address")
        OutlinedTextField(
            value = sel.host,
            onValueChange = vm::setHost,
            enabled = fieldsEnabled,
            label = { Text("Laptop IP") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            colors = tfColors,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = sel.port,
            onValueChange = vm::setPort,
            enabled = fieldsEnabled,
            label = { Text("Control Port") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            colors = tfColors,
            modifier = Modifier.fillMaxWidth(),
        )

        // Find laptop automatically
        OutlinedButton(
            onClick = { vm.discover() },
            enabled = fieldsEnabled && !discovering,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = c.accent),
        ) {
            if (discovering) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = c.accent)
                Spacer(Modifier.size(10.dp))
                Text("Searching for laptop…")
            } else {
                Icon(SsIcons.forKey("connection"), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(10.dp))
                Text("Find Laptop Automatically")
            }
        }

        Spacer(Modifier.height(4.dp))

        // Connect / Disconnect
        PrimaryActionButton(
            text = if (active) "Disconnect" else "Connect & Stream",
            onClick = { vm.toggleStreaming() },
            enabled = active || (sel.enabled.isNotEmpty() && sel.port.trim().toIntOrNull() != null),
            danger = active,
        )
        if (!active && sel.enabled.isEmpty()) {
            Text("Select at least one sensor (Sensors tab) before connecting.",
                color = c.warn, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        }

        // Diagnostics entry
        OutlinedButton(
            onClick = { nav.go(Screen.Diagnostics) },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = c.muted),
        ) {
            Icon(SsIcons.forKey("settings"), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(10.dp))
            Text("View Diagnostics")
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Ss.colors.muted, fontSize = 13.sp)
        Text(value, style = SsType.mono, color = Ss.colors.fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}
