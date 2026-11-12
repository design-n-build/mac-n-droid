package app.macndroid.service

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.util.Size
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.macndroid.MacNDroidApp
import app.macndroid.R
import app.macndroid.core.Computer
import app.macndroid.core.Link
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * The phone's photos and videos, browsed from the computer (`gallery_*` in PROTOCOL.md). The computer asks for a
 * page of items, then thumbnails for what's on screen, then the full file when you open or save one.
 * Nothing is sent unless the computer asks, and only to the paired computer.
 */
object Gallery {
    private const val TAG = "Gallery"
    private const val NOTIF = 45
    private val thumbLock = Mutex()

    /** Photos and videos. On Android 14+ the user can also share only some ("Selected photos"). */
    val permissions: Array<String>
        get() = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    private fun granted(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    /** What the computer may see: "off" (turned off on the Home tab), or what Android allows. */
    fun access(ctx: Context): String = if (!app.macndroid.core.Prefs.galleryOnMac.value) "off" else permissionAccess(ctx)

    /** "full", "photos" (photos but not videos), "partial" (only photos the user picked) or "none". */
    fun permissionAccess(ctx: Context): String = when {
        Build.VERSION.SDK_INT >= 33 && granted(ctx, Manifest.permission.READ_MEDIA_IMAGES) && granted(ctx, Manifest.permission.READ_MEDIA_VIDEO) -> "full"
        Build.VERSION.SDK_INT < 33 && granted(ctx, Manifest.permission.READ_EXTERNAL_STORAGE) -> "full"
        Build.VERSION.SDK_INT >= 33 && granted(ctx, Manifest.permission.READ_MEDIA_IMAGES) -> "photos"
        Build.VERSION.SDK_INT >= 33 && granted(ctx, Manifest.permission.READ_MEDIA_VIDEO) -> "partial"
        Build.VERSION.SDK_INT >= 34 && granted(ctx, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> "partial"
        else -> "none"
    }

    fun hasFullAccess(ctx: Context) = access(ctx) == "full"

    private val filesUri: Uri get() = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

    private fun itemUri(id: Long, video: Boolean): Uri = ContentUris.withAppendedId(
        if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

    private fun selection(kind: String, album: String?): Pair<String, Array<String>> {
        val types = when (kind) {
            "photos" -> "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}"
            "videos" -> "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}"
            else -> "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}, ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"
        }
        val sel = "$types AND ${MediaStore.MediaColumns.IS_PENDING} = 0" +
            (if (album != null) " AND ${MediaStore.MediaColumns.BUCKET_ID} = ?" else "")
        return sel to (if (album != null) arrayOf(album) else emptyArray())
    }

    /** `gallery_list` → `gallery`: one page, newest first; albums with the first page. */
    fun list(ctx: Context, msg: JSONObject) = Link.scope.launch(Dispatchers.IO) {
        val offset = msg.optInt("offset", 0).coerceAtLeast(0)
        val limit = msg.optInt("limit", 120).coerceIn(1, 300)
        val kind = msg.optString("kind", "all")
        val album = msg.optString("album").ifEmpty { null }
        val access = access(ctx)
        val out = JSONObject().put("t", "gallery").put("offset", offset).put("kind", kind).put("access", access)
        if (album != null) out.put("album", album)
        if (access == "none" || access == "off") { Link.send(out.put("items", JSONArray()).put("total", 0)); return@launch }
        val (sel, args) = selection(kind, album)
        val items = JSONArray()
        var total = 0
        runCatching {
            val cols = arrayOf(
                MediaStore.MediaColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT, MediaStore.MediaColumns.DURATION,
                MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.ORIENTATION,
            )
            val q = Bundle().apply {
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, sel)
                putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                    "${MediaStore.MediaColumns.DATE_ADDED} DESC, ${MediaStore.MediaColumns._ID} DESC")
                putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, limit)
                putInt(android.content.ContentResolver.QUERY_ARG_OFFSET, offset)
            }
            ctx.contentResolver.query(filesUri, cols, q, null)?.use { c ->
                while (c.moveToNext()) {
                    val video = c.getInt(1) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                    val taken = c.getLong(3).takeIf { it > 0 } ?: (c.getLong(4) * 1000)
                    var w = c.getInt(6); var h = c.getInt(7)
                    if (c.getInt(10) % 180 == 90) { val t = w; w = h; h = t }
                    items.put(JSONObject()
                        .put("id", (if (video) "v" else "i") + c.getLong(0))
                        .put("v", video)
                        .put("name", c.getString(2) ?: "")
                        .put("date", taken)
                        .put("size", c.getLong(5))
                        .put("w", w).put("h", h)
                        .put("dur", if (video) c.getLong(8) else 0)
                        .put("mime", c.getString(9) ?: ""))
                }
            }
            ctx.contentResolver.query(filesUri, arrayOf(MediaStore.MediaColumns._ID), Bundle().apply {
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, sel)
                putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
            }, null)?.use { total = it.count }
            if (offset == 0) out.put("albums", albums(ctx, kind))
        }.onFailure { Log.w(TAG, "list failed", it) }
        Link.send(out.put("items", items).put("total", total))
    }

    /** Albums (folders such as Camera, Screenshots, WhatsApp Images), biggest first. */
    private fun albums(ctx: Context, kind: String): JSONArray {
        val (sel, args) = selection(kind, null)
        val counts = LinkedHashMap<String, Pair<String, Int>>()
        ctx.contentResolver.query(filesUri,
            arrayOf(MediaStore.MediaColumns.BUCKET_ID, MediaStore.MediaColumns.BUCKET_DISPLAY_NAME), sel, args,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: "Other"
                counts[id] = name to ((counts[id]?.second ?: 0) + 1)
            }
        }
        val arr = JSONArray()
        counts.entries.sortedByDescending { it.value.second }.forEach { (id, v) ->
            arr.put(JSONObject().put("id", id).put("name", v.first).put("count", v.second))
        }
        return arr
    }

    private fun parse(id: String): Pair<Long, Boolean>? {
        val video = id.startsWith("v")
        return id.drop(1).toLongOrNull()?.let { it to video }
    }

    /** `gallery_thumbs` → one `gallery_thumb` each (small JPEGs), in order. */
    fun thumbs(ctx: Context, msg: JSONObject) = Link.scope.launch(Dispatchers.IO) {
        if (access(ctx) == "off") return@launch
        val ids = msg.optJSONArray("ids") ?: return@launch
        val px = msg.optInt("size", 256).coerceIn(64, 640)
        thumbLock.withLock {
            for (i in 0 until minOf(ids.length(), 60)) {
                val id = ids.optString(i)
                val (n, video) = parse(id) ?: continue
                val data = runCatching {
                    val bmp = ctx.contentResolver.loadThumbnail(itemUri(n, video), Size(px, px), null)
                    val bytes = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 78, bytes)
                    bmp.recycle()
                    Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
                }.getOrNull()
                val reply = JSONObject().put("t", "gallery_thumb").put("id", id)
                if (data != null) reply.put("d", data) else reply.put("missing", true)
                if (!Link.send(reply)) break
            }
        }
    }

    /** `gallery_get`: the original file, through the normal file transfer. `mode` "view" (opened on the computer)
     *  or "save" (saved to its Downloads, shown in transfers on both sides). */
    fun get(svc: SyncService, msg: JSONObject) {
        if (access(svc) == "off") return
        val id = msg.optString("id")
        val (n, video) = parse(id) ?: return
        svc.files.sendGallery(itemUri(n, video), id, view = msg.optString("mode") != "save")
    }

    /** `gallery_ask`: the computer has no access yet, so the phone offers to ask. */
    fun ask(ctx: Context) {
        if (access(ctx) == "off") return
        val pi = android.app.PendingIntent.getActivity(ctx, NOTIF,
            Intent(ctx, GalleryPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            android.app.PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, MacNDroidApp.CH_MIRROR)
            .setSmallIcon(R.drawable.ic_stat_logo)
            .setContentTitle("See your photos on ${Computer.your}?")
            .setContentText("Tap to allow photos and videos")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "Tap to let ${Computer.your} show this phone's photos and videos. They're sent only to ${Computer.your}, " +
                    "only when you open them there."))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setTimeoutAfter(120_000)
            .build()
        runCatching { ctx.getSystemService(android.app.NotificationManager::class.java).notify(NOTIF, n) }
    }

    /** Tells the computer the access changed, so it can load the library again. */
    fun reportAccess(ctx: Context) {
        Link.post(JSONObject().put("t", "gallery_access").put("access", access(ctx)))
    }
}

/** Invisible: asks Android for photo and video access, reports back to the computer, closes. */
class GalleryPermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions(Gallery.permissions, 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (ScreenshotWatcher.hasPermission(this)) app.macndroid.core.Prefs.setScreenshotsToMac(true)
        if (Gallery.permissionAccess(this) != "none") app.macndroid.core.Prefs.setGalleryOnMac(true)
        Gallery.reportAccess(this)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
