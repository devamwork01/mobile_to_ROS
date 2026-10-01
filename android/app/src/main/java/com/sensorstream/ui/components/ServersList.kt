package com.sensorstream.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sensorstream.core.ServerKind
import com.sensorstream.core.ServerList
import com.sensorstream.ui.signal.Fmt
import com.sensorstream.ui.theme.Ss
import com.sensorstream.ui.theme.SsType

private fun ServerKind.icon(): ImageVector = when (this) {
    ServerKind.LAPTOP -> Icons.Filled.Laptop
    ServerKind.DESKTOP -> Icons.Filled.Computer
    ServerKind.RASPBERRY_PI -> Icons.Filled.DeveloperBoard
    ServerKind.UNKNOWN -> Icons.Filled.Dns
}

private fun ServerKind.label(): String? = when (this) {
    ServerKind.LAPTOP -> "Laptop"
    ServerKind.DESKTOP -> "Desktop"
    ServerKind.RASPBERRY_PI -> "Raspberry Pi"
    ServerKind.UNKNOWN -> null
}

/**
 * Every SensorStream server found on the network: type icon, name, type/OS/IP, signal bars and
 * round trip. Tap a row to select it ([selectedKey] is highlighted). Scrolls on its own when long.
 */
@Composable
fun ServersList(
    rows: List<ServerList.Row>,
    selectedKey: String?,
    enabled: Boolean,
    onSelect: (ServerList.Row) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Ss.colors
    SsCard(modifier.fillMaxWidth()) {
        if (rows.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = c.accent)
                Column {
                    Text("Searching for servers…", color = c.fg, fontSize = 14.sp)
                    Text(
                        "Start the SensorStream server on a computer on this Wi-Fi, or enter its address below.",
                        color = c.faint, fontSize = 12.sp,
                    )
                }
            }
            return@SsCard
        }
        Column(
            Modifier.heightIn(max = 330.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            rows.forEach { r -> ServerRow(r, r.key == selectedKey, enabled) { onSelect(r) } }
        }
    }
}

@Composable
private fun ServerRow(r: ServerList.Row, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val c = Ss.colors
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) c.accent.copy(alpha = 0.12f) else c.surface2, shape)
            .border(1.dp, if (selected) c.accent else c.line, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .alpha(if (r.stale) 0.45f else 1f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(r.kind.icon(), contentDescription = r.kind.label() ?: "Server",
            tint = if (selected) c.accent else c.muted, modifier = Modifier.size(26.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(r.name, color = c.fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (r.lastUsed) {
                    Text("Last used", color = c.accent, fontSize = 10.sp,
                        modifier = Modifier.background(c.accent.copy(alpha = 0.15f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
                }
            }
            listOfNotNull(r.kind.label(), r.os).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                Text(it, color = c.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("${r.host}:${r.port}", style = SsType.mono, color = c.faint, fontSize = 11.sp, maxLines = 1)
            if (r.stale) Text("Not responding", color = c.warn, fontSize = 11.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            SignalBars(if (r.stale) 0 else r.bars)
            Text(r.rttMs?.let { "${Fmt.value(it, 0)} ms" } ?: "—", style = SsType.mono, color = c.muted, fontSize = 11.sp)
        }
    }
}

/** 4 rising bars, [bars] of them lit. */
@Composable
fun SignalBars(bars: Int, modifier: Modifier = Modifier) {
    val c = Ss.colors
    val lit = when {
        bars >= 3 -> c.ok
        bars == 2 -> c.warn
        else -> c.err
    }
    Canvas(modifier.size(width = 26.dp, height = 18.dp)) {
        val w = size.width / 4f
        for (i in 0 until 4) {
            val h = size.height * (i + 1) / 4f
            drawRoundRect(
                color = if (i < bars) lit else c.line,
                topLeft = Offset(i * w + w * 0.15f, size.height - h),
                size = Size(w * 0.7f, h),
                cornerRadius = CornerRadius(2f, 2f),
            )
        }
    }
}
