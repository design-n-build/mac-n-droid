package app.macndroid.service

import app.macndroid.core.Computer

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.FileProvider
import app.macndroid.core.Link
import org.json.JSONObject
import java.io.File

/** Phone-side clipboard logic shared by the tile, share sheet and automatic sync. */
object ClipboardSync {
    @Volatile private var lastFromMac: String? = null
    @Volatile private var lastSent: String? = null
    /** A copy made while the Mac was unreachable, sent on reconnect (if still fresh). */
    @Volatile private var pending: Pair<Long, () -> Unit>? = null

    enum class Result { SentText, SentImage, Empty, Duplicate, NotConnected, Sensitive }

    /** Call only while one of our windows has focus (Android 10+ rule). */
    fun sendCurrent(ctx: Context, auto: Boolean): Result {
        if (!Link.isConnected && !auto) return Result.NotConnected
        val clip = ctx.getSystemService(ClipboardManager::class.java).primaryClip
        val item = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return Result.Empty
        // Password managers mark copies as sensitive (ClipDescription.EXTRA_IS_SENSITIVE). Never
        // auto-send those; an explicit "Send clipboard" tap still works.
        if (auto && clip.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true) return Result.Sensitive
        val uri = item.uri
        val mime = uri?.let { ctx.contentResolver.getType(it) } ?: clip.description.takeIf { it.mimeTypeCount > 0 }?.getMimeType(0)
        if (uri != null && mime?.startsWith("image/") == true) {
            val signature = "img:$uri"
            if (auto && (signature == lastSent || uri.authority == AUTHORITY)) return Result.Duplicate
            lastSent = signature
            val sendIt = { SyncService.instance?.files?.send(listOf(uri), clip = true); Unit }
            if (!Link.isConnected) { pending = System.currentTimeMillis() to sendIt; return Result.NotConnected }
            sendIt()
            return Result.SentImage
        }
        val text = item.text?.toString() ?: item.coerceToText(ctx)?.toString()
        if (text.isNullOrEmpty()) return Result.Empty
        if (auto && (text == lastSent || text == lastFromMac)) return Result.Duplicate
        lastSent = text
        val msg = JSONObject().put("t", "clip").put("text", text).put("explicit", !auto)
        if (!Link.isConnected) { pending = System.currentTimeMillis() to { Link.post(msg) }; return Result.NotConnected }
        Link.post(msg)
        app.macndroid.core.Haptics.play(app.macndroid.core.Haptics.Kind.Tick)
        return Result.SentText
    }

    /** Called when the link comes up: deliver a copy made in the last 10 minutes while offline. */
    fun flushPending() {
        val (time, send) = pending ?: return
        pending = null
        if (System.currentTimeMillis() - time < 10 * 60_000) send()
    }

    fun receiveText(ctx: Context, text: String) {
        lastFromMac = text
        app.macndroid.core.Haptics.play(app.macndroid.core.Haptics.Kind.Tick)
        main.post {
            ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("From ${Computer.word}", text))
        }
    }

    /** An image copied on the Mac becomes a content:// clip any app can paste. */
    fun receiveImage(ctx: Context, file: File) {
        val uri = FileProvider.getUriForFile(ctx, AUTHORITY, file)
        lastSent = "img:$uri"
        main.post {
            ctx.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newUri(ctx.contentResolver, "Image from ${Computer.word}", uri))
        }
        // Keep only the latest few clipboard images.
        file.parentFile?.listFiles()?.sortedByDescending { it.lastModified() }?.drop(3)?.forEach { it.delete() }
    }

    fun clipDir(ctx: Context) = File(ctx.cacheDir, "clip").apply { mkdirs() }

    /** Whether automatic copying is on (it's part of Clipboard and controls). */
    fun isAutoEnabled(ctx: Context): Boolean = Controls.isEnabled(ctx)

    const val AUTHORITY = "app.macndroid.files"
    private val main = Handler(Looper.getMainLooper())
}
