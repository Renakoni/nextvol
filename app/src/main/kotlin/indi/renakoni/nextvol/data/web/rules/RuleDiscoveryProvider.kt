package indi.renakoni.nextvol.data.web.rules

import com.github.michaelbull.result.*
import hnovel.content.*
import hnovel.execution.SourceWorkRequest
import hnovel.network.BrokerLimits
import indi.renakoni.nextvol.data.web.DISCOVERY_SEARCH_PREFIX
import indi.renakoni.nextvol.data.web.ForegroundSourceRequest
import io.nightfish.lightnovelreader.api.web.WebDataSourcePriority
import io.nightfish.lightnovelreader.api.web.discovery.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** One adapter per page. The source runtime still owns revision/account/network authority. */
internal class RuleDiscoveryProvider(private val source: RuleSource,
    private val session: RuleDiscoverySession = source.openDiscovery(java.util.UUID.randomUUID().toString()),
    private val recovery: RuleRequestRecovery? = null,
    private val pixivFilter: PixivBookFilter? = null) : DiscoveryPreviewProvider {
    companion object { internal const val PREVIEW_CONCURRENCY = BrokerLimits.DEFAULT_CONCURRENCY }
    override val hasFeed get() = source.canFeed && (current?.takeIf {
        // Empty/login/transient responses cannot prove that the source is category-only.
        it.homepage != null || it.rows.any { row -> row.type == "url" && row.url.isNotBlank() }
    }?.let { RuleDiscoveryClassifier.feed(it).isNotEmpty() } ?: true)
    override val hasCategories get() = source.canCategorize
    override val hasInteractions = true
    override var failureField: String? = null
        private set
    override var permissionFailure: DiscoveryPermission? = null
        private set
    var diagnosticFailure: hnovel.execution.ExecutionResult.Failure? = null
        private set
    var httpStatus: Int? = null
        private set
    private val previewDiagnostics = java.util.concurrent.ConcurrentHashMap<String, hnovel.execution.ExecutionResult.Failure>()
    fun previewDiagnostic(id: String) = previewDiagnostics[id]
    private val previewHttpStatuses = java.util.concurrent.ConcurrentHashMap<String, Int>()
    fun previewHttpStatus(id: String) = previewHttpStatuses[id]
    private var current: RuleDiscoveryCatalog? = null
    private data class PageKey(val target: String, val filters: Map<String, String>)
    private val pages = object : LinkedHashMap<PageKey, RuleListSession>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<PageKey, RuleListSession>) = size > 8
    }
    override fun openSession(id: String, values: Map<String, String>, environment: DiscoveryEnvironment) =
        RuleDiscoveryProvider(source, source.openDiscovery(id, values, RuleDiscoveryEnvironment(environment.themeMode,
            Json.parseToJsonElement(environment.themeJson).jsonObject, Json.parseToJsonElement(environment.readingJson).jsonObject)), recovery, pixivFilter)

    override suspend fun catalog(refresh: Boolean) = request {
        map(session.catalog(refresh).also { current = it })
    }
    override suspend fun homepageCatalog(refresh: Boolean) = request {
        map(session.catalog(refresh, homepage = true).also { current = it })
    }
    override suspend fun categories() = catalog().map { it.categories }
    override suspend fun feed() = feedUpdates().last()
    override fun feedUpdates() = flow<Result<List<DiscoverySection>, DiscoveryError>> {
        previewDiagnostics.clear()
        previewHttpStatuses.clear()
        val definition = request { session.catalog(homepage = true) }
            .getOrElse { emit(Err(it)); return@flow }
        current = definition
        val catalog = map(definition)
        val entries = RuleDiscoveryClassifier.feed(definition)
        val sections = entries.map { category ->
            DiscoverySection(category.id, category.title, emptyList(), category.url,
                category.id.takeIf { definition.homepage == null }, previewLoading = true)
        }.toMutableList()
        if (sections.isNotEmpty()) emit(Ok(sections.toList()))
        suspend fun preparePreviews(offset: Int) = if (entries.size - offset > 1) try {
            session.concurrentPreviews(entries.drop(offset).map { it.url }, catalog.values)
        } catch (_: SourceContentException) { null } else null
        var offset = 0
        var prepared = preparePreviews(offset)
        if (prepared == null && entries.size > 1) {
            sections[0] = preview(sections[0], entries[0].url, catalog.values, allowInteraction = false)
            emit(Ok(sections.toList()))
            offset = 1
            // The first login hook may initialize cached request inputs. Recheck once;
            // stateful requests still fail the same safety gate and remain sequential.
            prepared = preparePreviews(offset)
        }
        val concurrent = prepared
        val remaining = entries.withIndex().drop(offset)
        if (concurrent != null) coroutineScope {
            val results = Channel<Pair<Int, DiscoverySection>>(PREVIEW_CONCURRENCY)
            val next = java.util.concurrent.atomic.AtomicInteger()
            try {
                // Bound waiting coroutines as well as active reads, even for a very large catalogue.
                repeat(minOf(PREVIEW_CONCURRENCY, remaining.size)) {
                    launch {
                        try {
                            while (true) {
                                val previewIndex = next.getAndIncrement()
                                val (index, category) = remaining.getOrNull(previewIndex) ?: break
                                results.send(index to preview(sections[index], category.url, catalog.values, allowInteraction = false) { concurrent[previewIndex].page(1) })
                            }
                        } catch (cancelled: CancellationException) {
                            // Session/route retirement can cancel one read without cancelling this collector.
                            // Do not leave the parent waiting for a result that this child can no longer send.
                            this@coroutineScope.cancel(cancelled)
                            throw cancelled
                        }
                    }
                }
                repeat(remaining.size) {
                    val (index, section) = results.receive()
                    sections[index] = section
                    emit(Ok(sections.toList()))
                }
            } finally { results.cancel() }
        } else for ((index, category) in remaining) {
            sections[index] = preview(sections[index], category.url, catalog.values, allowInteraction = false)
            emit(Ok(sections.toList()))
        }
        if (entries.isEmpty()) {
            if (hasFeed) { failureField = "exploreUrl"; emit(Err(DiscoveryError.InvalidResponse)) }
            else emit(Ok(emptyList()))
        }
    }

    override suspend fun preview(id: String): Result<DiscoverySection, DiscoveryError> {
        val definition = current ?: return Err(DiscoveryError.InvalidRequest)
        val category = RuleDiscoveryClassifier.feed(definition).find { it.id == id }
            ?: return Err(DiscoveryError.InvalidRequest)
        return Ok(preview(DiscoverySection(category.id, category.title, emptyList(), category.url,
            category.id.takeIf { definition.homepage == null }), category.url, definition.values))
    }

    private suspend fun preview(section: DiscoverySection, url: String, values: Map<String, String>,
        allowInteraction: Boolean = true,
        load: suspend () -> RuleListPage = { session.preview(url, values) }): DiscoverySection {
        previewDiagnostics.remove(section.id)
        previewHttpStatuses.remove(section.id)
        return try {
            // Automatic previews must neither prompt nor inherit the page's foreground priority.
            // Explicit retries retain their owner's interaction and scheduling context.
            val page = if (allowInteraction) {
                recovery?.execute { load() } ?: load()
            } else withContext(ForegroundSourceRequest(allowsInteraction = false) +
                SourceWorkRequest(WebDataSourcePriority.Low.priority)) {
                recovery?.execute { load() } ?: load()
            }
            section.copy(books = (pixivFilter?.filter(page) ?: page.books).take(6).map(::book), previewLoading = false,
                previewFailure = if (page.books.isEmpty() && page.nextCursor == null)
                    DiscoveryPreviewFailure(DiscoveryError.InvalidResponse, "ruleExplore.bookList") else null)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: SourceContentException) {
            failure.diagnostic?.let { previewDiagnostics[section.id] = it }
            failure.httpStatus?.let { previewHttpStatuses[section.id] = it }
            section.copy(previewLoading = false, previewFailure = DiscoveryPreviewFailure(failure.discoveryError(),
                failure.field, failure.denial?.let { DiscoveryPermission(it.origin, it.kind.name) }))
        }
    }
    override fun filters(target: String) = if (target.startsWith(DISCOVERY_SEARCH_PREFIX)) emptyList() else
        current?.rows.orEmpty().filter { row -> row.targetPrefixes.isEmpty() || row.targetPrefixes.any(target::startsWith) }.mapNotNull(::filter)

    override suspend fun page(request: DiscoveryRequest): Result<DiscoveryPage, DiscoveryError> {
        val key = PageKey(request.target, request.filters.toMap())
        val pager = synchronized(pages) {
            if (request.cursor == null) pages.remove(key)
            pages.getOrPut(key) {
                if (request.target.startsWith(DISCOVERY_SEARCH_PREFIX))
                    source.openSearchPages(request.target.removePrefix(DISCOVERY_SEARCH_PREFIX))
                else session.openPages(request.target, key.filters)
            }
        }
        return request {
            val page = pager.page(request.cursor)
            DiscoveryPage((pixivFilter?.filter(page) ?: page.books).map(::book), page.nextCursor)
        }
    }

    override suspend fun interact(id: String, value: String?, longClick: Boolean) = request(retry = false) {
        val updated = session.interact(id, value, longClick)
        val catalog = map(updated.catalog.also { current = it })
        DiscoveryUpdate(catalog, updated.actions.mapNotNull { action -> when (action.kind) {
            "login" -> DiscoveryAction.Login
            "settings" -> DiscoveryAction.Settings
            "configuration" -> null // Already persisted by the session; updated.refresh redraws this page.
            "results" -> DiscoveryAction.Results(action.value, action.title)
            "search" -> DiscoveryAction.Results(DISCOVERY_SEARCH_PREFIX + action.value, action.value)
            "browser" -> DiscoveryAction.Browser(action.value, action.title, action.html, action.script)
            else -> throw SourceContentException(ContentError.InvalidRule, "discovery.action")
        } }, updated.refresh)
    }

    override suspend fun openBrowser(action: DiscoveryAction.Browser) = request(retry = false) {
        source.openDiscoveryBrowser(action.url, action.html, action.script, action.title)
    }

    private fun map(catalog: RuleDiscoveryCatalog) = DiscoveryCatalog(
        catalog.rows.filter { it.type == "url" && it.showInCategories }
            .map { DiscoveryCategory(it.id, it.title, it.url) },
        catalog.rows.filter { it.targetPrefixes.isEmpty() }.mapNotNull(::filter),
        catalog.values, catalog.rows.filter { it.type == "button" }.map { DiscoveryButton(it.id, it.title) })

    private fun filter(row: RuleDiscoveryRow) = when (row.type) {
            "text" -> DiscoveryFilter.Text(row.id, row.title, row.default)
            "toggle", "select" -> DiscoveryFilter.Choice(row.id, row.title, row.choices.associateWith { it }, row.default)
            else -> null
        }

    private fun book(book: RuleBook) = DiscoveryBook(book.id, book.title, book.author, book.coverUrl)
    private suspend fun <T> request(retry: Boolean = true, block: suspend () -> T): Result<T, DiscoveryError> = try {
        failureField = null; permissionFailure = null; diagnosticFailure = null; httpStatus = null
        Ok(if (recovery == null || !retry) block() else recovery.execute(block))
    }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: SourceContentException) {
        failureField = failure.field
        diagnosticFailure = failure.diagnostic
        httpStatus = failure.httpStatus
        permissionFailure = failure.denial?.let { DiscoveryPermission(it.origin, it.kind.name) }
        Err(failure.discoveryError())
    }

    private fun SourceContentException.discoveryError() = when (code) {
        ContentError.MissingCapability -> DiscoveryError.Unsupported
        ContentError.LoginRequired -> DiscoveryError.AuthenticationRequired
        ContentError.BrowserRequired -> if (verification?.kind == hnovel.network.BrowserChallengeKind.Login)
            DiscoveryError.AuthenticationRequired else DiscoveryError.VerificationRequired
        ContentError.PermissionDenied -> DiscoveryError.PermissionDenied
        ContentError.AddressDenied -> DiscoveryError.AddressDenied
        ContentError.Dns -> DiscoveryError.Dns
        ContentError.RouteUnavailable -> DiscoveryError.RouteUnavailable
        ContentError.RouteUnsupported -> DiscoveryError.RouteUnsupported
        ContentError.Certificate -> DiscoveryError.Certificate
        ContentError.Network -> DiscoveryError.Network
        ContentError.Unavailable -> DiscoveryError.Unavailable
        ContentError.Limit -> DiscoveryError.Limit
        else -> DiscoveryError.InvalidRules
    }
}
