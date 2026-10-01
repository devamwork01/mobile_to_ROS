package com.sensorstream.ui.viz

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sensorstream.core.PressureGauge
import com.sensorstream.ui.signal.Fmt
import com.sensorstream.ui.theme.Ss

/**
 * Environmental hero: a circular gauge for bounded quantities (pressure), else a large numeric
 * readout. Chosen per the physical meaning of the signal (Spec §27) — no 3D phone for these.
 */
@Composable
fun EnvironmentalHero(value: Float?, unit: String, kind: String, modifier: Modifier = Modifier) {
    when (kind) {
        "pressure" -> Gauge(value, unit, modifier = modifier)
        else -> BigValue(value, unit, modifier)
    }
}

@Composable
private fun BigValue(value: Float?, unit: String, modifier: Modifier) {
    val c = Ss.colors
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (value != null) Fmt.value(value, 1) else "—",
                color = c.fg, fontSize = 56.sp, fontWeight = FontWeight.SemiBold,
            )
            Text(unit, color = c.muted, fontSize = 16.sp)
        }
    }
}

@Composable
private fun Gauge(value: Float?, unit: String, modifier: Modifier) {
    val c = Ss.colors
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 16.dp.toPx()
            val pad = stroke
            val d = minOf(size.width, size.height) - pad * 2
            val topLeft = Offset((size.width - d) / 2f, (size.height - d) / 2f + d * 0.08f)
            val sz = Size(d, d)
            val start = 135f
            val sweepFull = 270f
            // track
            drawArc(c.line, start, sweepFull, false, topLeft = topLeft, size = sz,
                style = Stroke(width = stroke, cap = StrokeCap.Round))
            // value arc
            if (value != null) {
                val frac = PressureGauge.fraction(value)
                drawArc(c.accent, start, sweepFull * frac, false, topLeft = topLeft, size = sz,
                    style = Stroke(width = stroke, cap = StrokeCap.Round))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (value != null) Fmt.value(value, 1) else "—",
                color = c.fg, fontSize = 44.sp, fontWeight = FontWeight.SemiBold)
            Text(unit, color = c.muted, fontSize = 15.sp)
            if (value != null) {
                Text(
                    "≈ ${Fmt.value(PressureGauge.altitudeM(value), 0)} m altitude",
                    color = c.faint, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        // Scale ends, under the arc's two tips.
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth(0.62f).padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("${PressureGauge.MIN_HPA.toInt()}", color = c.faint, fontSize = 11.sp)
            Text("${PressureGauge.MAX_HPA.toInt()}", color = c.faint, fontSize = 11.sp)
        }
    }
}
