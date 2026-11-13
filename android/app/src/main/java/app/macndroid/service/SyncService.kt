package app.macndroid.service

import app.macndroid.core.Computer

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.macndroid.MacNDroidApp
import app.macndroid.R
import app.macndroid.core.Link
import app.macndroid.core.Prefs
import app.macndroid.ui.MainActivity
import app.macndroid.ui.SendClipboardActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Foreground service that owns the Mac connection for as long as the phone is paired. */
class SyncService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var statusJob: Job? = null
    private var lastBattery = -1 to false
    private lateinit var ringer: Ringer
    lateinit var files: FileTransfers
        private set
    lateinit var media: MediaSync
        private set
    private lateinit var screenshots: ScreenshotWatcher
    private lateinit var volume: PhoneVolume

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = reportBattery(i, force = false)
    }

    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = Link.kick()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        TransferProgress.start(this)
        ringer = Ringer(this)
        files = FileTransfers(this)
        media = MediaSync(this)
        screenshots = ScreenshotWatcher(this)
        startInForeground(Link.status.value)
        media.start()
        volume = PhoneVolume(this).also { it.start() }
        Controls.checkLost(this)
        screenshots.refresh()

        Link.onMessage = ::handle
        LeftBehind.start(this)
        Link.onConnected = {
            app.macndroid.core.Pause.onConnected()
            LeftBehind.onConnected()
            // Which extras this phone wants from the Mac.
            Link.post(JSONObject().put("t", "mac_media_want").put("on", Prefs.macMusic.value))
            lastBattery = -1 to false
            app.macndroid.core.Haptics.play(app.macndroid.core.Haptics.Kind.Tick)
            registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { reportBattery(it, force = true) }
            NotificationMirror.instance?.syncAll()
            ClipboardSync.flushPending()
            app.macndroid.ui.ShareShortcuts.publish(this)
            media.resync()
            main.post { volume.report(force = true) }
            main.post { screenshots.refresh(); SmsBridge.start(this) }
        }
        Link.onDisconnected = {
            LeftBehind.onDisconnected()
            files.abortIncoming()
            main.post { ScreenMirror.stop(notifyMac = false) }
            NotificationMirror.instance?.resetSession()
            main.post { MacMusic.end(); SmsBridge.stop(this) }
        }
        Link.start(this)

        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(netCallback)

        statusJob = Link.scope.launch {
            combine(Link.status, Prefs.pairing, app.macndroid.core.Pause.until) { s, _, _ -> s }.collect { s -> main.post { updateNotification(s) } }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SEND_URIS -> {
                val uris = buildList {
                    intent.clipData?.let { cd -> for (i in 0 until cd.itemCount) cd.getItemAt(i).uri?.let(::add) }
                    intent.data?.let(::add)
                }.distinct()
                files.send(uris)
            }
            ACTION_SEND_FOLDER -> intent.data?.let(files::sendFolder)
            ACTION_STOP_RING -> ringer.stop(notifyMac = true)
            ACTION_TEST_RING -> {
                ringer.start()
                main.postDelayed({ ringer.stop(notifyMac = false) }, 3000)
            }
            ACTION_RECONNECT -> Link.kick()
            ACTION_RESUME -> app.macndroid.core.Pause.resume(this)
            ACTION_REFRESH -> { screenshots.refresh(); media.start(); SmsBridge.stop(this); if (Link.isConnected) SmsBridge.start(this) }
            ACTION_MAC_MEDIA -> intent.getStringExtra("cmd")?.let(MacMusic::command)
        }
        if (Prefs.pairing.value == null) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        statusJob?.cancel()
        ScreenMirror.stop(notifyMac = false)
        media.stop()
        volume.stop()
        screenshots.stop()
        ringer.stop(notifyMac = false)
        LeftBehind.stop()
        MacMusic.end()
        SmsBridge.stop(this)
        runCatching { unregisterReceiver(batteryReceiver) }
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(netCallback) }
        Link.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // MARK: messages from the Mac (IO thread, in order)

    private fun handle(msg: JSONObject) {
        val type = msg.optString("t")
        if (!app.macndroid.core.Pause.allowsIn(type)) {
            // Paused: tell the computer its screen request won't happen, rather than leaving it waiting.
            if (type == "mirror_start") Link.post(JSONObject().put("t", "mirror_stopped").put("reason", "paused"))
            return
        }
        when (type) {
            "clip" -> if (Prefs.receiveClipboard.value || msg.optBoolean("explicit")) {
                ClipboardSync.receiveText(this, msg.optString("text"))
            }
            "notif_dismiss" -> NotificationMirror.instance?.dismiss(msg.optString("key"))
            "notif_action" -> NotificationMirror.instance?.perform(
                msg.optString("key"), msg.optInt("i"), msg.optString("text").ifEmpty { null })
            "ring" -> main.post { ringer.start() }
            "ring_stop" -> main.post { ringer.stop(notifyMac = false) }
            "file_start", "file_chunk", "file_end" -> files.handleIncoming(msg)
            "mirror_start" -> main.post { ScreenMirror.request(this) }
            "mirror_stop" -> main.post { ScreenMirror.stop(notifyMac = false) }
            "mirror_key" -> ScreenMirror.requestKeyFrame()
            "bye" -> LeftBehind.onMacSleeping()
            "approve_request" -> PairingPrompts.onRequest(this, msg)
            "unpaired" -> PairingPrompts.onUnpaired(this, msg)
            "input" -> {
                val cw = CopyWatcher.instance
                val key = msg.optString("key")
                if (key == "volume_up" || key == "volume_down") {
                    // Works without the control service: the system volume panel shows on the phone.
                    getSystemService(android.media.AudioManager::class.java).adjustSuggestedStreamVolume(
                        if (key == "volume_up") android.media.AudioManager.ADJUST_RAISE else android.media.AudioManager.ADJUST_LOWER,
                        android.media.AudioManager.USE_DEFAULT_STREAM_TYPE, android.media.AudioManager.FLAG_SHOW_UI)
                } else if (cw == null) {
                    Link.post(JSONObject().put("t", "input_unavailable"))
                    main.post { Controls.prompt(this) }
                } else {
                    val pts = msg.optJSONArray("p")
                    val points = (0 until (pts?.length() ?: 0)).mapNotNull { i ->
                        pts?.optJSONArray(i)?.let { it.optDouble(0).toFloat() to it.optDouble(1).toFloat() }
                    }
                    when {
                        msg.has("key") -> cw.key(msg.optString("key"))
                        msg.has("text") -> cw.type(msg.optString("text"))
                        else -> cw.gesture(points, msg.optLong("dur", 60))
                    }
                }
            }
            "open_url" -> main.post { Handoff.openFromMac(this, msg.optString("url")) }
            "clip_history" -> ClipHistory.receive(msg)
            "mac_media" -> main.post { MacMusic.receive(this, msg) }
            "mac_media_end" -> main.post { MacMusic.end() }
            "mac_volume" -> main.post { MacMusic.volume(msg) }
            "volume_set" -> main.post { volume.set(msg.optInt("vol", -1).takeIf { it >= 0 } ?: return@post) }
            "gallery_list" -> Gallery.list(this, msg)
            "gallery_thumbs" -> Gallery.thumbs(this, msg)
            "gallery_get" -> Gallery.get(this, msg)
            "gallery_ask" -> main.post { Gallery.ask(this) }
            "sms_threads" -> SmsBridge.sendThreads(this)
            "sms_thread" -> SmsBridge.sendThread(this, msg.optString("id"))
            "contacts_search" -> SmsBridge.searchContacts(this, msg.optString("q"))
            "sms_send" -> SmsBridge.send(this, msg.optString("address"), msg.optString("text"), msg.optString("id"), msg.optString("thread"))
            "media_cmd" -> media.command(msg.optString("cmd"), msg.optLong("pos"), msg.optString("id").ifEmpty { null })
        }
    }

    private fun reportBattery(i: Intent, force: Boolean) {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else return
        if (!force && lastBattery == pct to charging) return
        lastBattery = pct to charging
        Link.post(JSONObject().put("t", "battery").put("level", pct).put("charging", charging))
    }

    // MARK: status notification

    private fun buildNotification(s: Link.Status): Notification {
        val mac = Prefs.pairing.value?.macName ?: Computer.word
        val text = when (s) {
            Link.Status.Connected -> "Connected to $mac"
            Link.Status.Connecting -> "Connecting to $mac…"
            Link.Status.Searching -> "Looking for $mac…"
            Link.Status.AuthFailed -> "No longer paired with $mac. Scan its code to pair again."
            Link.Status.AwaitingApproval -> "Waiting for approval on ${Link.approvalBy.value ?: "your other phone"} or $mac"
            Link.Status.Denied -> "$mac didn't allow this phone"
            Link.Status.Unpaired -> "Not paired"
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val paused = app.macndroid.core.Pause.until.value
        val title = if (paused != 0L && s == Link.Status.Connected) "Sharing paused · " + app.macndroid.ui.pauseEndText(this, paused) else text
        val b = NotificationCompat.Builder(this, MacNDroidApp.CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_logo)
            .setContentTitle(title)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (paused != 0L) {
            val resume = PendingIntent.getService(
                this, 4, Intent(this, SyncService::class.java).setAction(ACTION_RESUME), PendingIntent.FLAG_IMMUTABLE)
            b.addAction(R.drawable.ic_stat_logo, "Resume", resume)
        } else if (s == Link.Status.Connected) {
            val clip = PendingIntent.getActivity(
                this, 1, Intent(this, SendClipboardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE)
            b.addAction(R.drawable.ic_clipboard, "Send clipboard", clip)
        } else if (s != Link.Status.Unpaired) {
            val retry = PendingIntent.getService(
                this, 2, Intent(this, SyncService::class.java).setAction(ACTION_RECONNECT), PendingIntent.FLAG_IMMUTABLE)
            b.addAction(R.drawable.ic_stat_logo, "Retry now", retry)
        }
        return b.build()
    }

    private fun startInForeground(s: Link.Status) {
        val n = buildNotification(s)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    /** Adds/removes the mediaProjection foreground type around a mirroring session. */
    fun setProjectionForeground(on: Boolean) {
        val n = buildNotification(Link.status.value)
        runCatching {
            if (on && Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else startInForeground(Link.status.value)
        }.onFailure { android.util.Log.w("SyncService", "foreground type change failed", it) }
    }

    private fun updateNotification(s: Link.Status) {
        runCatching {
            getSystemService(android.app.NotificationManager::class.java).notify(NOTIF_ID, buildNotification(s))
        }
    }

    companion object {
        const val NOTIF_ID = 1
        const val ACTION_SEND_URIS = "app.macndroid.SEND_URIS"
        const val ACTION_SEND_FOLDER = "app.macndroid.SEND_FOLDER"
        const val ACTION_STOP_RING = "app.macndroid.STOP_RING"
        const val ACTION_RECONNECT = "app.macndroid.RECONNECT"
        const val ACTION_TEST_RING = "app.macndroid.TEST_RING"
        const val ACTION_REFRESH = "app.macndroid.REFRESH"
        const val ACTION_MAC_MEDIA = "app.macndroid.MAC_MEDIA"
        const val ACTION_RESUME = "app.macndroid.RESUME"

        @Volatile var instance: SyncService? = null
            private set

        fun start(ctx: Context) {
            if (Prefs.pairing.value == null) return
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, SyncService::class.java)) }
        }

        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, SyncService::class.java))

        /** Hands URIs (with read grants) to the service so transfers outlive the calling activity. */
        fun sendUris(ctx: Context, uris: List<Uri>) {
            if (uris.isEmpty() || app.macndroid.core.Pause.active) return
            val i = Intent(ctx, SyncService::class.java).setAction(ACTION_SEND_URIS)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val cd = ClipData.newRawUri("files", uris.first())
            uris.drop(1).forEach { cd.addItem(ClipData.Item(it)) }
            i.clipData = cd
            ContextCompat.startForegroundService(ctx, i)
        }

        /** A folder from the system folder picker (a document tree). */
        fun sendFolder(ctx: Context, tree: Uri) {
            if (app.macndroid.core.Pause.active) return
            val i = Intent(ctx, SyncService::class.java).setAction(ACTION_SEND_FOLDER).setData(tree)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ContextCompat.startForegroundService(ctx, i)
        }
    }
}
