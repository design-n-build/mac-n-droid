package app.macndroid.service

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.macndroid.R
import app.macndroid.core.Pause
import app.macndroid.core.Prefs
import app.macndroid.ui.pauseEndText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Quick Settings tiles that pause sharing (see [Pause]): for 1 hour, for 3 hours, or until you resume.
 * Add whichever you like from the Quick Settings edit screen. While paused, every tile is lit and shows when
 * sharing comes back; tapping any of them resumes.
 */
abstract class PauseTileBase(private val durationMs: Long?, private val label: String) : TileService() {
    private var watch: Job? = null

    override fun onStartListening() {
        watch?.cancel()
        watch = app.macndroid.core.Link.scope.launch(Dispatchers.Main) { Pause.until.collect { show(it) } }
    }

    override fun onStopListening() { watch?.cancel(); watch = null }

    override fun onClick() {
        if (Prefs.pairing.value == null) return
        if (Pause.active) Pause.resume(this) else Pause.pause(this, durationMs)
    }

    private fun show(until: Long) {
        val tile = qsTile ?: return
        tile.icon = Icon.createWithResource(this, R.drawable.ic_pause_tile)
        val paused = until != 0L
        tile.state = when {
            Prefs.pairing.value == null -> Tile.STATE_UNAVAILABLE
            paused -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = if (paused) "Sharing paused" else label
        tile.subtitle = if (paused) pauseEndText(this, until) else "Mac n Droid"
        tile.contentDescription = if (paused) "Sharing paused, ${tile.subtitle}. Tap to resume." else "$label, Mac n Droid"
        tile.updateTile()
    }
}

class PauseHourTile : PauseTileBase(Pause.HOUR, "Pause 1 hour")
class PauseThreeHoursTile : PauseTileBase(3 * Pause.HOUR, "Pause 3 hours")
class PauseTile : PauseTileBase(null, "Pause sharing")
