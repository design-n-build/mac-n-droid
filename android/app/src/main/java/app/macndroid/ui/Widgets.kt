package app.macndroid.ui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import app.macndroid.R
import app.macndroid.core.Computer
import app.macndroid.core.Link
import app.macndroid.core.Pause
import app.macndroid.core.Prefs
import app.macndroid.service.MacMusic
import org.json.JSONObject

/**
 * Three home-screen widgets, plain Android widgets (RemoteViews) in Material You colours (res/values-v31), each with
 * its own preview in the widget picker (the layout itself). On Android 12+ each one gives the launcher a layout per
 * size, so it rearranges itself as it's resized:
 *  - [OneWidget] (1×1): the logo shows whether sharing is on (filled) or paused (dimmed, with a pause badge). Tap to
 *    switch. A label appears when it's big enough.
 *  - [TwoWidget] (2×1): Pause / Resume and Send files; labels when there's room.
 *  - [FourWidget] (4×2): what's playing on the computer (play/pause, previous, next, Play on phone), then Pause,
 *    Send files, Send clipboard and Open. Smaller sizes show less: the four buttons only when it's one row high.
 */
object Widgets {
    /** Redraw every widget (pausing, connecting, the computer's music changing…). */
    fun refresh(ctx: Context) {
        val app = ctx.applicationContext
        val mgr = AppWidgetManager.getInstance(app) ?: return
        fun ids(c: Class<*>) = runCatching { mgr.getAppWidgetIds(ComponentName(app, c)) }.getOrDefault(IntArray(0))
        ids(OneWidget::class.java).forEach { runCatching { mgr.updateAppWidget(it, one(app)) } }
        ids(TwoWidget::class.java).forEach { runCatching { mgr.updateAppWidget(it, two(app)) } }
        ids(FourWidget::class.java).forEach { runCatching { mgr.updateAppWidget(it, four(app)) } }
    }

    /** A layout per size on Android 12+ (the launcher picks the one that fits); one layout before that. */
    private fun responsive(sizes: List<Pair<SizeF, () -> RemoteViews>>, fallback: () -> RemoteViews): RemoteViews {
        preview?.let { (w, h) ->
            // Screenshot tests: the variant a launcher would pick at this size (the largest that fits).
            return sizes.filter { it.first.width <= w && it.first.height <= h }.maxByOrNull { it.first.width * it.first.height }
                ?.second?.invoke() ?: sizes.first().second()
        }
        return if (Build.VERSION.SDK_INT >= 31) RemoteViews(sizes.associate { (s, f) -> s to f() }) else fallback()
    }

    /** Tests only: the computer's music to show. */
    @Volatile internal var previewTrack: MacMusic.Track? = null

    /** Tests only: build the variant for this size (dp) instead of the responsive set. */
    @Volatile internal var preview: Pair<Float, Float>? = null

    /** Tests only: one widget ("one", "two", "four") as it looks at w×h dp. */
    internal fun previewViews(ctx: Context, which: String, w: Float, h: Float): RemoteViews {
        preview = w to h
        try {
            return when (which) { "one" -> one(ctx); "two" -> two(ctx); else -> four(ctx) }
        } finally { preview = null }
    }

    // MARK: 1×1

    private fun one(ctx: Context): RemoteViews {
        fun make(circle: Int, label: Boolean) = RemoteViews(ctx.packageName, R.layout.widget_one).apply {
            val paused = Pause.active
            setViewVisibility(R.id.on, if (paused) View.GONE else View.VISIBLE)
            setViewVisibility(R.id.off, if (paused) View.VISIBLE else View.GONE)
            setViewVisibility(R.id.badge, if (paused) View.VISIBLE else View.GONE)
            setTextViewText(R.id.label, if (paused) "Paused" else "Sharing on")
            setViewVisibility(R.id.label, if (label) View.VISIBLE else View.GONE)
            if (Build.VERSION.SDK_INT >= 31) {
                setViewLayoutWidth(R.id.badge_frame, circle.toFloat(), android.util.TypedValue.COMPLEX_UNIT_DIP)
                setViewLayoutHeight(R.id.badge_frame, circle.toFloat(), android.util.TypedValue.COMPLEX_UNIT_DIP)
            }
            setContentDescription(R.id.root, if (paused) "Sharing paused. Tap to resume." else "Sharing on. Tap to pause.")
            setOnClickPendingIntent(R.id.root, action(ctx, ACTION_TOGGLE_PAUSE))
        }
        return responsive(listOf(
            SizeF(40f, 40f) to { make(48, label = false) },
            SizeF(90f, 100f) to { make(64, label = true) },
        )) { make(56, label = false) }
    }

