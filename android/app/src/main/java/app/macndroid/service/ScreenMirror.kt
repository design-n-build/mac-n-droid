package app.macndroid.service

import app.macndroid.core.Computer

import android.app.Activity
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import app.macndroid.MacNDroidApp
import app.macndroid.R
import app.macndroid.core.Haptics
import app.macndroid.core.Link
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * Screen mirroring: MediaProjection → VirtualDisplay → hardware H.264 encoder → encrypted link.
 * The Mac decodes it with VideoToolbox. Taps and keys from the Mac are injected by [CopyWatcher] (clipboard and controls).
 */
object ScreenMirror {
    private const val TAG = "ScreenMirror"
    private const val MAX_LONG_SIDE = 1600
    private const val CONSENT_NOTIF = 40

    @Volatile var isRunning = false
        private set

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var encoder: Encoder? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var sender: Job? = null
    private var outbox = Channel<JSONObject>(4)
    private val main = Handler(Looper.getMainLooper())
    private var displayListener: DisplayManager.DisplayListener? = null
    private var appContext: Context? = null

    /** The Mac asked to mirror: Android requires the user to approve it on the phone each time. */
    fun request(ctx: Context) {
        if (isRunning) { encoder?.requestKeyFrame(resendConfig = true); return }
        val intent = Intent(ctx, MirrorConsentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Background activity starts are allowed while our accessibility service is bound; the
        // notification is the fallback when they aren't.
        runCatching { ctx.startActivity(intent) }
        val pi = PendingIntent.getActivity(ctx, 40, intent, PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, MacNDroidApp.CH_MIRROR)
            .setSmallIcon(R.drawable.ic_stat_logo)
            .setContentTitle("Show this screen on ${Computer.your}?")
            .setContentText("Tap to allow screen mirroring")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setTimeoutAfter(60_000)
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(CONSENT_NOTIF, n) }
    }

    fun consentFinished(ctx: Context) {
        runCatching { ctx.getSystemService(NotificationManager::class.java).cancel(CONSENT_NOTIF) }
    }

