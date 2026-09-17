package com.sensorstream.ui.viz

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import com.sensorstream.ui.theme.Ss
import kotlin.math.hypot

/** Pure projection of the body axes to screen offsets, so it JVM-tests without a Canvas. */
object Phone3DConfig {
    fun axisEndpoints(
        r: FloatArray, cx: Float, cy: Float, len: Float,
        yaw: Float = 0f, pitch: Float = Projection.DEFAULT_PITCH,
    ): Map<String, Pair<Float, Float>> {
        fun ep(axis: Vec3): Pair<Float, Float> {
            val rotated = Projection.rotate(r, axis)
            val (dx, dy) = Projection.project(rotated, len, yaw, pitch)
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
    showWorldFrame: Boolean = false,
    showGrid: Boolean = true,
) {
    val colors = Ss.colors
    // Orbitable camera: drag rotates the viewpoint ONLY when rotate-mode is on (the corner toggle),
    // so the 3D never steals the page scroll — important in landscape where it can fill the width.
    // Double-tap resets. The phone body still reflects device orientation; only the camera moves.
    var camYaw by remember { mutableFloatStateOf(0f) }
    var camPitch by remember { mutableFloatStateOf(Projection.DEFAULT_PITCH) }
    var rotateEnabled by remember { mutableStateOf(false) }
    Box(modifier) {
        val gestures = if (rotateEnabled) {
            Modifier
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        camYaw += drag.x * 0.01f
                        camPitch = (camPitch + drag.y * 0.01f).coerceIn(-1.4f, 1.4f)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { camYaw = 0f; camPitch = Projection.DEFAULT_PITCH })
                }
        } else Modifier
        Canvas(Modifier.fillMaxSize().then(gestures)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val unit = minOf(size.width, size.height) * 0.34f
        val yaw = camYaw
        val pitch = camPitch
        // Render in the SAME space the laptop uses (Rx(-90°) ENU->Y-up) so the on-device phone's
        // orientation matches the dashboard's 3D viz exactly.
        val r = Projection.threeMatrix(rotationVector ?: floatArrayOf(0f, 0f, 0f))

        // Contact shadow beneath the phone (soft, elliptical).
        drawOvalShadow(cx, cy + unit * 0.95f, unit * 1.1f, unit * 0.28f, colors.isDark)

        // World ground grid on the horizontal (East-North) plane below the phone — a fixed spatial
        // reference that orbits with the camera, like the laptop's grid floor.
        if (showGrid) {
            val gy = -unit * 0.92f          // ground level (below origin; Up is +Y)
            val ext = unit * 1.7f
            val step = ext / 3f
            val gridCol = colors.line.copy(alpha = 0.6f)
            fun gp(x: Float, z: Float): Offset {
                val (dx, dy) = Projection.project(Vec3(x, gy, z), 1f, yaw, pitch)
                return Offset(cx + dx, cy - dy)
            }
            var i = -3
            while (i <= 3) {
                val o = i * step
                drawLine(gridCol, gp(-ext, o), gp(ext, o), strokeWidth = 1f) // lines running East
                drawLine(gridCol, gp(o, -ext), gp(o, ext), strokeWidth = 1f) // lines running North
                i++
            }
        }

        // World frame (fixed, does NOT rotate with the phone): E->+X, N->-Z, U->+Y in render space.
        // Drawn faint + dashed-ish behind the phone so it reads as the reference ground truth.
        if (showWorldFrame) {
            val wl = unit * 1.45f
            val worldCol = colors.muted.copy(alpha = 0.55f)
            fun wep(v: Vec3): Pair<Float, Float> {
                val (dx, dy) = Projection.project(v, wl, yaw, pitch); return Pair(cx + dx, cy - dy)
            }
            drawAxis(cx, cy, wep(Vec3(0f, 1f, 0f)), worldCol, thick = 2.5f)   // Up
            drawAxis(cx, cy, wep(Vec3(1f, 0f, 0f)), worldCol, thick = 2.5f)   // East
            drawAxis(cx, cy, wep(Vec3(0f, 0f, -1f)), worldCol, thick = 2.5f)  // North
            if (showLabels) {
                fun wlab(v: Vec3): Pair<Float, Float> {
                    val (dx, dy) = Projection.project(v, unit * 1.62f, yaw, pitch); return Pair(cx + dx, cy - dy)
                }
                drawAxisLabel("U", wlab(Vec3(0f, 1f, 0f)), worldCol)
                drawAxisLabel("E", wlab(Vec3(1f, 0f, 0f)), worldCol)
                drawAxisLabel("N", wlab(Vec3(0f, 0f, -1f)), worldCol)
            }
        }

        // Phone body: an extruded slab (glass front + metallic back + camera module) so it reads as
        // a real phone. Projected through the orbit camera; faces drawn back-to-front (painter's).
        val hw = unit * 0.40f  // half width
        val hh = unit * 0.82f  // half height
        val t2 = unit * 0.055f // half thickness
        fun proj(v: Vec3): Offset {
            val rot = Projection.rotate(r, v)
            val (dx, dy) = Projection.project(rot, 1f, yaw, pitch)
            return Offset(cx + dx, cy - dy)
        }
        fun quad(pts: List<Offset>) = Path().apply {
            moveTo(pts[0].x, pts[0].y); for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y); close()
        }
        val front = listOf(Vec3(-hw, hh, t2), Vec3(hw, hh, t2), Vec3(hw, -hh, t2), Vec3(-hw, -hh, t2)).map { proj(it) }
        val back = listOf(Vec3(-hw, hh, -t2), Vec3(hw, hh, -t2), Vec3(hw, -hh, -t2), Vec3(-hw, -hh, -t2)).map { proj(it) }
        val screenNear = Projection.depth(Projection.rotate(r, Vec3(0f, 0f, t2)), yaw, pitch) >=
            Projection.depth(Projection.rotate(r, Vec3(0f, 0f, -t2)), yaw, pitch)

        val metalBrush = Brush.linearGradient(
            colors = if (colors.isDark) listOf(Color(0xFF3B424E), Color(0xFF1A1F27))
            else listOf(Color(0xFFEDF1F7), Color(0xFFBFC8D4)),
            start = back[3], end = back[1],
        )
        val screenBrush = Brush.linearGradient(
            colors = if (colors.isDark) listOf(Color(0xFF141922), Color(0xFF05070B))
            else listOf(Color(0xFF2B313B), Color(0xFF0E1116)),
            start = front[3], end = front[1],
        )
        val edgeCol = if (colors.isDark) Color(0xFF2A313D) else Color(0xFFAAB3C0)
        fun drawSides() {
            for (i in 0 until 4) {
                val j = (i + 1) % 4
                drawPath(quad(listOf(front[i], front[j], back[j], back[i])), color = edgeCol)
            }
        }
        // Camera module on the back face (top-left), drawn only when the back faces the viewer.
        fun drawCamera() {
            val z = -t2 - 0.001f
            val mcx = -hw * 0.40f; val mcy = hh * 0.50f; val mw = hw * 0.5f; val mh = hh * 0.30f
            val modCorners = listOf(
                Vec3(mcx - mw, mcy + mh, z), Vec3(mcx + mw, mcy + mh, z),
                Vec3(mcx + mw, mcy - mh, z), Vec3(mcx - mw, mcy - mh, z),
            ).map { proj(it) }
            drawPath(quad(modCorners), color = if (colors.isDark) Color(0xFF20262F) else Color(0xFF98A2B0))
            val lensR = mw * 0.24f
            listOf(mh * 0.5f, 0f, -mh * 0.5f).forEach { dy ->
                drawCircle(Color(0xFF07090D), radius = lensR, center = proj(Vec3(mcx, mcy + dy, z)))
                drawCircle(edgeCol, radius = lensR, center = proj(Vec3(mcx, mcy + dy, z)), style = Stroke(1.5f))
            }
        }

        if (screenNear) {
            drawPath(quad(back), brush = metalBrush); drawSides()
            drawPath(quad(front), brush = screenBrush)
            drawPath(quad(front), color = colors.line, style = Stroke(width = 2.dp.toPx()))
        } else {
            drawPath(quad(front), brush = screenBrush); drawSides()
            drawPath(quad(back), brush = metalBrush)
            drawPath(quad(back), color = colors.line, style = Stroke(width = 2.dp.toPx()))
            drawCamera()
        }

        // Axes (drawn Z first so X/Y read on top). Labels sit just past each arrow tip.
        if (showAxes) {
            val ep = Phone3DConfig.axisEndpoints(r, cx, cy, unit * 1.15f, yaw, pitch)
            drawAxis(cx, cy, ep["Z"]!!, colors.axisZ)
            drawAxis(cx, cy, ep["X"]!!, colors.axisX)
            drawAxis(cx, cy, ep["Y"]!!, colors.axisY)
            if (showLabels) {
                val lp = Phone3DConfig.axisEndpoints(r, cx, cy, unit * 1.34f, yaw, pitch) // labels a bit beyond tips
                drawAxisLabel("Z", lp["Z"]!!, colors.axisZ)
                drawAxisLabel("X", lp["X"]!!, colors.axisX)
                drawAxisLabel("Y", lp["Y"]!!, colors.axisY)
            }
            if (sensorVector != null && sensorVectorColor != null) {
                val rot = Projection.rotate(r, normalize(sensorVector))
                val (dx, dy) = Projection.project(rot, unit * 1.0f, yaw, pitch)
                drawAxis(cx, cy, Pair(cx + dx, cy - dy), sensorVectorColor, thick = 5f)
            }
        }

        // World-axes gizmo (top-left): a compact reference triad that orbits with the camera, so
        // you can always read which way world X/Y/Z point in the current view.
        val gcx = size.width * 0.13f
        val gcy = size.height * 0.15f
        val glen = minOf(size.width, size.height) * 0.085f
        fun gp(v: Vec3, s: Float): Pair<Float, Float> {
            val (dx, dy) = Projection.project(v, s, yaw, pitch); return Pair(gcx + dx, gcy - dy)
        }
        drawAxis(gcx, gcy, gp(Vec3(0f, 0f, 1f), glen), colors.axisZ, thick = 2.2f)
        drawAxis(gcx, gcy, gp(Vec3(1f, 0f, 0f), glen), colors.axisX, thick = 2.2f)
        drawAxis(gcx, gcy, gp(Vec3(0f, 1f, 0f), glen), colors.axisY, thick = 2.2f)
        drawAxisLabel("X", gp(Vec3(1f, 0f, 0f), glen * 1.42f), colors.axisX, size = 22f)
        drawAxisLabel("Y", gp(Vec3(0f, 1f, 0f), glen * 1.42f), colors.axisY, size = 22f)
        drawAxisLabel("Z", gp(Vec3(0f, 0f, 1f), glen * 1.42f), colors.axisZ, size = 22f)
        }

