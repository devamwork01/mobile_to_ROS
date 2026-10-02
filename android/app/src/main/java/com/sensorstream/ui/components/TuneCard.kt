package com.sensorstream.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sensorstream.core.TunePhase
import com.sensorstream.ui.theme.Ss
import com.sensorstream.ui.theme.SsType
import com.sensorstream.vm.StreamViewModel

/**
 * Guided filter tuning (server --filter): what to do now, the countdown, then what was tuned.
 * Full size on Home, compact on the other tabs.
 */
@Composable
fun TuneCard(vm: StreamViewModel, compact: Boolean, modifier: Modifier = Modifier) {
    val ui by vm.tune.collectAsState()
    val t = ui ?: return
    val c = Ss.colors
    val names = t.prompt.sensors.joinToString(", ") { it.name }
    val title = if (t.prompt.reason == "new_sensor") "Tuning the $names filter" else "Tuning filters"
    SsCard(modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = c.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            when (val ph = t.phase) {
                is TunePhase.Lead -> {
                    Text("Pick up the phone and move it the way you'll use it", color = c.fg, fontSize = if (compact) 13.sp else 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("Starting in ${ph.secondsLeft}…", style = SsType.mono, color = c.muted, fontSize = if (compact) 13.sp else 22.sp)
                }
                is TunePhase.Capture -> {
                    Text("Keep moving the phone", color = c.fg, fontSize = if (compact) 13.sp else 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("${ph.secondsLeft} s", style = SsType.mono, color = c.fg, fontSize = if (compact) 16.sp else 40.sp, fontWeight = FontWeight.Bold)
                }
                TunePhase.Waiting -> {
                    Text("Done — you can put the phone down", color = c.fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Working out the filters…", color = c.faint, fontSize = 12.sp)
                        // No answer can come (server restarted, another phone took over): never trap the card.
                        Text("Dismiss", color = c.muted, fontSize = 13.sp, modifier = Modifier.clickable { vm.dismissTune() })
                    }
                }
                is TunePhase.Result -> {
                    ph.sensors.forEach { r ->
                        Text("${if (r.ok) "✓" else "✗"} ${r.name}: ${r.summary ?: r.message ?: ""}",
                            color = if (r.ok) c.fg else c.warn, fontSize = 12.sp)
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Re-tune", color = c.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable { vm.dismissTune(); vm.retuneFilters() })
                        Text("Dismiss", color = c.muted, fontSize = 13.sp, modifier = Modifier.clickable { vm.dismissTune() })
                    }
                }
            }
        }
    }
}
