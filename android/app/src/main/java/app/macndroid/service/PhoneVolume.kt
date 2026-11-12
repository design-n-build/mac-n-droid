package app.macndroid.service

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import app.macndroid.core.Link
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * The phone's media volume, shown on the computer as a slider next to what's playing (`volume` /
 * `volume_set` in PROTOCOL.md). Setting the music volume needs no permission and no accessibility service.
 */
class PhoneVolume(private val ctx: Context) {
    private val audio = ctx.getSystemService(AudioManager::class.java)
    private var last = -1
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = report()
    }

    fun start() {
        // Volume changes (buttons, the volume panel, other apps) update the system settings.
        ctx.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
    }

    fun stop() = ctx.contentResolver.unregisterContentObserver(observer)

    private fun percent(): Int {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return (audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max).roundToInt()
    }

    fun report(force: Boolean = false) {
        if (!Link.isConnected) return
        val v = percent()
        if (!force && v == last) return
        last = v
        Link.post(JSONObject().put("t", "volume").put("vol", v))
    }

    /** From the computer's slider: 0…100. Shows Android's volume panel so the change is visible here. */
    fun set(percent: Int) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val index = (percent.coerceIn(0, 100) * max / 100f).roundToInt()
        runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, index, AudioManager.FLAG_SHOW_UI) }
        report()
    }
}
