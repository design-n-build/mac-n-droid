package app.macndroid

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.macndroid.ui.Widgets
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Pictures of the home-screen widgets at the sizes a launcher gives them, light and dark, to check their layout:
 *   ./gradlew :app:testDebugUnitTest --tests app.macndroid.WidgetShots -DwidgetShots.out=/some/folder
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class WidgetShots {
    /** Launcher sizes (dp) for 1×1, 2×1, 4×1, 2×2, 4×2 and 4×3 on a phone. */
    private val sizes = listOf(70f to 70f, 150f to 70f, 330f to 70f, 150f to 150f, 330f to 150f, 330f to 240f)

    @Test fun light() = shoot("light")

    @Test fun playing() {
        val art = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).drawColor(Color.rgb(222, 120, 80))
        }
        Widgets.previewTrack = app.macndroid.service.MacMusic.Track("Golden Hour Drive", "The Midnight Avenue", "YouTube Music", true, art, 60)
        try { shoot("playing") } finally { Widgets.previewTrack = null }
    }

    @Test fun dark() {
        RuntimeEnvironment.setQualifiers("+night")
        shoot("dark")
    }

    private fun shoot(theme: String) {
        val out = File(System.getProperty("widgetShots.out") ?: "build/widget-shots").apply { mkdirs() }
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val d = ctx.resources.displayMetrics.density
        for ((w, h) in sizes) {
            val rv = Widgets.previewViews(ctx, w, h)
            val pw = (w * d).toInt(); val ph = (h * d).toInt()
            val parent = FrameLayout(ctx)
            val view = rv.apply(ctx, parent)
            parent.addView(view, FrameLayout.LayoutParams(pw, ph))
            parent.measure(View.MeasureSpec.makeMeasureSpec(pw + 48, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(ph + 48, View.MeasureSpec.EXACTLY))
            parent.setPadding(24, 24, 24, 24)
            parent.measure(View.MeasureSpec.makeMeasureSpec(pw + 48, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(ph + 48, View.MeasureSpec.EXACTLY))
            parent.layout(0, 0, pw + 48, ph + 48)
            val bmp = Bitmap.createBitmap(pw + 48, ph + 48, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(if (theme == "dark") Color.rgb(40, 44, 52) else Color.rgb(196, 208, 222))
            parent.draw(c)
            File(out, "$theme-${w.toInt()}x${h.toInt()}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
