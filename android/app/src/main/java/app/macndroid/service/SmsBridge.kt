package app.macndroid.service

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import app.macndroid.core.Link
import app.macndroid.core.Prefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * Read and reply to text messages (SMS) from the Mac.
 *
 * Reads the phone's SMS inbox only when the Mac asks (opening Messages on the Mac), and sends a text
 * only when you press Send there. Needs the SMS permissions, which you turn on in the app.
 * Picture messages (MMS) and RCS chats aren't in Android's SMS store, so they don't show here; their
 * notifications (with Reply) still reach the Mac as usual.
 */
object SmsBridge {
    private const val TAG = "SmsBridge"
    private const val ACTION_SENT = "app.macndroid.SMS_SENT"
    private val main = Handler(Looper.getMainLooper())
    private var observer: ContentObserver? = null
    private var sentReceiver: BroadcastReceiver? = null
    private val names = HashMap<String, String?>()

    fun hasPermission(ctx: Context) = listOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS).all {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }

    val permissions = arrayOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.READ_CONTACTS)

    private fun available(ctx: Context) = Prefs.smsOnMac.value && hasPermission(ctx)

    private fun unavailable() =
        Link.post(JSONObject().put("t", "sms_unavailable").put("reason", if (Prefs.smsOnMac.value) "permission" else "off"))

    /** Watches for new and sent texts while connected, so the Mac's list stays current. */
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        if (!available(app) || observer != null) return
        var pending: Runnable? = null
        val o = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) {
                pending?.let(main::removeCallbacks)
                val r = Runnable { if (Link.isConnected) Link.post(JSONObject().put("t", "sms_changed")) }
                pending = r
                main.postDelayed(r, 700)
            }
        }
        runCatching { app.contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, o) }
            .onSuccess { observer = o }
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (resultCode != android.app.Activity.RESULT_OK) {
                    Link.post(JSONObject().put("t", "sms_sent").put("ok", false).put("id", i.getStringExtra("id")))
                } else {
                    Link.post(JSONObject().put("t", "sms_sent").put("ok", true).put("id", i.getStringExtra("id")))
                }
            }
        }
        ContextCompat.registerReceiver(app, r, IntentFilter(ACTION_SENT), ContextCompat.RECEIVER_NOT_EXPORTED)
        sentReceiver = r
    }

    fun stop(ctx: Context) {
        val app = ctx.applicationContext
        observer?.let { runCatching { app.contentResolver.unregisterContentObserver(it) } }
        observer = null
        sentReceiver?.let { runCatching { app.unregisterReceiver(it) } }
        sentReceiver = null
    }

    /** Latest conversations, newest first. */
    fun sendThreads(ctx: Context) {
        if (!available(ctx)) { unavailable(); return }
        start(ctx)
        val threads = LinkedHashMap<Long, JSONObject>()
        val unread = HashMap<Long, Int>()
        runCatching {
            ctx.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE,
                    Telephony.Sms.TYPE, Telephony.Sms.READ),
                null, null, "${Telephony.Sms.DATE} DESC",
            )?.use { c ->
                var scanned = 0
                while (c.moveToNext() && scanned < 4000 && threads.size < 80) {
                    scanned++
                    val tid = c.getLong(0)
                    val type = c.getInt(4)
                    if (type == Telephony.Sms.MESSAGE_TYPE_INBOX && c.getInt(5) == 0) unread[tid] = (unread[tid] ?: 0) + 1
                    if (tid in threads) continue
                    val address = c.getString(1).orEmpty()
                    threads[tid] = JSONObject()
                        .put("id", tid.toString())
                        .put("address", address)
                        .put("name", contactName(ctx, address) ?: "")
                        .put("snippet", c.getString(2).orEmpty().take(200))
                        .put("date", c.getLong(3))
                        .put("out", type != Telephony.Sms.MESSAGE_TYPE_INBOX)
                }
            }
        }.onFailure { Log.w(TAG, "threads failed", it) }
        // Chats (RCS) only show up in the messaging app's notifications: attach them to the matching
        // SMS conversation (same contact or number), or list them on their own.
        val chats = ChatNotifications.all()
        val matched = HashSet<String>()
        synchronized(threadChat) { threadChat.clear() }
        threads.forEach { (tid, o) ->
            val address = o.optString("address")
            val name = o.optString("name")
            val chat = chats.firstOrNull { c -> c.key !in matched && matches(c.name, name, address) }
            if (chat != null) {
                matched.add(chat.key)
                synchronized(threadChat) { threadChat[tid.toString()] = chat.key }
                val last = chat.latest
                if (last != null && last.date > o.optLong("date")) {
                    o.put("snippet", last.body.take(200)).put("date", last.date).put("out", last.out)
                }
            }
        }
        val list = threads.map { (tid, o) -> o.put("unread", unread[tid] ?: 0) }.toMutableList()
        chats.filter { it.key !in matched }.forEach { c ->
            val last = c.latest ?: return@forEach
            list.add(JSONObject().put("id", "chat:" + c.key).put("address", "").put("name", c.name)
                .put("snippet", last.body.take(200)).put("date", last.date).put("out", last.out).put("unread", 0)
                .put("reply", c.replyIndex != null))
        }
        val arr = JSONArray()
        list.sortedByDescending { it.optLong("date") }.forEach { arr.put(it) }
        Link.post(JSONObject().put("t", "sms_threads").put("threads", arr))
    }

    /** Same person? Matches the contact name, or the number when the chat is titled with one. */
    private fun matches(chatName: String, contactName: String, address: String): Boolean {
        if (contactName.isNotBlank() && chatName.equals(contactName, ignoreCase = true)) return true
        val a = address.filter(Char::isDigit).takeLast(10)
        val c = chatName.filter(Char::isDigit).takeLast(10)
        return a.length >= 7 && a == c
    }

    /** SMS thread id → chat key, from the last conversation list. */
    private val threadChat = HashMap<String, String>()

    private fun chatFor(threadId: String): ChatNotifications.Chat? {
        val key = if (threadId.startsWith("chat:")) threadId.removePrefix("chat:") else synchronized(threadChat) { threadChat[threadId] }
        return key?.let(ChatNotifications::get)
    }

    /** The last 200 texts in one conversation, oldest first. */
    fun sendThread(ctx: Context, threadId: String) {
        if (!available(ctx)) { unavailable(); return }
        val list = ArrayList<JSONObject>()
        chatFor(threadId)?.messages?.toList()?.forEach { m ->
            list.add(JSONObject().put("id", m.id).put("body", m.body).put("date", m.date).put("out", m.out))
        }
        val tid = threadId.toLongOrNull()
        if (tid != null) runCatching {
            ctx.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
                "${Telephony.Sms.THREAD_ID} = ?", arrayOf(tid.toString()), "${Telephony.Sms.DATE} DESC",
            )?.use { c ->
                while (c.moveToNext() && list.size < 200) {
                    val type = c.getInt(3)
                    list.add(JSONObject()
                        .put("id", c.getLong(0).toString())
                        .put("body", c.getString(1).orEmpty())
                        .put("date", c.getLong(2))
                        .put("out", type != Telephony.Sms.MESSAGE_TYPE_INBOX)
                        .put("failed", type == Telephony.Sms.MESSAGE_TYPE_FAILED)
                        .put("pending", type == Telephony.Sms.MESSAGE_TYPE_OUTBOX || type == Telephony.Sms.MESSAGE_TYPE_QUEUED))
                }
            }
        }.onFailure { Log.w(TAG, "thread failed", it) }
        // Oldest first, without the same message twice (an SMS and its notification, or a reply sent
        // from the Mac and the messaging app's copy of it).
        val out = ArrayList<JSONObject>()
        for (m in list.sortedBy { it.optLong("date") }) {
            val dup = out.any { o ->
                o.optString("body") == m.optString("body") && o.optBoolean("out") == m.optBoolean("out") &&
                    kotlin.math.abs(o.optLong("date") - m.optLong("date")) < 120_000
            }
            if (!dup) out.add(m)
        }
        val arr = JSONArray()
        out.takeLast(200).forEach { arr.put(it) }
        Link.post(JSONObject().put("t", "sms_thread").put("id", threadId).put("messages", arr))
    }

    /** Sends a text you wrote on the Mac. Android files it in the conversation itself. */
    fun send(ctx: Context, address: String, text: String, localId: String, threadId: String = "") {
        if (!available(ctx)) { unavailable(); return }
        if (text.isBlank()) return
        // A chat (RCS) conversation still in the shade: reply through the messaging app so it stays a chat.
        val chat = if (threadId.isNotEmpty()) chatFor(threadId) else null
        if (chat != null && ChatNotifications.reply(chat.key, text)) {
            Link.post(JSONObject().put("t", "sms_sent").put("ok", true).put("id", localId))
            return
        }
        if (address.isBlank()) {
            Link.post(JSONObject().put("t", "sms_sent").put("ok", false).put("id", localId))
            return
        }
        start(ctx)
        runCatching {
            @Suppress("DEPRECATION")
            val sms = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
            val parts = sms.divideMessage(text)
            val sent = PendingIntent.getBroadcast(ctx, localId.hashCode(),
                Intent(ACTION_SENT).setPackage(ctx.packageName).putExtra("id", localId), PendingIntent.FLAG_IMMUTABLE)
            val intents = ArrayList<PendingIntent>().apply { repeat(parts.size) { add(sent) } }
            sms.sendMultipartTextMessage(address, null, parts, intents, null)
        }.onFailure {
            Log.w(TAG, "send failed", it)
            Link.post(JSONObject().put("t", "sms_sent").put("ok", false).put("id", localId))
        }
    }

    /** Contacts matching what you type in "To:" on the Mac (name or number). */
    fun searchContacts(ctx: Context, query: String) {
        val reply = JSONObject().put("t", "contacts").put("q", query)
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            Link.post(reply.put("results", JSONArray()).put("permission", false))
            return
        }
        val results = JSONArray()
        val seen = HashSet<String>()
        if (query.isNotBlank()) runCatching {
            ctx.contentResolver.query(
                Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(query.trim())),
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.LABEL),
                null, null, "${ContactsContract.CommonDataKinds.Phone.TIMES_CONTACTED} DESC",
            )?.use { c ->
                while (c.moveToNext() && results.length() < 25) {
                    val number = c.getString(1).orEmpty()
                    val key = number.filter { it.isDigit() || it == '+' }
                    if (key.isEmpty() || !seen.add(key)) continue
                    val label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(ctx.resources, c.getInt(2), c.getString(3)).toString()
                    results.put(JSONObject().put("name", c.getString(0).orEmpty()).put("number", number).put("label", label))
                }
            }
        }.onFailure { Log.w(TAG, "contact search failed", it) }
        Link.post(reply.put("results", results).put("permission", true))
    }

    private fun contactName(ctx: Context, address: String): String? {
        if (address.isBlank()) return null
        names[address]?.let { return it }
        if (names.containsKey(address)) return null
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val name = runCatching {
            ctx.contentResolver.query(
                Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address)),
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null,
            )?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
        names[address] = name
        return name
    }
}
