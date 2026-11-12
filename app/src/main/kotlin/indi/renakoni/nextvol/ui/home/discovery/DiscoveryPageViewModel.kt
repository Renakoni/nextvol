package indi.renakoni.nextvol.ui.home.discovery

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.michaelbull.result.*
import indi.renakoni.nextvol.data.web.*
import io.nightfish.lightnovelreader.api.Route
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.discovery.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.UUID

data class DiscoveryPageContent(
    val categories: List<SourceDiscoveryCategory> = emptyList(),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val error: DiscoveryError? = null,
    val scroll: DiscoveryScroll = DiscoveryScroll(),
    val filters: List<DiscoveryFilter> = emptyList(),
    val values: Map<String, String> = emptyMap(),
    val buttons: List<DiscoveryButton> = emptyList(),
    val acting: Boolean = false,
    val errorField: String? = null,
    val errorPermission: DiscoveryPermission? = null,
    val sections: List<SourceDiscoverySection> = emptyList(),
    val resetId: Long = 0,
    val errorDiagnostic: hnovel.execution.ExecutionResult.Failure? = null,
    val errorHttpStatus: Int? = null,
)

data class DiscoveryPageState(
    val sources: List<SourceListing> = emptyList(),
    val selected: Identifier? = null,
    val content: Map<Identifier, DiscoveryPageContent> = emptyMap(),
    val loadingSources: Boolean = false,
    val scope: SourceCategory? = null,
    val availableSources: List<SourceListing> = sources,
    val restorationFailed: Boolean = false,
) {
    val pageIndex: Int get() = sources.indexOfFirst { it.metadata.id == selected }.coerceAtLeast(0) / SOURCE_PAGE_SIZE
    val pageCount: Int get() = (sources.size + SOURCE_PAGE_SIZE - 1) / SOURCE_PAGE_SIZE
    val pageSources: List<SourceListing> get() = sources.drop(pageIndex * SOURCE_PAGE_SIZE).take(SOURCE_PAGE_SIZE)
}

internal const val SOURCE_PAGE_SIZE = 20
internal const val MAX_RETAINED_SOURCE_PAGES = 8

data class DiscoveryCommand(val source: Identifier, val epoch: Long, val action: DiscoveryAction, val values: Map<String, String>)

