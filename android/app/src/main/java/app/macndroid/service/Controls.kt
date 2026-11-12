package app.macndroid.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import app.macndroid.MacNDroidApp
import app.macndroid.R
import app.macndroid.core.Computer

/**
 * "Mac n Droid clipboard and controls": the app's one optional accessibility service. With it on:
 *  - copying here goes to the computer (Android only lets the focused window read the clipboard, so when it
 *    sees a Copy action it adds a 1×1 invisible overlay that takes focus for a moment, reads it and goes away);
 *  - you can use this phone from the computer's Phone Screen window (taps, swipes, keys and typing, only in
 *    response to what you do in that window);
 *  - links from the computer open right away, music started from the computer plays (Android 17 mutes
 *    background starts otherwise), and live activities stay up while you're in that app.
 *
 * The class keeps its old name so phones that had clipboard sync on keep it on after updating.
 */
class CopyWatcher : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var lastTrigger = 0L
    private var probe: View? = null
    private val grab = Runnable { grabClipboard() }

    override fun onServiceConnected() {
        instance = this
        // Remembered so the app can tell you if an update or Android switches it off.
        getSharedPreferences("a11y", MODE_PRIVATE).edit().putBoolean("copyWatcherWasOn", true).apply()
        getSystemService(NotificationManager::class.java)?.cancel(Controls.NOTIF_LOST)
        getSystemService(NotificationManager::class.java)?.cancel(Controls.NOTIF_PROMPT)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    // MARK: Copy detection and the app on screen

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (app.macndroid.core.Prefs.pairing.value == null) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName) return
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != "com.android.systemui" &&
            !pkg.contains("inputmethod") && e.className?.contains("Toast") != true && foregroundPackage != pkg) {
            foregroundPackage = pkg
            NotificationMirror.instance?.onForegroundChanged(pkg)
        }
        // Only the label of what was tapped (or of a "Copied" message), to tell whether it was Copy.
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

    // MARK: Control from the computer (only in response to input from its Phone Screen window)

    /** Taps, long-presses, swipes and scrolls. Points are normalized (0…1) to the screen. */
    fun gesture(points: List<Pair<Float, Float>>, durationMs: Long) = handler.post {
        if (points.isEmpty()) return@post
        val (w, h) = screenSize()
        val path = android.graphics.Path()
        path.moveTo(points[0].first * w, points[0].second * h)
        if (points.size == 1) path.lineTo(points[0].first * w, points[0].second * h)
        for (p in points.drop(1)) path.lineTo(p.first * w, p.second * h)
        val dur = durationMs.coerceIn(1, GestureDescription.getMaxGestureDuration() - 1)
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, dur)).build()
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
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        if (!node.isEditable) return
        val current = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        val selStart = node.textSelectionStart.takeIf { it in 0..current.length } ?: current.length
        val selEnd = node.textSelectionEnd.takeIf { it in selStart..current.length } ?: selStart
        val result = change(current, selStart, selEnd)
        if (result == null) {
            if (Build.VERSION.SDK_INT >= 30) node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            return
        }
        val (text, s, e) = result
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        })
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, android.os.Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, s)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, e)
        })
    }

    private fun screenSize(): Pair<Float, Float> {
        val wm = getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= 30) {
            wm.maximumWindowMetrics.bounds.let { it.width().toFloat() to it.height().toFloat() }
        } else {
            @Suppress("DEPRECATION")
            android.util.DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }
                .let { it.widthPixels.toFloat() to it.heightPixels.toFloat() }
        }
    }

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

/** The one switch, as the app talks about it: "Clipboard and controls". */
object Controls {
    const val NAME = "Mac n Droid clipboard and controls"
    internal const val NOTIF_PROMPT = 41
    internal const val NOTIF_LOST = 43
    private var lastPrompt = 0L

    /** Running right now (bound by Android). */
    val running: Boolean get() = CopyWatcher.instance != null

    /** Switched on in Accessibility settings. */
    fun isEnabled(ctx: Context): Boolean {
        val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(ctx, CopyWatcher::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    fun settingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** The first time someone clicks on the mirrored screen while it's off. */
    fun prompt(ctx: Context) {
        val now = System.currentTimeMillis()
        if (now - lastPrompt < 5 * 60_000) return
        lastPrompt = now
        notify(ctx, NOTIF_PROMPT, "Control this phone from ${Computer.your}?",
            "Tap, then turn on $NAME",
            "Tap here, open Installed apps → $NAME and turn it on. Then clicks on ${Computer.your} become taps here.",
            timeout = 120_000)
    }

    /**
     * Android can switch the service off (it has happened when the app is updated). If it was on before and is
     * off now, a notification brings you straight to the switch, once per change. Phones that only had the old,
     * separate "phone control" service on are told once that it's now part of this one.
     */
    fun checkLost(ctx: Context) {
        val sp = ctx.getSharedPreferences("a11y", Context.MODE_PRIVATE)
        if (isEnabled(ctx)) return
        val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val hadOldControl = enabled.contains("${ctx.packageName}/") && enabled.contains("PhoneControl") && !sp.getBoolean("toldMerged", false)
        if (!sp.getBoolean("copyWatcherWasOn", false) && !hadOldControl) return
        sp.edit().putBoolean("copyWatcherWasOn", false).putBoolean("toldMerged", true).apply()
        notify(ctx, NOTIF_LOST, "Clipboard and controls is off",
            "Tap to turn $NAME back on",
            "Android turned off $NAME. Tap here, open Installed apps → $NAME and turn it on, so copying, " +
                "controlling this phone, links and music from ${Computer.your} work again.")
    }

    private fun notify(ctx: Context, id: Int, title: String, text: String, long: String, timeout: Long = 0) {
        val pi = PendingIntent.getActivity(ctx, id, settingsIntent(), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, MacNDroidApp.CH_MIRROR)
            .setSmallIcon(R.drawable.ic_stat_logo)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(long))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .apply { if (timeout > 0) setTimeoutAfter(timeout) }
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(id, n) }
    }
}
