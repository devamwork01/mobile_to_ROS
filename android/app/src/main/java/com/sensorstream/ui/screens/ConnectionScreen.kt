package com.sensorstream.ui.screens

import com.sensorstream.ui.components.ServersList
import com.sensorstream.core.ConnectionErrors
import com.sensorstream.core.ServerList
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
    val servers by vm.servers.collectAsState()
    val serverName by vm.serverName.collectAsState()
    var manual by rememberSaveable { mutableStateOf(false) }
    val connectNotice by vm.connectNotice.collectAsState()
    val c = Ss.colors

    val phase = state.phase()
    val active = phase == StreamPhase.STREAMING || state.connecting || state.connected

    // Search the network only while this tab is open, the app is on screen (scanning stops when the
    // app goes to the background and starts again on return), and the phone isn't connected or
    // trying to connect. Leaving the tab disposes this and stops the scan.
    val lifecycleOwner = LocalLifecycleOwner.current
    var visible by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_START) visible = true
            if (e == Lifecycle.Event.ON_STOP) visible = false
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val scan = visible && !active
    DisposableEffect(scan) {
        vm.setServerScan(scan)
        onDispose { vm.setServerScan(false) }
    }
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

    val target = serverName ?: "${sel.host}:${sel.port}"
    // The Connect / Disconnect button sits in a fixed bar under the scrolling content, so status
    // text and the server list appearing or disappearing (e.g. on a reconnect) never move it.
    Column(Modifier.fillMaxSize()) {
    Column(
        Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
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
                    (if (state.connected || state.streaming) "Server · " else "Selected · ") +
                        (serverName?.let { "$it · " } ?: "") + "${sel.host}:${sel.port}",
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
                    Text(ConnectionErrors.friendly(it, target, sel.port), color = c.muted, fontSize = 12.sp)
                    Text("Details: View Diagnostics", color = c.faint, fontSize = 11.sp)
                }
            }
        }

        // Servers found on the network: tap one to choose where the phone connects.
        SectionHeader("Servers")
        val selectedKey = runCatching { ServerList.key(sel.host.trim(), sel.port.trim().toInt()) }.getOrNull()
        if (active) {
            Text("Disconnect to search for other servers.", color = c.muted, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 4.dp))
        } else {
            ServersList(servers, selectedKey, enabled = fieldsEnabled, onSelect = vm::selectServer)
        }

        // Manual address, for networks that block discovery.
        Text(
            if (manual) "Hide manual address" else "Enter address manually",
            color = c.accent, fontSize = 13.sp,
            modifier = Modifier.clickable { manual = !manual }.padding(horizontal = 4.dp, vertical = 2.dp),
        )
        if (manual) {
            OutlinedTextField(
                value = sel.host,
                onValueChange = vm::setHost,
                enabled = fieldsEnabled,
                label = { Text("Server IP") },
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

    // Fixed action bar: notices above the button, so they grow upward and the button stays put.
    Column(
        Modifier.fillMaxWidth().background(c.bg)
            .padding(horizontal = SsDims.screenPad, vertical = SsDims.gap),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        connectNotice?.let { Text(it.text, color = c.warn, fontSize = 12.sp) }
        if (!active && sel.enabled.isEmpty()) {
            Text("Select at least one sensor (Sensors tab) before connecting.", color = c.warn, fontSize = 12.sp)
        }
        PrimaryActionButton(
            text = if (active) "Disconnect" else "Connect & Stream",
            onClick = { vm.toggleStreaming() },
            enabled = active || sel.enabled.isNotEmpty(),
            danger = active,
        )
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
