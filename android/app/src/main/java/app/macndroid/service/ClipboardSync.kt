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
    /** What the Mac's clipboard holds as far as the phone knows: the last text it sent us, or the last we sent it. */
    @Volatile private var lastFromMac: String? = null
    @Volatile private var lastSent: String? = null
    @Volatile private var lastSentAt = 0L
    /** A copy made while the Mac was unreachable, sent on reconnect (if still fresh). */
    @Volatile private var pending: Pair<Long, () -> Unit>? = null

    enum class Result { SentText, SentImage, Empty, Duplicate, NotConnected, Sensitive, Paused }

    /** Call only while one of our windows has focus (Android 10+ rule). */
    fun sendCurrent(ctx: Context, auto: Boolean): Result {
        if (app.macndroid.core.Pause.active) return Result.Paused
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
            if (auto && (uri.authority == authority(ctx) || isRepeat(signature))) return Result.Duplicate
            markSent(signature)
            // Looked up when it's sent: the service may only be starting now.
            val sendIt = { SyncService.instance?.files?.send(listOf(uri), clip = true); Unit }
            if (!Link.isConnected) { queue(sendIt); SyncService.start(ctx); return Result.NotConnected }
            deliver(sendIt)
            return Result.SentImage
        }
        val text = item.text?.toString() ?: item.coerceToText(ctx)?.toString()
        if (text.isNullOrEmpty()) return Result.Empty
        // Skip echoes of what the Mac just sent us, and the same copy reported twice in a row. A copy of
        // text sent earlier still goes out if the Mac's clipboard has changed since.
        if (auto && (text == lastFromMac || isRepeat(text))) return Result.Duplicate
        markSent(text)
        val msg = JSONObject().put("t", "clip").put("text", text).put("explicit", !auto)
        if (!Link.isConnected) { queue { Link.post(msg) }; SyncService.start(ctx); return Result.NotConnected }
        deliver { Link.post(msg) }
        app.macndroid.core.Haptics.play(app.macndroid.core.Haptics.Kind.Tick)
        return Result.SentText
    }

    /** The same copy reported again moments later (a Copy button plus Android's "Copied" preview, or a refocus). */
    private fun isRepeat(signature: String) = signature == lastSent && System.currentTimeMillis() - lastSentAt < 5_000

    private fun markSent(signature: String) {
        lastSent = signature
        lastSentAt = System.currentTimeMillis()
        lastFromMac = null   // the Mac now holds what we sent, not what it sent us before
    }

    /**
     * Hold a copy made while the computer was unreachable and reconnect now instead of waiting out the retry
     * delay (up to 30 s). Phones that pause apps in the background often drop the connection quietly.
     */
    fun queue(send: () -> Unit) {
        pending = System.currentTimeMillis() to send
        Link.kick()
    }

    /**
     * Send now, and keep it until the computer has answered a ping that followed it. If it doesn't answer, the
     * connection is rebuilt and the copy goes out again on reconnect (sending the same clipboard twice is harmless).
     */
    fun deliver(send: () -> Unit) {
        val entry = System.currentTimeMillis() to send
        pending = entry
        send()
        Link.confirm { if (pending === entry) pending = null }
    }

    /** Called when the link comes up: deliver a copy made in the last 10 minutes while offline. */
    fun flushPending() {
        val (time, send) = pending ?: return
        pending = null
        if (System.currentTimeMillis() - time < 10 * 60_000) send()
    }

    fun receiveText(ctx: Context, text: String) {
        lastFromMac = text
        lastSent = null      // the Mac's clipboard changed: copying our earlier text again must send it again
        app.macndroid.core.Haptics.play(app.macndroid.core.Haptics.Kind.Tick)
        main.post {
            ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("From ${Computer.word}", text))
        }
    }

    /** An image copied on the Mac becomes a content:// clip any app can paste. */
    fun receiveImage(ctx: Context, file: File) {
        val uri = FileProvider.getUriForFile(ctx, authority(ctx), file)
        lastSent = "img:$uri"
        lastSentAt = System.currentTimeMillis()
        lastFromMac = null
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

    /** The app's file provider (AndroidManifest: ${applicationId}.files), so the testing build has its own. */
    fun authority(ctx: Context) = ctx.packageName + ".files"
    private val main = Handler(Looper.getMainLooper())
}
