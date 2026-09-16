package com.sensorstream.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sensorstream.ui.signal.SignalInfo
import com.sensorstream.ui.theme.Ss
import com.sensorstream.ui.theme.SsDims

/** Premium sensor row: icon, human name + description, rate·unit badge, toggle. Subtle
 *  highlight when enabled (Spec §17/§20). */
@Composable
fun SensorCard(
    info: SignalInfo,
    rateText: String,
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    onOpen: (() -> Unit)? = null,
    subtitle: String = info.description,
) {
    val c = Ss.colors
    Surface(
        modifier = if (onOpen != null) modifier.fillMaxWidth().clickable(onClick = onOpen) else modifier.fillMaxWidth(),
        color = if (enabled) c.accentSoft else c.surface,
        contentColor = c.fg,
        shape = RoundedCornerShape(SsDims.radiusSm),
        border = BorderStroke(1.dp, if (enabled) c.accent.copy(alpha = 0.5f) else c.line),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(11.dp))
                    .background(if (enabled) c.accent.copy(alpha = 0.18f) else c.surface2),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    SsIcons.forKey(info.icon), contentDescription = null,
                    tint = if (enabled) c.accent else c.muted, modifier = Modifier.size(20.dp),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(info.humanName, color = c.fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = c.muted, fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SamplingRateBadge(rateText)
                    if (info.unit.isNotBlank()) Text(info.unit, color = c.faint, fontSize = 11.sp)
                }
            }
            Switch(
                checked = enabled,
                onCheckedChange = { onToggle() },
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
}
