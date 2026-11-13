package kz.crdrive.wear

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.media.AudioManager
import android.media.ToneGenerator

object WatchHaptics {
    fun playAlert(context: Context) {
        context.getSystemService(Vibrator::class.java)?.vibrate(
            VibrationEffect.createWaveform(
                longArrayOf(0, 700, 240, 700, 240, 900),
                intArrayOf(0, 255, 0, 255, 0, 255),
                -1
            )
        )
    }

    fun playContactWarning(context: Context) {
        context.getSystemService(Vibrator::class.java)?.vibrate(
            VibrationEffect.createWaveform(
                longArrayOf(0, 350, 180, 350, 180, 550),
                intArrayOf(0, 220, 0, 220, 0, 255),
                -1
            )
        )
    }

    fun playMotionWarning(context: Context) {
        context.getSystemService(Vibrator::class.java)?.vibrate(
            VibrationEffect.createWaveform(
                longArrayOf(0, 900, 180, 900, 180, 1200),
                intArrayOf(0, 255, 0, 255, 0, 255),
                -1
            )
        )
        // Alarm stream is used intentionally: the driver must hear this warning
        // even when the app screen is off. Device volume/DND policy still applies.
        val tone = ToneGenerator(AudioManager.STREAM_ALARM, 100)
        tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 3_500)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ tone.release() }, 4_000)
    }
}
