package indi.renakoni.nextvol.data.book

import android.content.Context
import android.content.SharedPreferences
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.andThen
import com.github.michaelbull.result.map
import com.github.michaelbull.result.mapError
import dagger.hilt.android.qualifiers.ApplicationContext
import indi.renakoni.nextvol.data.download.BookDownloadStore
import indi.renakoni.nextvol.data.local.LocalBookDataSource
import indi.renakoni.nextvol.data.web.BackgroundSourceRequest
import indi.renakoni.nextvol.data.web.ForegroundSourceRequest
import indi.renakoni.nextvol.data.web.SourceRuntime
import indi.renakoni.nextvol.data.web.WebSourceRegistry
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.web.WebDataSourcePriority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import javax.inject.Inject
import javax.inject.Singleton

/** Event-triggered checks. Their interval never expires readable data or schedules a timer. */
@Singleton
class BookDirectoryChecks internal constructor(
    private val registry: WebSourceRegistry,
    private val local: LocalBookDataSource,
    private val downloads: BookDownloadStore,
    private val checked: SharedPreferences,
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Inject constructor(registry: WebSourceRegistry, local: LocalBookDataSource,
        downloads: BookDownloadStore, @ApplicationContext context: Context) :
        this(registry, local, downloads, context.getSharedPreferences("book_directory_checks", Context.MODE_PRIVATE))

    private data class Key(val runtime: SourceRuntime, val book: SourceBookId, val owner: ForegroundSourceRequest?)
    private class Request<T>(val result: Deferred<Result<T, WebRequestError>>, var consumers: Int = 0)
    private val informationRequests = mutableMapOf<Key, Request<BookInformation>>()
    private val directoryRequests = mutableMapOf<Key, Request<BookVolumes>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun information(book: SourceBookId, priority: WebDataSourcePriority, prepare: Boolean = false): Result<BookInformation, WebRequestError> {
        val requested = local.aliases.resolve(book)
        return registry.request(requested) { runtime ->
            check(informationRequests, "information", requested, runtime,
                cached = { local.getBookInformation(requested.storageKey) },
                preserveOwner = prepare,
                requirePreparation = { prepare && !runtime.hasPreparedInformation(requested.remoteId) }) {
                runtime.getBookInformation(requested.remoteId, priority).andThen { remote ->
                    runtime.persistCanonicalBook(requested, local, downloads).map { canonical ->
                        val information = requested.bind(remote)
                        if (canonical == requested) {
                            local.updateBookInformation(information)
                            information
                        } else local.getBookInformation(requested.storageKey) ?: information
                    }
                }
            }.map { it.copy(id = book.storageKey) }
        }
    }

    suspend fun volumes(book: SourceBookId, priority: WebDataSourcePriority): Result<BookVolumes, WebRequestError> {
        val requested = local.aliases.resolve(book)
        return registry.request(requested) { runtime ->
            check(directoryRequests, "directory", requested, runtime,
                cached = { local.getBookVolumes(requested.storageKey) }) {
                // Directory scripts may consume variables and URLs prepared by the details script.
                information(requested, priority, prepare = true).andThen {
                    runtime.getBookVolumes(requested.remoteId, priority).mapError { error ->
                        error.mapAvailableVolumes { requested.bind(it).rebind(requested, book) }
                    }.andThen { remote ->
                        runtime.persistCanonicalBook(requested, local, downloads).map { canonical ->
                            val volumes = requested.bind(remote)
                            if (canonical == requested) local.updateBookVolumes(volumes)
                            volumes
                        }
                    }
                }
            }.map { it.rebind(requested, book) }
        }
    }

    private suspend fun <T> check(requests: MutableMap<Key, Request<T>>, operation: String,
        book: SourceBookId, runtime: SourceRuntime, cached: suspend () -> T?,
        preserveOwner: Boolean = false, requirePreparation: () -> Boolean = { false },
        fetch: suspend () -> Result<T, WebRequestError>): Result<T, WebRequestError> {
        val local = cached()
        val context = currentCoroutineContext()
        val automatic = local != null && !preserveOwner
        val owner = context[ForegroundSourceRequest]?.takeIf { !automatic && it.allowsInteraction }
        val key = Key(runtime, book, owner)
        val entry = synchronized(requests) {
            requests.getOrPut(key) {
                val storageKey = "$operation:${book.storageKey}"
                val version = BookIdentity.encode("check", listOf(runtime.metadata.revision, runtime.metadata.accountGeneration.toString()))
                val previous = checked.getString(storageKey, null)
                val elapsed = previous?.substringAfterLast(':')?.toLongOrNull()?.let { now() - it }
                // Cache-only results never occupy a shared task that a preparation caller could join.
                if (!requirePreparation() && local != null && previous?.substringBeforeLast(':') == version &&
                    elapsed != null && elapsed in 0 until 600_000L) return Ok(local)
                var ownership = context.minusKey(Job)
                if (automatic) ownership = ownership.minusKey(BackgroundSourceRequest) + ForegroundSourceRequest(false)
                Request(scope.async(ownership, start = CoroutineStart.LAZY) {
                    runtime.execute {
                        // Failed attempts are throttled as well, including across process recreation.
                        checked.edit().putString(storageKey, "$version:${now()}").apply()
                        fetch()
                    }
                })
            }.also { it.consumers++ }
        }
        try {
            return entry.result.await()
        } finally {
            synchronized(requests) {
                if (--entry.consumers == 0) {
                    requests.remove(key)
                    entry.result.cancel()
                }
            }
        }
    }
}
