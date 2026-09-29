package com.sensorstream.ui.settings

import android.content.Context

/** How the app chooses light vs dark. SYSTEM follows the OS setting. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** How long the sender may wait to pack more samples into one UDP datagram. Longer = fewer
 *  packets (less radio wake-up / battery) at the cost of that much added latency. */
enum class BatchMode(val windowMs: Long) { LOW_LATENCY(0), BALANCED(2), BATTERY(5) }

/**
 * User-facing display preferences. UI-only: these never touch sensor acquisition, timestamps,
 * fusion, the wire format, or the network — they change how values are rendered and which theme
 * is shown. Persisted verbatim to SharedPreferences by [SettingsStore].
 */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Initial state of the world-frame overlay on the Orientation detail screen. */
    val default3dWorldFrame: Boolean = true,
    /** Initial state of the axis-label overlay on the 3D visualizations. */
    val default3dLabels: Boolean = true,
    /** Packet batching for the telemetry stream. */
    val batchMode: BatchMode = BatchMode.LOW_LATENCY,
)

/** Thin SharedPreferences wrapper. Reads are cheap; writes are fire-and-forget (apply). */
class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("ss_settings", Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        themeMode = runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: "SYSTEM") }
            .getOrDefault(ThemeMode.SYSTEM),
        default3dWorldFrame = prefs.getBoolean(KEY_WORLD, true),
        default3dLabels = prefs.getBoolean(KEY_LABELS, true),
        batchMode = runCatching { BatchMode.valueOf(prefs.getString(KEY_BATCH, null) ?: "LOW_LATENCY") }
            .getOrDefault(BatchMode.LOW_LATENCY),
    )

    fun save(s: AppSettings) {
        prefs.edit()
            .putString(KEY_THEME, s.themeMode.name)
            .putBoolean(KEY_WORLD, s.default3dWorldFrame)
            .putBoolean(KEY_LABELS, s.default3dLabels)
            .putString(KEY_BATCH, s.batchMode.name)
            .apply()
    }

    private companion object {
        const val KEY_THEME = "theme_mode"
        const val KEY_WORLD = "default_3d_world_frame"
        const val KEY_LABELS = "default_3d_labels"
        const val KEY_BATCH = "batch_mode"
    }
}
