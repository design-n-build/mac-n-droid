package app.macndroid.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * Optional, separate accessibility service for controlling the phone from the Mac's mirror window:
 * taps, swipes, Back/Home/Recents and typing. Kept apart from [CopyWatcher] so people who only want
 * clipboard sync never grant the ability to tap on other apps (which banking apps warn about).
 * It ignores all accessibility events and only acts on input sent from the paired Mac.
 */
class PhoneControl : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() { instance = this }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {}
    override fun onInterrupt() {}

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

    companion object {
        @Volatile var instance: PhoneControl? = null
            private set

        fun isEnabled(ctx: Context): Boolean {
            val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = ComponentName(ctx, PhoneControl::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}

/**
 * Asked for only when it's needed: the first time someone clicks on the mirrored screen without
 * phone control turned on, the phone shows this, and tapping it opens the right settings page.
 */
object PhoneControlPrompt {
    private const val NOTIF = 41
    private var lastShown = 0L

    fun show(ctx: Context) {
        val now = System.currentTimeMillis()
        if (now - lastShown < 5 * 60_000) return
        lastShown = now
        val intent = android.content.Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = android.app.PendingIntent.getActivity(ctx, NOTIF, intent, android.app.PendingIntent.FLAG_IMMUTABLE)
        val n = androidx.core.app.NotificationCompat.Builder(ctx, app.macndroid.MacNDroidApp.CH_MIRROR)
            .setSmallIcon(app.macndroid.R.drawable.ic_stat_logo)
            .setContentTitle("Control this phone from your Mac?")
            .setContentText("Tap, then turn on Mac n Droid phone control")
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(
                "Tap here, open Installed apps → Mac n Droid phone control and turn it on. " +
                    "Then clicks on your Mac become taps here. Some banking apps show a warning while it's on."))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setTimeoutAfter(120_000)
            .build()
        runCatching { ctx.getSystemService(android.app.NotificationManager::class.java).notify(NOTIF, n) }
    }
}