/** Entries share source selection while owning their drafts, requests and browser handoff. */
abstract class DiscoveryPageViewModel(
    private val registry: WebSourceRegistry,
    accounts: SourceSessionManager,
    private val saved: SavedStateHandle,
    private val capability: SourceCapability,
    private val browsing: SourceBrowseSettings,
) : ViewModel() {
    private val key = if (capability == SourceCapability.Categories) "category" else "explore"
    private var contentId = 0L
    private val mutableState = MutableStateFlow(DiscoveryPageState(loadingSources = true, scope = browsing.scope.value))
    val state = mutableState.asStateFlow()
    private var versions = emptyMap<Identifier, DiscoveryVersion>()
    // Route arguments are one-shot requests. Restoring an entry must not replay its original source.
    private var explicitRequest = if (saved.get<Boolean>("$key.initialSourceHandled") != true)
        saved.get<String>("namespace")?.let { namespace -> saved.get<String>("sourceId")?.let { Identifier(namespace, it) } }
        else null
    private val pageSelections = mutableMapOf<Pair<SourceCategory?, Int>, Identifier>()
    private var active = false
    private var pending: Job? = null
    private var browser: Job? = null
    private var browserToken: Any? = null
    private var serial = 0L
    private var actionEpoch = 0L
    private val previewRetries = mutableMapOf<String, Pair<SourceDiscoverySection, Job>>()
    private val previewOverrides = mutableMapOf<String, SourceDiscoverySection>()
    private val sessions = mutableMapOf<Identifier, SourceDiscovery>()
    private val recentPages = linkedSetOf<Identifier>()
    private val refreshCatalog = mutableSetOf<Identifier>()
    private val resumablePreviews = mutableSetOf<Identifier>()
    private val outgoing = Channel<DiscoveryCommand>(Channel.BUFFERED)
    val commands = outgoing.receiveAsFlow()
    private val pageId = saved.get<String>("$key.session") ?: UUID.randomUUID().toString().also { saved["$key.session"] = it }
    private var environment = DiscoveryEnvironment()

    init {
        viewModelScope.launch {
            combine(registry.restoredSources, accounts.changes, browsing.scope) { sources, generations, scope ->
                Triple(discoverySources(sources, capability), generations, scope)
            }.collect { (available, generations, scope) ->
                // Queued inventory/scope emissions must use the latest user selection.
                val shared = browsing.selections.value[scope]
                val explicit = available.find { it.metadata.id == explicitRequest }
                if (explicit == null) clearExplicitRequest()
                if (explicit != null && scope != null && explicit.metadata.category != scope) {
                    browsing.selectScope(explicit.metadata.category)
                    return@collect
                }
                val sources = available.filter { scope == null || it.metadata.category == scope }
                val ids = sources.map { it.metadata.id }.toSet()
                val previous = state.value
                val scopeChanged = previous.loadingSources || previous.scope != scope
                val restored = if (scopeChanged || previous.selected == null) browsing.selected(key, scope) else null
                val requested = explicit?.metadata?.id ?: shared?.takeIf { it in ids } ?: restored?.takeIf { it in ids }
                    ?: previous.selected?.takeIf { it in ids }
                val oldIndex = previous.sources.indexOfFirst { it.metadata.id == previous.selected }
                val neighbor = if (requested == null && !scopeChanged && oldIndex >= 0) {
                    (previous.sources.drop(oldIndex + 1) + previous.sources.take(oldIndex).asReversed())
                        .firstOrNull { it.metadata.id in ids }?.metadata?.id
                } else null
                val selected = requested ?: neighbor ?: sources.firstOrNull()?.metadata?.id
                // Scope changes must not retire other sources' cached content, drafts or sessions.
                val next = available.associate { it.metadata.id to it.version(generations) }
                val changed = selected != state.value.selected || versions[selected] != next[selected]
                if (changed) cancelLoad()
                // cancelLoad also clears loading flags; do not restore a snapshot captured before it.
                val content = state.value.content.filterKeys { it in next && versions[it] == next[it] }
                sessions.keys.retainAll(content.keys)
                recentPages.retainAll(content.keys)
                resumablePreviews.retainAll(content.keys)
                refreshCatalog.retainAll(next.keys)
                versions = next
                mutableState.value = DiscoveryPageState(sources, selected, content, scope = scope, availableSources = available,
                    restorationFailed = registry.restoration.value == SourceRestoration.Failed)
                if (selected != null) remember(selected)
                if (explicit != null && explicit.metadata.id == selected) {
                    clearExplicitRequest()
                    browsing.selectSource(scope, explicit.metadata.id)
                }
                if (active) load()
            }
        }
        viewModelScope.launch {
            browsing.selections.collect { if (syncSelection() && active) load() }
        }
    }

    private val foreground = indi.renakoni.nextvol.data.web.ForegroundSourceRequest()

    fun setActive(value: Boolean, retainBrowser: Boolean = false) {
        active = value
        foreground.setActive(value, retainBrowser)
        if (value) {
            // A root can resume before the shared selection's collector has run.
            syncSelection()
            load()
        } else if (!retainBrowser || !foreground.verifying) cancelLoad(retainBrowser)
    }

    fun select(id: Identifier) {
        if (updateSelection(id, publish = true) && active) load()
    }

    private fun syncSelection(): Boolean {
        if (explicitRequest != null || state.value.scope != browsing.scope.value) return false
        return browsing.selections.value[state.value.scope]?.let { updateSelection(it) } ?: false
    }

    private fun updateSelection(id: Identifier, publish: Boolean = false): Boolean {
        if (state.value.sources.none { it.metadata.id == id }) return false
        if (publish) {
            clearExplicitRequest()
            browsing.selectSource(state.value.scope, id)
        }
        if (id == state.value.selected) return false
        cancelLoad()
        remember(id)
        mutableState.value = state.value.copy(selected = id)
        return true
    }

    fun selectScope(category: SourceCategory?) {
        clearExplicitRequest()
        browsing.selectScope(category)
    }

    fun selectPage(page: Int) {
        if (page !in 0 until state.value.pageCount) return
        val sources = state.value.sources.drop(page * SOURCE_PAGE_SIZE).take(SOURCE_PAGE_SIZE)
        val remembered = pageSelections[state.value.scope to page]
        select(selectedSource(sources, remembered) ?: return)
    }

    /** Explicit routes can reveal a source outside the shared scope; bottom-tab visits never do. */
    fun openSource(id: Identifier) {
        val source = state.value.availableSources.find { it.metadata.id == id } ?: return
        if (state.value.scope == null || source.metadata.category == state.value.scope) select(id)
        else {
            cancelLoad()
            explicitRequest = id
            browsing.selectScope(source.metadata.category)
        }
    }

    fun refresh() {
        val id = state.value.selected ?: return
        indi.renakoni.nextvol.data.image.SourceImageRetryEvents.request(id)
        cancelLoad()
        refreshCatalog += id
        // A completed page remains useful while its replacement is loading.
        // Interrupted refreshes still discard their partial snapshot.
        if (state.value.content[id]?.loaded == true) resumablePreviews += id else resumablePreviews -= id
        put(id, (state.value.content[id] ?: newContent()).copy(loaded = false, loading = false, error = null))
        if (active) load()
    }

    fun retryPreview(section: SourceDiscoverySection) {
        val source = state.value.selected ?: return
        val page = state.value.content[source] ?: return
        val discovery = sessions[source] ?: return
        if (!active || page.acting || !section.previewRetryAvailable || section.previewLoading ||
            section.previewFailure == null || section !in page.sections || section.id in previewRetries) return
        val token = serial
        fun replace(value: SourceDiscoverySection) {
            previewOverrides[section.id] = value
            state.value.content[source]?.let { current ->
                put(source, current.copy(sections = current.sections.map { if (it.id == section.id) value else it }))
            }
        }
        replace(section.copy(previewLoading = true, previewFailure = null, diagnosticFailure = null, httpStatus = null))
        val job = viewModelScope.launch(foreground, start = CoroutineStart.LAZY) {
            val result = discoveryRequest { preview(discovery, section.id) }
            if (serial != token || state.value.selected != source) return@launch
            replace(result.getOrElse { section.copy(previewLoading = false,
                previewFailure = DiscoveryPreviewFailure(it), diagnosticFailure = null, httpStatus = null) })
            previewRetries.remove(section.id)
        }
        previewRetries[section.id] = section to job
        job.start()
    }

    fun environment(value: DiscoveryEnvironment) {
        if (environment == value) return
        environment = value
        cancelLoad()
        sessions.clear()
        resumablePreviews.clear()
        mutableState.value = state.value.copy(content = state.value.content.mapValues { (_, page) -> page.copy(loaded = false, error = null) })
    }

    fun accepts(command: DiscoveryCommand) = active && state.value.selected == command.source && command.epoch == actionEpoch

    fun interact(id: String, value: String? = null, longClick: Boolean = false) {
        val source = state.value.selected ?: return
        val previous = state.value.content[source] ?: return
        val discovery = sessions[source] ?: return
        if (!active || previous.loading || previous.acting) return
        cancelLoad()
        resumablePreviews -= source
        val token = ++serial
        val epoch = actionEpoch
        put(source, previous.copy(acting = true, error = null))
        pending = viewModelScope.launch(foreground) {
            val result = discoveryRequest { discovery.interact(id, value, longClick) }
            if (serial != token || !active || state.value.selected != source) return@launch
            result.onErr { put(source, state.value.content.getValue(source).copy(acting = false, error = it, errorField = discovery.failureField, errorPermission = discovery.permissionFailure, errorDiagnostic = discovery.diagnosticFailure, errorHttpStatus = discovery.httpStatus)) }
                .onOk { update ->
                    put(source, applyCatalog(state.value.content.getValue(source), update.catalog))
                    // Record invalidation before an emitted action can stop/cancel this page's work.
                    if (update.refresh || capability == SourceCapability.Explore) {
                        if (update.refresh) refreshCatalog += source
                        put(source, state.value.content.getValue(source).copy(loaded = false))
                    }
                    update.actions.forEach { outgoing.send(DiscoveryCommand(source, epoch, it, update.catalog.values)) }
                    if ((update.refresh || capability == SourceCapability.Explore) && active) load()
                }
        }
    }

    fun openBrowser(command: DiscoveryCommand) {
        if (!accepts(command)) return
        val action = command.action as? DiscoveryAction.Browser ?: return
        val discovery = sessions[command.source] ?: return
        resumablePreviews -= command.source
        // A queued catalogue refresh must not race the browser's page/session ownership.
        serial++
        pending?.cancel()
        pending = null
        cancelPreviews()
        val token = Any()
        browserToken = token
        browser?.cancel()
        put(command.source, state.value.content.getValue(command.source).copy(loading = false, acting = true, error = null, errorField = null, errorPermission = null, errorDiagnostic = null, errorHttpStatus = null))
        browser = viewModelScope.launch {
            val result = discoveryRequest { discovery.openBrowser(action) }
            // Its Activity covers this destination. Completion may precede onStart, after UI
            // command epochs have expired; only this captured browser request may finish the handoff.
            if (browserToken !== token) return@launch
            browserToken = null
            browser = null
            result.onErr { put(command.source, state.value.content.getValue(command.source).copy(acting = false, error = it, errorField = discovery.failureField, errorPermission = discovery.permissionFailure, errorDiagnostic = discovery.diagnosticFailure, errorHttpStatus = discovery.httpStatus)) }
                .onOk { refresh() }
        }
    }

    fun scroll(id: Identifier, position: DiscoveryScroll) {
        state.value.content[id]?.let { put(id, it.copy(scroll = position)) }
    }

    fun result(category: SourceDiscoveryCategory): Route.Main.DiscoveryResults? {
        val id = state.value.selected ?: return null
        if (category.target.sourceId != id || category.target.target.isBlank() || category !in state.value.content[id]?.categories.orEmpty()) return null
        // Rule input IDs equal the original form title/infoMap key, not viewName or URL-row digests.
        // filtersJson relies on this to seed both result filter normalization and the page-local draft.
        return Route.Main.DiscoveryResults(id.namespace, id.id, category.target.target, category.title,
            UUID.randomUUID().toString(), category.id, Json.encodeToString(state.value.content[id]?.values.orEmpty()))
    }

    private fun remember(id: Identifier) {
        val scope = state.value.scope
        // Automatic fallbacks must not replace a shared source unsupported by this page.
        browsing.remember(key, scope, id)
        val page = state.value.sources.indexOfFirst { it.metadata.id == id } / SOURCE_PAGE_SIZE
        pageSelections[scope to page] = id
    }

    private fun clearExplicitRequest() {
        if (explicitRequest != null) {
            explicitRequest = null
            saved["$key.initialSourceHandled"] = true
        }
    }

    private fun cancelLoad(retainBrowser: Boolean = false) {
        serial++
        actionEpoch++
        pending?.cancel()
        pending = null
        cancelPreviews()
        if (!retainBrowser) {
            browserToken = null
            browser?.cancel()
            browser = null
        }
        state.value.selected?.let { id -> state.value.content[id]?.let { page ->
            if (page.loading && page.sections.any { it.previewFailure == null && it.books.isNotEmpty() })
                resumablePreviews += id
            put(id, page.copy(loading = false, acting = browserToken != null))
        } }
    }

    private fun cancelPreviews() {
        previewRetries.values.forEach { it.second.cancel() }
        state.value.selected?.let { id -> state.value.content[id]?.let { page ->
            put(id, page.copy(sections = page.sections.map { previewRetries[it.id]?.first ?: it }))
        } }
        previewRetries.clear()
        previewOverrides.clear()
    }

    private fun put(id: Identifier, value: DiscoveryPageContent) {
        mutableState.value = state.value.copy(content = state.value.content + (id to value))
    }

    private fun applyCatalog(previous: DiscoveryPageContent, catalog: SourceDiscoveryCatalog) = previous.copy(
        categories = catalog.categories, filters = catalog.filters, values = catalog.values, buttons = catalog.buttons,
        loaded = true, loading = false, acting = false, error = null, errorField = null, errorPermission = null, errorDiagnostic = null, errorHttpStatus = null)

    protected open fun feedUpdates(discovery: SourceDiscovery) = discovery.feedUpdates()
    protected open suspend fun preview(discovery: SourceDiscovery, id: String) = discovery.preview(id)

    private fun newContent() = DiscoveryPageContent(resetId = ++contentId)

    /** Old pages shed rebuildable content; drafts and positions survive the next session. */
    private fun retainPage(id: Identifier) {
        recentPages.remove(id)
        recentPages.add(id)
        while (recentPages.size > MAX_RETAINED_SOURCE_PAGES) {
            val oldest = recentPages.first()
            recentPages.remove(oldest)
            sessions.remove(oldest)
            resumablePreviews.remove(oldest)
            state.value.content[oldest]?.let { page ->
                put(oldest, DiscoveryPageContent(values = page.values, scroll = page.scroll, resetId = page.resetId))
            }
        }
    }

    private fun load() {
        // Scope changes are collected asynchronously; never start work for the previous scope.
        if (state.value.scope != browsing.scope.value) return
        val id = state.value.selected ?: return
        retainPage(id)
        val previous = state.value.content[id] ?: newContent()
        if (previous.loaded || previous.loading || previous.acting || previous.error != null) return
        val retained = if (resumablePreviews.remove(id))
            previous.sections.filter { it.books.isNotEmpty() }.associateBy { it.id }
            else emptyMap()
        val token = ++serial
        put(id, previous.copy(loading = true))
        pending = viewModelScope.launch(foreground) {
            val result = discoveryRequest {
                val discovery = sessions[id] ?: registry.discovery(id).getOrElse { return@discoveryRequest com.github.michaelbull.result.Err(it) }
                    .forSession(pageId, previous.values, environment).also { sessions[id] = it }
                var content = previous
                // Native feeds need no catalogue request. Rules declare their interactive catalogue.
                if (capability == SourceCapability.Categories || discovery.hasInteractions) {
                    val catalog = (if (capability == SourceCapability.Explore) discovery.homepageCatalog(id in refreshCatalog)
                        else discovery.catalog(id in refreshCatalog)).getOrElse { return@discoveryRequest Err(it) }
                    content = applyCatalog(content, catalog)
                }
                if (capability == SourceCapability.Explore) {
                    var failure: DiscoveryError? = null
                    feedUpdates(discovery).collect { update ->
                        val sections = update.getOrElse { failure = it; return@collect }
                        content = content.copy(sections = sections.map { section ->
                            previewOverrides[section.id] ?: retained[section.id]?.takeIf {
                                (section.previewLoading || section.previewFailure != null) && section.books.isEmpty() && content.values == previous.values &&
                                    it.title == section.title && it.more == section.more && it.categoryId == section.categoryId
                            }?.let { section.copy(books = it.books) } ?: section
                        })
                        if (serial == token && state.value.selected == id) {
                            put(id, content.copy(loaded = false, loading = true,
                                scroll = state.value.content[id]?.scroll ?: content.scroll))
                        }
                    }
                    failure?.let { return@discoveryRequest Err(it) }
                }
                Ok(content.copy(loaded = true, loading = false, acting = false, error = null, errorField = null, errorPermission = null, errorDiagnostic = null, errorHttpStatus = null))
            }
            // A retained verification window may fail before this page resumes. Its result
            // still belongs here; navigation/cancellation already invalidate serial.
            if (serial != token || state.value.selected != id) return@launch
            val current = state.value.content[id] ?: previous
            result.onOk { page -> refreshCatalog -= id; put(id, page.copy(scroll = current.scroll,
                sections = page.sections.map { previewOverrides[it.id] ?: it })) }
                .onErr { put(id, current.copy(error = it, loading = false, errorField = sessions[id]?.failureField, errorPermission = sessions[id]?.permissionFailure, errorDiagnostic = sessions[id]?.diagnosticFailure, errorHttpStatus = sessions[id]?.httpStatus)) }
        }
    }
}
