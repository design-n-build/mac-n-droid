package app.macndroid.core

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/** Everything needed to reach and authenticate with a paired Mac. */
data class Pairing(
    val macId: String,
    val key: ByteArray,
    val hosts: List<String>,
    val port: Int,
    val macName: String,
) {
    companion object {
        /** Parses `macndroid://pair?v=1&id=…&k=…&p=…&h=…&n=…` from the Mac's QR code. */
        fun parse(raw: String): Pairing? = runCatching {
            val uri = Uri.parse(raw.trim())
            if (uri.scheme != "macndroid" || uri.host != "pair") return null
            val key = Base64.decode(uri.getQueryParameter("k")!!, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            require(key.size == 32)
            Pairing(
                macId = uri.getQueryParameter("id")!!,
                key = key,
                hosts = uri.getQueryParameter("h").orEmpty().split(",").filter { it.isNotBlank() },
                port = uri.getQueryParameter("p")?.toIntOrNull() ?: 47800,
                macName = uri.getQueryParameter("n") ?: "Mac",
            )
        }.getOrNull()
    }
}

object Prefs {
    private lateinit var sp: SharedPreferences

    private val _pairing = MutableStateFlow<Pairing?>(null)
    val pairing: StateFlow<Pairing?> = _pairing

    private val _mirrorNotifications = MutableStateFlow(true)
    val mirrorNotifications: StateFlow<Boolean> = _mirrorNotifications
    private val _receiveClipboard = MutableStateFlow(true)
    val receiveClipboard: StateFlow<Boolean> = _receiveClipboard
    private val _blockedApps = MutableStateFlow<Set<String>>(emptySet())
    /** Apps whose notifications are NOT sent to the Mac. */
    val blockedApps: StateFlow<Set<String>> = _blockedApps
    private val _haptics = MutableStateFlow(true)
    val haptics: StateFlow<Boolean> = _haptics
    private val _screenshotsToMac = MutableStateFlow(true)
    val screenshotsToMac: StateFlow<Boolean> = _screenshotsToMac

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("macndroid", Context.MODE_PRIVATE)
        _pairing.value = loadPairing()
        _mirrorNotifications.value = sp.getBoolean("mirrorNotifications", true)
        _receiveClipboard.value = sp.getBoolean("receiveClipboard", true)
        _screenshotsToMac.value = sp.getBoolean("screenshotsToMac", true)
        _haptics.value = sp.getBoolean("haptics", true)
        _blockedApps.value = sp.getStringSet("blockedApps", emptySet()).orEmpty().toSet()
    }

    val phoneId: String
        get() = sp.getString("phoneId", null) ?: UUID.randomUUID().toString().also {
            sp.edit().putString("phoneId", it).apply()
        }

    fun savePairing(p: Pairing) {
        sp.edit()
            .putString("macId", p.macId)
            .putString("key", Base64.encodeToString(p.key, Base64.NO_WRAP))
            .putString("hosts", p.hosts.joinToString(","))
            .putInt("port", p.port)
            .putString("macName", p.macName)
            .apply()
        _pairing.value = p
    }

    fun updateEndpoint(host: String, port: Int) {
        val p = _pairing.value ?: return
        val hosts = (listOf(host) + p.hosts.filter { it != host }).take(4)
        if (hosts == p.hosts && port == p.port) return
        savePairing(p.copy(hosts = hosts, port = port))
    }

    fun updateMacName(name: String) {
        val p = _pairing.value ?: return
        if (p.macName != name) savePairing(p.copy(macName = name))
    }

    fun clearPairing() {
        sp.edit().remove("macId").remove("key").remove("hosts").remove("port").remove("macName").apply()
        _pairing.value = null
    }

    fun setMirrorNotifications(on: Boolean) {
        sp.edit().putBoolean("mirrorNotifications", on).apply(); _mirrorNotifications.value = on
    }

    fun setReceiveClipboard(on: Boolean) {
        sp.edit().putBoolean("receiveClipboard", on).apply(); _receiveClipboard.value = on
    }

    fun setBlockedApps(apps: Set<String>) {
        sp.edit().putStringSet("blockedApps", apps).apply(); _blockedApps.value = apps
    }

    fun setHaptics(on: Boolean) {
        sp.edit().putBoolean("haptics", on).apply(); _haptics.value = on
    }

    fun setScreenshotsToMac(on: Boolean) {
        sp.edit().putBoolean("screenshotsToMac", on).apply(); _screenshotsToMac.value = on
    }

    private fun loadPairing(): Pairing? {
        val id = sp.getString("macId", null) ?: return null
        val key = sp.getString("key", null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        return Pairing(
            macId = id,
            key = key,
            hosts = sp.getString("hosts", "").orEmpty().split(",").filter { it.isNotBlank() },
            port = sp.getInt("port", 47800),
            macName = sp.getString("macName", "Mac") ?: "Mac",
        )
    }
}
