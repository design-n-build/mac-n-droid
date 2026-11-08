package app.macndroid.service

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
            ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("From Mac", text))
        }
    }

    /** An image copied on the Mac becomes a content:// clip any app can paste. */
    fun receiveImage(ctx: Context, file: File) {
        val uri = FileProvider.getUriForFile(ctx, AUTHORITY, file)
        lastSent = "img:$uri"
        main.post {
            ctx.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newUri(ctx.contentResolver, "Image from Mac", uri))
        }
        // Keep only the latest few clipboard images.
        file.parentFile?.listFiles()?.sortedByDescending { it.lastModified() }?.drop(3)?.forEach { it.delete() }
    }

    fun clipDir(ctx: Context) = File(ctx.cacheDir, "clip").apply { mkdirs() }

    fun isAutoEnabled(ctx: Context): Boolean {
        val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(ctx, CopyWatcher::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    const val AUTHORITY = "app.macndroid.files"
    private val main = Handler(Looper.getMainLooper())
}

/**
 * Optional accessibility service. Android only lets the focused window read the clipboard, so when it
 * sees a "Copy" action it adds a 1×1 invisible overlay window that takes focus for a few milliseconds,
 * reads the clipboard and removes itself. No activity launch, no animation, and the keyboard stays put.
 */
class CopyWatcher : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var lastTrigger = 0L
    private var probe: View? = null
    private val grab = Runnable { grabClipboard() }

    override fun onServiceConnected() { instance = this }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    // MARK: Remote control from the Mac's mirror window

    /** Taps, long-presses, swipes and scrolls. Points are normalized (0…1) to the screen. */
    fun gesture(points: List<Pair<Float, Float>>, durationMs: Long) = handler.post {
        if (points.isEmpty()) return@post
        val (w, h) = screenSize()
        val path = android.graphics.Path()
        path.moveTo(points[0].first * w, points[0].second * h)
        if (points.size == 1) path.lineTo(points[0].first * w, points[0].second * h)
        for (p in points.drop(1)) path.lineTo(p.first * w, p.second * h)
        val dur = durationMs.coerceIn(1, android.accessibilityservice.GestureDescription.getMaxGestureDuration() - 1)
        val g = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, dur))
            .build()
        runCatching { dispatchGesture(g, null, null) }
    }

    fun key(name: String) = handler.post {
        val action = when (name) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "lock" -> GLOBAL_ACTION_LOCK_SCREEN
            "screenshot" -> GLOBAL_ACTION_TAKE_SCREENSHOT
            "enter" -> { editFocused { _, _, _ -> null }; return@post }
            "backspace" -> { editFocused { text, start, end ->
                if (start != end) Triple(text.removeRange(start, end), start, start)
                else if (start > 0) Triple(text.removeRange(start - 1, start), start - 1, start - 1) else null
            }; return@post }
            else -> return@post
        }
        performGlobalAction(action)
    }

    /** Types text into the focused field (at the cursor, replacing any selection). */
    fun type(input: String) = handler.post {
        editFocused { text, start, end ->
            Triple(text.replaceRange(start, end, input), start + input.length, start + input.length)
        }
    }

    /** `change` returns (new text, selection start, selection end), or null to press the IME action (Enter). */
    private fun editFocused(change: (String, Int, Int) -> Triple<String, Int, Int>?) {
        val node = findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        if (!node.isEditable) return
        val current = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        val selStart = node.textSelectionStart.takeIf { it in 0..current.length } ?: current.length
        val selEnd = node.textSelectionEnd.takeIf { it in selStart..current.length } ?: selStart
        val result = change(current, selStart, selEnd)
        if (result == null) {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                node.performAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }
            return
        }
        val (text, s, e) = result
        node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
            putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        })
        node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_SELECTION, android.os.Bundle().apply {
            putInt(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, s)
            putInt(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, e)
        })
    }

    private fun screenSize(): Pair<Float, Float> {
        val wm = getSystemService(WindowManager::class.java)
        return if (android.os.Build.VERSION.SDK_INT >= 30) {
            wm.maximumWindowMetrics.bounds.let { it.width().toFloat() to it.height().toFloat() }
        } else {
            @Suppress("DEPRECATION")
            android.util.DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }
                .let { it.widthPixels.toFloat() to it.heightPixels.toFloat() }
        }
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (app.macndroid.core.Prefs.pairing.value == null) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName) return
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != "com.android.systemui" &&
            !pkg.contains("inputmethod") && e.className?.contains("Toast") != true && foregroundPackage != pkg) {
            val previous = foregroundPackage
            foregroundPackage = pkg
            NotificationMirror.instance?.onForegroundChanged(previous, pkg)
        }
        val words = buildString {
            e.text?.forEach { append(it).append(' ') }
            e.contentDescription?.let { append(it) }
        }.trim().lowercase()

        val isCopy = when (e.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED ->
                COPY_LABEL.containsMatchIn(words) ||
                    e.source?.viewIdResourceName?.lowercase()?.let { it.contains("copy") } == true
            // Android 13+ shows a "Copied" preview from System UI after every copy.
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                pkg == "com.android.systemui" && (words.contains("copied") || e.className?.contains("Clipboard", true) == true)
            // Older apps announce copies with a toast.
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED ->
                e.className?.contains("Toast") == true && words.contains("cop")
            else -> false
        }
        if (!isCopy) return
        val now = System.currentTimeMillis()
        if (now - lastTrigger < 700) return
        lastTrigger = now
        handler.removeCallbacks(grab)
        handler.postDelayed(grab, 200)   // let the app finish writing the clipboard
    }

    private fun grabClipboard() {
        if (probe != null) return
        val wm = getSystemService(WindowManager::class.java)
        var done = false
        val view = object : View(this) {
            override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                super.onWindowFocusChanged(hasWindowFocus)
                if (!hasWindowFocus || done) return
                done = true
                val result = runCatching { ClipboardSync.sendCurrent(context, auto = true) }
                Log.i(TAG, "clipboard grab: ${result.getOrNull() ?: result.exceptionOrNull()}")
                // Never remove the window inside its own focus callback: ViewRootImpl is still
                // using it and would crash. Remove on the next loop instead.
                handler.post { removeProbe(wm) }
            }
        }
        val lp = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Focusable (needed for clipboard access) but never talks to the keyboard and lets touches through.
            WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSPARENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            windowAnimations = 0
            title = "MacNDroidClipboard"
        }
        runCatching { wm.addView(view, lp); probe = view }
            .onFailure { Log.w(TAG, "overlay failed", it); return }
        // Never linger: give up quietly if focus doesn't arrive.
        handler.postDelayed({
            if (!done) Log.w(TAG, "clipboard grab: overlay never got focus")
            removeProbe(wm)
        }, 600)
    }

    private fun removeProbe(wm: WindowManager) {
        val v = probe ?: return
        probe = null
        if (v.isAttachedToWindow) runCatching { wm.removeView(v) }
    }

    override fun onInterrupt() {}

    companion object {
        @Volatile var instance: CopyWatcher? = null
            private set
        /** The app on screen (from window events), or null when unknown. */
        @Volatile var foregroundPackage: String? = null
            private set
        private const val TAG = "CopyWatcher"
        private val COPY_LABEL = Regex("""(^|\b)(copy|copied|copy text|copy link|copy code|copy to clipboard|kopieren|copiar|copier)(\b|$)""")
    }
}
