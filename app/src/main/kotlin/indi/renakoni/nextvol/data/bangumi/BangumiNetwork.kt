package indi.renakoni.nextvol.data.bangumi

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.xbill.DNS.Type
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Official API only. The search mirror keeps its separate, credential-free client. */
@Singleton
class BangumiNetwork internal constructor(
    private val bootstrap: OkHttpClient,
    private val configEndpoints: List<HttpUrl>,
    private val dnsEndpoint: HttpUrl,
    private val secureClient: (ByteArray, Dns) -> OkHttpClient,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    @Inject constructor() : this(bootstrapClient(), CONFIG_ENDPOINTS, DNS_ENDPOINT, tlsClients())

    private data class Config(val bytes: ByteArray, val expires: Long)
    private data class Route(val client: OkHttpClient, val expires: Long)
    private val mutex = Mutex()
    private val doh = BangumiDoh()
    private val route = AtomicReference<Route?>()

    suspend fun client(): OkHttpClient = try {
        withTimeout(12_000) {
            mutex.withLock {
                route.get()?.takeIf { now() < it.expires }?.let { return@withLock it.client }
                route.getAndSet(null)?.client?.connectionPool?.evictAll()
                val ech = loadConfig()
                val resolver = secureClient(ech.bytes, fixedDns(mapOf(DNS_HOST to DNS_ADDRESSES)))
                val candidates = ConcurrentLinkedQueue<OkHttpClient>()
                var selected: Route? = null
                try {
                    selected = channelFlow {
                        for (type in listOf(Type.A, Type.AAAA)) launch {
                            try {
                                val answer = doh.query(resolver, dnsEndpoint, API_HOST, type)
                                val addresses = answer.addresses().distinct()
                                if (addresses.isEmpty()) return@launch
                                val expires = minOf(ech.expires, now() + answer.ttl * 1000)
                                val client = secureClient(ech.bytes, fixedDns(mapOf(API_HOST to addresses)))
                                candidates += client
                                // A DNS answer alone cannot prove that this network can use IPv6.
                                probe(client)
                                send(Route(client, expires))
                            } catch (_: IOException) { currentCoroutineContext().ensureActive() }
                        }
                    }.firstOrNull() ?: throw UnknownHostException("Bangumi connection unavailable")
                    route.set(selected)
                    selected.client
                } finally {
                    resolver.connectionPool.evictAll()
                    candidates.filter { it !== selected?.client }.forEach { it.connectionPool.evictAll() }
                }
            }
        }
    } catch (timeout: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        throw InterruptedIOException("Bangumi connection preparation timed out")
    }

    suspend fun invalidate(client: OkHttpClient) {
        val failed = route.get()
        // A failed request must not wait behind another request's network preparation.
        if (failed?.client === client && route.compareAndSet(failed, null)) {
            client.connectionPool.evictAll()
        }
    }

    /** HEAD establishes reusable TLS without an account, cookies, or a business write. */
    private suspend fun probe(client: OkHttpClient): Unit = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder().url("https://$API_HOST/").head()
            .header("User-Agent", "Renakoni/NextVol (https://github.com/Renakoni/nextvol)").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                response.close()
                continuation.resume(Unit)
            }
        })
    }

    private suspend fun loadConfig(): Config {
        for (endpoint in configEndpoints) {
            try {
                val answer = doh.query(bootstrap, endpoint, "cloudflare-ech.com", Type.HTTPS)
                return Config(answer.echConfig(), now() + answer.ttl * 1000)
            } catch (_: IOException) { currentCoroutineContext().ensureActive() }
        }
        throw IOException("Bangumi ECH configuration unavailable")
    }

    companion object {
        internal const val API_HOST = "api.bgm.tv"
        private const val DNS_HOST = "cloudflare-dns.com"
        private val DNS_ENDPOINT = "https://cloudflare-dns.com/dns-query".toHttpUrl()
        private val CONFIG_ENDPOINTS = listOf("https://doh.pub/dns-query", "https://dns.alidns.com/dns-query").map { it.toHttpUrl() }
        // Bootstrap hints belong to the resolver operators. TLS still verifies their DNS names.
        private val DNS_ADDRESSES = addresses("104.16.248.249", "104.16.249.249", "2606:4700::6810:f8f9", "2606:4700::6810:f9f9")
        private fun addresses(vararg values: String) = values.map(InetAddress::getByName)
        internal fun fixedDns(hosts: Map<String, List<InetAddress>>): Dns = Dns { host ->
            // OkHttp also uses this DNS for an explicitly configured HTTP proxy's hostname.
            hosts[host] ?: Dns.SYSTEM.lookup(host)
        }
        private fun bootstrapClient() = OkHttpClient.Builder()
            .dns(fixedDns(mapOf("doh.pub" to addresses("1.12.12.12", "120.53.53.53"),
                "dns.alidns.com" to addresses("223.5.5.5", "223.6.6.6", "2400:3200::1", "2400:3200:baba::1"))))
            .followRedirects(false).followSslRedirects(false)
            .connectTimeout(2, TimeUnit.SECONDS).callTimeout(3, TimeUnit.SECONDS).build()

        private fun tlsClients(): (ByteArray, Dns) -> OkHttpClient {
            val tls by lazy { BangumiEchTls() }
            return { config, dns ->
                OkHttpClient.Builder().dns(dns).sslSocketFactory(tls.socketFactory(config), tls.trustManager)
                    .followRedirects(false).followSslRedirects(false)
                    .connectTimeout(4, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
                    .callTimeout(30, TimeUnit.SECONDS).build()
            }
        }
    }
}