    // MARK: 2×1

    private fun two(ctx: Context): RemoteViews {
        fun make(labels: Boolean) = RemoteViews(ctx.packageName, R.layout.widget_two).apply {
            val paused = Pause.active
            setImageViewResource(R.id.pause_icon, if (paused) R.drawable.ic_w_play else R.drawable.ic_w_pause)
            setTextViewText(R.id.pause_label, if (paused) "Resume" else "Pause")
            setContentDescription(R.id.pause, if (paused) "Resume sharing" else "Pause sharing")
            setViewVisibility(R.id.pause_label, if (labels) View.VISIBLE else View.GONE)
            setViewVisibility(R.id.files_label, if (labels) View.VISIBLE else View.GONE)
            setOnClickPendingIntent(R.id.pause, action(ctx, ACTION_TOGGLE_PAUSE))
            setOnClickPendingIntent(R.id.files, activity(ctx, PickFilesActivity::class.java, 1))
        }
        return responsive(listOf(
            SizeF(100f, 40f) to { make(labels = false) },
            SizeF(220f, 40f) to { make(labels = true) },
        )) { make(labels = true) }
    }

    // MARK: 4×2

    private enum class Four { Actions, Compact, Full }

    private fun four(ctx: Context): RemoteViews {
        // The album art is decoded once per update and shared by the size variants.
        val track = preview?.let { previewTrack } ?: MacMusic.current
        val art = track?.art?.let(::thumb)
        fun make(kind: Four) = RemoteViews(ctx.packageName, R.layout.widget_four).apply {
            val paused = Pause.active
            val connected = Link.isConnected || (preview != null && previewTrack != null)
            val music = Prefs.macMusic.value && connected && track != null
            val showMusic = kind != Four.Actions
            if (kind == Four.Actions && Build.VERSION.SDK_INT >= 31) {
                // One row high: just the four buttons, centred.
                val px = (8 * ctx.resources.displayMetrics.density).toInt()
                setViewPadding(R.id.root, px, px, px, px)
                setViewLayoutMargin(R.id.actions, RemoteViews.MARGIN_TOP, 0f, android.util.TypedValue.COMPLEX_UNIT_DIP)
            }
            // The player, or the same row saying why nothing's there.
            setViewVisibility(R.id.music, if (showMusic) View.VISIBLE else View.GONE)
            setViewVisibility(R.id.empty, View.GONE)
            setViewVisibility(R.id.controls, if (kind == Four.Full && music) View.VISIBLE else View.GONE)
            setViewVisibility(R.id.play, if (music) View.VISIBLE else View.GONE)
            // Play on phone: a small button beside play at this size; the labelled one below when there's room.
            val playOn = music && (Link.PLAY_ON in Link.computerCaps || preview != null)
            setViewVisibility(R.id.here_small, if (playOn && kind == Four.Compact) View.VISIBLE else View.GONE)
            setViewVisibility(R.id.here, if (playOn) View.VISIBLE else View.INVISIBLE)
            setOnClickPendingIntent(R.id.here_small, action(ctx, ACTION_PLAY_HERE))
            if (music && track != null) {
                setTextViewText(R.id.title, track.title)
                setTextViewText(R.id.subtitle, listOf(track.artist, track.app).filter { it.isNotBlank() }.joinToString(" · ")
                    .ifEmpty { "On ${computerName()}" })
                setImageViewResource(R.id.play, if (track.playing) R.drawable.ic_w_pause else R.drawable.ic_w_play)
                setContentDescription(R.id.play, if (track.playing) "Pause" else "Play")
                setOnClickPendingIntent(R.id.play, action(ctx, ACTION_MUSIC, if (track.playing) "pause" else "play"))
                setOnClickPendingIntent(R.id.prev, action(ctx, ACTION_MUSIC, "prev"))
                setOnClickPendingIntent(R.id.next, action(ctx, ACTION_MUSIC, "next"))
                setOnClickPendingIntent(R.id.here, action(ctx, ACTION_PLAY_HERE))
                setOnClickPendingIntent(R.id.music, activity(ctx, MainActivity::class.java, 5))
            } else {
                setTextViewText(R.id.title, when {
                    !Prefs.macMusic.value -> "Music on ${Computer.word} is off"
                    !connected -> "Not connected"
                    else -> "Nothing playing"
                })
                setTextViewText(R.id.subtitle, when {
                    !Prefs.macMusic.value -> "Turn it on in Mac n Droid"
                    !connected -> "Open Mac n Droid there"
                    else -> "Play something on ${computerName()}"
                })
                setOnClickPendingIntent(R.id.music, activity(ctx, MainActivity::class.java, 5))
            }
            val showArt = music && art != null
            if (showArt) setImageViewBitmap(R.id.art, art)
            setViewVisibility(R.id.art, if (showArt) View.VISIBLE else View.GONE)
            setViewVisibility(R.id.art_placeholder, if (showArt) View.GONE else View.VISIBLE)
            // The four actions.
            setImageViewResource(R.id.a_pause, if (paused) R.drawable.ic_w_play else R.drawable.ic_w_pause)
            setContentDescription(R.id.a_pause, if (paused) "Resume sharing" else "Pause sharing")
            setInt(R.id.a_pause, "setBackgroundResource", if (paused) R.drawable.widget_btn_primary else R.drawable.widget_btn)
            setOnClickPendingIntent(R.id.a_pause, action(ctx, ACTION_TOGGLE_PAUSE))
            setOnClickPendingIntent(R.id.a_files, activity(ctx, PickFilesActivity::class.java, 2))
            setOnClickPendingIntent(R.id.a_clip, activity(ctx, SendClipboardActivity::class.java, 3))
            setOnClickPendingIntent(R.id.a_open, activity(ctx, MainActivity::class.java, 4))
        }
        return responsive(listOf(
            SizeF(180f, 40f) to { make(Four.Actions) },
            SizeF(180f, 110f) to { make(Four.Compact) },
            SizeF(180f, 170f) to { make(Four.Full) },
        )) { make(Four.Full) }
    }

