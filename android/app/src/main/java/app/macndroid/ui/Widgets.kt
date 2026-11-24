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
 * One home-screen widget that changes with its size, like Google's widgets. A plain Android widget (RemoteViews) in
 * Material You colours (res/values-v31); on Android 12+ the launcher gets a layout per size and picks the one that
 * fits as it's resized:
 *  - 1×1 ([Size.One]): the logo, filled while sharing and dimmed with a pause badge while paused. Tap to switch.
 *  - 2×1 ([Size.Two]): Pause/Resume and Send files.
 *  - 4×1 ([Size.Four]): and Send clipboard and Open.
 *  - 2×2 ([Size.Square]): whether sharing is on and with which computer, and two labelled buttons.
 *  - 4×2 ([Size.Player]): the computer's music (Play on phone, play/pause) and the four buttons.
 *  - 4×3 and up ([Size.Full]): the connection, the music with previous/next and Play on phone, and labelled buttons.
 */
object Widgets {
    private enum class Size { One, Two, Four, Square, Player, Full }

    /** Each layout and the smallest size (dp) it's shown at; the launcher picks the largest that fits. */
    private val SIZES = listOf(
        Size.One to SizeF(40f, 40f),
        Size.Two to SizeF(100f, 40f),
        Size.Four to SizeF(180f, 40f),
        Size.Square to SizeF(100f, 100f),
        Size.Player to SizeF(180f, 100f),
        Size.Full to SizeF(180f, 180f),
    )

    /** Redraw the widget (pausing, connecting, the computer's music changing…). */
    fun refresh(ctx: Context) {
        val app = ctx.applicationContext
        val mgr = AppWidgetManager.getInstance(app) ?: return
        val ids = runCatching { mgr.getAppWidgetIds(ComponentName(app, MacNDroidWidget::class.java)) }.getOrDefault(IntArray(0))
        if (ids.isEmpty()) return
        val views = build(app)
        ids.forEach { runCatching { mgr.updateAppWidget(it, views) } }
    }

    private fun build(ctx: Context): RemoteViews {
        val s = State()
        preview?.let { (w, h) ->
            val pick = SIZES.filter { it.second.width <= w && it.second.height <= h }.maxByOrNull { it.second.width * it.second.height }
            return views(ctx, pick?.first ?: Size.One, s)
        }
        return if (Build.VERSION.SDK_INT >= 31) RemoteViews(SIZES.associate { (size, min) -> min to views(ctx, size, s) })
               else views(ctx, Size.Player, s)
    }

    /** What every size shows, read once per update. */
    private class State {
        val paused = Pause.active
        val connected = Link.isConnected || (preview != null && previewTrack != null)
        val computer = Prefs.pairing.value?.macName ?: Computer.your
        val track = if (preview != null) previewTrack else MacMusic.current
        val musicOn = Prefs.macMusic.value || preview != null
        val music = musicOn && connected && track != null
        val art = track?.art?.let(::thumb)
        val playOn = Link.PLAY_ON in Link.computerCaps || preview != null
        val paired = Prefs.pairing.value != null || preview != null
    }

    private fun views(ctx: Context, size: Size, s: State): RemoteViews = when (size) {
        Size.One -> one(ctx, s)
        Size.Two, Size.Four -> row(ctx, s, four = size == Size.Four)
        Size.Square -> square(ctx, s)
        Size.Player, Size.Full -> player(ctx, s, full = size == Size.Full)
    }

    // MARK: 1×1

    private fun one(ctx: Context, s: State) = RemoteViews(ctx.packageName, R.layout.widget_one).apply {
        setViewVisibility(R.id.on, if (s.paused) View.GONE else View.VISIBLE)
        setViewVisibility(R.id.off, if (s.paused) View.VISIBLE else View.GONE)
        setViewVisibility(R.id.badge, if (s.paused) View.VISIBLE else View.GONE)
        setViewVisibility(R.id.label, View.GONE)
        setContentDescription(R.id.root, if (s.paused) "Sharing paused. Tap to resume." else "Sharing on. Tap to pause.")
        setOnClickPendingIntent(R.id.root, action(ctx, ACTION_TOGGLE_PAUSE))
    }

