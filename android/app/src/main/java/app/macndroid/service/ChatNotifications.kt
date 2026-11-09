package app.macndroid.service

import android.app.Notification
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import app.macndroid.core.Link
import app.macndroid.core.Prefs
import org.json.JSONObject

/**
 * Chat (RCS) messages for Messages on the Mac.
 *
 * Google Messages keeps chat messages in its own private storage, not in Android's SMS store, so
 * the only way to see them is the messaging app's own notifications (sender, text and time, plus a
 * Reply that sends as chat). They're collected here while Mac n Droid is running and merged into
 * the matching SMS conversation. Kept in memory only.
 */
object ChatNotifications {
    data class Msg(val id: String, val body: String, val date: Long, val out: Boolean)

    class Chat(val key: String, var name: String) {
        val messages = ArrayList<Msg>()
        /** The live notification and its Reply action, while it's in the shade. */
        var notifKey: String? = null
        var replyIndex: Int? = null
        val latest get() = messages.maxByOrNull { it.date }
    }

    private val chats = LinkedHashMap<String, Chat>()
    private val main = Handler(Looper.getMainLooper())
    private val changed = Runnable { if (Link.isConnected) Link.post(JSONObject().put("t", "sms_changed")) }
    private val known = setOf("com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms")

    private fun isMessagesApp(ctx: Context, pkg: String) =
        pkg in known || runCatching { Telephony.Sms.getDefaultSmsPackage(ctx) }.getOrNull() == pkg

    fun onPosted(ctx: Context, sbn: StatusBarNotification) {
        if (!Prefs.smsOnMac.value || !isMessagesApp(ctx, sbn.packageName)) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n) ?: return
        val me = style.user
        val incomingName = style.messages.lastOrNull { m -> m.person != null && m.person?.name != me.name }?.person?.name?.toString()
        val name = style.conversationTitle?.toString() ?: incomingName ?: n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val key = n.shortcutId ?: name
        val replyIndex = n.actions?.indexOfFirst { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }?.takeIf { it >= 0 }
        synchronized(chats) {
            val chat = chats.getOrPut(key) { Chat(key, name) }
            chat.name = name
            chat.notifKey = sbn.key
            chat.replyIndex = replyIndex
            for (m in style.messages) {
                val body = m.text?.toString()?.takeIf { it.isNotBlank() } ?: continue
                val out = m.person == null || m.person?.name == me.name
                val id = "c" + (body + m.timestamp + out).hashCode().toUInt().toString(36)
                if (chat.messages.none { it.id == id }) chat.messages.add(Msg(id, body, m.timestamp, out))
            }
            chat.messages.sortBy { it.date }
            while (chat.messages.size > 100) chat.messages.removeAt(0)
            while (chats.size > 60) chats.remove(chats.keys.first())
        }
        main.removeCallbacks(changed)
        main.postDelayed(changed, 600)
    }

    fun onRemoved(sbn: StatusBarNotification) {
        synchronized(chats) { chats.values.filter { it.notifKey == sbn.key }.forEach { it.notifKey = null; it.replyIndex = null } }
    }

    fun all(): List<Chat> = synchronized(chats) { chats.values.toList() }

    fun get(key: String): Chat? = synchronized(chats) { chats[key] }

    /** Replies through the messaging app (so a chat stays a chat). False when its notification is gone. */
    fun reply(key: String, text: String): Boolean {
        val chat = get(key) ?: return false
        val nk = chat.notifKey ?: return false
        val idx = chat.replyIndex ?: return false
        val mirror = NotificationMirror.instance ?: return false
        mirror.perform(nk, idx, text)
        synchronized(chats) { chat.messages.add(Msg("c" + System.nanoTime().toString(36), text, System.currentTimeMillis(), true)) }
        return true
    }
}
