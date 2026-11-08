package hnovel.content

import hnovel.network.BrokerRequest
import hnovel.network.SourceRouteReference
import kotlinx.serialization.json.JsonObject

/** Short handoff from successful homepage previews to a resumed feed or its full list. */
internal class DiscoveryPreviewDocuments(private val pool: Pool = sharedPool) {
    data class Key(val request: BrokerRequest, val route: SourceRouteReference, val cookies: List<String>,
        val headers: Map<String, String>, val environment: JsonObject?)
    data class Entry(val document: PageDocument, val userAgent: String?, val savedAt: Long)
    data class OwnedKey(val owner: Any, val key: Key)
    private val owner = Any()
    private var version = 0L
    fun generation() = synchronized(pool) { version }

    fun get(key: Key, now: Long = System.nanoTime()): Entry? = synchronized(pool) {
        pool.prune(now)
        pool.entries[OwnedKey(owner, key)]
    }

    fun put(key: Key, document: PageDocument, userAgent: String?, generation: Long, now: Long = System.nanoTime()) = synchronized(pool) {
        pool.prune(now)
        if (generation != version || document.body.length > MAX_DOCUMENT_CHARS || !key.route.available) return@synchronized
        val owned = OwnedKey(owner, key)
        pool.entries.remove(owned)
        val local = pool.entries.keys.filter { it.owner === owner }
        if (local.size >= 4) pool.entries.remove(local.first())
        pool.entries[owned] = Entry(document, userAgent, now)
        while (pool.entries.size > MAX_DOCUMENTS || pool.entries.values.sumOf { it.document.body.length.toLong() } > MAX_TOTAL_CHARS) {
            pool.entries.remove(pool.entries.keys.first())
        }
    }

    fun clear() {
        synchronized(pool) {
            version++
            pool.entries.keys.removeAll { it.owner === owner }
        }
    }

    internal class Pool {
        val entries = LinkedHashMap<OwnedKey, Entry>(MAX_DOCUMENTS, 0.75f, true)
        fun prune(now: Long) {
            entries.entries.removeAll { (key, value) -> !key.key.route.available || now - value.savedAt >= 60_000_000_000L }
        }
    }

    companion object {
        internal const val MAX_DOCUMENTS = 16
        internal const val MAX_DOCUMENT_CHARS = 256 * 1024
        // At most 4 MiB of UTF-16 body data across all sources, plus bounded keys/entry overhead.
        internal const val MAX_TOTAL_CHARS = 2 * 1024 * 1024
        private val sharedPool = Pool()
    }
}