    /** Album art small enough for a widget (they have a tight memory limit), square with rounded corners. */
    private fun thumb(b: Bitmap): Bitmap? = runCatching {
        val size = 144
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
        val r = size * 14f / 48f   // the same corners as the placeholder (14dp on 48dp)
        c.drawRoundRect(0f, 0f, size.toFloat(), size.toFloat(), r, r, paint)
        paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
        // Centre-crop into the square.
        val side = minOf(b.width, b.height)
        val src = android.graphics.Rect((b.width - side) / 2, (b.height - side) / 2, (b.width + side) / 2, (b.height + side) / 2)
        c.drawBitmap(b, src, android.graphics.Rect(0, 0, size, size), paint)
        out
    }.getOrNull()

    // MARK: actions

    const val ACTION_TOGGLE_PAUSE = "app.macndroid.widget.TOGGLE_PAUSE"
    const val ACTION_MUSIC = "app.macndroid.widget.MUSIC"
    const val ACTION_PLAY_HERE = "app.macndroid.widget.PLAY_HERE"

    private fun action(ctx: Context, what: String, cmd: String = ""): PendingIntent {
        val i = Intent(ctx, WidgetActionReceiver::class.java).setAction(what).putExtra("cmd", cmd)
        return PendingIntent.getBroadcast(ctx, (what + cmd).hashCode(), i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun activity(ctx: Context, c: Class<*>, code: Int): PendingIntent =
        PendingIntent.getActivity(ctx, 900 + code, Intent(ctx, c).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun handle(ctx: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TOGGLE_PAUSE -> if (Pause.active) Pause.resume(ctx) else Pause.pause(ctx, null)
            ACTION_MUSIC -> intent.getStringExtra("cmd")?.takeIf { it.isNotEmpty() }?.let(MacMusic::command)
            ACTION_PLAY_HERE -> Link.post(JSONObject().put("t", "play_on_phone"))
        }
        refresh(ctx)
    }
}

internal fun computerName() = Prefs.pairing.value?.macName ?: Computer.your

/** The widgets' buttons (pause, music, Play on phone). */
class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Widgets.handle(context, intent)
    }
}

/** Shared by the three widgets: draw them when added, resized or asked to. */
abstract class BaseWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = Widgets.refresh(context)
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) =
        Widgets.refresh(context)
}

class OneWidget : BaseWidget()
class TwoWidget : BaseWidget()
class FourWidget : BaseWidget()
