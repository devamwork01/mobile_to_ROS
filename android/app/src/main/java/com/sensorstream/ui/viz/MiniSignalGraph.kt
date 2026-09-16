package com.sensorstream.ui.viz

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.sensorstream.ui.signal.Fmt
import com.sensorstream.ui.theme.Ss
import com.sensorstream.ui.theme.SsType

/**
 * Compact rolling line graph (secondary to the 3D hero). Keeps a per-component ring buffer of the
 * most recent values it's handed; caller passes the latest sample each recomposition. Shows a legend
 * (which color is which component + its live value) and the current min/max magnitude of the window
 * so the plotted signal is readable, not just decorative.
 */
@Composable
fun MiniSignalGraph(
    values: FloatArray?,
    colors: List<Color>,
    modifier: Modifier = Modifier,
    labels: List<String> = emptyList(),
    unit: String = "",
    capacity: Int = 140,
) {
    val c = Ss.colors
    val n = colors.size
    val rings: List<SnapshotStateList<Float>> = remember(n) { List(n) { mutableStateListOf<Float>() } }
    if (values != null) {
        for (i in 0 until minOf(n, values.size)) {
            val ring = rings[i]
            ring.add(values[i])
            while (ring.size > capacity) ring.removeAt(0)
        }
    }

    // Shared scale across components for comparability, computed here so it can be labeled.
    var lo = Float.POSITIVE_INFINITY
    var hi = Float.NEGATIVE_INFINITY
    for (ring in rings) for (v in ring) { if (v < lo) lo = v; if (v > hi) hi = v }
    val hasData = lo != Float.POSITIVE_INFINITY
    if (!hasData) { lo = 0f; hi = 1f }
    if (hi - lo < 1e-3f) { hi += 1f; lo -= 1f }
    val span = hi - lo
    val unitSuffix = if (unit.isNotBlank()) " $unit" else ""

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Legend: colored dot + component label + live value.
        if (labels.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                for (i in 0 until n) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(colors.getOrElse(i) { Color.Gray }))
                        Text(labels.getOrElse(i) { "" }, color = c.muted, fontSize = 11.sp)
                        values?.getOrNull(i)?.let {
                            Text(Fmt.signed(it), style = SsType.mono, color = c.fg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        Box(Modifier.fillMaxWidth().weight(1f)) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                for (g in 0..3) {
                    val y = h * g / 3f
                    drawLine(c.line, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
                }
                rings.forEachIndexed { ci, ring ->
                    if (ring.size < 2) return@forEachIndexed
                    val path = Path()
                    ring.forEachIndexed { i, v ->
                        val x = w * i / (capacity - 1).toFloat()
                        val y = h - ((v - lo) / span) * h
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, colors.getOrElse(ci) { Color.Gray }, style = Stroke(width = 2.dp.toPx()))
                }
            }
            // Magnitude scale: max at top-left, min at bottom-left.
            Text(
                Fmt.value(hi, 2) + unitSuffix,
                style = SsType.mono, color = c.faint, fontSize = 10.sp,
                modifier = Modifier.align(Alignment.TopStart),
            )
            Text(
                Fmt.value(lo, 2) + unitSuffix,
                style = SsType.mono, color = c.faint, fontSize = 10.sp,
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }
    }
}
