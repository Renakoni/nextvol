package hnovel.network

import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dns
import javax.net.SocketFactory

/** Device preference, independent of origin permissions and account credentials. */
enum class SourceNetworkMode { SystemDefault, BypassVpn }

/** Trusted host port. Capture once when admitting a request, including its browser children. */
fun interface SourceRouteProvider {
    fun snapshot(): SourceNetworkRoute
}

/** One network generation. Android supplies a matching resolver and socket factory.
 * Sessions own their clients/pools; only physical network changes retire a route. */
class SourceNetworkRoute(val mode: SourceNetworkMode, val dns: Dns,
    val socketFactory: SocketFactory = SocketFactory.getDefault()) {
    val reference = SourceRouteReference(this)
    private val pools = mutableSetOf<ConnectionPool>()
    private val calls = mutableSetOf<Call>()
    private val invalidations = mutableSetOf<() -> Unit>()
    @Volatile var available: Boolean = true
        private set

    fun invalidate() {
        val retired = synchronized(this) {
            if (!available) return
            available = false
            Triple(calls.toList(), pools.toList(), invalidations.toList()).also {
                calls.clear(); pools.clear(); invalidations.clear()
            }
        }
        retired.first.forEach { it.cancel() }
        retired.second.forEach { it.evictAll() }
        retired.third.forEach { runCatching(it) }
    }

    /** A native browser retains this subscription while its process can still issue requests. */
    fun onInvalidated(action: () -> Unit): AutoCloseable {
        val listener = { action() }
        val registered = synchronized(this) { if (available) invalidations.add(listener) else false }
        if (!registered) action()
        return AutoCloseable { synchronized(this) { invalidations.remove(listener) } }
    }

    internal fun checkAvailable() {
        if (!available) throw BrokerFailure(RequestStage.Connect, FailureCode.RouteUnavailable)
    }

    @Synchronized internal fun attach(pool: ConnectionPool) {
        checkAvailable()
        pools.add(pool)
    }

    @Synchronized internal fun detach(pool: ConnectionPool) {
        pools.remove(pool)
        pool.evictAll()
    }

    @Synchronized internal fun track(call: Call) {
        checkAvailable()
        calls.add(call)
    }

    @Synchronized internal fun finished(call: Call, pool: ConnectionPool) {
        calls.remove(call)
        if (!available) pool.evictAll()
    }

    companion object {
        fun systemDefault() = SourceNetworkRoute(SourceNetworkMode.SystemDefault, VpnDns.Default)
    }
}
