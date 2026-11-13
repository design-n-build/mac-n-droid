package app.macndroid.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.service.quicksettings.TileService
import app.macndroid.service.Gallery
import app.macndroid.service.MacMusic
import app.macndroid.service.PauseHourTile
import app.macndroid.service.PauseThreeHoursTile
import app.macndroid.service.PauseTile
import app.macndroid.service.ScreenMirror
import app.macndroid.service.SyncService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * "Pause sharing": the phone stays connected to the computer, but nothing is shared until you resume.
 *
 * Pausing remembers which of the app's own switches were on and turns them off; resuming turns exactly those
 * back on. Things that run on Android's own permissions (notifications, controls, screen sharing) aren't
 * switched off in Settings — the link simply stops carrying them (see [allowsOut] and [allowsIn]), so
 * resuming is instant and nothing has to be allowed again. Find my phone and the battery level keep working.
 */
object Pause {
    /** 0 = not paused; [FOREVER] = until you resume; otherwise the time it resumes by itself (ms). */
    const val FOREVER = Long.MAX_VALUE
    const val HOUR = 60 * 60_000L

    private lateinit var sp: SharedPreferences
    private lateinit var appCtx: Context
    private val _until = MutableStateFlow(0L)
    val until: StateFlow<Long> = _until
    val active: Boolean get() = _until.value != 0L
    private var timer: Job? = null

    /** The switches a pause turns off and a resume puts back. */
    private val switches: List<Triple<String, () -> Boolean, (Context, Boolean) -> Unit>> = listOf(
        Triple("receiveClipboard", { Prefs.receiveClipboard.value }, { _, on -> Prefs.setReceiveClipboard(on) }),
        Triple("screenshotsToMac", { Prefs.screenshotsToMac.value }, { _, on -> Prefs.setScreenshotsToMac(on) }),
        Triple("galleryOnMac", { Prefs.galleryOnMac.value }, { c, on -> Prefs.setGalleryOnMac(on); Gallery.reportAccess(c) }),
        Triple("smsOnMac", { Prefs.smsOnMac.value }, { _, on -> Prefs.setSmsOnMac(on) }),
        Triple("macMusic", { Prefs.macMusic.value }, { _, on ->
            Prefs.setMacMusic(on)
            if (!on) MacMusic.end()
            Link.post(JSONObject().put("t", "mac_media_want").put("on", on))
        }),
        Triple("leftBehind", { Prefs.leftBehind.value }, { _, on -> Prefs.setLeftBehind(on) }),
    )

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        sp = ctx.getSharedPreferences("pause", Context.MODE_PRIVATE)
        _until.value = sp.getLong("until", 0L)
        if (active && _until.value != FOREVER && _until.value <= System.currentTimeMillis()) resume(ctx) else armTimer()
    }

    /** Pause for [durationMs], or until you resume when null. Pausing again just changes when it ends. */
    fun pause(ctx: Context, durationMs: Long?) {
        if (!active) {
            // Remember what was on, then switch it off while the link still carries the "off" messages.
            sp.edit().apply { switches.forEach { (key, get, _) -> putBoolean("was_$key", get()) } }.apply()
            switches.forEach { (_, get, set) -> if (get()) set(ctx, false) }
            ScreenMirror.stop(notifyMac = true)
        }
        val until = durationMs?.let { System.currentTimeMillis() + it } ?: FOREVER
        sp.edit().putLong("until", until).apply()
        _until.value = until
        armTimer()
        tellComputer()
        changed(ctx)
        Haptics.play(Haptics.Kind.Click)
    }

    fun resume(ctx: Context) {
        if (!active) return
        sp.edit().putLong("until", 0L).apply()
        _until.value = 0L
        timer?.cancel()
        // Exactly what was on before, nothing more.
        switches.forEach { (key, _, set) -> if (sp.getBoolean("was_$key", false)) set(ctx, true) }
        tellComputer()
        // Catch the computer up (notifications, music, clipboard history…), as on a fresh connection.
        if (Link.isConnected) Link.scope.launch { runCatching { Link.onConnected() } }
        changed(ctx)
        Haptics.play(Haptics.Kind.Success)
    }

    /** Called when the link connects, so the computer knows straight away. */
    fun onConnected() { if (active) tellComputer() }

    private fun tellComputer() {
        val u = _until.value
        Link.post(JSONObject().put("t", "paused").put("on", u != 0L).put("until", if (u == FOREVER) 0L else u))
    }

    private fun armTimer() {
        timer?.cancel()
        val u = _until.value
        if (u == 0L || u == FOREVER) return
        timer = Link.scope.launch {
            delay((u - System.currentTimeMillis()).coerceAtLeast(0))
            if (_until.value == u) resume(appCtx)
        }
    }

    /** Refresh the status notification and the Quick Settings tiles. */
    private fun changed(ctx: Context) {
        runCatching {
            ctx.startService(Intent(ctx, SyncService::class.java).setAction(SyncService.ACTION_REFRESH))
        }
        listOf(PauseHourTile::class.java, PauseThreeHoursTile::class.java, PauseTile::class.java).forEach {
            runCatching { TileService.requestListeningState(ctx, ComponentName(ctx, it)) }
        }
    }

    // MARK: what still travels while paused

    /** Phone → computer: staying connected, battery, Find my phone, and messages that turn things off. */
    private val OUT = setOf(
        "ping", "pong", "hello", "device", "battery", "paused", "ring_stopped",
        "gallery_access", "mac_media_want", "notif_removed", "live_end", "media_end", "mirror_stopped",
    )
    /** Computer → phone: Find my phone, and the computer going to sleep. */
    private val IN = setOf("ring", "ring_stop", "bye")

    fun allowsOut(type: String) = !active || type in OUT
    fun allowsIn(type: String) = !active || type in IN
}