    fun begin(ctx: Context, resultCode: Int, data: Intent) {
        val app = ctx.applicationContext
        appContext = app
        val service = SyncService.instance ?: run { Log.w(TAG, "service not running"); return }
        // Android 14+: the foreground service must hold the mediaProjection type before getMediaProjection().
        service.setProjectionForeground(true)
        val mp = runCatching {
            app.getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, data)
        }.getOrNull() ?: run { service.setProjectionForeground(false); denied(); return }
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { stop(notifyMac = true) } }
        }, main)

        outbox = Channel(4)
        sender = Link.scope.launch { for (m in outbox) Link.send(m) }

        val (w, h, dpi) = targetSize(app)
        val enc = Encoder(w, h)
        encoder = enc
        display = mp.createVirtualDisplay("Mac n Droid", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, enc.surface, null, null)
        isRunning = true

        // Keep the screen on while mirrored, like a cable-connected display would.
        @Suppress("DEPRECATION")
        wakeLock = app.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "macndroid:mirror")
            .apply { acquire(4 * 60 * 60_000L) }

        // Rotation: re-create the encoder at the new aspect ratio.
        val dm = app.getSystemService(DisplayManager::class.java)
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(id: Int) { if (id == Display.DEFAULT_DISPLAY) main.post { onRotate() } }
            override fun onDisplayAdded(id: Int) {}
            override fun onDisplayRemoved(id: Int) {}
        }
        dm.registerDisplayListener(listener, main)
        displayListener = listener
        Log.i(TAG, "mirroring ${w}x$h")
        Haptics.play(Haptics.Kind.Success)
    }

    fun denied() {
        Link.post(JSONObject().put("t", "mirror_stopped").put("reason", "denied"))
    }

    private fun onRotate() {
        val app = appContext ?: return
        val vd = display ?: return
        val old = encoder ?: return
        val (w, h, dpi) = targetSize(app)
        if (w == old.width && h == old.height) return
        val enc = Encoder(w, h)
        encoder = enc
        vd.resize(w, h, dpi)
        vd.surface = enc.surface
        old.release()
    }

    fun stop(notifyMac: Boolean) {
        if (!isRunning) return
        isRunning = false
        displayListener?.let { appContext?.getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(it) }
        displayListener = null
        runCatching { display?.release() }
        display = null
        encoder?.release()
        encoder = null
        runCatching { projection?.stop() }
        projection = null
        runCatching { wakeLock?.release() }
        wakeLock = null
        outbox.close()
        sender = null
        SyncService.instance?.setProjectionForeground(false)
        if (notifyMac) Link.post(JSONObject().put("t", "mirror_stopped"))
        Log.i(TAG, "mirroring stopped")
        Haptics.play(Haptics.Kind.Click)
    }

    fun requestKeyFrame() = encoder?.requestKeyFrame(resendConfig = true)

    /** Display size scaled so the long side is ≤ 1600 px, rounded to multiples of 16 (encoder friendly). */
    private fun targetSize(ctx: Context): Triple<Int, Int, Int> {
        val wm = ctx.getSystemService(WindowManager::class.java)
        val (rw, rh) = if (Build.VERSION.SDK_INT >= 30) {
            wm.maximumWindowMetrics.bounds.let { it.width() to it.height() }
        } else {
            @Suppress("DEPRECATION")
            DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }.let { it.widthPixels to it.heightPixels }
        }
        val scale = minOf(1.0, MAX_LONG_SIDE.toDouble() / maxOf(rw, rh))
        fun r16(v: Double) = maxOf(16, (v / 16).roundToInt() * 16)
        return Triple(r16(rw * scale), r16(rh * scale), ctx.resources.displayMetrics.densityDpi)
    }

    /** Hardware H.264 encoder fed by a Surface; output goes to the Mac with simple congestion control. */
    private class Encoder(val width: Int, val height: Int) {
        private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val surface: Surface
        @Volatile private var running = true
        @Volatile private var config: ByteArray? = null
        @Volatile private var configPending = false
        private var dropping = false

        init {
            val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 60)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 4)
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000)   // static screens still refresh
                setInteger(MediaFormat.KEY_PRIORITY, 0)                          // realtime
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)                      // lowest latency
            }
            codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = codec.createInputSurface()
            codec.start()
            thread(name = "mirror-encoder", isDaemon = true) { drain() }
        }

        fun requestKeyFrame(resendConfig: Boolean) {
            if (resendConfig) configPending = true
            runCatching { codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
        }

        private fun drain() {
            val info = MediaCodec.BufferInfo()
            while (running) {
                val idx = try { codec.dequeueOutputBuffer(info, 100_000) } catch (e: IllegalStateException) { break }
                if (idx < 0) continue
                val buf = codec.getOutputBuffer(idx)
                val bytes = if (buf != null && info.size > 0) {
                    ByteArray(info.size).also { buf.position(info.offset); buf.get(it) }
                } else null
                runCatching { codec.releaseOutputBuffer(idx, false) }
                bytes ?: continue
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    config = bytes
                    configPending = true
                    continue
                }
                val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                if (configPending && key) {
                    val cfg = config ?: continue
                    if (!outbox.trySend(JSONObject().put("t", "mirror_cfg").put("w", width).put("h", height)
                            .put("d", Base64.encodeToString(cfg, Base64.NO_WRAP))).isSuccess) continue
                    configPending = false
                }
                // While congested, skip frames until the next key frame so the picture never smears.
                if (dropping && !key) continue
                val sent = outbox.trySend(JSONObject().put("t", "mirror_frame").put("k", key)
                    .put("d", Base64.encodeToString(bytes, Base64.NO_WRAP))).isSuccess
                if (sent) dropping = false
                else if (!dropping) { dropping = true; requestKeyFrame(resendConfig = false) }
            }
        }

        fun release() {
            running = false
            runCatching { codec.signalEndOfInputStream() }
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { surface.release() }
        }
    }
}

/** Shows Android's "Start recording or casting?" dialog. Entire screen only. */
class MirrorConsentActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ScreenMirror.consentFinished(this)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else mpm.createScreenCaptureIntent()
        @Suppress("DEPRECATION")
        startActivityForResult(intent, 1)
    }

    @Deprecated("Activity result API is fine for a one-shot invisible activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK && data != null) ScreenMirror.begin(this, resultCode, data) else ScreenMirror.denied()
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
