package app.macndroid.service

import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
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
import java.io.File
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

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
    val isFolder: Boolean get() = mime == FOLDER_MIME
    val progress: Float get() = if (size > 0) (bytes.toFloat() / size).coerceIn(0f, 1f) else if (state == State.Done) 1f else 0f
}

const val FOLDER_MIME = DocumentsContract.Document.MIME_TYPE_DIR

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

/**
 * Phone ⇄ Mac file transfer. Received files go to Downloads/Mac n Droid.
 * A folder travels as one zip marked `folder: true` and is unpacked into a folder on arrival.
 */
class FileTransfers(private val ctx: Context) {
    private class Incoming(
        val uri: Uri?, val out: OutputStream, var name: String, val size: Long,
        val clipFile: File? = null, var written: Long = 0,
        val folderZip: File? = null,
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
        if (msg.optBoolean("folder")) {
            val folder = name.removeSuffix(".zip").ifBlank { "Folder" }
            val file = File(ctx.cacheDir, "incoming-$id.zip")
            incoming[id] = Incoming(null, file.outputStream().buffered(256 * 1024), folder, size, folderZip = file)
            Transfers.add(TransferItem(id, folder, size, outgoing = false, mime = FOLDER_MIME))
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
        f.folderZip?.let { zip ->
            // Unpack off the connection's read thread; it can take a while for big folders.
            unpacking++
            Link.scope.launch {
                val folder = runCatching { unpackFolder(zip, f.name) }.getOrNull()
                zip.delete()
                Transfers.update(id) {
                    if (folder != null) it.copy(name = folder, bytes = f.written, state = TransferItem.State.Done)
                    else it.copy(state = TransferItem.State.Failed)
                }
                main.post {
                    unpacking--
                    if (folder != null) {
                        f.name = folder
                        synchronized(receivedBatch) { receivedBatch.add(f) }
                        main.removeCallbacks(receivedSummary)
                        main.postDelayed(receivedSummary, 900)
                    } else {
                        app.macndroid.core.Haptics.play(app.macndroid.core.Haptics.Kind.Failure)
                    }
                }
            }
            return
        }
        val uri = f.uri ?: return
        ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        Transfers.update(id) { it.copy(bytes = f.written, state = TransferItem.State.Done) }
        synchronized(receivedBatch) { receivedBatch.add(f) }
        // One sound, tap and notification for a batch, shortly after the last file.
        main.removeCallbacks(receivedSummary)
        main.postDelayed(receivedSummary, 900)
    }

    /** Folders still being unpacked; touched only on the main thread. */
    private var unpacking = 0

    /**
     * Unpacks a received folder into Downloads/Mac n Droid/<name>, file by file through MediaStore.
     * Returns the folder's final name (" 2" is added if the name is taken).
     */
    private fun unpackFolder(zip: File, name: String): String {
        val safe = name.replace('/', '-').trim().trimStart('.').ifBlank { "Folder" }
        val root = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Mac n Droid")
        var folder = safe
        var n = 2
        while (File(root, folder).exists()) folder = "$safe ${n++}"
        ZipFile(zip).use { z ->
            // Clean every path: no "..", no leading "/", no Mac-only extras.
            val entries = z.entries().toList().filter { !it.isDirectory }.mapNotNull { e ->
                val parts = e.name.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." && it != ".." }
                if (parts.isEmpty() || parts.first() == "__MACOSX" || parts.last() == ".DS_Store") null else e to parts
            }
            // Zips made from a folder hold that folder at the top; drop it so files aren't nested twice.
            val top = entries.firstOrNull()?.second?.first()
            val strip = top != null && entries.all { it.second.size > 1 && it.second.first() == top }
            val resolver = ctx.contentResolver
            for ((entry, all) in entries) {
                val parts = if (strip) all.drop(1) else all
                val file = parts.last()
                val dirs = parts.dropLast(1).joinToString("/") { it.replace(Regex("^\\.+"), "_") }
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.substringAfterLast('.', "").lowercase())
                    ?: "application/octet-stream"
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, file)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH,
                        listOf(Environment.DIRECTORY_DOWNLOADS, "Mac n Droid", folder, dirs).filter { it.isNotEmpty() }.joinToString("/"))
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: continue
                resolver.openOutputStream(uri)?.use { out -> z.getInputStream(entry).use { it.copyTo(out, 256 * 1024) } }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            }
        }
        return folder
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val receivedBatch = mutableListOf<Incoming>()
    private val receivedSummary: Runnable = object : Runnable {
        override fun run() {
            if (unpacking > 0 || runCatching { incoming.values.any { it.clipFile == null } }.getOrDefault(false)) { main.postDelayed(this, 900); return }
            val batch = synchronized(receivedBatch) { receivedBatch.toList().also { receivedBatch.clear() } }
            if (batch.isEmpty()) return
            app.macndroid.core.Haptics.play(app.macndroid.core.Haptics.Kind.Success)
            notifyReceived(batch)
        }
    }

    private val sendBatch = mutableListOf<Pair<String, Boolean>>()
    @Volatile private var sendsActive = 0

    fun abortIncoming() {
        incoming.forEach { (id, f) ->
            runCatching { f.out.close() }
            f.clipFile?.delete()
            f.folderZip?.delete()
            f.uri?.let { runCatching { ctx.contentResolver.delete(it, null, null) } }
            Transfers.update(id) { it.copy(state = TransferItem.State.Failed) }
        }
        incoming.clear()
    }

    private fun notifyReceived(batch: List<Incoming>) {
        val f = batch.last()
        val uri = f.uri
        // One file opens directly; a folder opens Downloads; several open the app's Files tab.
        val pi = if (batch.size == 1 && f.folderZip != null) {
            val downloads = Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            PendingIntent.getActivity(ctx, f.name.hashCode(), downloads, PendingIntent.FLAG_IMMUTABLE)
        } else if (batch.size == 1 && uri != null) {
            val mime = ctx.contentResolver.getType(uri) ?: "*/*"
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            PendingIntent.getActivity(ctx, uri.hashCode(), view, PendingIntent.FLAG_IMMUTABLE)
        } else {
            PendingIntent.getActivity(ctx, 0, Intent(ctx, app.macndroid.ui.MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        }
        val mac = app.macndroid.core.Prefs.pairing.value?.macName ?: "Mac"
        val n = NotificationCompat.Builder(ctx, MacNDroidApp.CH_FILES)
            .setSilent(!app.macndroid.core.Prefs.sounds.value)
            .setSmallIcon(R.drawable.ic_stat_logo)
            .setContentTitle(if (batch.size == 1) "Received from $mac" else "${batch.size} items from $mac")
            .setContentText(if (batch.size == 1) f.name else batch.joinToString(", ") { it.name })
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching {
            ctx.getSystemService(android.app.NotificationManager::class.java).notify((uri ?: f.name).hashCode(), n)
        }
    }

    // MARK: phone → Mac

    /** `clip` = clipboard image: it lands on the Mac's clipboard instead of in Downloads. */
    fun send(uris: List<Uri>, clip: Boolean = false) {
        for (uri in uris) {
            val (name, size) = meta(uri)
            val id = UUID.randomUUID().toString()
            val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
            if (!clip) {
                Transfers.add(TransferItem(id, name, size, outgoing = true, uri = uri, mime = mime))
                synchronized(sendBatch) { sendsActive++ }
            }
            Link.scope.launch {
                sendLock.withLock { stream(uri, id, name, size, mime, clip) }
            }
        }
    }

    /** Sends a folder (from the system folder picker) as one zip, unpacked again on the Mac. */
    fun sendFolder(tree: Uri) {
        val id = UUID.randomUUID().toString()
        val docId = DocumentsContract.getTreeDocumentId(tree)
        val name = displayName(DocumentsContract.buildDocumentUriUsingTree(tree, docId))
            ?.replace('/', '-')?.ifBlank { null } ?: "Folder"
        Transfers.add(TransferItem(id, name, 0, outgoing = true, mime = FOLDER_MIME))
        synchronized(sendBatch) { sendsActive++ }
        Link.scope.launch {
            val zip = File(ctx.cacheDir, "outgoing-$id.zip")
            try {
                val zipped = runCatching { zipTree(tree, docId, name, zip) }.isSuccess
                if (!zipped) {
                    Transfers.update(id) { it.copy(state = TransferItem.State.Failed) }
                    finishSend(name, false)
                    return@launch
                }
                val size = zip.length()
                Transfers.update(id) { it.copy(size = size) }
                sendLock.withLock { stream(Uri.fromFile(zip), id, "$name.zip", size, "application/zip", clip = false, folder = true, label = name) }
            } finally {
                zip.delete()
            }
        }
    }

    /** Zips the picked folder (with the folder itself at the top) into [out]. */
    private fun zipTree(tree: Uri, rootId: String, rootName: String, out: File) {
        val resolver = ctx.contentResolver
        ZipOutputStream(out.outputStream().buffered(256 * 1024)).use { zip ->
            zip.setLevel(1)   // Photos and videos don't shrink; keep it fast.
            fun walk(docId: String, path: String) {
                val children = mutableListOf<Triple<String, String, String>>()
                resolver.query(
                    DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId),
                    arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE),
                    null, null, null,
                )?.use { c -> while (c.moveToNext()) children += Triple(c.getString(0), c.getString(1) ?: "file", c.getString(2) ?: "") }
                if (children.isEmpty()) { zip.putNextEntry(ZipEntry(path)); zip.closeEntry(); return }
                for ((id, rawName, mime) in children) {
                    val name = rawName.replace('/', '-')
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) { walk(id, "$path$name/"); continue }
                    zip.putNextEntry(ZipEntry("$path$name"))
                    resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, id))?.use { it.copyTo(zip, 256 * 1024) }
                    zip.closeEntry()
                }
            }
            walk(rootId, "$rootName/")
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private suspend fun stream(
        uri: Uri, id: String, name: String, size: Long, mime: String, clip: Boolean,
        folder: Boolean = false, label: String = name,
    ) {
        val start = JSONObject().put("t", "file_start").put("id", id).put("name", name).put("size", size).put("mime", mime)
        if (clip) start.put("clip", true)
        if (folder) start.put("folder", true)
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
        if (clip) {
            app.macndroid.core.Haptics.play(if (ok) app.macndroid.core.Haptics.Kind.Tick else app.macndroid.core.Haptics.Kind.Failure)
            return
        }
        finishSend(label, ok)
    }

    /** One confirmation for the whole batch, after the last file. */
    private fun finishSend(name: String, ok: Boolean) {
        val batch = synchronized(sendBatch) {
            sendBatch.add(name to ok)
            sendsActive--
            if (sendsActive > 0) null else sendBatch.toList().also { sendBatch.clear() }
        } ?: return
        val failed = batch.count { !it.second }
        notifySent(batch.map { it.first }, failed)
        app.macndroid.core.Haptics.play(if (failed > 0) app.macndroid.core.Haptics.Kind.Failure else app.macndroid.core.Haptics.Kind.Success)
    }

    /** Short-lived confirmation with the channel's (subtle) sound. */
    private fun notifySent(names: List<String>, failed: Int) {
        val mac = app.macndroid.core.Prefs.pairing.value?.macName ?: "Mac"
        val ok = failed == 0
        val name = names.joinToString(", ")
        val n = NotificationCompat.Builder(ctx, MacNDroidApp.CH_SENT)
            .setSilent(!app.macndroid.core.Prefs.sounds.value)
            .setSmallIcon(R.drawable.ic_stat_logo)
            .setContentTitle(when {
                !ok && names.size > 1 -> "Couldn't send $failed of ${names.size} items to $mac"
                !ok -> "Couldn't send to $mac"
                names.size > 1 -> "Sent ${names.size} items to $mac"
                else -> "Sent to $mac"
            })
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
