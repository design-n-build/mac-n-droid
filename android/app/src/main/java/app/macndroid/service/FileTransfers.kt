package app.macndroid.service

import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import app.macndroid.MacNDroidApp
import app.macndroid.R
import app.macndroid.core.Link
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.OutputStream
import java.util.UUID

data class TransferItem(
    val id: String,
    val name: String,
    val size: Long,
    val outgoing: Boolean,
    val bytes: Long = 0,
    val state: State = State.Active,
    val uri: Uri? = null,
    val mime: String = "",
    val time: Long = System.currentTimeMillis(),
) {
    enum class State { Active, Done, Failed }
    val progress: Float get() = if (size > 0) (bytes.toFloat() / size).coerceIn(0f, 1f) else if (state == State.Done) 1f else 0f
}

/** Global list the UI observes. */
object Transfers {
    private val _items = MutableStateFlow<List<TransferItem>>(emptyList())
    val items: StateFlow<List<TransferItem>> = _items

    fun add(t: TransferItem) { _items.value = (listOf(t) + _items.value).take(30) }
    fun update(id: String, f: (TransferItem) -> TransferItem) {
        _items.value = _items.value.map { if (it.id == id) f(it) else it }
    }
    fun clearFinished() { _items.value = _items.value.filter { it.state == TransferItem.State.Active } }
}

/** Phone ⇄ Mac file transfer. Received files go to Downloads/Mac n Droid. */
class FileTransfers(private val ctx: Context) {
    private class Incoming(
        val uri: Uri?, val out: OutputStream, val name: String, val size: Long,
        val clipFile: java.io.File? = null, var written: Long = 0,
    )

    private val incoming = HashMap<String, Incoming>()   // touched only on the Link read thread
    private val sendLock = Mutex()

    // MARK: Mac → phone

    fun handleIncoming(msg: JSONObject) {
        val id = msg.optString("id")
        when (msg.optString("t")) {
            "file_start" -> start(id, msg)
            "file_chunk" -> incoming[id]?.let { f ->
                val data = Base64.decode(msg.optString("data"), Base64.DEFAULT)
                f.out.write(data)
                val before = f.written
                f.written += data.size
                // Throttle UI updates to ~every 1 MiB.
                if (f.clipFile == null && f.written / (1 shl 20) != before / (1 shl 20)) {
                    Transfers.update(id) { it.copy(bytes = f.written) }
                }
            }
            "file_end" -> finish(id)
        }
    }

