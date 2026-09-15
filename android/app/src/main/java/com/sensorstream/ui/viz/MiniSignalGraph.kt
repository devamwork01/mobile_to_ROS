package com.sensorstream.ui.viz

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.sensorstream.ui.theme.Ss

/**
 * Compact rolling line graph (secondary to the 3D hero). Keeps a per-component ring buffer of the
 * most recent values it's handed; caller passes the latest sample each recomposition. Thin lines,
 * subtle grid, axis colors. No chart library.
 */
@Composable
fun MiniSignalGraph(
    values: FloatArray?,
    colors: List<Color>,
    modifier: Modifier = Modifier,
    capacity: Int = 140,
) {
    val n = colors.size
    val rings: List<SnapshotStateList<Float>> = remember(n) { List(n) { mutableStateListOf<Float>() } }
    if (values != null) {
        for (i in 0 until minOf(n, values.size)) {
            val ring = rings[i]
            ring.add(values[i])
            while (ring.size > capacity) ring.removeAt(0)
        }
    }
    val line = Ss.colors.line

    Canvas(modifier) {
        val w = size.width
        val h = size.height
        // grid: 3 horizontal lines
        for (g in 0..3) {
            val y = h * g / 3f
            drawLine(line, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }
        // shared scale across components for comparability
        var lo = Float.POSITIVE_INFINITY
        var hi = Float.NEGATIVE_INFINITY
        for (ring in rings) for (v in ring) { if (v < lo) lo = v; if (v > hi) hi = v }
        if (lo == Float.POSITIVE_INFINITY) return@Canvas
        if (hi - lo < 1e-3f) { hi += 1f; lo -= 1f }
        val span = hi - lo

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
}
