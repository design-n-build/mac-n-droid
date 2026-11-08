package app.macndroid.core

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/** Finds the paired Mac on the LAN via Bonjour (`_macndroid._tcp`, TXT id=<macId>). */
object Discovery {
    private const val TYPE = "_macndroid._tcp."

    suspend fun find(ctx: Context, macId: String, timeoutMs: Long = 6000): Pair<String, Int>? {
        val nsd = ctx.getSystemService(NsdManager::class.java) ?: return null
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val resolving = java.util.concurrent.atomic.AtomicBoolean(false)
                val pending = ArrayDeque<NsdServiceInfo>()
                lateinit var listener: NsdManager.DiscoveryListener

                fun finish(result: Pair<String, Int>?) {
                    if (cont.isActive) {
                        runCatching { nsd.stopServiceDiscovery(listener) }
                        cont.resume(result)
                    }
                }

                fun resolveNext() {
                    if (!cont.isActive || !resolving.compareAndSet(false, true)) return
                    val info = synchronized(pending) { pending.removeFirstOrNull() }
                    if (info == null) { resolving.set(false); return }
                    resolve(nsd, info) { resolved ->
                        resolving.set(false)
                        val id = resolved?.attributes?.get("id")?.let { String(it) }
                        val host = resolved?.let { hostOf(it) }
                        if (id == macId && host != null) finish(host to resolved.port) else resolveNext()
                    }
                }

                listener = object : NsdManager.DiscoveryListener {
                    override fun onServiceFound(info: NsdServiceInfo) {
                        synchronized(pending) { pending.addLast(info) }
                        resolveNext()
                    }
                    override fun onStartDiscoveryFailed(t: String, e: Int) { if (cont.isActive) cont.resume(null) }
                    override fun onStopDiscoveryFailed(t: String, e: Int) {}
                    override fun onDiscoveryStarted(t: String) {}
                    override fun onDiscoveryStopped(t: String) {}
                    override fun onServiceLost(info: NsdServiceInfo) {}
                }
                nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
                cont.invokeOnCancellation { runCatching { nsd.stopServiceDiscovery(listener) } }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun resolve(nsd: NsdManager, info: NsdServiceInfo, done: (NsdServiceInfo?) -> Unit) {
        if (Build.VERSION.SDK_INT >= 34) {
            val exec = Executors.newSingleThreadExecutor()
            val cb = object : NsdManager.ServiceInfoCallback {
                override fun onServiceUpdated(si: NsdServiceInfo) {
                    runCatching { nsd.unregisterServiceInfoCallback(this) }
                    done(si)
                }
                override fun onServiceInfoCallbackRegistrationFailed(err: Int) = done(null)
                override fun onServiceLost() {}
                override fun onServiceInfoCallbackUnregistered() { exec.shutdown() }
            }
            nsd.registerServiceInfoCallback(info, exec, cb)
        } else {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onServiceResolved(si: NsdServiceInfo) = done(si)
                override fun onResolveFailed(si: NsdServiceInfo, err: Int) = done(null)
            })
        }
    }

    @Suppress("DEPRECATION")
    private fun hostOf(si: NsdServiceInfo): String? {
        val addrs = if (Build.VERSION.SDK_INT >= 34) si.hostAddresses else listOfNotNull(si.host)
        return (addrs.firstOrNull { it is Inet4Address } ?: addrs.firstOrNull())?.hostAddress
    }
}
