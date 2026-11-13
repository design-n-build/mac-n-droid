package app.macndroid.service

import android.app.Activity
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import androidx.core.app.NotificationCompat
import app.macndroid.MacNDroidApp
import app.macndroid.R
import app.macndroid.core.Computer
import app.macndroid.core.Haptics
import app.macndroid.core.Link
import app.macndroid.core.Prefs
import app.macndroid.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * Another phone scanned the computer's pairing code while this one is paired: this phone may allow it
 * (from a notification or a dialog in the app), and the computer asks too. The first answer wins.
 * If it's allowed, this phone is told it was replaced and forgets the pairing.
 */
object PairingPrompts {
    data class Request(val id: String, val name: String)

    private const val NOTIF = 61
    private const val NOTIF_UNPAIRED = 62
    const val ACTION_ANSWER = "app.macndroid.PAIRING_ANSWER"
    const val EXTRA_ID = "id"
    const val EXTRA_ALLOW = "allow"
    const val EXTRA_NAME = "name"

    private val _request = MutableStateFlow<Request?>(null)
    /** Shown as a dialog while the app is open. */
    val request: StateFlow<Request?> = _request

    fun onRequest(ctx: Context, msg: JSONObject) {
        val r = Request(msg.optString("id"), msg.optString("name").ifBlank { "Another phone" })
        if (r.id.isEmpty()) return
        _request.value = r
        Haptics.play(Haptics.Kind.Click)
        val mac = Prefs.pairing.value?.macName ?: Computer.word
        fun answer(allow: Boolean, code: Int) = PendingIntent.getBroadcast(
            ctx, code,
            Intent(ctx, PairingAnswerReceiver::class.java).setAction(ACTION_ANSWER)
                .putExtra(EXTRA_ID, r.id).putExtra(EXTRA_ALLOW, allow),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(ctx, NOTIF, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, MacNDroidApp.CH_PAIRING)
            .setSmallIcon(R.drawable.ic_stat_logo)
            .setContentTitle("Allow ${r.name} to connect to $mac?")
            .setContentText("It scanned the pairing code. If you allow it, this phone is disconnected.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "${r.name} scanned the pairing code on $mac. If you allow it, it replaces this phone, which will be " +
                    "disconnected. Only allow a phone that's yours."))
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setTimeoutAfter(120_000)
            .addAction(0, "Don't allow", answer(false, NOTIF + 1))
            .addAction(0, "Allow", PendingIntent.getActivity(
                ctx, NOTIF + 2,
                Intent(ctx, ApproveActivity::class.java).putExtra(EXTRA_ID, r.id).putExtra(EXTRA_NAME, r.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(NOTIF, n) }
    }

    /**
     * Allowing a new phone needs the owner: fingerprint, face or the screen lock first (a phone without a screen
     * lock can't check, so it just allows). Cancelling leaves the question open.
     */
    fun confirmAllow(activity: Activity, id: String, name: String, done: (Boolean) -> Unit = {}) {
        val km = activity.getSystemService(KeyguardManager::class.java)
        if (!km.isDeviceSecure) { answer(activity, id, true); done(true); return }
        val mac = Prefs.pairing.value?.macName ?: Computer.word
        val b = BiometricPrompt.Builder(activity)
            .setTitle("Allow $name?")
            .setSubtitle("Confirm it's you to let $name connect to $mac")
        if (Build.VERSION.SDK_INT >= 30) {
            b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        } else {
            @Suppress("DEPRECATION") b.setDeviceCredentialAllowed(true)
        }
        b.build().authenticate(CancellationSignal(), activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                answer(activity, id, true); done(true)
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { done(false) }
        })
    }

    /** From the notification or the in-app dialog. */
    fun answer(ctx: Context, id: String, allow: Boolean) {
        if (_request.value?.id == id) _request.value = null
        runCatching { ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF) }
        Link.post(JSONObject().put("t", "approve").put("id", id).put("allow", allow))
    }

    /** Another phone was allowed in its place: forget the pairing and say why. */
    fun onUnpaired(ctx: Context, msg: JSONObject) {
        val by = msg.optString("by").ifBlank { "Another phone" }
        val mac = Prefs.pairing.value?.macName ?: Computer.word
        _request.value = null
        val nm = ctx.getSystemService(NotificationManager::class.java)
        runCatching { nm.cancel(NOTIF) }
        Prefs.clearPairing()
        val open = PendingIntent.getActivity(ctx, NOTIF_UNPAIRED, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, MacNDroidApp.CH_PAIRING)
            .setSmallIcon(R.drawable.ic_stat_logo)
            .setContentTitle("Disconnected from $mac")
            .setContentText("$by is now paired with $mac. Scan its code to pair this phone again.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "$by is now paired with $mac instead of this phone. To use this phone again, scan the pairing code on $mac."))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(NOTIF_UNPAIRED, n) }
    }
}

class PairingAnswerReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != PairingPrompts.ACTION_ANSWER) return
        val id = intent.getStringExtra(PairingPrompts.EXTRA_ID) ?: return
        // Only "Don't allow" comes this way; allowing goes through ApproveActivity (fingerprint or screen lock).
        PairingPrompts.answer(ctx, id, false)
    }
}

/** The notification's "Allow": asks for the fingerprint or screen lock over whatever is on screen, then answers. */
class ApproveActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(PairingPrompts.EXTRA_ID)
        if (id == null || PairingPrompts.request.value?.id != id) { finish(); return }
        PairingPrompts.confirmAllow(this, id, intent.getStringExtra(PairingPrompts.EXTRA_NAME) ?: "this phone") { finish() }
    }
}
