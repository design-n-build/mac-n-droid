package app.macndroid.service

import android.app.ActivityOptions
import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import android.util.Log
import app.macndroid.core.Link
import app.macndroid.core.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/** Mirrors phone notifications to the Mac and carries out actions/dismissals sent back. */
class NotificationMirror : NotificationListenerService() {
    private val sentIcons = ConcurrentHashMap.newKeySet<String>()
    private val lastSent = ConcurrentHashMap<String, Int>()
    private val labels = ConcurrentHashMap<String, String>()
    private val liveSent = ConcurrentHashMap<String, String>()
    /** Live activities whose app hid its notification while you're in it (key → package). */
    private val heldLive = ConcurrentHashMap<String, String>()
    private val heldSince = ConcurrentHashMap<String, Long>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val checkHeld = object : Runnable {
        override fun run() {
            if (heldLive.isEmpty()) return
            val pm = getSystemService(android.os.PowerManager::class.java)
            val km = getSystemService(android.app.KeyguardManager::class.java)
            // Locking the phone means you left the app: it would have posted the notification again by now.
            val away = pm?.isInteractive != true || km?.isKeyguardLocked == true
            val now = System.currentTimeMillis()
            for ((key, pkg) in heldLive) {
                val left = CopyWatcher.foregroundPackage != pkg
                if (away || left || now - (heldSince[key] ?: now) > 10 * 60_000) release(key)
            }
            if (heldLive.isNotEmpty()) main.postDelayed(this, 2_000)
        }
    }

    private fun release(key: String) {
        heldLive.remove(key)
        heldSince.remove(key)
        if (liveSent.remove(key) != null) Link.post(JSONObject().put("t", "live_end").put("key", key))
    }

    /** Left the app whose live notification we were holding: end it unless it comes back. */
    fun onForegroundChanged(current: String) {
        val held = heldLive.filterValues { it != current }.keys
        if (held.isEmpty()) return
        main.postDelayed({
            val active = runCatching { activeNotifications }.getOrNull()?.map { it.key }?.toSet() ?: emptySet()
            for (key in held) {
                if (heldLive[key] == CopyWatcher.foregroundPackage) continue   // back in the app
                if (key !in active) release(key) else { heldLive.remove(key); heldSince.remove(key) }
            }
        }, 1500)
    }