    // MARK: 2×1 and 4×1

    private fun row(ctx: Context, s: State, four: Boolean) = RemoteViews(ctx.packageName, R.layout.widget_row).apply {
        pauseButton(this, s, R.id.b_pause, R.id.b_pause, ctx)
        setOnClickPendingIntent(R.id.b_files, activity(ctx, PickFilesActivity::class.java, 1))
        setViewVisibility(R.id.b_clip, if (four) View.VISIBLE else View.GONE)
        setViewVisibility(R.id.b_open, if (four) View.VISIBLE else View.GONE)
        setOnClickPendingIntent(R.id.b_clip, activity(ctx, SendClipboardActivity::class.java, 2))
        setOnClickPendingIntent(R.id.b_open, activity(ctx, MainActivity::class.java, 3))
    }

    // MARK: 2×2

    private fun square(ctx: Context, s: State) = RemoteViews(ctx.packageName, R.layout.widget_square).apply {
        setViewVisibility(R.id.on, if (s.paused) View.GONE else View.VISIBLE)
        setViewVisibility(R.id.off, if (s.paused) View.VISIBLE else View.GONE)
        setTextViewText(R.id.status_title, statusTitle(s))
        setTextViewText(R.id.status_sub, if (s.paired) s.computer.replaceFirstChar { it.uppercase() } else "Open the app to pair")
        setOnClickPendingIntent(R.id.status, activity(ctx, MainActivity::class.java, 4))
        pauseButton(this, s, R.id.c_pause, R.id.c_pause_icon, ctx, labelId = R.id.c_pause_label, tile = true)
        setOnClickPendingIntent(R.id.c_files, activity(ctx, PickFilesActivity::class.java, 1))
    }

    // MARK: 4×2 and larger

    private fun player(ctx: Context, s: State, full: Boolean) = RemoteViews(ctx.packageName, R.layout.widget_four).apply {
        // The connection line (tallest size only).
        setViewVisibility(R.id.header, if (full) View.VISIBLE else View.GONE)
        setTextViewText(R.id.header_text, when {
            !s.paired -> "Mac n Droid · Not paired"
            s.paused -> "Sharing paused"
            s.connected -> "Connected to ${s.computer}"
            else -> "${s.computer.replaceFirstChar { it.uppercase() }} isn't connected"
        })
        // The computer's music, or why there isn't any.
        val t = s.track
        setViewVisibility(R.id.empty, View.GONE)
        setViewVisibility(R.id.play, if (s.music) View.VISIBLE else View.GONE)
        setViewVisibility(R.id.controls, if (full && s.music) View.VISIBLE else View.GONE)
        setViewVisibility(R.id.here_small, if (s.music && s.playOn && !full) View.VISIBLE else View.GONE)
        setViewVisibility(R.id.here, if (s.playOn) View.VISIBLE else View.INVISIBLE)
        if (s.music && t != null) {
            setTextViewText(R.id.title, t.title)
            setTextViewText(R.id.subtitle, listOf(t.artist, t.app).filter { it.isNotBlank() }.joinToString(" · ")
                .ifEmpty { "On ${s.computer}" })
            setImageViewResource(R.id.play, if (t.playing) R.drawable.ic_w_pause else R.drawable.ic_w_play)
            setContentDescription(R.id.play, if (t.playing) "Pause" else "Play")
            setOnClickPendingIntent(R.id.play, action(ctx, ACTION_MUSIC, if (t.playing) "pause" else "play"))
            setOnClickPendingIntent(R.id.prev, action(ctx, ACTION_MUSIC, "prev"))
            setOnClickPendingIntent(R.id.next, action(ctx, ACTION_MUSIC, "next"))
            setOnClickPendingIntent(R.id.here, action(ctx, ACTION_PLAY_HERE))
            setOnClickPendingIntent(R.id.here_small, action(ctx, ACTION_PLAY_HERE))
        } else {
            setTextViewText(R.id.title, when {
                !s.musicOn -> "Music on ${Computer.word} is off"
                !s.connected -> "Not connected"
                else -> "Nothing playing"
            })
            setTextViewText(R.id.subtitle, when {
                !s.musicOn -> "Turn it on in Mac n Droid"
                !s.connected -> "Open Mac n Droid on ${Computer.your}"
                else -> "Music and videos on ${s.computer} show here"
            })
        }
        setOnClickPendingIntent(R.id.music, activity(ctx, MainActivity::class.java, 5))
        val art = s.art
        val showArt = s.music && art != null
        if (showArt) setImageViewBitmap(R.id.art, art)
        setViewVisibility(R.id.art, if (showArt) View.VISIBLE else View.GONE)
        setViewVisibility(R.id.art_placeholder, if (showArt) View.GONE else View.VISIBLE)
        // The four buttons: labels when there's room.
        val labels = if (full) View.VISIBLE else View.GONE
        listOf(R.id.a_pause_label, R.id.a_files_label, R.id.a_clip_label, R.id.a_open_label).forEach { setViewVisibility(it, labels) }
        pauseButton(this, s, R.id.a_pause, R.id.a_pause_icon, ctx, labelId = R.id.a_pause_label)
        setOnClickPendingIntent(R.id.a_files, activity(ctx, PickFilesActivity::class.java, 1))
        setOnClickPendingIntent(R.id.a_clip, activity(ctx, SendClipboardActivity::class.java, 2))
        setOnClickPendingIntent(R.id.a_open, activity(ctx, MainActivity::class.java, 3))
    }

