package indi.renakoni.nextvol.data.web

import io.nightfish.lightnovelreader.api.image.SourceImageProvider

import android.content.Context
import android.util.Log
import android.os.SystemClock
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.get
import com.github.michaelbull.result.onOk
import indi.renakoni.nextvol.data.web.proxy.ProxyCachedWebBookDataSource
import indi.renakoni.nextvol.data.web.proxy.ProxyCoalescingWebBookDataSource
import indi.renakoni.nextvol.data.web.proxy.ProxyPriorityWebBookDataSource
import indi.renakoni.nextvol.data.explore.PagedSearchProvider
import indi.renakoni.nextvol.data.explore.SearchPage
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.Volume
import io.nightfish.lightnovelreader.api.util.Cache
import io.nightfish.lightnovelreader.api.web.WebBookDataSource
import io.nightfish.lightnovelreader.api.web.WebDataSourcePriority
import io.nightfish.lightnovelreader.api.web.search.SearchProvider
import io.nightfish.lightnovelreader.api.web.search.SearchType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One registration generation, resolved by source identity rather than a global provider. */
class SourceRuntime internal constructor(
    val metadata: SourceMetadata,
    private val source: WebBookDataSource,
    private val lifetime: CoroutineScope,
    private val cleanupScope: CoroutineScope,
    internal val discoveryResolved: (Boolean) -> Unit = {},
) {
    private companion object { const val DIRECTORY_HANDOFF_MILLIS = 120_000 }
    val id get() = metadata.id
    val isAvailable get() = lifetime.isActive
    internal val canReplayDownloads get() = (source as? indi.renakoni.nextvol.data.web.rules.RuleWebBookDataSource)?.canReplayDownloads == true
    // Audited native reads only; replay-safe rules can still share mutable execution state.
    internal val canDownloadConcurrently get() = source is indi.renakoni.nextvol.defaultplugin.wenku8.Wenku8Api
    private val responseCache = source.cache?.let { Cache(it.maxCountEachType, it.timeout) }
    private val priority = ProxyPriorityWebBookDataSource(source)
    private val coalescing = ProxyCoalescingWebBookDataSource(priority)
    // A source's Cache is configuration, not shared runtime storage. Even adapters
    // accidentally returning one Cache instance cannot share host response entries.
    private val cached = ProxyCachedWebBookDataSource(coalescing, responseCache)
    // A bounded handoff from details/selection to one download, never an offline fallback.
    private data class RecentInformation(val id: String, val information: BookInformation, val completedAt: Long)
    private data class RecentDirectory(val id: String, val volumes: BookVolumes, val completedAt: Long,
        val information: BookInformation?)
    private val directoryLock = Any()
    private var directoryGeneration = 0L
    private var informationGeneration = 0L
    private var recentDirectory: RecentDirectory? = null
    private var recentInformation: RecentInformation? = null

    internal fun checkAvailable() {
        if (!isAvailable) throw SourceUnavailableException(id)
    }

    internal suspend fun <T> execute(block: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        checkAvailable()
        val version = currentCoroutineContext()[SourceRequestVersion]
        version?.check(metadata)
        val interaction = currentCoroutineContext()[ForegroundSourceRequest] ?: kotlin.coroutines.EmptyCoroutineContext
        val retry = currentCoroutineContext()[hnovel.network.RequestRetryContext] ?: kotlin.coroutines.EmptyCoroutineContext
        val background = currentCoroutineContext()[BackgroundSourceRequest] ?: kotlin.coroutines.EmptyCoroutineContext
        val download = currentCoroutineContext()[DownloadSourceRequest] ?: kotlin.coroutines.EmptyCoroutineContext
        val scheduling = currentCoroutineContext()[hnovel.execution.SourceWorkRequest] ?:
            if (interaction is ForegroundSourceRequest && interaction.allowsInteraction && interaction.isActive)
                hnovel.execution.SourceWorkRequest(WebDataSourcePriority.High.priority) else kotlin.coroutines.EmptyCoroutineContext
        val request = lifetime.async(interaction + retry + background + download + scheduling + (version ?: kotlin.coroutines.EmptyCoroutineContext) + SourceRequestOwner(id)) { block() }
        return try {
            request.await().also { checkAvailable() }
        } finally {
            request.cancel()
        }
    }

    internal fun <T> observe(block: () -> Flow<T>): Flow<T> = flow {
        coroutineScope {
            checkAvailable()
            val collection = requireNotNull(currentCoroutineContext()[Job])
            val handle = requireNotNull(lifetime.coroutineContext[Job]).invokeOnCompletion { collection.cancel() }
            try {
                block().collect { checkAvailable(); emit(it) }
            } finally {
                handle.dispose()
            }
        }
    }.flowOn(SourceRequestOwner(id))

    internal fun ownedScope(parent: CoroutineScope): CoroutineScope {
        checkAvailable()
        val child = Job(parent.coroutineContext[Job])
        val handle = requireNotNull(lifetime.coroutineContext[Job]).invokeOnCompletion { child.cancel() }
        child.invokeOnCompletion { handle.dispose() }
        return CoroutineScope(parent.coroutineContext + child + SourceRequestOwner(id))
    }

    fun bookTagPage(tag: String): String? {
        checkAvailable()
        return source.bookTagPage(tag)
    }

    suspend fun volumeCover(bookId: String, volume: Volume,
        chapters: MutableMap<String, ChapterContent>, context: Context) =
        execute { source.getCoverUriInVolume(bookId, volume, chapters, context) }

    val discovery: SourceDiscovery? by lazy { source.discoveryProvider?.let { SourceDiscovery(this, it) } }

    fun imageHeaders(): Map<String, String> {
        checkAvailable()
        return source.imageHeader.toMap()
    }

    val hasImageProvider get() = source is SourceImageProvider

    suspend fun imageBytes(bookId: String, url: String, cover: Boolean) = execute {
        (source as SourceImageProvider).getImage(bookId, url, cover)
    }

    suspend fun getBookInformation(bookId: String, priority: WebDataSourcePriority = WebDataSourcePriority.Default, refresh: Boolean = false) = execute {
        val download = currentCoroutineContext()[DownloadSourceRequest] != null
        val generation: Long
        synchronized(directoryLock) {
            val recent = recentDirectory
            if (download && recent?.id == bookId && SystemClock.elapsedRealtime() - recent.completedAt < DIRECTORY_HANDOFF_MILLIS)
                recent.information?.let { return@execute Ok(it) }
            // Information scripts can change book variables and the directory URL.
            // Keep a directory only when its matching information can also be reused.
            recentDirectory = null
            recentInformation = null
            directoryGeneration++
            generation = ++informationGeneration
        }
        cached.getBookInformation(bookId, priority, refresh).also { result -> synchronized(directoryLock) {
            if (generation == informationGeneration && isAvailable) {
                // A directory completed before this information response may use an older URL/state.
                recentDirectory = null
                recentInformation = result.get()?.let { RecentInformation(bookId, it, SystemClock.elapsedRealtime()) }
            }
        } }
    }

    internal suspend fun bookInformationForDisplay(bookId: String, information: BookInformation) = execute {
        (source as? indi.renakoni.nextvol.data.web.rules.RuleWebBookDataSource)
            ?.informationForDisplay(bookId, information) ?: information
    }

    /** Only the imported-rule adapter can propose a remote alias; source identity stays host-owned. */
    internal suspend fun canonicalBookId(bookId: String): String = execute {
        (source as? indi.renakoni.nextvol.data.web.rules.RuleWebBookDataSource)?.canonicalBookId(bookId) ?: bookId
    }

    suspend fun getBookVolumes(bookId: String, priority: WebDataSourcePriority = WebDataSourcePriority.Default,
        refresh: Boolean = false, reuseRecent: Boolean = false) = execute {
        val consume = currentCoroutineContext()[DownloadSourceRequest] != null
        val generation: Long
        synchronized(directoryLock) {
            val recent = recentDirectory
            if (reuseRecent && recent?.id == bookId && SystemClock.elapsedRealtime() - recent.completedAt < DIRECTORY_HANDOFF_MILLIS) {
                if (consume) recentDirectory = null
                return@execute Ok(recent.volumes)
            }
            // Invalidate before refresh, including failure/cancellation. An older request
            // finishing later must not republish a superseded snapshot.
            recentDirectory = null
            generation = ++directoryGeneration
        }
        cached.getBookVolumes(bookId, priority, refresh).onOk { volumes ->
            if (!consume && volumes.volumes.any { it.chapters.isNotEmpty() }) synchronized(directoryLock) {
                if (generation == directoryGeneration && isAvailable) {
                    val now = SystemClock.elapsedRealtime()
                    val information = recentInformation?.takeIf { it.id == bookId && now - it.completedAt < DIRECTORY_HANDOFF_MILLIS }?.information
                    recentDirectory = RecentDirectory(bookId, volumes, now, information)
                }
            }
        }
    }

    suspend fun getChapterContent(chapterId: String, bookId: String, priority: WebDataSourcePriority = WebDataSourcePriority.Default, refresh: Boolean = false) =
        execute { cached.getChapterContent(chapterId, bookId, priority, refresh) }

    val search: SearchProvider = object : SearchProvider {
        override val searchTypes get() = run { checkAvailable(); source.searchProvider.searchTypes.toList() }
        override fun search(searchType: SearchType, keyword: String) = observe {
            source.searchProvider.search(searchType, keyword)
        }
        override fun getSearchSuggestions(history: List<String>, keyword: String): List<String> {
            checkAvailable()
            return source.searchProvider.getSearchSuggestions(history, keyword)
        }
    }

    internal val hasSearchPages get() = source.searchProvider is PagedSearchProvider

    internal fun searchPage(type: SearchType, keyword: String, page: Int, query: String? = null): Flow<SearchPage> = observe {
        (source.searchProvider as PagedSearchProvider).searchPageUpdates(type, keyword, page, query)
    }

    internal fun retire() {
        lifetime.cancel()
        synchronized(directoryLock) { recentDirectory = null; recentInformation = null; directoryGeneration++ }
        coalescing.close()
        cleanupScope.launch {
            try {
                lifetime.coroutineContext[Job]?.join()
                // Coalesced work has its own scope. Await its cancellation handlers
                // before closing the client or shutting down their dispatcher.
                coalescing.closeAndJoin()
                (source as? AutoCloseable)?.close()
            } catch (failure: Exception) {
                Log.w("SourceRuntime", "Could not close source $id", failure)
            } finally {
                priority.close()
            }
        }
    }
}
