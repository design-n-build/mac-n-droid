package app.macndroid.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import app.macndroid.MacNDroidApp
import app.macndroid.R
import app.macndroid.core.Link
import org.json.JSONObject

/** "Find my phone": rings at full alarm volume, even on silent, until stopped or 60 s pass. */
class Ringer(private val ctx: Context) {
    private var player: MediaPlayer? = null
    private var savedVolume = -1
    private val handler = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { stop(notifyMac = true) }

    fun start() {
        if (player != null) return
        val audio = ctx.getSystemService(AudioManager::class.java)
        savedVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)

        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
                setDataSource(ctx, uri)
                isLooping = true
                prepare()
                start()
            }
        }.getOrNull()

        ctx.getSystemService(Vibrator::class.java)?.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0, 600, 400), 0))

        val stop = PendingIntent.getService(
            ctx, 10, Intent(ctx, SyncService::class.java).setAction(SyncService.ACTION_STOP_RING),
            PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, MacNDroidApp.CH_RING)
            .setSmallIcon(R.drawable.ic_stat_logo)
            .setContentTitle("Your Mac is looking for this phone")
            .setContentText("Tap to stop ringing")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(stop)
            .setDeleteIntent(stop)
            .addAction(R.drawable.ic_stat_logo, "Stop", stop)
            .build()
        runCatching { ctx.getSystemService(android.app.NotificationManager::class.java).notify(RING_ID, n) }
        handler.postDelayed(autoStop, 60_000)
    }

    fun stop(notifyMac: Boolean) {
        handler.removeCallbacks(autoStop)
        val p = player ?: return
        player = null
        runCatching { p.stop(); p.release() }
        ctx.getSystemService(Vibrator::class.java)?.cancel()
        if (savedVolume >= 0) {
            runCatching { ctx.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0) }
        }
        ctx.getSystemService(android.app.NotificationManager::class.java).cancel(RING_ID)
        if (notifyMac) Link.post(JSONObject().put("t", "ring_stopped"))
    }

    companion object { const val RING_ID = 42 }
}
