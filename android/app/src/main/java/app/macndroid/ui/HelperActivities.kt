package app.macndroid.ui

import app.macndroid.core.Computer

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
                ClipboardSync.Result.NotConnected -> toast(this, "Not connected to ${Computer.your}")
                ClipboardSync.Result.Paused -> toast(this, "Sharing is paused. Open Mac n Droid to resume.")
                ClipboardSync.Result.Duplicate, ClipboardSync.Result.Sensitive -> {}
            }
        }
        finish()
        overridePendingTransition(0, 0)
    }

    companion object { const val EXTRA_AUTO = "auto" }
}

/** "Share → Send to Mac": files go to the Mac's Downloads; text and links go to its clipboard. */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.parcelable<Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> intent.parcelableList<Uri>(Intent.EXTRA_STREAM)
            else -> emptyList()
        }
        when {
            !Link.isConnected -> toast(this, "Not connected to ${Computer.your}")
            app.macndroid.core.Pause.active -> toast(this, "Sharing is paused. Open Mac n Droid to resume.")
            uris.isNotEmpty() -> {
                ShareShortcuts.reportUsed(this, ShareShortcuts.SEND_ID)
                SyncService.sendUris(this, uris)
                toast(this, if (uris.size == 1) "Sending to ${macName()}…" else "Sending ${uris.size} files to ${macName()}…")
            }
            else -> {
                ShareShortcuts.reportUsed(this, ShareShortcuts.SEND_ID)
                sendText(this, intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())
            }
        }
        finish()
    }
}

/** "Share → Open on Mac": the shared link opens in the Mac's browser (link handoff). */
class OpenOnMacActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val link = app.macndroid.service.Handoff.findLink(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())
        when {
            link == null -> toast(this, "There's no web link to open")
            !Link.isConnected -> toast(this, "Not connected to ${Computer.your}")
            app.macndroid.service.Handoff.sendToMac(link) -> {
                ShareShortcuts.reportUsed(this, ShareShortcuts.OPEN_ID)
                toast(this, "Opening on ${macName()}")
            }
        }
        finish()
    }
}

/** Quick Settings "Send files to Mac": shows the file picker over whatever's on screen, then sends. */
class PickFilesActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        runCatching { startActivityForResult(pick, 1) }.onFailure { finish() }
    }

    @Deprecated("Activity result API isn't available on plain Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uris = buildList {
            data?.clipData?.let { cd -> for (i in 0 until cd.itemCount) cd.getItemAt(i).uri?.let(::add) }
            if (isEmpty()) data?.data?.let(::add)
        }
        when {
            resultCode != RESULT_OK || uris.isEmpty() -> {}
            !Link.isConnected -> toast(this, "Not connected to ${Computer.your}")
            else -> {
                SyncService.sendUris(this, uris)
                toast(this, if (uris.size == 1) "Sending to ${macName()}…" else "Sending ${uris.size} files to ${macName()}…")
            }
        }
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }
}

private fun macName() = Prefs.pairing.value?.macName ?: Computer.word

private fun toast(a: Activity, s: String) = Toast.makeText(a.applicationContext, s, Toast.LENGTH_SHORT).show()

fun sendText(a: Activity, text: String?) {
    when {
        text.isNullOrEmpty() -> toast(a, "Clipboard is empty")
        !Link.isConnected -> toast(a, "Not connected to ${Computer.your}")
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