        // Rotate-mode toggle (top-end). OFF by default so a drag scrolls the page; ON = drag orbits
        // + double-tap resets. This is the fix for the 3D "eating" scroll, especially in landscape.
        val toggleBg = if (rotateEnabled) colors.accent else colors.surface2
        val toggleFg = if (rotateEnabled) Color.White else colors.muted
        Box(
            Modifier.align(Alignment.TopEnd).padding(8.dp).size(36.dp)
                .clip(CircleShape).background(toggleBg)
                .clickable { rotateEnabled = !rotateEnabled },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = if (rotateEnabled) "Rotation on" else "Rotation off",
                tint = toggleFg,
                modifier = Modifier.size(20.dp),
            )
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

private fun DrawScope.drawAxisLabel(text: String, at: Pair<Float, Float>, color: Color, size: Float = 34f) {
    val paint = android.graphics.Paint().apply {
        this.color = android.graphics.Color.argb(
            (color.alpha * 255).toInt(), (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt(),
        )
        textSize = size
        isAntiAlias = true
        isFakeBoldText = true
        textAlign = android.graphics.Paint.Align.CENTER
    }
    // Center vertically on the point (baseline offset ~ textSize/3).
    drawContext.canvas.nativeCanvas.drawText(text, at.first, at.second + size / 3f, paint)
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
