package indi.renakoni.nextvol.defaultplugin.wenku8.search

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.WordCount
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.*
import org.jsoup.Jsoup
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class Wenku8SearchEntry(val id: String, val title: String, val author: String, val aliases: List<String> = emptyList()) {
    val names = (Wenku8SearchText.names(title) + aliases).map(Wenku8SearchText::key).filter(String::isNotBlank).distinct()
    private val authorKey = Wenku8SearchText.key(author)
    fun score(query: String, authorOnly: Boolean): Int? = if (authorOnly) Wenku8SearchText.score(query, authorKey)
        else (names.mapNotNull { Wenku8SearchText.score(query, it) } +
            listOfNotNull(Wenku8SearchText.score(query, authorKey)?.plus(5))).minOrNull()

    // Search metadata is a preview, never inserted into the saved book/directory tables.
    fun preview() = BookInformation(id, title, author = author, description = "", publishingHouse = "",
        coverUri = Uri.EMPTY, wordCount = WordCount(0), lastUpdated = LocalDateTime.MIN, isComplete = false)
}

@Singleton
class Wenku8SearchCatalog @Inject constructor(@param:ApplicationContext private val context: Context) {
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var refreshing: Job? = null
    private var attemptedAt = 0L
    private var entries: List<Wenku8SearchEntry>? = null
    @Volatile internal var generation = 0L
        private set
    private val file get() = File(context.cacheDir, "$DIRECTORY/catalog.tsv.gz")
    private val freshness get() = File(context.cacheDir, "$DIRECTORY/refreshed-at")
    private val client by lazy { OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS).followRedirects(false).build() }

    internal suspend fun snapshot(): List<Wenku8SearchEntry> = withContext(Dispatchers.IO) { lock.withLock {
        entries ?: run {
            val saved = try { read(AtomicFile(file).openRead()) } catch (_: IOException) { null }
                catch (_: IllegalArgumentException) { null }
            (saved ?: context.assets.open("wenku8-search/catalog.bin").let(::read)).also { entries = it }
        }
    } }

    internal fun refreshInBackground() {
        scope.launch { lock.withLock {
            val now = System.currentTimeMillis()
            val refreshedAt = try { freshness.readText().toLongOrNull() ?: 0L } catch (_: IOException) { 0L }
            if (refreshing?.isActive == true || now - attemptedAt < 10 * 60_000 ||
                now - refreshedAt in 0 until 24 * 60 * 60_000L) return@withLock
            attemptedAt = now
            val version = generation
            refreshing = scope.launch {
                try {
                    val base = snapshot()
                    val html = download()
                    val updates = parsePage(html)
                    lock.withLock {
                        if (version != generation) return@withLock
                        // The download page is a subset; absence there does not delete a catalogue book.
                        val combined = (entries ?: base).associateBy { it.id }.toMutableMap()
                        updates.forEach { entry ->
                            val old = combined[entry.id]
                            combined[entry.id] = old?.copy(aliases = (old.aliases + entry.aliases +
                                listOfNotNull(entry.title.takeIf { it != old.title })).distinct()) ?: entry
                        }
                        val next = combined.values.sortedBy { it.id.toInt() }
                        write(file, next)
                        entries = next
                        freshness.writeText(System.currentTimeMillis().toString())
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* The bundled/previous catalogue remains usable. */ }
            }
        } }
    }

    suspend fun clear() = withContext(Dispatchers.IO) { lock.withLock {
        generation++
        refreshing?.cancel()
        refreshing = null
        entries = null
        attemptedAt = System.currentTimeMillis()
        val directory = File(context.cacheDir, DIRECTORY)
        check(!directory.exists() || directory.deleteRecursively()) { "Cannot clear search cache" }
    } }

    internal suspend fun remember(books: List<BookInformation>, version: Long) = withContext(Dispatchers.IO) {
        val observed = books.filter { it.lastUpdated != LocalDateTime.MIN && it.id.toIntOrNull()?.let { id -> id > 0 } == true }
        if (observed.isEmpty()) return@withContext
        val base = snapshot()
        lock.withLock {
            if (version != generation) return@withLock
            val combined = (entries ?: base).associateBy { it.id }.toMutableMap()
            var changed = false
            for (book in observed) {
                val old = combined[book.id]
                val title = book.title + book.subtitle.takeIf(String::isNotBlank)?.let { "($it)" }.orEmpty()
                val aliases = (old?.aliases.orEmpty() + listOfNotNull(old?.title?.takeIf { it != title })).distinct()
                val next = Wenku8SearchEntry(book.id, title, book.author, aliases)
                if (next != old) { combined[book.id] = next; changed = true }
            }
            if (changed) {
                val next = combined.values.sortedBy { it.id.toInt() }
                write(file, next)
                entries = next
            }
        }
    }

    private suspend fun download(): String = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder().url("https://wenku.mojimoon.top/")
            .header("User-Agent", "Renakoni/NextVol (${indi.renakoni.nextvol.ProjectLinks.GITHUB})").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val text = response.use {
                        if (!it.isSuccessful) throw IOException("Catalogue unavailable")
                        val bytes = it.body.byteStream().readBytesLimited(4 * 1024 * 1024)
                        bytes.toString(Charsets.UTF_8)
                    }
                    continuation.resume(text)
                } catch (failure: Exception) { continuation.resumeWithException(failure) }
            }
        })
    }

    companion object {
        const val DIRECTORY = "wenku8-search"
        internal fun read(input: InputStream): List<Wenku8SearchEntry> = input.use { GZIPInputStream(it).use { gzip ->
            val text = gzip.readBytesLimited(4 * 1024 * 1024).toString(Charsets.UTF_8)
            val lines = text.lineSequence().iterator()
            require(lines.hasNext() && lines.next() == "wenku8-search-v1")
            lines.asSequence().filter(String::isNotBlank).map { line ->
                val fields = line.split('\t')
                require(fields.size >= 3 && fields[0].toIntOrNull()?.let { it > 0 } == true && fields[1].isNotBlank())
                Wenku8SearchEntry(fields[0], fields[1], fields[2], fields.drop(3))
            }.toList().also { require(it.isNotEmpty() && it.size <= 20_000 && it.distinctBy { row -> row.id }.size == it.size) }
        } }

        internal fun write(file: File, entries: List<Wenku8SearchEntry>) {
            val buffer = java.io.ByteArrayOutputStream()
            GZIPOutputStream(buffer).bufferedWriter().use { writer ->
                writer.appendLine("wenku8-search-v1")
                entries.forEach { entry -> writer.appendLine((listOf(entry.id, entry.title, entry.author) + entry.aliases)
                    .joinToString("\t") { it.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ') }) }
            }
            file.parentFile!!.mkdirs()
            val atomic = AtomicFile(file)
            val output = atomic.startWrite()
            try {
                buffer.writeTo(output)
                atomic.finishWrite(output)
            } catch (failure: Exception) { atomic.failWrite(output); throw failure }
        }

        internal fun parsePage(html: String): List<Wenku8SearchEntry> {
            val data = Jsoup.parse(html).selectFirst("script#data")?.data() ?: throw IOException("Missing catalogue")
            val items = Json.parseToJsonElement(data).jsonObject["items"]?.jsonArray ?: throw IOException("Missing books")
            return items.mapNotNull { element ->
                val item = element.jsonObject
                val id = item["n"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
                val title = item["t"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val alias = item["a"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                Wenku8SearchEntry(id.toString(), title, item["au"]?.jsonPrimitive?.contentOrNull.orEmpty(), listOfNotNull(alias))
            }.also { require(it.size in 100..20_000) }.distinctBy { it.id }
        }

        private fun InputStream.readBytesLimited(limit: Int): ByteArray {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = read(buffer)
                if (count < 0) break
                if (output.size() + count > limit) throw IOException("Catalogue too large")
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }
}