    private fun start(id: String, msg: JSONObject) {
        val name = msg.optString("name", "file").substringAfterLast('/').ifBlank { "file" }
        val size = msg.optLong("size")
        if (msg.optBoolean("clip")) {
            // Clipboard image from the Mac: keep it private in cache, not in Downloads.
            val file = java.io.File(ClipboardSync.clipDir(ctx), "mac-${System.currentTimeMillis()}.png")
            incoming[id] = Incoming(null, file.outputStream().buffered(256 * 1024), name, size, clipFile = file)
            return
        }
        val mime = msg.optString("mime").ifBlank {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "")) ?: "application/octet-stream"
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Mac n Droid")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        val out = resolver.openOutputStream(uri)?.buffered(256 * 1024) ?: return
        incoming[id] = Incoming(uri, out, name, size)
        Transfers.add(TransferItem(id, name, size, outgoing = false, uri = uri, mime = mime))
    }

    private fun finish(id: String) {
        val f = incoming.remove(id) ?: return
        runCatching { f.out.close() }
        if (f.clipFile != null) {
            ClipboardSync.receiveImage(ctx, f.clipFile)
            app.macndroid.core.Haptics.play(app.macndroid.core.Haptics.Kind.Tick)
            return
        }
        val uri = f.uri ?: return
        ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        Transfers.update(id) { it.copy(bytes = f.written, state = TransferItem.State.Done) }
        app.macndroid.core.Haptics.play(app.macndroid.core.Haptics.Kind.Success)
        notifyReceived(f)
    }

    fun abortIncoming() {
        incoming.forEach { (id, f) ->
            runCatching { f.out.close() }
            f.clipFile?.delete()
            f.uri?.let { runCatching { ctx.contentResolver.delete(it, null, null) } }
            Transfers.update(id) { it.copy(state = TransferItem.State.Failed) }
        }
        incoming.clear()
    }

    private fun notifyReceived(f: Incoming) {
        val uri = f.uri ?: return
        val mime = ctx.contentResolver.getType(uri) ?: "*/*"
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(ctx, uri.hashCode(), view, PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, MacNDroidApp.CH_FILES)
            .setSmallIcon(R.drawable.ic_stat_logo)
            .setContentTitle("Received from Mac")
            .setContentText(f.name)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching {
            ctx.getSystemService(android.app.NotificationManager::class.java).notify(uri.hashCode(), n)
        }
    }

    // MARK: phone → Mac

    /** `clip` = clipboard image: it lands on the Mac's clipboard instead of in Downloads. */
    fun send(uris: List<Uri>, clip: Boolean = false) {
        for (uri in uris) {
            val (name, size) = meta(uri)
            val id = UUID.randomUUID().toString()
            val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
            if (!clip) Transfers.add(TransferItem(id, name, size, outgoing = true, uri = uri, mime = mime))
            Link.scope.launch {
                sendLock.withLock { stream(uri, id, name, size, mime, clip) }
            }
        }
    }

    private suspend fun stream(uri: Uri, id: String, name: String, size: Long, mime: String, clip: Boolean) {
        val start = JSONObject().put("t", "file_start").put("id", id).put("name", name).put("size", size).put("mime", mime)
        if (clip) start.put("clip", true)
        var ok = Link.send(start)
        var sent = 0L
        if (ok) {
            try {
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    val buf = ByteArray(256 * 1024)
                    while (ok) {
                        val n = readFully(input, buf)
                        if (n <= 0) break
                        ok = Link.send(JSONObject().put("t", "file_chunk").put("id", id)
                            .put("data", Base64.encodeToString(buf, 0, n, Base64.NO_WRAP)))
                        val before = sent
                        sent += n
                        if (sent / (1 shl 20) != before / (1 shl 20)) Transfers.update(id) { it.copy(bytes = sent) }
                    }
                } ?: run { ok = false }
            } catch (e: Exception) {
                ok = false
            }
        }
        if (ok) ok = Link.send(JSONObject().put("t", "file_end").put("id", id))
        Transfers.update(id) {
            it.copy(bytes = sent, state = if (ok) TransferItem.State.Done else TransferItem.State.Failed)
        }
        if (!clip) notifySent(name, ok)
        app.macndroid.core.Haptics.play(if (!ok) app.macndroid.core.Haptics.Kind.Failure else if (clip) app.macndroid.core.Haptics.Kind.Tick else app.macndroid.core.Haptics.Kind.Success)
    }

    /** Short-lived confirmation with the channel's (subtle) sound. */
    private fun notifySent(name: String, ok: Boolean) {
        val mac = app.macndroid.core.Prefs.pairing.value?.macName ?: "Mac"
        val n = NotificationCompat.Builder(ctx, MacNDroidApp.CH_SENT)
            .setSmallIcon(R.drawable.ic_stat_logo)
            .setContentTitle(if (ok) "Sent to $mac" else "Couldn't send to $mac")
            .setContentText(name)
            .setTimeoutAfter(if (ok) 5_000 else 30_000)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        runCatching {
            ctx.getSystemService(android.app.NotificationManager::class.java).notify(("sent" + name).hashCode(), n)
        }
    }

    private fun readFully(input: java.io.InputStream, buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val n = input.read(buf, total, buf.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    private fun meta(uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        var size = 0L
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        return name to size
    }
}
