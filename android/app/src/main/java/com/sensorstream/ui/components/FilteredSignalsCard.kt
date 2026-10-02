package com.sensorstream.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sensorstream.core.filter.FilterConfigCodec
import com.sensorstream.core.filter.FilterStatus
import com.sensorstream.ui.signal.SignalCatalog
import com.sensorstream.ui.theme.Ss
import com.sensorstream.ui.theme.SsType
import com.sensorstream.vm.FilterTap
import com.sensorstream.vm.FilterTrace
import com.sensorstream.vm.StreamViewModel
import com.sensorstream.vm.TracePoint
import java.util.Locale

/** 3 significant digits (e.g. 0.0124, 3.11e-05); "—" when unknown. */
fun sig3(x: Float): String = if (x.isNaN()) "—" else String.format(Locale.US, "%.3g", x)

/**
 * Home card: every sensor with a laptop-configured filter, filtered on the phone itself.
 * Per sensor a live 10 s graph (raw faint, filtered bold; tap to switch axis) and the noise
 * before -> after. Keeps the phone's filter tap alive while it is on screen.
 */
@Composable
fun FilteredSignalsCard(vm: StreamViewModel, modifier: Modifier = Modifier) {
    val sensors by vm.filteredSensors.collectAsState()
    val traces by vm.filterTraces.collectAsState()
    val c = Ss.colors
    DisposableEffect(Unit) {
        vm.startFilterTap()
        onDispose { vm.stopFilterTap() }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionHeader("Filtered Signals")
            Text("set on the server", color = c.faint, fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp))
            val canRetune by vm.tuneAvailable.collectAsState()
            if (canRetune) {
                Spacer(Modifier.weight(1f))
                Text("Re-tune", color = c.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { vm.retuneFilters() })
            }
        }
        SsCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                sensors.forEach { s -> FilteredRow(s, traces[s.handle]) }
            }
        }
    }
}

@Composable
private fun FilteredRow(s: StreamViewModel.FilteredSensor, trace: FilterTrace?) {
    val c = Ss.colors
    val axisColors = listOf(c.axisX, c.axisY, c.axisZ)
    val name = SignalCatalog.of(s.info.type, s.info.stringType).humanName
    var axis by remember(s.handle) { mutableIntStateOf(0) }
    val nAxes = trace?.points?.lastOrNull()?.raw?.size ?: 3
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, color = c.fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(FilterConfigCodec.summary(s.config), color = c.faint, fontSize = 11.sp)
            }
            if (trace != null && trace.status == FilterStatus.Running) {
                Text(
                    "σ ${sig3(trace.sigmaRaw)} → ${sig3(trace.sigmaFiltered)}",
                    style = SsType.mono, color = c.fg, fontSize = 12.sp,
                )
            }
        }
        val status = trace?.status
        when {
            !s.enabled -> Text("Not enabled on this phone — turn it on under Sensors.", color = c.faint, fontSize = 12.sp)
            status is FilterStatus.Invalid -> Text(status.message, color = c.warn, fontSize = 12.sp)
            status != FilterStatus.Running || trace.points.size < 2 ->
                Text("Measuring the sample rate…", color = c.faint, fontSize = 12.sp)
            else -> {
                val a = axis.coerceIn(0, maxOf(0, nAxes - 1))
                RawFilteredGraph(
                    points = trace.points, axis = a, color = axisColors.getOrElse(a) { c.accent },
                    modifier = Modifier.fillMaxWidth().height(90.dp).clickable { axis = (a + 1) % maxOf(1, nAxes) },
                )
                Text(
                    "${listOf("X", "Y", "Z").getOrElse(a) { "value" }} axis · faint = raw, bold = filtered · tap to switch",
                    color = c.faint, fontSize = 10.sp,
                )
            }
        }
    }
}

/** One axis of a trace: raw (faint) and filtered (bold) over the last [FilterTap.WINDOW_MS]. */
@Composable
fun RawFilteredGraph(points: List<TracePoint>, axis: Int, color: Color, modifier: Modifier = Modifier) {
    val c = Ss.colors
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        for (g in 0..2) {
            val y = h * g / 2f
            drawLine(c.line, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }
        if (points.size < 2) return@Canvas
        var lo = Float.POSITIVE_INFINITY
        var hi = Float.NEGATIVE_INFINITY
        for (p in points) {
            p.raw.getOrNull(axis)?.takeIf { it.isFinite() }?.let { lo = minOf(lo, it); hi = maxOf(hi, it) }
            p.filtered?.getOrNull(axis)?.takeIf { it.isFinite() }?.let { lo = minOf(lo, it); hi = maxOf(hi, it) }
        }
        if (lo == Float.POSITIVE_INFINITY) return@Canvas
        if (hi - lo < 1e-6f) { hi += 1f; lo -= 1f }
        val span = hi - lo
        val tEnd = points.last().tMs
        val tStart = tEnd - FilterTap.WINDOW_MS
        fun path(select: (TracePoint) -> Float?): Path {
            val path = Path()
            var started = false
            for (p in points) {
                val v = select(p)
                if (v == null || !v.isFinite()) { started = false; continue }
                val x = w * (p.tMs - tStart) / FilterTap.WINDOW_MS.toFloat()
                val y = h - ((v - lo) / span) * h
                if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
            }
            return path
        }
        drawPath(path { it.raw.getOrNull(axis) }, color.copy(alpha = 0.35f), style = Stroke(width = 1.5.dp.toPx()))
        drawPath(path { it.filtered?.getOrNull(axis) }, color, style = Stroke(width = 2.5.dp.toPx()))
    }
}
