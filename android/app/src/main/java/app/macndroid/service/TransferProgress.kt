package app.macndroid.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import app.macndroid.MacNDroidApp
import app.macndroid.R
import app.macndroid.core.Computer
import app.macndroid.core.Link
import app.macndroid.core.Prefs
import app.macndroid.ui.MainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A live progress notification while files are moving, so a big video shows how far along it is without
 * opening the app. Android 16 draws it as a progress-style notification; older versions as a progress bar.
 */
object TransferProgress {
    private const val ID = 47
    private var job: Job? = null

    fun start(ctx: Context) {
        if (job?.isActive == true) return
        val app = ctx.applicationContext
        job = Link.scope.launch {
            Transfers.items.collect { items ->
                show(app, items.filter { it.state == TransferItem.State.Active })
                delay(500)   // at most twice a second; Android drops faster updates anyway
            }
        }
    }

    private fun show(ctx: Context, active: List<TransferItem>) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (active.isEmpty()) { nm.cancel(ID); return }
        val mac = Prefs.pairing.value?.macName ?: Computer.word
        val out = active.count { it.outgoing }
        val title = when {
            active.size == 1 && out == 1 -> "Sending ${active[0].name} to $mac"
            active.size == 1 -> "Receiving ${active[0].name}"
            out == active.size -> "Sending ${active.size} files to $mac"
            out == 0 -> "Receiving ${active.size} files"
            else -> "Moving ${active.size} files"
        }
        val total = active.sumOf { it.size.coerceAtLeast(0) }
        val done = active.sumOf { it.bytes }
        val permille = if (total > 0) (done * 1000 / total).toInt().coerceIn(0, 1000) else 0
        val open = PendingIntent.getActivity(ctx, 3, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val text = if (total > 0) "${permille / 10}% · ${size(done)} of ${size(total)}" else "Starting…"

        val n: Notification = if (Build.VERSION.SDK_INT >= 36) {
            Notification.Builder(ctx, MacNDroidApp.CH_PROGRESS)
                .setSmallIcon(R.drawable.ic_upload)
                .setContentTitle(title).setContentText(text)
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .setStyle(Notification.ProgressStyle()
                    .setProgressSegments(listOf(Notification.ProgressStyle.Segment(1000).setColor(0xFF0DA457.toInt())))
                    .setProgress(permille)
                    .setStyledByProgress(true))
                .build()
        } else {
            NotificationCompat.Builder(ctx, MacNDroidApp.CH_PROGRESS)
                .setSmallIcon(R.drawable.ic_upload)
                .setContentTitle(title).setContentText(text)
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
                .setProgress(1000, permille, total <= 0)
                .build()
        }
        runCatching { nm.notify(ID, n) }
    }

    private fun size(b: Long): String = when {
        b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
        else -> "${(b / 1024).coerceAtLeast(1)} KB"
    }
}
