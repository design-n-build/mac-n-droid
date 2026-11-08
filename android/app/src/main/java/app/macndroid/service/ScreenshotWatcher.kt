package app.macndroid.service

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import app.macndroid.core.Link
import app.macndroid.core.Prefs

/** Puts every new phone screenshot on the Mac's clipboard (and nowhere else). */
class ScreenshotWatcher(private val ctx: Context) : ContentObserver(Handler(Looper.getMainLooper())) {
    private val handler = Handler(Looper.getMainLooper())
    private var lastId = -1L
    private var registered = false
    private val check = Runnable { checkNewest() }

    fun refresh() {
        val want = Prefs.screenshotsToMac.value && hasPermission(ctx)
        if (want && !registered) {
            lastId = newest()?.first ?: -1L
            ctx.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, this)
            registered = true
        } else if (!want && registered) {
            ctx.contentResolver.unregisterContentObserver(this)
            registered = false
        }
    }

    fun stop() {
        if (registered) ctx.contentResolver.unregisterContentObserver(this)
        registered = false
    }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        // A screenshot fires several changes (pending → published); wait for it to settle.
        handler.removeCallbacks(check)
        handler.postDelayed(check, 600)
    }

    private fun checkNewest() {
        val (id, isShot) = newest() ?: return
        if (id <= lastId) return
        lastId = id
        if (!isShot || !Link.isConnected) return
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        Log.i("ScreenshotWatcher", "screenshot → Mac clipboard")
        SyncService.instance?.files?.send(listOf(uri), clip = true)
    }

    /** Newest published image: (id, looks like a fresh screenshot). */
    private fun newest(): Pair<Long, Boolean>? = runCatching {
        val cols = arrayOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATE_ADDED,
        )
        ctx.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cols,
            "${MediaStore.Images.Media.IS_PENDING} = 0", null,
            "${MediaStore.Images.Media._ID} DESC",
        )?.use { c ->
            if (!c.moveToFirst()) return@use null
            val path = c.getString(1).orEmpty() + c.getString(2).orEmpty()
            val fresh = System.currentTimeMillis() / 1000 - c.getLong(3) < 30
            c.getLong(0) to (fresh && path.contains("screenshot", ignoreCase = true))
        }
    }.getOrNull()

    companion object {
        val permission: String
            get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

        fun hasPermission(ctx: Context) =
            ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED
    }
}