    private fun statusTitle(s: State) = when {
        !s.paired -> "Not paired"
        s.paused -> "Sharing paused"
        s.connected -> "Sharing on"
        else -> "Away"
    }

    /** Pause / Resume: the icon says what a tap does, and the button is filled while paused, so it stands out. */
    private fun pauseButton(rv: RemoteViews, s: State, buttonId: Int, iconId: Int, ctx: Context, labelId: Int? = null,
                            tile: Boolean = false) {
        rv.setImageViewResource(iconId, if (s.paused) R.drawable.ic_w_play else R.drawable.ic_w_pause)
        rv.setInt(buttonId, "setBackgroundResource", when {
            tile -> if (s.paused) R.drawable.widget_tile_primary else R.drawable.widget_art_bg
            else -> if (s.paused) R.drawable.widget_btn_primary else R.drawable.widget_btn
        })
        // On the filled button the icon and label use the primary's text colour.
        if (s.paused && Build.VERSION.SDK_INT >= 31) {
            rv.setColorStateList(iconId, "setImageTintList", R.color.w_on_primary)
            labelId?.let { rv.setColorStateList(it, "setTextColor", R.color.w_on_primary) }
        }
        labelId?.let { rv.setTextViewText(it, if (s.paused) "Resume" else "Pause") }
        rv.setContentDescription(buttonId, if (s.paused) "Resume sharing" else "Pause sharing")
        rv.setOnClickPendingIntent(buttonId, action(ctx, ACTION_TOGGLE_PAUSE))
    }

    /** Album art small enough for a widget (they have a tight memory limit), square with rounded corners. */
    private fun thumb(b: Bitmap): Bitmap? = runCatching {
        val size = 144
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
        val r = size * 14f / 44f   // the same corners as the placeholder (14dp on 44dp)
        c.drawRoundRect(0f, 0f, size.toFloat(), size.toFloat(), r, r, paint)
        paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
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

    // MARK: screenshot tests

    /** Tests only: the computer's music to show. */
    @Volatile internal var previewTrack: MacMusic.Track? = null

    /** Tests only: build the layout for this size (dp) instead of the responsive set. */
    @Volatile private var preview: Pair<Float, Float>? = null

    internal fun previewViews(ctx: Context, w: Float, h: Float): RemoteViews {
        preview = w to h
        try { return build(ctx) } finally { preview = null }
    }
}

/** The widget's buttons (pause, music, Play on phone). */
class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Widgets.handle(context, intent)
}

/** The home-screen widget: drawn when added, resized or asked to. */
class MacNDroidWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = Widgets.refresh(context)
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) =
        Widgets.refresh(context)
}
