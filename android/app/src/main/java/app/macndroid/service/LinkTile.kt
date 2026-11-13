package app.macndroid.service

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.macndroid.R
import app.macndroid.core.Computer
import app.macndroid.core.Link
import app.macndroid.core.Prefs
import app.macndroid.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * A Quick Settings tile: lit while the phone is connected, with the computer's name and status underneath.
 * Tap (or long-press) to open Mac n Droid; if it isn't connected, it starts reconnecting at the same time.
 */
class LinkTile : TileService() {
    private var watch: Job? = null

    override fun onStartListening() {
        watch?.cancel()
        watch = Link.scope.launch(Dispatchers.Main) { Link.status.collect { show(it) } }
    }

    override fun onStopListening() { watch?.cancel(); watch = null }

    override fun onClick() {
        if (Prefs.pairing.value != null && Link.status.value != Link.Status.Connected) {
            SyncService.start(this)
            Link.kick()
        }
        if (isLocked) unlockAndRun { openApp() } else openApp()
    }

    private fun show(s: Link.Status) {
        val tile = qsTile ?: return
        val mac = Prefs.pairing.value?.macName ?: Computer.word
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_logo)
        tile.label = "Mac n Droid"
        tile.state = if (s == Link.Status.Connected) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = when (s) {
            Link.Status.Connected -> mac
            Link.Status.Connecting -> "Connecting…"
            Link.Status.Searching -> "Reconnecting…"
            Link.Status.AuthFailed -> "Pair again"
            Link.Status.Unpaired -> "Not paired"
        }
        tile.contentDescription = "Mac n Droid, ${tile.subtitle}"
        tile.updateTile()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated") startActivityAndCollapse(intent)
        }
    }
}
