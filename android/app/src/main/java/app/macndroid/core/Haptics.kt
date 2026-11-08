package app.macndroid.core

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Light, system-standard haptics for things the app does (file sent/received, mirroring, clipboard). */
object Haptics {
    enum class Kind { Tick, Click, Success, Failure }

    private var vibrator: Vibrator? = null

    fun init(ctx: Context) {
        vibrator = if (Build.VERSION.SDK_INT >= 31) {
            ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)
        }
    }

    fun play(kind: Kind) {
        if (!Prefs.haptics.value) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val effect = when (kind) {
            Kind.Tick -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
            Kind.Click -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
            Kind.Success -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
            Kind.Failure -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
        }
        runCatching { v.vibrate(effect) }
    }
}