    override fun onListenerConnected() {
        instance = this
        SyncService.start(this)
        if (Link.isConnected) syncAll()
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        runCatching { ChatNotifications.onPosted(this, sbn) }
        if (!Link.isConnected) return
        if (forwardLive(sbn)) return
        // WhatsApp and others re-post every unread chat at once (after "Clear all", or when one new
        // message arrives). Those carry the time of their own last message: old ones go quietly.
        val whenMs = sbn.notification.`when`
        val stale = whenMs > 0 && System.currentTimeMillis() - whenMs > 5 * 60_000
        forward(sbn, silent = stale)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) = removed(sbn, REASON_APP_CANCEL)

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap?, reason: Int) = removed(sbn, reason)

    private fun removed(sbn: StatusBarNotification, reason: Int) {
        ChatNotifications.onRemoved(sbn)
        if (liveSent.containsKey(sbn.key)) {
            // Clock, Maps and others hide their live notification while the app itself is open, and post it
            // again when you leave. Keep it on the computer meanwhile, but only while that app is really on
            // screen (known from the clipboard and controls accessibility service; without it, it just ends), only when
            // the app removed it itself (not you swiping it away), never for calls (a removed call notification
            // means the call ended), and only while the phone is unlocked. It ends as soon as you leave the
            // app, lock the phone, or after 10 minutes.
            val isCall = sbn.notification.category == Notification.CATEGORY_CALL
            val unlocked = getSystemService(android.os.PowerManager::class.java)?.isInteractive == true &&
                getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked != true
            val byApp = reason == REASON_APP_CANCEL || reason == REASON_CLICK
            val inThatApp = CopyWatcher.instance != null && CopyWatcher.foregroundPackage == sbn.packageName
            if (!isCall && unlocked && byApp && inThatApp) {
                heldLive[sbn.key] = sbn.packageName
                heldSince[sbn.key] = System.currentTimeMillis()
                main.removeCallbacks(checkHeld)
                main.postDelayed(checkHeld, 2_000)
            } else {
                liveSent.remove(sbn.key)
                Link.post(JSONObject().put("t", "live_end").put("key", sbn.key))
            }
        }
        if (lastSent.remove(sbn.key) != null) {
            Link.post(JSONObject().put("t", "notif_removed").put("key", sbn.key))
        }
    }

    /** Sends everything currently in the shade, without banners on the Mac. */
    fun syncAll() {
        resetSession()
        runCatching { activeNotifications }.getOrNull()
            ?.sortedBy { it.postTime }
            ?.forEach { if (!forwardLive(it)) forward(it, silent = true) }
    }

    fun resetSession() {
        sentIcons.clear()
        lastSent.clear()
        liveSent.clear()
        heldLive.clear()
        heldSince.clear()
    }

    // MARK: Live activities (timers, stopwatches, ongoing calls, rides & deliveries, downloads)

    /** Sends ongoing "live" notifications as a live activity. Returns true if [sbn] was one. */
    private fun forwardLive(sbn: StatusBarNotification): Boolean {
        val live = liveInfo(sbn)
        if (live == null) {
            // Stopped being live (e.g. timer dismissed into a normal notification).
            if (liveSent.remove(sbn.key) != null) Link.post(JSONObject().put("t", "live_end").put("key", sbn.key))
            return false
        }
        if (!Prefs.mirrorNotifications.value) return true
        val signature = live.toString()
        heldLive.remove(sbn.key)
        heldSince.remove(sbn.key)
        if (liveSent[sbn.key] == signature) return true
        liveSent[sbn.key] = signature
        Log.i("NotificationMirror", "live ${live.optString("kind")} ${sbn.packageName} glance='${live.optString("short")}' when=${live.optDouble("when")} paused=${live.optDouble("paused")}")
        if (!sentIcons.contains(sbn.packageName)) {
            appIcon(sbn)?.let { live.put("icon", it); sentIcons.add(sbn.packageName) }
        }
        Link.post(live)
        return true
    }

    private fun liveInfo(sbn: StatusBarNotification): JSONObject? {
        val n = sbn.notification
        val ex = n.extras
        if (sbn.packageName == packageName || sbn.packageName in SYSTEM_PACKAGES) return null
        if (!sbn.isOngoing || n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        if (n.category == Notification.CATEGORY_TRANSPORT) return null   // media: see MediaSync
        val isCall = n.category == Notification.CATEGORY_CALL
        if (isCall) {
            // Ringing calls stay regular notifications (with Answer / Decline).
            val callType = ex.getInt("android.callType", 0)   // 1 incoming, 2 ongoing, 3 screening
            val ringing = callType == 1 || callType == 3 ||
                n.actions?.any { it.title?.toString()?.contains("answer", true) == true } == true
            if (ringing) return null
        }
        val metric = criticalMetric(ex)
        val chrono = ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
        val countDown = ex.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN)
        val max = ex.getInt(Notification.EXTRA_PROGRESS_MAX)
        val progress = ex.getInt(Notification.EXTRA_PROGRESS)
        val indeterminate = ex.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)
        val short = ex.getCharSequence("android.shortCriticalText")?.toString()   // Android 16 Live Updates
        val promoted = n.flags and 0x00040000 != 0                                // FLAG_PROMOTED_ONGOING
        val liveCategory = n.category in setOf(
            Notification.CATEGORY_NAVIGATION, "stopwatch",
            Notification.CATEGORY_PROGRESS, Notification.CATEGORY_ALARM, "workout", "location_sharing",
        )
        val tracked = sbn.packageName in LIVE_PACKAGES
        val kind = when {
            isCall -> "call"
            metric?.kind != null -> metric.kind
            chrono && countDown -> "timer"
            chrono -> "stopwatch"
            max > 0 && !indeterminate -> "progress"
            short != null || metric != null || promoted || liveCategory || tracked -> "status"
            else -> return null
        }
        val title = ex.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()
            ?: ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val label = metric?.label.orEmpty()
        val glance = short ?: metric?.text
        if (title.isBlank() && text.isBlank() && glance == null && !chrono && metric?.kind == null) return null
        val base = when {
            metric?.zeroMs != null -> metric.zeroMs
            chrono -> n.`when`
            else -> 0L
        }
        return JSONObject()
            .put("t", "live").put("key", sbn.key).put("pkg", sbn.packageName).put("app", appLabel(sbn))
            .put("kind", kind).put("title", title.ifBlank { label }).put("text", text).put("short", glance ?: "")
            .put("when", base.toDouble())
            .put("paused", (metric?.pausedMs ?: -1L).toDouble())
            .put("progress", progress).put("max", if (indeterminate) 0 else max)
            .put("time", sbn.postTime.toDouble())
    }

    private fun shouldMirror(sbn: StatusBarNotification): Boolean {
        if (!Prefs.mirrorNotifications.value) return false
        val n = sbn.notification
        if (sbn.packageName == packageName) return false
        if (sbn.packageName in Prefs.blockedApps.value) return false
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        val isCall = n.category == Notification.CATEGORY_CALL
        if (!isCall && (sbn.isOngoing || !sbn.isClearable)) return false
        if (n.category == Notification.CATEGORY_TRANSPORT) return false   // media controls
        return true
    }

    private fun forward(sbn: StatusBarNotification, silent: Boolean) {
        if (!shouldMirror(sbn)) return
        val n = sbn.notification
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()
            ?: ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = messageText(ex)
        if (title.isBlank() && text.isBlank()) return

        val actions = JSONArray()
        n.actions?.forEachIndexed { i, a ->
            // Redacted (sensitive) notifications can carry actions with blank titles: skip those.
            val label = a.title?.toString()?.takeIf { it.isNotBlank() } ?: return@forEachIndexed
            val reply = a.remoteInputs?.any { it.allowFreeFormInput } == true
            // Opens a screen on the phone (Gmail's Reply has no text box, even on the phone itself).
            val opens = !reply && Build.VERSION.SDK_INT >= 31 && runCatching { a.actionIntent?.isActivity == true }.getOrDefault(false)
            actions.put(JSONObject().put("i", i).put("title", label).put("reply", reply).put("opens", opens))
        }

        // Skip no-op updates (progress ticks, re-posts with identical content).
        val signature = (title + "\u0000" + text + "\u0000" + actions).hashCode()
        if (lastSent[sbn.key] == signature) return
        val wasSent = lastSent.containsKey(sbn.key)
        lastSent[sbn.key] = signature

        // Apps mark progress-style updates "alert only once": the first one alerts, the rest go quietly.
        val firstTime = !wasSent
        val quiet = silent || (!firstTime && n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)

        val msg = JSONObject()
            .put("t", "notif")
            .put("key", sbn.key)
            .put("pkg", sbn.packageName)
            .put("app", appLabel(sbn))
            .put("title", title)
            .put("text", text)
            .put("time", sbn.postTime.toDouble())
            .put("call", n.category == Notification.CATEGORY_CALL)
            .put("cat", categorize(sbn))
            .put("silent", quiet)
            .put("actions", actions)
        if (!sentIcons.contains(sbn.packageName)) {
            appIcon(sbn)?.let { msg.put("icon", it); sentIcons.add(sbn.packageName) }
        }
        Link.post(msg)
    }

    private fun messageText(ex: Bundle): String {
        // Messaging apps: show the latest message rather than "3 new messages".
        val messages = if (Build.VERSION.SDK_INT >= 33) {
            ex.getParcelableArray(Notification.EXTRA_MESSAGES, Bundle::class.java)
        } else {
            @Suppress("DEPRECATION") ex.getParcelableArray(Notification.EXTRA_MESSAGES)
        }
        val last = (messages?.lastOrNull() as? Bundle)?.getCharSequence("text")?.toString()
        return last
            ?: ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n")
            ?: ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: ""
    }

    /** The posting app's ApplicationInfo: from the package manager, or embedded in the notification itself. */
    private fun appInfo(sbn: StatusBarNotification): ApplicationInfo? =
        runCatching { packageManager.getApplicationInfo(sbn.packageName, 0) }.getOrNull()
            ?: if (Build.VERSION.SDK_INT >= 33) {
                sbn.notification.extras.getParcelable("android.appInfo", ApplicationInfo::class.java)
            } else {
                @Suppress("DEPRECATION") sbn.notification.extras.getParcelable("android.appInfo")
            }

    private fun appLabel(sbn: StatusBarNotification): String = labels.getOrPut(sbn.packageName) {
        appInfo(sbn)?.let { runCatching { packageManager.getApplicationLabel(it).toString() }.getOrNull() }
            ?: prettyName(sbn.packageName)
    }

    /** Last resort: "in.amazon.mShop.android.shopping" → "Amazon". */
    private fun prettyName(pkg: String): String {
        val skip = setOf("com", "in", "org", "net", "android", "app", "apps", "google", "mobile", "shopping", "client")
        return pkg.split('.').firstOrNull { it.length > 2 && it.lowercase() !in skip }
            ?.replaceFirstChar { it.uppercase() } ?: pkg
    }

    private fun appIcon(sbn: StatusBarNotification): String? = runCatching {
        val info = appInfo(sbn)
        val d = info?.let { packageManager.getApplicationIcon(it) } ?: packageManager.getApplicationIcon(sbn.packageName)
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, size, size)
        d.draw(Canvas(bmp))
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }.getOrNull()

    // MARK: commands from the Mac

    fun dismiss(key: String) {
        runCatching { cancelNotification(key) }
    }

    fun perform(key: String, index: Int, text: String?) {
        val sbn = runCatching { activeNotifications }.getOrNull()?.firstOrNull { it.key == key } ?: return
        val action = sbn.notification.actions?.getOrNull(index) ?: return
        val fill = Intent()
        val inputs = action.remoteInputs
        if (text != null && inputs != null && inputs.isNotEmpty()) {
            val results = Bundle()
            inputs.forEach { results.putCharSequence(it.resultKey, text) }
            RemoteInput.addResultsToIntent(inputs, fill, results)
        }
        val options = if (Build.VERSION.SDK_INT >= 34) {
            @Suppress("DEPRECATION")
            ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle()
        } else null
        try {
            action.actionIntent.send(this, 0, fill, null, null, null, options)
        } catch (e: Exception) {
            Log.w("NotificationMirror", "action failed", e)
        }
    }

    /** The phone's app filter changed: take back notifications from apps that are now off. */
    fun applyFilter() {
        val blocked = Prefs.blockedApps.value
        runCatching { activeNotifications }.getOrNull()?.forEach { sbn ->
            if (sbn.packageName in blocked && lastSent.remove(sbn.key) != null) {
                Link.post(JSONObject().put("t", "notif_removed").put("key", sbn.key))
            }
        }
        if (Link.isConnected) runCatching { activeNotifications }.getOrNull()
            ?.filter { it.packageName !in blocked && !lastSent.containsKey(it.key) }
            ?.forEach { if (!forwardLive(it)) forward(it, silent = true) }
    }

    /** Groups apps for the Mac's notification filters: messages, social, email, calls, shopping & food… */
    private fun categorize(sbn: StatusBarNotification): String =
        Categories.categorize(packageManager, sbn.packageName, sbn.notification.category, appInfo(sbn))

    object Categories {
        fun categorize(pm: android.content.pm.PackageManager, packageName: String, notifCategory: String?, info: ApplicationInfo?): String {
        val pkg = packageName.lowercase()
        KNOWN_CATEGORIES.entries.firstOrNull { (_, pkgs) -> pkgs.any { pkg == it.lowercase() || pkg.startsWith(it.lowercase() + ".") } }?.let { return it.key }
        when (notifCategory) {
            Notification.CATEGORY_CALL, Notification.CATEGORY_MISSED_CALL -> return "calls"
            Notification.CATEGORY_MESSAGE -> return "messages"
            Notification.CATEGORY_EMAIL -> return "email"
            Notification.CATEGORY_SOCIAL -> return "social"
            Notification.CATEGORY_TRANSPORT -> return "media"
            Notification.CATEGORY_NAVIGATION -> return "travel"
        }
        when ((info ?: runCatching { pm.getApplicationInfo(packageName, 0) }.getOrNull())?.category) {
            ApplicationInfo.CATEGORY_SOCIAL -> return "social"
            ApplicationInfo.CATEGORY_AUDIO, ApplicationInfo.CATEGORY_VIDEO, ApplicationInfo.CATEGORY_IMAGE -> return "media"
            ApplicationInfo.CATEGORY_NEWS -> return "news"
            ApplicationInfo.CATEGORY_MAPS -> return "travel"
            ApplicationInfo.CATEGORY_PRODUCTIVITY -> return "work"
            ApplicationInfo.CATEGORY_GAME -> return "games"
        }
        return when {
            Regex("bank|pay|upi|wallet|money|finance|card|credit|loan|invest|trade|stock|mutual|insur").containsMatchIn(pkg) -> "finance"
            Regex("shop|store|mart|cart|deliver|food|grocer|fashion").containsMatchIn(pkg) -> "shopping"
            Regex("music|video|tv|movie|stream|podcast|player|radio").containsMatchIn(pkg) -> "media"
            Regex("mail").containsMatchIn(pkg) -> "email"
            Regex("chat|messag|messenger|sms").containsMatchIn(pkg) -> "messages"
            packageName.startsWith("com.android.") || packageName.startsWith("com.google.android.apps.wellbeing") -> "system"
            else -> "other"
        }
        }
    }

    /** Android 17 MetricStyle ("android.metrics"): stopwatches, timers, and fixed values like an ETA. */
    private class MetricInfo(val label: String, val kind: String?, val zeroMs: Long?, val pausedMs: Long?, val text: String?)

    @Suppress("DEPRECATION")
    private fun criticalMetric(ex: Bundle): MetricInfo? {
        val list = (ex.get("android.metrics") as? List<*>)?.filterIsInstance<Bundle>() ?: return null
        if (list.isEmpty()) return null
        val m = list.getOrNull(ex.getInt("android.metrics.criticalIndex", 0)) ?: list.first()
        val label = m.get("label")?.toString().orEmpty()
        val v = m.get("value") as? Bundle ?: return MetricInfo(label, null, null, null, null)
        val keys = v.keySet()
        val longOf = { k: String -> (v.get(k) as? Number)?.toLong() }
        val isTimeDiff = keys.any { it.contains("zero", true) || it.contains("paused", true) } || v.containsKey("countDown")
        if (isTimeDiff) {
            val countDown = v.getBoolean("countDown")
            val paused = keys.firstOrNull { it.contains("paused", true) }?.let(longOf)
            // Either an absolute zero time or one on the elapsed-realtime clock; convert to wall time.
            val zeroWall = keys.firstOrNull { it.contains("zero", true) && !it.contains("elapsed", true) }?.let(longOf)
            val zeroElapsed = keys.firstOrNull { it.contains("zero", true) && it.contains("elapsed", true) }?.let(longOf)
            val zero = zeroWall ?: zeroElapsed?.let { System.currentTimeMillis() - (android.os.SystemClock.elapsedRealtime() - it) }
            return MetricInfo(label, if (countDown) "timer" else "stopwatch", zero, if (zero == null) paused else null, null)
        }
        // FixedText / FixedInt / FixedFloat / FixedTime / FixedDate → "12 min", "₹249", "7:45 PM".
        val value = v.get("value")?.toString()
        val unit = v.get("unit")?.toString()
        val text = listOfNotNull(value, unit).joinToString(" ").ifBlank { null }
        return MetricInfo(label, null, null, null, text)
    }

    companion object {
        private val KNOWN_CATEGORIES = linkedMapOf(
            "messages" to listOf("com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.thoughtcrime.securesms",
                "com.google.android.apps.messaging", "com.facebook.orca", "com.discord", "com.Slack", "com.microsoft.teams",
                "com.samsung.android.messaging", "jp.naver.line.android", "com.viber.voip", "com.snapchat.android"),
            "calls" to listOf("com.google.android.dialer", "com.android.server.telecom", "com.samsung.android.dialer", "com.truecaller"),
            "email" to listOf("com.google.android.gm", "com.microsoft.office.outlook", "com.yahoo.mobile.client.android.mail", "ch.protonmail.android"),
            "social" to listOf("com.instagram.android", "com.facebook.katana", "com.twitter.android", "com.zhiliaoapp.musically",
                "com.linkedin.android", "com.reddit.frontpage", "com.pinterest", "com.instagram.barcelona", "com.sharechat.app"),
            "shopping" to listOf("in.amazon.mShop.android.shopping", "com.flipkart.android", "com.myntra.android", "com.meesho.supply",
                "in.swiggy.android", "com.application.zomato", "com.grofers.customerapp", "com.zeptoconsumerapp",
                "com.bigbasket.mobileapp", "com.dunzo.user", "com.ril.ajio", "com.nykaa.app", "com.dominos", "club.cred"),
            "finance" to listOf("com.phonepe.app", "net.one97.paytm", "com.google.android.apps.nbu.paisa.user", "com.snapwork.hdfc",
                "com.csam.icici.bank.imobile", "com.sbi.lotusintouch", "com.zerodha.kite3", "com.nextbillion.groww",
                "com.axis.mobile", "com.msf.kbank.mobile", "com.dreamplug.androidapp", "in.org.npci.upiapp", "com.angelbroking.angelone"),
            "media" to listOf("com.google.android.youtube", "com.google.android.apps.youtube.music", "com.spotify.music",
                "com.netflix.mediaclient", "in.startv.hotstar", "com.jio.media.jiobeats", "com.gaana", "com.amazon.avod.thirdpartyclient",
                "com.google.android.apps.photos"),
            "travel" to listOf("com.ubercab", "com.olacabs.customer", "com.rapido.passenger", "com.google.android.apps.maps",
                "com.makemytrip", "in.goindigo.android", "com.irctc.railconnect", "cris.org.in.prs.ima", "com.ixigo.train.ixitrain"),
            "work" to listOf("com.google.android.calendar", "com.google.android.apps.docs", "com.microsoft.office.officehubrow",
                "com.notion.id", "com.atlassian.android.jira.core", "com.google.android.keep", "com.todoist"),
        )
        /** Ongoing notifications from these delivery & ride apps are order/ride trackers. */
        private val LIVE_PACKAGES = setOf(
            "in.swiggy.android", "com.application.zomato", "com.grofers.customerapp", "com.zeptoconsumerapp",
            "com.bigbasket.mobileapp", "com.dunzo.user", "com.ubercab", "com.ubercab.eats", "com.olacabs.customer",
            "com.rapido.passenger", "in.amazon.mShop.android.shopping", "com.flipkart.android", "com.done.faasos",
            "com.dominos", "com.google.android.apps.maps", "com.waze", "com.namma.yatri",
        )
        @Volatile var instance: NotificationMirror? = null
            private set
        private val SYSTEM_PACKAGES = setOf("android", "com.android.systemui", "com.google.android.gms",
            "com.android.vending", "com.google.android.apps.wellbeing")
    }
}
