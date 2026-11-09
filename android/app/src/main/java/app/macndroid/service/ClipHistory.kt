package app.macndroid.service

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
import app.macndroid.core.Link
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * The last 20 things copied on either device. The Mac keeps the list (it sees both sides) and sends
 * it here whenever it changes. Kept in memory only: clipboards can hold private things.
 */
object ClipHistory {
    data class Item(
        val id: String,
        val text: String?,
        val image: Bitmap?,
        val fromMac: Boolean,
        val time: Long,
        /** Text was too long to send in the list; tapping asks the Mac for all of it. */
        val truncated: Boolean,
    )

    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items
    private val main = Handler(Looper.getMainLooper())

    fun receive(msg: JSONObject) {
        val arr = msg.optJSONArray("items") ?: return
        val old = _items.value.associateBy { it.id }
        _items.value = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").ifEmpty { return@mapNotNull null }
            val image = old[id]?.image ?: o.optString("thumb").takeIf { it.isNotEmpty() }?.let {
                runCatching { Base64.decode(it, Base64.DEFAULT).let { b -> BitmapFactory.decodeByteArray(b, 0, b.size) } }.getOrNull()
            }
            Item(
                id = id,
                text = if (o.optString("kind") == "image") null else o.optString("text"),
                image = image,
                fromMac = o.optString("from") != "phone",
                time = o.optLong("time"),
                truncated = o.optBoolean("truncated"),
            )
        }
    }

    fun clear() { _items.value = emptyList() }

    /** Empties the list on both devices. What's on the clipboard right now stays. */
    fun clearEverywhere(): Boolean {
        if (!Link.isConnected) return false
        Link.post(JSONObject().put("t", "clip_clear"))
        clear()
        return true
    }

    /** Puts an item back on this phone's clipboard. Images (and very long text) come from the Mac. */
    fun copy(ctx: Context, item: Item): Boolean {
        if (item.text != null && !item.truncated) {
            main.post {
                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Clipboard history", item.text))
            }
            return true
        }
        if (!Link.isConnected) return false
        Link.post(JSONObject().put("t", "clip_get").put("id", item.id))
        return true
    }

    /** Sends a phone-side history item to the Mac's clipboard. */
    fun copyToMac(item: Item): Boolean {
        if (!Link.isConnected) return false
        Link.post(JSONObject().put("t", "clip_use").put("id", item.id))
        return true
    }
}
