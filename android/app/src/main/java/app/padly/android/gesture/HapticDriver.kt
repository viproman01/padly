package app.padly.android.gesture

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Short haptic ticks emulating a trackpad click. */
class HapticDriver(private val context: Context) {
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    fun tick(strength: Float = 0.6f) {
        if (!vibrator.hasVibrator()) return
        val amplitude = (strength * 255f).toInt().coerceIn(1, 255)
        if (Build.VERSION.SDK_INT >= 29 && vibrator.hasAmplitudeControl()) {
            vibrator.vibrate(
                VibrationEffect.createOneShot(10, amplitude)
            )
        } else {
            vibrator.vibrate(VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }
}
