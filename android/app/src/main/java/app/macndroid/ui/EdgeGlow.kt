package app.macndroid.ui

import android.app.Activity
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator

/**
 * A soft blue-to-violet glow that traces the edge of the screen once, so you know the computer can now see this
 * phone's screen. Drawn by our own see-through consent screen; no overlay permission is used.
 */
internal object EdgeGlow {
    fun play(activity: Activity, done: () -> Unit) {
        val reduced = Settings.Global.getFloat(activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        if (reduced) { done(); return }
        // Taps go straight through to whatever is underneath while it plays.
        activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        val view = GlowView(activity)
        activity.addContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        view.alpha = 0f
        val ease = PathInterpolator(0.2f, 0f, 0f, 1f)
        view.animate().alpha(1f).setDuration(350).setInterpolator(ease).withEndAction {
            view.animate().alpha(0f).setStartDelay(650).setDuration(500).setInterpolator(ease).withEndAction(done).start()
        }.start()
    }

    private class GlowView(ctx: Context) : View(ctx) {
        private val d = ctx.resources.displayMetrics.density
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 10 * d; maskFilter = BlurMaskFilter(14 * d, BlurMaskFilter.Blur.NORMAL)
        }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * d }
        private val rect = RectF()

        init { setLayerType(LAYER_TYPE_SOFTWARE, null) }   // the blur needs a software layer

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            val shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(),
                intArrayOf(0xFF3B82F6.toInt(), 0xFF8B5CF6.toInt(), 0xFF0EA5E9.toInt()), null, Shader.TileMode.CLAMP)
            glow.shader = shader; line.shader = shader
            rect.set(1.5f * d, 1.5f * d, w - 1.5f * d, h - 1.5f * d)
        }

        override fun onDraw(c: Canvas) {
            // Roughly the corner radius of today's phone screens.
            val r = 36 * d
            c.drawRoundRect(rect, r, r, glow)
            c.drawRoundRect(rect, r, r, line)
        }
    }
}
