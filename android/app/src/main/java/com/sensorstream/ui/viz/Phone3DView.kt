package com.sensorstream.ui.viz

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.sensorstream.ui.theme.Ss
import kotlin.math.hypot

/** Pure projection of the body axes to screen offsets, so it JVM-tests without a Canvas. */
object Phone3DConfig {
    fun axisEndpoints(r: FloatArray, cx: Float, cy: Float, len: Float): Map<String, Pair<Float, Float>> {
        fun ep(axis: Vec3): Pair<Float, Float> {
            val rotated = Projection.rotate(r, axis)
            val (dx, dy) = Projection.project(rotated, len)
            return Pair(cx + dx, cy - dy) // screen y grows downward
        }
        return mapOf(
            "X" to ep(Vec3(1f, 0f, 0f)),
            "Y" to ep(Vec3(0f, 1f, 0f)),
            "Z" to ep(Vec3(0f, 0f, 1f)),
        )
    }
}

/**
 * Canvas pseudo-3D phone: a stylized rounded body (metallic gradient + contact shadow + glow) and
 * the body X/Y/Z axes (+ optional sensor vector) projected from the live rotation vector. Reuses the
 * existing orientation values; no 3D engine, no assets. Recomposes only when its inputs change.
 */
@Composable
fun Phone3DView(
    rotationVector: FloatArray?,
    modifier: Modifier = Modifier,
    sensorVector: Vec3? = null,
    sensorVectorColor: Color? = null,
    showAxes: Boolean = true,
    showLabels: Boolean = true,
) {
    val colors = Ss.colors
    Canvas(modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val unit = minOf(size.width, size.height) * 0.34f
        val r = Projection.rotationVectorToMatrix(rotationVector ?: floatArrayOf(0f, 0f, 0f))

        // Contact shadow beneath the phone (soft, elliptical).
        drawOvalShadow(cx, cy + unit * 0.95f, unit * 1.1f, unit * 0.28f, colors.isDark)

        // Phone body: project its 4 corners so it tilts with orientation.
        val hw = unit * 0.42f // half width
        val hh = unit * 0.86f // half height
        val corners = listOf(
            Vec3(-hw, hh, 0f), Vec3(hw, hh, 0f), Vec3(hw, -hh, 0f), Vec3(-hw, -hh, 0f),
        ).map { c ->
            val rot = Projection.rotate(r, c)
            val (dx, dy) = Projection.project(rot, 1f)
            Offset(cx + dx, cy - dy)
        }
        val body = Path().apply {
            moveTo(corners[0].x, corners[0].y)
            for (i in 1 until corners.size) lineTo(corners[i].x, corners[i].y)
            close()
        }
        // Metallic fill + edge.
        drawPath(
            body,
            brush = Brush.linearGradient(
                colors = if (colors.isDark)
                    listOf(Color(0xFF2A313D), Color(0xFF12161D))
                else
                    listOf(Color(0xFFDDE3EC), Color(0xFFB9C2CE)),
                start = corners[3], end = corners[1],
            ),
        )
        drawPath(body, color = colors.line, style = Stroke(width = 2.dp.toPx()))

        // Axes.
        if (showAxes) {
            val ep = Phone3DConfig.axisEndpoints(r, cx, cy, unit * 1.15f)
            drawAxis(cx, cy, ep["Z"]!!, colors.axisZ)
            drawAxis(cx, cy, ep["X"]!!, colors.axisX)
            drawAxis(cx, cy, ep["Y"]!!, colors.axisY)
            if (sensorVector != null && sensorVectorColor != null) {
                val rot = Projection.rotate(r, normalize(sensorVector))
                val (dx, dy) = Projection.project(rot, unit * 1.0f)
                drawAxis(cx, cy, Pair(cx + dx, cy - dy), sensorVectorColor, thick = 5f)
            }
        }
    }
}

private fun DrawScope.drawOvalShadow(cx: Float, cy: Float, rx: Float, ry: Float, dark: Boolean) {
    val shadow = if (dark) Color(0x66000000) else Color(0x22000000)
    drawOval(
        brush = Brush.radialGradient(
            colors = listOf(shadow, Color.Transparent),
            center = Offset(cx, cy), radius = rx,
        ),
        topLeft = Offset(cx - rx, cy - ry),
        size = Size(rx * 2, ry * 2),
    )
}

private fun DrawScope.drawAxis(cx: Float, cy: Float, end: Pair<Float, Float>, color: Color, thick: Float = 4f) {
    val e = Offset(end.first, end.second)
    drawLine(color, Offset(cx, cy), e, strokeWidth = thick.dp.toPx() / 1.5f)
    // Arrowhead
    val dx = e.x - cx; val dy = e.y - cy
    val len = hypot(dx, dy).coerceAtLeast(0.001f)
    val ux = dx / len; val uy = dy / len
    val head = 14f
    val a = Offset(e.x - head * (ux + uy * 0.5f), e.y - head * (uy - ux * 0.5f))
    val b = Offset(e.x - head * (ux - uy * 0.5f), e.y - head * (uy + ux * 0.5f))
    val tri = Path().apply { moveTo(e.x, e.y); lineTo(a.x, a.y); lineTo(b.x, b.y); close() }
    drawPath(tri, color)
}

private fun normalize(v: Vec3): Vec3 {
    val n = kotlin.math.sqrt(v.x * v.x + v.y * v.y + v.z * v.z)
    return if (n == 0f) v else Vec3(v.x / n, v.y / n, v.z / n)
}
