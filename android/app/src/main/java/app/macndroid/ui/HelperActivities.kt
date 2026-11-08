package app.macndroid.ui

import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import app.macndroid.core.Link
import app.macndroid.core.Prefs
import app.macndroid.service.ClipboardSync
import app.macndroid.service.SyncService
import org.json.JSONObject

/**
 * Android 10+ only lets the focused app read the clipboard, so this invisible
 * activity grabs focus for a moment, sends the clipboard and closes.
 */
class SendClipboardActivity : Activity() {
    private var done = false

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || done) return
        done = true
        val auto = intent.getBooleanExtra(EXTRA_AUTO, false)
        val result = ClipboardSync.sendCurrent(this, auto)
        if (!auto) {
            when (result) {
                ClipboardSync.Result.SentText -> toast(this, "Copied to ${macName()}")
                ClipboardSync.Result.SentImage -> toast(this, "Image copied to ${macName()}")
                ClipboardSync.Result.Empty -> toast(this, "Clipboard is empty")
                ClipboardSync.Result.NotConnected -> toast(this, "Not connected to your Mac")
                ClipboardSync.Result.Duplicate, ClipboardSync.Result.Sensitive -> {}
            }
        }
        finish()
        overridePendingTransition(0, 0)
    }

    companion object { const val EXTRA_AUTO = "auto" }
}

/** Receives "Share → Send to Mac" for text, links and files. */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.parcelable<Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> intent.parcelableList<Uri>(Intent.EXTRA_STREAM)
            else -> emptyList()
        }
        when {
            !Link.isConnected -> toast(this, "Not connected to your Mac")
            uris.isNotEmpty() -> {
                ShareShortcuts.reportUsed(this)
                SyncService.sendUris(this, uris)
                toast(this, if (uris.size == 1) "Sending to ${macName()}…" else "Sending ${uris.size} files to ${macName()}…")
            }
            else -> sendText(this, intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())
        }
        finish()
    }
}

private fun macName() = Prefs.pairing.value?.macName ?: "Mac"

private fun toast(a: Activity, s: String) = Toast.makeText(a.applicationContext, s, Toast.LENGTH_SHORT).show()

fun sendText(a: Activity, text: String?) {
    when {
        text.isNullOrEmpty() -> toast(a, "Clipboard is empty")
        !Link.isConnected -> toast(a, "Not connected to your Mac")
        else -> {
            Link.post(JSONObject().put("t", "clip").put("text", text).put("explicit", true))
            toast(a, "Copied to ${macName()}")
        }
    }
}

@Suppress("DEPRECATION")
inline fun <reified T : android.os.Parcelable> Intent.parcelable(key: String): T? =
    if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, T::class.java) else getParcelableExtra(key)

@Suppress("DEPRECATION")
inline fun <reified T : android.os.Parcelable> Intent.parcelableList(key: String): List<T> =
    (if (Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(key, T::class.java) else getParcelableArrayListExtra(key))
        ?: emptyList()
