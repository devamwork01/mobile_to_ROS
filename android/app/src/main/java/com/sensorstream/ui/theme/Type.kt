package com.sensorstream.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Premium type scale on the high-quality system sans (Spec §8 — Inter isn't bundled; the system
 * font is the accepted equivalent). [SsType.mono] uses tabular figures so streaming numbers don't
 * jitter in width.
 */
object SsType {
    val mono = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontFeatureSettings = "tnum",
        fontWeight = FontWeight.Medium,
    )
}

fun ssTypography(): Typography {
    val base = Typography()
    return base.copy(
        displayLarge = base.displayLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 48.sp),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Medium),
        labelSmall = base.labelSmall.copy(letterSpacing = 0.8.sp),
    )
}
