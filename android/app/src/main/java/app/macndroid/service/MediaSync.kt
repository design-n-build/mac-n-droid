package app.macndroid.service

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.os.Build
import app.macndroid.core.Link
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Now Playing for every media app on the phone (YouTube, YouTube Music, Spotify, podcasts…).
 * Each player is sent to the Mac separately (`media` with `id`, `media_end` when it goes away),
 * so the Mac can list them all and control any of them. Uses the notification-listener permission.
 */
class MediaSync(private val ctx: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val msm = ctx.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(ctx, NotificationMirror::class.java)

    private class Player(val controller: MediaController, val callback: MediaController.Callback) {
        val id: String = controller.packageName
        var playingSince = 0L
        var lastSignature = ""
        var lastArtKey: String? = null
        var sent = false
        var iconSent = false
    }

    private val players = mutableMapOf<MediaSession.Token, Player>()
    private var started = false

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> sync(list.orEmpty()) }

    private fun callbackFor(token: () -> MediaSession.Token) = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            val p = players[token()] ?: return
            // Never pause other apps here: Android's audio focus already decides who plays sound, and a silent
            // video starting in a feed (Instagram, YouTube) would otherwise stop your music.
            push(p)
            handler.postDelayed({ MacMusic.refresh() }, 500)
        }
        override fun onMetadataChanged(metadata: MediaMetadata?) { players[token()]?.let(::push) }
        override fun onSessionDestroyed() = refreshSessions()
    }

    private fun isPlaying(st: PlaybackState?) =
        st?.state == PlaybackState.STATE_PLAYING || st?.state == PlaybackState.STATE_BUFFERING
    private fun isAlive(st: PlaybackState?) =
        st != null && st.state != PlaybackState.STATE_NONE && st.state != PlaybackState.STATE_STOPPED && st.state != PlaybackState.STATE_ERROR

    fun start() {
        if (started) return
        runCatching {
            msm.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
            started = true
            refreshSessions()
        }   // SecurityException until notification access is granted
    }

    fun stop() {
        runCatching { msm.removeOnActiveSessionsChangedListener(sessionsListener) }
        players.values.forEach { it.controller.unregisterCallback(it.callback) }
        players.clear()
        started = false
    }

    /** On (re)connect: send everything again, including album art. */
    fun resync() {
        if (!started) start()
        handler.post {
            players.values.forEach { it.lastSignature = ""; it.lastArtKey = null; it.sent = false; it.iconSent = false; push(it) }
        }
    }

    private fun refreshSessions() {
        // Skip our own "music on your Mac" player, or it would bounce back to the Mac.
        handler.post { runCatching { sync(msm.getActiveSessions(component).filter { it.packageName != ctx.packageName }) } }
    }

    private fun sync(all: List<MediaController>) {
        val list = all.filter { it.packageName != ctx.packageName }
        val tokens = list.map { it.sessionToken }.toSet()
        players.keys.filter { it !in tokens }.forEach { t ->
            players.remove(t)?.let { p ->
                p.controller.unregisterCallback(p.callback)
                if (p.sent && players.values.none { it.id == p.id }) end(p.id)
            }
        }
        for (c in list) {
            if (c.sessionToken in players) continue
            val token = c.sessionToken
            val p = Player(c, callbackFor { token })
            c.registerCallback(p.callback, handler)
            players[token] = p
        }
        players.values.forEach(::push)
    }

    private fun end(id: String) = Link.post(JSONObject().put("t", "media_end").put("id", id))

    private fun push(p: Player) {
        if (!Link.isConnected) return
        val c = p.controller
        val md = c.metadata
        val st = c.playbackState
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        if (md == null || st == null || title.isNullOrBlank() || !isAlive(st)) {
            if (p.sent) { p.sent = false; p.lastSignature = ""; end(p.id) }
            return
        }
        val playing = isPlaying(st)
        if (playing && p.playingSince == 0L) p.playingSince = System.currentTimeMillis()
        if (!playing) p.playingSince = 0L
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE).orEmpty()
        val album = md.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
        val duration = md.getLong(MediaMetadata.METADATA_KEY_DURATION)
        // Position as of a wall-clock instant, so the Mac can tick the progress bar itself.
        val positionAt = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - st.lastPositionUpdateTime)
        val a = st.actions
        val msg = JSONObject()
            .put("t", "media").put("active", true).put("id", p.id)
            .put("pkg", c.packageName).put("app", appLabel(c.packageName))
            .put("title", title).put("artist", artist).put("album", album)
            .put("duration", duration).put("playing", playing)
            .put("since", p.playingSince.toDouble())
            .put("position", st.position).put("positionAt", positionAt.toDouble())
            .put("speed", st.playbackSpeed.toDouble())
            .put("canPrev", a and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L)
            .put("canNext", a and PlaybackState.ACTION_SKIP_TO_NEXT != 0L)
            .put("canSeek", a and PlaybackState.ACTION_SEEK_TO != 0L)

        val artKey = "$title|$artist|$album"
        val signature = "$artKey|$duration|$playing|${st.position / 1000}|${st.playbackSpeed}"
        if (signature == p.lastSignature) return
        p.lastSignature = signature
        if (artKey != p.lastArtKey) {
            val bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            msg.put("art", bmp?.let(::encodeArt) ?: "")
            p.lastArtKey = artKey
        }
        if (!p.iconSent) { appIcon(c.packageName)?.let { msg.put("icon", it) }; p.iconSent = true }
        p.sent = true
        Link.post(msg)
    }

    /** `id` picks the player (package name); without it, the most recently playing one. */
    fun command(cmd: String, position: Long, id: String?) = handler.post {
        val p = players.values.firstOrNull { it.id == id }
            ?: players.values.maxByOrNull { it.playingSince }
            ?: return@post
        val t = p.controller.transportControls
        // Android 17 mutes an app that starts playing in the background because another app asked, unless that
        // app may act for you in the background (Mac n Droid can while one of its accessibility services is on).
        // Tell the computer, so it can explain instead of the music silently not playing.
        val starts = cmd == "play" || (cmd == "toggle" && !isPlaying(p.controller.playbackState))
        if (starts && Build.VERSION.SDK_INT >= 37 && !Controls.running) {
            Link.post(JSONObject().put("t", "media_limited").put("id", p.id).put("reason", "background"))
        }
        when (cmd) {
            "play" -> t.play()
            "pause" -> t.pause()
            "toggle" -> if (isPlaying(p.controller.playbackState)) t.pause() else t.play()
            "next" -> t.skipToNext()
            "prev" -> t.skipToPrevious()
            "seek" -> t.seekTo(position)
        }
    }

    private fun encodeArt(bmp: Bitmap): String? = runCatching {
        val size = 300
        val scaled = if (maxOf(bmp.width, bmp.height) > size) {
            val s = size.toFloat() / maxOf(bmp.width, bmp.height)
            Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt().coerceAtLeast(1), (bmp.height * s).toInt().coerceAtLeast(1), true)
        } else bmp
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }.getOrNull()

    private fun appIcon(pkg: String): String? = runCatching {
        val d = ctx.packageManager.getApplicationIcon(pkg)
        val bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, 96, 96)
        d.draw(android.graphics.Canvas(bmp))
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }.getOrNull()

    private fun appLabel(pkg: String): String = runCatching {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}
