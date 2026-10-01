package com.sensorstream.ui.viz

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.sensorstream.vm.TracePoint

/**
 * Compact rolling line graph with an interactive legend. Caller passes the latest sample each
 * recomposition; a new point is recorded only when a new sample array arrives, timestamped on
 * arrival. [windowMs] is the visible time span (e.g. 5 s / 10 s / 20 s) — the x-axis is time, so
 * the window is exact regardless of sensor rate or recomposition cadence. Tap a legend entry to
 * isolate that signal (tap again for "all"). Shows the current window's min/max scale.
 */
@Composable
fun MiniSignalGraph(
    values: FloatArray?,
    colors: List<Color>,
    modifier: Modifier = Modifier,
    labels: List<String> = emptyList(),
    unit: String = "",
    windowMs: Long = 10_000L,
    /**
     * Phone-side filter output at full rate (same uptime clock as the raw buffer); drawn bold over
     * the raw trace, which fades. Axis i of a point is drawn as component i.
     */
    filtered: List<TracePoint>? = null,
) {
    val c = Ss.colors
    val n = colors.size
    val buffer = remember(n) { TimeWindowBuffer() }
    val lastSample = remember(n) { arrayOfNulls<FloatArray>(1) }
    // Each sensor event delivers a fresh array; unrelated recompositions re-pass the same one.
    if (values != null && values !== lastSample[0]) {
        lastSample[0] = values
        buffer.add(SystemClock.uptimeMillis(), values.copyOf(minOf(n, values.size)))
    }
    val pts = buffer.visible(windowMs)
    val fPts = filtered ?: emptyList()

    // -1 = show all; otherwise the isolated component index.
    var selected by remember(n) { mutableIntStateOf(-1) }
    val shown: (Int) -> Boolean = { i -> selected == -1 || selected == i }

    // Shared scale over the shown components so the isolated signal fills the view.
    var lo = Float.POSITIVE_INFINITY
    var hi = Float.NEGATIVE_INFINITY
    for (p in pts) for (i in 0 until minOf(n, p.v.size)) if (shown(i)) {
        val v = p.v[i]; if (v < lo) lo = v; if (v > hi) hi = v
    }
    val hasData = lo != Float.POSITIVE_INFINITY
    if (!hasData) { lo = 0f; hi = 1f }
    if (hi - lo < 1e-3f) { hi += 1f; lo -= 1f }
    val span = hi - lo
    val unitSuffix = if (unit.isNotBlank()) " $unit" else ""

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Legend: tap to isolate / show-all. Non-selected entries dim when one is isolated.
        if (labels.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (i in 0 until n) {
                    val active = shown(i)
                    Row(
                        Modifier.clip(RoundedCornerShape(8.dp))
                            .background(if (selected == i) colors.getOrElse(i) { Color.Gray }.copy(alpha = 0.16f) else Color.Transparent)
                            .clickable { selected = if (selected == i) -1 else i }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Box(Modifier.size(8.dp).clip(CircleShape)
                            .background(colors.getOrElse(i) { Color.Gray }.copy(alpha = if (active) 1f else 0.35f)))
                        Text(labels.getOrElse(i) { "" }, color = if (active) c.muted else c.faint, fontSize = 11.sp)
                        values?.getOrNull(i)?.let {
                            Text(Fmt.signed(it), style = SsType.mono, color = if (active) c.fg else c.faint,
                                fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
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
                if (pts.size >= 2) {
                    // Right edge = newest sample; left edge = newest - window.
                    val tStart = pts.last().tMs - windowMs
                    for (ci in 0 until n) {
                        if (!shown(ci)) continue
                        val path = Path()
                        var started = false
                        for (p in pts) {
                            if (ci >= p.v.size) continue
                            val x = w * (p.tMs - tStart) / windowMs.toFloat()
                            val y = h - ((p.v[ci] - lo) / span) * h
                            if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
                        }
                        val col = colors.getOrElse(ci) { Color.Gray }
                        if (fPts.size >= 2) {
                            drawPath(path, col.copy(alpha = 0.35f), style = Stroke(width = 1.5.dp.toPx()))
                            val fPath = Path()
                            var fStarted = false
                            for (p in fPts) {
                                val v = p.filtered?.getOrNull(ci)
                                if (v == null || !v.isFinite() || p.tMs < tStart) continue
                                val x = w * (p.tMs - tStart) / windowMs.toFloat()
                                val y = h - ((v - lo) / span) * h
                                if (!fStarted) { fPath.moveTo(x, y); fStarted = true } else fPath.lineTo(x, y)
                            }
                            drawPath(fPath, col, style = Stroke(width = 2.5.dp.toPx()))
                        } else {
                            drawPath(path, col, style = Stroke(width = 2.dp.toPx()))
                        }
                    }
                }
            }
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
            Text(
                "${windowMs / 1000}s",
                style = SsType.mono, color = c.faint, fontSize = 10.sp,
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
    }
}
