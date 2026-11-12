package app.macndroid.service

import app.macndroid.core.Computer

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
    private var macVolume = -1
    private var volumeFixed = false
    private var lastMsg: JSONObject? = null

    /**
     * The Mac's volume as a "remote" player, like a Cast device: Android shows a volume slider for the Mac in
     * its media controls, and the volume buttons change the Mac's volume while this is the playing media.
     * No accessibility service or permission needed.
     */
    private var provider: android.media.VolumeProvider? = null

    private var macPlaying = false

    /** Phone players started or stopped: hand the volume keys back to the phone while it plays sound. */
    fun refresh() { session?.let { applyVolume(it) } }

    private fun applyVolume(s: MediaSession) {
        // The volume keys control the Mac only while the Mac is playing and the phone isn't: with music on the
        // phone too, they must keep changing the phone's own volume.
        val phonePlaying = ctx?.getSystemService(android.media.AudioManager::class.java)?.isMusicActive == true
        if (macVolume < 0 || volumeFixed || !macPlaying || phonePlaying) {
            if (provider != null) { s.setPlaybackToLocal(android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA).build()); provider = null }
            return
        }
        val p = provider ?: object : android.media.VolumeProvider(VOLUME_CONTROL_ABSOLUTE, 100, macVolume) {
            override fun onSetVolumeTo(volume: Int) = setMacVolume(volume)
            override fun onAdjustVolume(direction: Int) {
                if (direction != 0) setMacVolume(currentVolume + direction * 6)
            }
        }.also { provider = it; s.setPlaybackToRemote(it) }
        if (p.currentVolume != macVolume) p.currentVolume = macVolume
    }

    private fun setMacVolume(v: Int) {
        val vol = v.coerceIn(0, 100)
        macVolume = vol
        provider?.currentVolume = vol
        Link.post(JSONObject().put("t", "mac_volume_set").put("vol", vol))
    }

    /** The Mac's volume changed (its keys, Control Center, or this phone). */
    fun volume(msg: JSONObject) {
        macVolume = if (msg.optBoolean("muted")) 0 else msg.optInt("vol", -1)
        volumeFixed = msg.optBoolean("fixed")
        session?.let { applyVolume(it) }
    }

    /** From the notification's volume buttons. */
    fun stepVolume(up: Boolean) {
        if (macVolume < 0) return
        setMacVolume(macVolume + if (up) 6 else -6)
    }

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
        if (msg.has("vol")) {
            macVolume = if (msg.optBoolean("muted")) 0 else msg.optInt("vol", -1)
            volumeFixed = msg.optBoolean("fixed")
        }
        val playing = msg.optBoolean("playing")
        macPlaying = playing
        val app_ = msg.optString("app").ifEmpty { Computer.word }
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
        applyVolume(s)
        s.isActive = true
        post(app, s, title, msg.optString("artist"), app_, playing)
    }

    private fun post(c: Context, s: MediaSession, title: String, artist: String, app: String, playing: Boolean) {
        val mac = Prefs.pairing.value?.macName ?: Computer.word
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
            .apply {
                // Expanded notification: the Mac's volume, down and up.
                if (macVolume >= 0 && !volumeFixed) {
                    addAction(action(R.drawable.ic_volume_down, "Volume down", "vol_down"))
                    addAction(action(R.drawable.ic_volume_up, "Volume up", "vol_up"))
                }
            }
            .setStyle(Notification.MediaStyle().setMediaSession(s.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
        runCatching { c.getSystemService(NotificationManager::class.java).notify(NOTIF, n); isShowing = true }
    }

    fun command(cmd: String) {
        if (cmd == "vol_up" || cmd == "vol_down") { stepVolume(cmd == "vol_up"); return }
        Link.post(JSONObject().put("t", "mac_media_cmd").put("cmd", cmd))
    }

    fun end() {
        ctx?.getSystemService(NotificationManager::class.java)?.cancel(NOTIF)
        session?.let { it.isActive = false; it.release() }
        session = null
        provider = null
        macPlaying = false
        macVolume = -1
        art = null
        lastTitle = ""
        isShowing = false
    }
}
