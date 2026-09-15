package com.sensorstream.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sensorstream.stream.EngineState
import com.sensorstream.ui.theme.Ss
import com.sensorstream.ui.theme.SsDims
import com.sensorstream.ui.theme.SsType

enum class StreamPhase(val label: String) {
    READY("Ready"),
    CONNECTING("Connecting"),
    CONNECTED("Connected"),
    STREAMING("Streaming"),
    RECONNECTING("Reconnecting"),
    DISCONNECTED("Disconnected"),
    ERROR("Error"),
}

/** Derive a user-facing phase from the engine's boolean state (Spec §12). */
fun EngineState.phase(): StreamPhase = when {
    error != null && !connected && !streaming -> StreamPhase.ERROR
    streaming -> StreamPhase.STREAMING
    connected -> StreamPhase.CONNECTED
    connecting -> StreamPhase.CONNECTING
    else -> StreamPhase.READY
}

@Composable
private fun StreamPhase.color(): Color = when (this) {
    StreamPhase.STREAMING, StreamPhase.CONNECTED -> Ss.colors.ok
    StreamPhase.CONNECTING, StreamPhase.RECONNECTING -> Ss.colors.warn
    StreamPhase.ERROR, StreamPhase.DISCONNECTED -> Ss.colors.err
    StreamPhase.READY -> Ss.colors.muted
}

/** Dot + text status. Never color-only (Spec §12/§47): the label always accompanies the dot. */
@Composable
fun StatusBadge(phase: StreamPhase, modifier: Modifier = Modifier) {
    val c = phase.color()
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(c))
        Text(phase.label.uppercase(), color = c, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = 0.6.sp)
    }
}

@Composable
fun SsCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        color = Ss.colors.surface,
        contentColor = Ss.colors.fg,
        shape = RoundedCornerShape(SsDims.radius),
        border = BorderStroke(1.dp, Ss.colors.line),
        shadowElevation = 2.dp,
    ) { Box(Modifier.padding(SsDims.cardPad)) { content() } }
}

@Composable
fun PrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    val bg = if (danger) Ss.colors.err else Ss.colors.accent
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 54.dp),
        shape = RoundedCornerShape(SsDims.radius),
        colors = ButtonDefaults.buttonColors(
            containerColor = bg,
            contentColor = Color.White,
            disabledContainerColor = Ss.colors.surface2,
            disabledContentColor = Ss.colors.faint,
        ),
    ) { Text(text.uppercase(), fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp) }
}

@Composable
fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    SsCard(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label.uppercase(), color = Ss.colors.faint, fontSize = 11.sp, letterSpacing = 0.8.sp)
            Text(value, style = SsType.mono, color = Ss.colors.fg, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier.padding(start = 4.dp, top = 4.dp, bottom = 6.dp),
        color = Ss.colors.muted,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
    )
}

@Composable
fun SamplingRateBadge(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = Ss.colors.surface2,
        contentColor = Ss.colors.muted,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Ss.colors.line),
    ) {
        Text(text, style = SsType.mono, fontSize = 12.sp, color = Ss.colors.muted,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

/** A labeled signal value with optional axis color for the label dot. */
@Composable
fun SignalValue(label: String, value: String, unit: String, axisColor: Color? = null, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (axisColor != null) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(axisColor))
            Spacer(Modifier.size(8.dp))
        }
        Text(label, color = Ss.colors.muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, style = SsType.mono, color = Ss.colors.fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.size(6.dp))
        Text(unit, color = Ss.colors.faint, fontSize = 12.sp)
    }
}

@Composable
fun SensorStreamHeader(title: String, subtitle: String? = null, onSettings: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Ss.colors.fg, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            if (subtitle != null) Text(subtitle, color = Ss.colors.muted, fontSize = 13.sp)
        }
        if (onSettings != null) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(Ss.colors.surface2)
                    .clickable(onClick = onSettings),
                contentAlignment = Alignment.Center,
            ) { Icon(SsIcons.forKey("settings"), contentDescription = "Settings", tint = Ss.colors.muted, modifier = Modifier.size(20.dp)) }
        }
    }
}
