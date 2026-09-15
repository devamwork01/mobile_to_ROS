package com.sensorstream.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Premium, theme-aware design tokens. Dark is the default; light is a real re-design (warm
 * off-white ground, soft gray surfaces, dark type), not an inversion. Axis colors (X/Y/Z) are
 * universal and identical in both themes.
 */
data class SsColors(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val line: Color,
    val fg: Color,
    val muted: Color,
    val faint: Color,
    val accent: Color,
    val accentSoft: Color,
    val ok: Color,
    val warn: Color,
    val err: Color,
    val axisX: Color,
    val axisY: Color,
    val axisZ: Color,
    val isDark: Boolean,
)

// Universal axis convention — never themed, never changed.
private val AxisX = Color(0xFFFF5C5C)
private val AxisY = Color(0xFF3FD07A)
private val AxisZ = Color(0xFF4C8DFF)

fun darkSsColors() = SsColors(
    bg = Color(0xFF0A0D12),
    surface = Color(0xFF12161D),
    surface2 = Color(0xFF171C25),
    line = Color(0xFF272E39),
    fg = Color(0xFFE8EDF4),
    muted = Color(0xFF8B95A4),
    faint = Color(0xFF5A6472),
    accent = Color(0xFF3D7BFD),
    accentSoft = Color(0xFF17233D),
    ok = Color(0xFF3FD07A),
    warn = Color(0xFFE3A635),
    err = Color(0xFFFF5C5C),
    axisX = AxisX, axisY = AxisY, axisZ = AxisZ,
    isDark = true,
)

fun lightSsColors() = SsColors(
    bg = Color(0xFFECEFF4),
    surface = Color(0xFFFFFFFF),
    surface2 = Color(0xFFF6F8FB),
    line = Color(0xFFD6DCE5),
    fg = Color(0xFF1A2029),
    muted = Color(0xFF5A6472),
    faint = Color(0xFF8A93A1),
    accent = Color(0xFF2F6BF0),
    accentSoft = Color(0xFFE6EEFE),
    ok = Color(0xFF199A54),
    warn = Color(0xFFB07714),
    err = Color(0xFFDC3B3B),
    axisX = AxisX, axisY = AxisY, axisZ = AxisZ,
    isDark = false,
)
