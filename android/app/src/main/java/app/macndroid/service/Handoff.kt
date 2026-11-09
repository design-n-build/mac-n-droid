package app.macndroid.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import app.macndroid.MacNDroidApp
import app.macndroid.R
import app.macndroid.core.Link
import app.macndroid.core.Prefs
import org.json.JSONObject

/**
 * Link handoff. A link sent from the Mac opens in the phone's browser (or the app that owns it,
 * like YouTube). Android only lets apps open screens from the background in a few cases; when we
 * can't, the link waits in a notification you tap.
 */
object Handoff {
    private const val TAG = "Handoff"
    private const val NOTIF = 80

    /** Only web links: a paired Mac still shouldn't be able to open arbitrary app schemes. */
    fun isWebLink(s: String): Boolean {
        val u = runCatching { Uri.parse(s.trim()) }.getOrNull() ?: return false
        return (u.scheme == "http" || u.scheme == "https") && !u.host.isNullOrBlank()
    }

    /** First http(s) link in a piece of shared text ("Check this out https://…"). */
    fun findLink(text: String?): String? =
        text?.let { Regex("""https?://\S+""").find(it)?.value?.trimEnd('.', ',', ')', '"', '\'') }?.takeIf(::isWebLink)

    fun openFromMac(ctx: Context, url: String) {
        if (!isWebLink(url)) return
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val screenOn = ctx.getSystemService(PowerManager::class.java)?.isInteractive == true
        val locked = ctx.getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true
        // An enabled accessibility service lets the app open screens from the background.
        val canOpen = CopyWatcher.instance != null || PhoneControl.instance != null
        if (screenOn && !locked && canOpen && runCatching { ctx.startActivity(view) }.isSuccess) {
            Log.i(TAG, "opened link from Mac")
            app.macndroid.core.Haptics.play(app.macndroid.core.Haptics.Kind.Tick)
            return
        }
        // Locked: open it the moment you unlock (the notification stays as a fallback).
        if (canOpen) openOnUnlock(ctx.applicationContext, view)
        val mac = Prefs.pairing.value?.macName ?: "your Mac"
        val host = Uri.parse(url).host?.removePrefix("www.") ?: url
        val open = PendingIntent.getActivity(ctx, url.hashCode(), view, PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, MacNDroidApp.CH_LINKS)
            .setSmallIcon(R.drawable.ic_stat_logo)
            .setContentTitle("Link from $mac")
            .setContentText(host)
            .setStyle(NotificationCompat.BigTextStyle().bigText(url))
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .addAction(R.drawable.ic_stat_logo, "Open", open)
            .setAutoCancel(true)
            .setTimeoutAfter(10 * 60_000)
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(NOTIF, n) }
    }

    private var pending: Pair<Intent, Long>? = null
    private var unlockReceiver: android.content.BroadcastReceiver? = null

    private fun openOnUnlock(app: Context, view: Intent) {
        pending = view to System.currentTimeMillis()
        if (unlockReceiver != null) return
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val (intent, at) = pending ?: return
                pending = null
                runCatching { app.unregisterReceiver(this) }
                unlockReceiver = null
                if (System.currentTimeMillis() - at > 30 * 60_000) return   // too old to surprise you with
                if (runCatching { app.startActivity(intent) }.isSuccess) {
                    app.getSystemService(NotificationManager::class.java).cancel(NOTIF)
                    Log.i(TAG, "opened link from Mac after unlock")
                }
            }
        }
        unlockReceiver = r
        androidx.core.content.ContextCompat.registerReceiver(app, r, android.content.IntentFilter(Intent.ACTION_USER_PRESENT),
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
    }

    /** Phone → Mac: opens in the Mac's default browser. */
    fun sendToMac(url: String): Boolean {
        if (!Link.isConnected || !isWebLink(url)) return false
        Link.post(JSONObject().put("t", "open_url").put("url", url))
        return true
    }
}
