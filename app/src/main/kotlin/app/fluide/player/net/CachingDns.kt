package app.fluide.player.net

import android.os.SystemClock
import okhttp3.Dns
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

/**
 * DNS cache in front of the system resolver.
 *
 * - Zapping between channels of the same server never waits for DNS.
 * - When the resolver fails (cheap ISP DNS, captive Wi-Fi hiccup) a recent answer is reused instead of
 *   cutting the stream: "stale on error".
 * - IPv4 first: OkHttp 4 tries addresses one after the other, so a broken IPv6 route would cost a full
 *   connect timeout on every zap. Many IPTV servers publish AAAA records they do not really serve.
 */
class CachingDns(
    private val delegate: Dns = Dns.SYSTEM,
    private val ttlMs: Long = 5 * 60_000,
    private val staleMs: Long = 6 * 60 * 60_000,
) : Dns {

    private class Entry(val addresses: List<InetAddress>, val resolvedAt: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    override fun lookup(hostname: String): List<InetAddress> {
        val now = SystemClock.elapsedRealtime()
        val cached = cache[hostname]
        if (cached != null && now - cached.resolvedAt < ttlMs) return cached.addresses
        return try {
            val fresh = ipv4First(delegate.lookup(hostname))
            if (fresh.isEmpty()) throw UnknownHostException(hostname)
            cache[hostname] = Entry(fresh, now)
            fresh
        } catch (e: UnknownHostException) {
            if (cached != null && now - cached.resolvedAt < staleMs) cached.addresses else throw e
        }
    }

    /** Resolves ahead of time (called for the channels around the current one). Never throws. */
    fun prefetch(hostname: String) {
        runCatching { lookup(hostname) }
    }

    private fun ipv4First(addresses: List<InetAddress>): List<InetAddress> =
        if (addresses.size < 2) addresses else addresses.sortedBy { if (it is Inet4Address) 0 else 1 }
}
