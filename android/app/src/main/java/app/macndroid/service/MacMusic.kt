package app.macndroid.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock
import android.util.Base64
import app.macndroid.MacNDroidApp
import app.macndroid.R
import app.macndroid.core.Link
import app.macndroid.core.Prefs
import app.macndroid.ui.MainActivity
import org.json.JSONObject

/**
 * What's playing on the Mac (Music or Spotify), as a normal Android media player: it shows in the
 * notification shade and the Quick Settings media carousel, with play/pause and skip.
 *
 * The session never plays sound itself, so it doesn't take over headphone buttons or audio focus.
 */
object MacMusic {
    private const val NOTIF = 70
    private var session: MediaSession? = null
    private var ctx: Context? = null
    private var art: Bitmap? = null
    private var lastTitle = ""
    @Volatile var isShowing = false
        private set

    fun receive(c: Context, msg: JSONObject) {
        if (!Prefs.macMusic.value) { end(); return }
        val app = c.applicationContext
        ctx = app
        val title = msg.optString("title")
        if (title.isEmpty()) { end(); return }
        val s = session ?: MediaSession(app, "MacMusic").also { s ->
            s.setCallback(object : MediaSession.Callback() {
                override fun onPlay() = command("play")
                override fun onPause() = command("pause")
                override fun onSkipToNext() = command("next")
                override fun onSkipToPrevious() = command("prev")
                override fun onStop() = command("pause")
            })
            s.setSessionActivity(PendingIntent.getActivity(app, 0, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            session = s
        }
        if (title != lastTitle) art = null
        lastTitle = title
        msg.optString("art").takeIf { it.isNotEmpty() }?.let { b64 ->
            art = runCatching { Base64.decode(b64, Base64.DEFAULT).let { BitmapFactory.decodeByteArray(it, 0, it.size) } }.getOrNull() ?: art
        }
        val playing = msg.optBoolean("playing")
        val app_ = msg.optString("app").ifEmpty { "Mac" }
        val durMs = (msg.optDouble("dur", 0.0) * 1000).toLong()
        val posMs = (msg.optDouble("pos", 0.0) * 1000).toLong()
        s.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, msg.optString("artist"))
            .putString(MediaMetadata.METADATA_KEY_ALBUM, msg.optString("album"))
            .putLong(MediaMetadata.METADATA_KEY_DURATION, durMs)
            .apply { art?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) } }
            .build())
        s.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
            .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, posMs, if (playing) 1f else 0f,
                SystemClock.elapsedRealtime())
            .build())
        s.isActive = true
        post(app, s, title, msg.optString("artist"), app_, playing)
    }

    private fun post(c: Context, s: MediaSession, title: String, artist: String, app: String, playing: Boolean) {
        val mac = Prefs.pairing.value?.macName ?: "Mac"
        fun action(icon: Int, label: String, cmd: String) = Notification.Action.Builder(
            Icon.createWithResource(c, icon), label,
            PendingIntent.getService(c, cmd.hashCode(), Intent(c, SyncService::class.java)
                .setAction(SyncService.ACTION_MAC_MEDIA).putExtra("cmd", cmd), PendingIntent.FLAG_IMMUTABLE),
        ).build()
        val n = Notification.Builder(c, MacNDroidApp.CH_MAC_MUSIC)
            .setSmallIcon(R.drawable.ic_stat_logo)
            .setContentTitle(title)
            .setContentText(artist.ifEmpty { app })
            .setSubText("$app on $mac")
            .setLargeIcon(art)
            .setOngoing(playing)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action(android.R.drawable.ic_media_previous, "Previous", "prev"))
            .addAction(if (playing) action(android.R.drawable.ic_media_pause, "Pause", "pause")
                else action(android.R.drawable.ic_media_play, "Play", "play"))
            .addAction(action(android.R.drawable.ic_media_next, "Next", "next"))
            .setStyle(Notification.MediaStyle().setMediaSession(s.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
        runCatching { c.getSystemService(NotificationManager::class.java).notify(NOTIF, n); isShowing = true }
    }

    fun command(cmd: String) {
        Link.post(JSONObject().put("t", "mac_media_cmd").put("cmd", cmd))
    }

    fun end() {
        ctx?.getSystemService(NotificationManager::class.java)?.cancel(NOTIF)
        session?.let { it.isActive = false; it.release() }
        session = null
        art = null
        lastTitle = ""
        isShowing = false
    }
}
