package app.macndroid.core

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom

/** Keeps one encrypted connection to the paired Mac alive, reconnecting with backoff. */
object Link {
    enum class Status { Unpaired, Searching, Connecting, Connected, AuthFailed }

    private const val TAG = "Link"
    private const val MAX_FRAME = 8 * 1024 * 1024

    private val _status = MutableStateFlow(Status.Unpaired)
    val status: StateFlow<Status> = _status

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Called on the IO thread for every decrypted message, in order. */
    var onMessage: (JSONObject) -> Unit = {}
    var onConnected: () -> Unit = {}
    var onDisconnected: () -> Unit = {}

    private var job: Job? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val writeLock = Mutex()
    @Volatile private var socket: Socket? = null
    @Volatile private var out: DataOutputStream? = null
    @Volatile private var cipher: SessionCipher? = null

    val isConnected get() = _status.value == Status.Connected

    fun start(ctx: Context) {
        if (job?.isActive == true) { kick(); return }
        val app = ctx.applicationContext
        job = scope.launch { runLoop(app) }
    }

    fun stop() {
        job?.cancel()
        job = null
        closeSocket()
        _status.value = if (Prefs.pairing.value == null) Status.Unpaired else Status.Searching
    }

    /** Retry immediately (network changed, user tapped reconnect, …). */
    fun kick() { wake.trySend(Unit) }

    fun post(msg: JSONObject) { scope.launch { send(msg) } }

    suspend fun send(msg: JSONObject): Boolean {
        val c = cipher ?: return false
        val o = out ?: return false
        return writeLock.withLock {
            try {
                writeFrame(o, c.seal(msg.toString().toByteArray()))
                true
            } catch (e: IOException) {
                Log.w(TAG, "send failed", e); closeSocket(); false
            }
        }
    }

    private suspend fun runLoop(ctx: Context) {
        var backoff = 1000L
        while (scope.isActive) {
            val pairing = Prefs.pairing.value
            if (pairing == null) { _status.value = Status.Unpaired; wake.receive(); continue }
            if (_status.value != Status.AuthFailed) _status.value = Status.Searching

            val sock = connect(ctx, pairing)
            Log.i(TAG, "connect to ${pairing.hosts} → ${sock?.inetAddress}")
            if (sock != null) {
                _status.value = Status.Connecting
                when (session(ctx, sock, pairing)) {
                    Outcome.AuthFailed -> _status.value = Status.AuthFailed
                    Outcome.Ended -> backoff = 1000L
                    Outcome.Failed -> {}
                }
            }
            if (_status.value == Status.Connected || _status.value == Status.Connecting) _status.value = Status.Searching
            // Wait for backoff or an explicit wake-up, whichever comes first.
            withTimeoutOrNull(backoff) { wake.receive() }
            backoff = (backoff * 2).coerceAtMost(30_000L)
        }
    }

    private suspend fun connect(ctx: Context, p: Pairing): Socket? {
        for (host in p.hosts) tryConnect(host, p.port, 2500)?.let { return it }
        val found = Discovery.find(ctx, p.macId) ?: return null
        return tryConnect(found.first, found.second, 4000)?.also {
            Prefs.updateEndpoint(found.first, found.second)
        }
    }

    private fun tryConnect(host: String, port: Int, timeout: Int): Socket? = try {
        Socket().apply {
            tcpNoDelay = true
            keepAlive = true
            connect(InetSocketAddress(host, port), timeout)
            soTimeout = 15_000
        }
    } catch (e: IOException) {
        null
    }

    private enum class Outcome { Ended, Failed, AuthFailed }

    private suspend fun session(ctx: Context, sock: Socket, p: Pairing): Outcome {
        socket = sock
        val input = DataInputStream(sock.getInputStream().buffered(64 * 1024))
        val output = DataOutputStream(sock.getOutputStream().buffered(64 * 1024))
        var welcomed = false
        try {
            // 1. Plaintext hello exchange.
            val phoneNonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
            writeFrame(output, JSONObject()
                .put("t", "hello").put("v", 1).put("phoneId", Prefs.phoneId)
                .put("nonce", Base64.encodeToString(phoneNonce, Base64.NO_WRAP))
                .toString().toByteArray())
            val hello = JSONObject(String(readFrame(input)))
            if (hello.optString("t") != "hello" || hello.optString("macId") != p.macId) return Outcome.Failed
            val macNonce = Base64.decode(hello.getString("nonce"), Base64.DEFAULT)
            val c = SessionCipher(p.key, phoneNonce, macNonce)

            // 2. Encrypted device info. A wrong key makes the Mac hang up before "welcome".
            writeFrame(output, c.seal(JSONObject()
                .put("t", "device")
                .put("name", deviceName(ctx))
                .put("model", "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}")
                .put("os", "Android ${Build.VERSION.RELEASE}")
                .toString().toByteArray()))
            val welcome = JSONObject(String(c.open(readFrame(input))))
            if (welcome.optString("t") != "welcome") return Outcome.Failed
            welcomed = true
            welcome.optString("name").takeIf { it.isNotBlank() }?.let { Prefs.updateMacName(it) }
            Prefs.updateEndpoint(sock.inetAddress.hostAddress ?: "", sock.port)

            cipher = c
            out = output
            sock.soTimeout = 60_000   // both sides ping every 20 s
            _status.value = Status.Connected
            onConnected()
            val pinger = scope.launch {
                while (isActive) { delay(20_000); send(JSONObject().put("t", "ping")) }
            }
            try {
                while (true) {
                    val msg = JSONObject(String(c.open(readFrame(input))))
                    when (msg.optString("t")) {
                        "ping" -> post(JSONObject().put("t", "pong"))
                        "pong" -> {}
                        else -> runCatching { onMessage(msg) }.onFailure { Log.e(TAG, "handler", it) }
                    }
                }
            } finally {
                pinger.cancel()
            }
        } catch (e: EOFException) {
            Log.i(TAG, "session EOF (welcomed=$welcomed)")
            return if (welcomed) Outcome.Ended else Outcome.AuthFailed
        } catch (e: Exception) {
            Log.i(TAG, "session ended: $e")
            return if (welcomed) Outcome.Ended else Outcome.Failed
        } finally {
            val wasConnected = cipher != null
            closeSocket()
            if (wasConnected) onDisconnected()
        }
    }

    private fun closeSocket() {
        cipher = null
        out = null
        runCatching { socket?.close() }
        socket = null
    }

    private fun writeFrame(o: DataOutputStream, payload: ByteArray) {
        synchronized(o) {
            o.writeInt(payload.size)
            o.write(payload)
            o.flush()
        }
    }

    private fun readFrame(i: DataInputStream): ByteArray {
        val len = i.readInt()
        if (len < 0 || len > MAX_FRAME) throw IOException("bad frame length $len")
        return ByteArray(len).also { i.readFully(it) }
    }

    fun deviceName(ctx: Context): String =
        Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME)
            ?: "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
}
