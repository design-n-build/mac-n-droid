package app.macndroid.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import app.macndroid.MacNDroidApp
import app.macndroid.R
import app.macndroid.core.Haptics
import app.macndroid.core.Link
import app.macndroid.core.Prefs
import app.macndroid.ui.MainActivity

/**
 * "You left your MacBook behind."
 *
 * There's no distance measurement between the devices, so Wi-Fi is the signal: the phone drops off
 * Wi-Fi roughly 30–60 m from the router, and we then wait [DELAY] (about another 60 m of walking)
 * for the link to come back. If it doesn't, the phone has walked away from an awake Mac.
 *
 * Stays quiet when:
 *  - the Mac said it was going to sleep or quitting (you closed the lid on purpose),
 *  - Wi-Fi was turned off on the phone at any point (that's not walking away),
 *  - the phone gets Wi-Fi back during the wait (a brief drop, or a router restart),
 *  - the phone isn't moving like someone walking (router down while it sits on the desk),
 *  - the phone wasn't connected to the Mac in the last minute.
 */
object LeftBehind {
    private const val TAG = "LeftBehind"
    private const val NOTIF = 60
    private const val DELAY = 45_000L

    private val main = Handler(Looper.getMainLooper())
    private var ctx: Context? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var check: Runnable? = null
    @Volatile private var macAsleep = false
    @Volatile private var lastLinked = 0L

    fun start(context: Context) {
        val app = context.applicationContext
        ctx = app
        if (callback != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) { main.post { onWifiLost() } }
            // Back on Wi-Fi: whatever happened, we haven't walked away.
            override fun onAvailable(network: Network) { main.post { if (check != null) { Log.i(TAG, "Wi-Fi back; cancelled"); cancelCheck() } } }
        }
        val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        runCatching { app.getSystemService(ConnectivityManager::class.java).registerNetworkCallback(req, cb) }
            .onSuccess { callback = cb }
    }

    fun stop() {
        val app = ctx ?: return
        callback?.let { runCatching { app.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        callback = null
        cancelCheck()
    }

    fun onConnected() {
        macAsleep = false
        lastLinked = System.currentTimeMillis()
        main.post {
            cancelCheck()
            ctx?.getSystemService(NotificationManager::class.java)?.cancel(NOTIF)
        }
    }

    fun onDisconnected() {
        if (!macAsleep) lastLinked = System.currentTimeMillis()
    }

    /** The Mac is going to sleep or quitting, so losing it is expected. */
    fun onMacSleeping() { macAsleep = true }

    private fun wifiOn(c: Context) = c.getSystemService(WifiManager::class.java)?.isWifiEnabled == true

    private fun onWifiLost() {
        val c = ctx ?: return
        if (!Prefs.leftBehind.value || Prefs.pairing.value == null || macAsleep || !wifiOn(c)) return
        val recentlyLinked = Link.isConnected || System.currentTimeMillis() - lastLinked < 60_000
        if (!recentlyLinked) return
        Log.i(TAG, "Wi-Fi lost while linked; checking in ${DELAY / 1000}s")
        cancelCheck()
        motion.start(c)
        val started = System.currentTimeMillis()
        // Re-check every few seconds so turning Wi-Fi off (even briefly) cancels the alert.
        val r = object : Runnable {
            override fun run() {
                if (!wifiOn(c) || macAsleep || Link.isConnected || !Prefs.leftBehind.value) { cancelCheck(); return }
                if (System.currentTimeMillis() - started < DELAY) { main.postDelayed(this, 5_000); return }
                val walked = motion.walkingSeconds
                cancelCheck()
                Log.i(TAG, "walking ${walked}s of ${DELAY / 1000}s")
                if (walked >= MIN_WALKING_SECONDS) alert(c)
            }
        }
        check = r
        main.postDelayed(r, 5_000)
    }

    private fun cancelCheck() {
        check?.let(main::removeCallbacks)
        check = null
        motion.stop()
    }

    private const val MIN_WALKING_SECONDS = 12

    /**
     * Counts seconds that look like walking: the acceleration swings by more than a phone lying
     * still or held in a hand would. Runs only during the 45-second check. No permission needed.
     */
    private val motion = object : SensorEventListener {
        var walkingSeconds = 0
            private set
        private var sm: SensorManager? = null
        private var bucketStart = 0L
        private var n = 0
        private var sum = 0.0
        private var sumSq = 0.0

        fun start(c: Context) {
            stop()
            walkingSeconds = 0
            val m = c.getSystemService(SensorManager::class.java) ?: return
            val sensor = m.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
            sm = m
            m.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI, main)
        }

        fun stop() {
            sm?.unregisterListener(this)
            sm = null
            n = 0; sum = 0.0; sumSq = 0.0; bucketStart = 0L
        }

        override fun onSensorChanged(e: SensorEvent) {
            val (x, y, z) = Triple(e.values[0].toDouble(), e.values[1].toDouble(), e.values[2].toDouble())
            val mag = Math.sqrt(x * x + y * y + z * z)
            val now = System.currentTimeMillis()
            if (bucketStart == 0L) bucketStart = now
            n++; sum += mag; sumSq += mag * mag
            if (now - bucketStart >= 1000) {
                val mean = sum / n
                val sd = Math.sqrt(maxOf(0.0, sumSq / n - mean * mean))
                if (sd > 1.0) walkingSeconds++    // m/s²: still ≈ 0.05, in hand ≈ 0.3, walking ≈ 1.5–4
                bucketStart = now; n = 0; sum = 0.0; sumSq = 0.0
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun alert(c: Context) {
        val mac = Prefs.pairing.value?.macName ?: "your Mac"
        Log.i(TAG, "alerting: left $mac behind")
        val open = PendingIntent.getActivity(c, NOTIF, Intent(c, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(c, MacNDroidApp.CH_LEFT_BEHIND)
            .setSmallIcon(R.drawable.ic_stat_logo)
            .setContentTitle("You left $mac behind")
            .setContentText("Your phone lost touch with it as you walked away.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "Your phone lost touch with $mac as you walked away. It was awake and still connected a moment ago. " +
                    "You can turn these alerts off in Mac n Droid → Sync."))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { c.getSystemService(NotificationManager::class.java).notify(NOTIF, n) }
        Haptics.play(Haptics.Kind.Failure)
    }
}
