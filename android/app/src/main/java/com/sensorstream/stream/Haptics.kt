package com.sensorstream.stream

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.sensorstream.core.Buzz

/** Guided-tuning cues. Alarm usage so they also vibrate with the ringer on silent (DND may still block). */
class Haptics(context: Context) {
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)

    fun buzz(b: Buzz) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        try {
            when {
                Build.VERSION.SDK_INT >= 33 -> v.vibrate(
                    VibrationEffect.createWaveform(b.pattern, -1),
                    VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM),
                )
                Build.VERSION.SDK_INT >= 26 -> @Suppress("DEPRECATION") v.vibrate(
                    VibrationEffect.createWaveform(b.pattern, -1),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
                )
                else -> @Suppress("DEPRECATION") v.vibrate(b.pattern, -1)
            }
        } catch (_: Exception) { /* a cue is best-effort; the on-screen flow still runs */ }
    }
}
