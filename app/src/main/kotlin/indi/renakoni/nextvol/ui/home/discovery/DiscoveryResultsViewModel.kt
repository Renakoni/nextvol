package indi.renakoni.nextvol.ui.home.discovery

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.github.michaelbull.result.*
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.renakoni.nextvol.data.web.*
import io.nightfish.lightnovelreader.api.Route
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.discovery.DiscoveryError
import io.nightfish.lightnovelreader.api.web.discovery.DiscoveryPermission
import io.nightfish.lightnovelreader.api.web.discovery.DiscoveryFilter
import io.nightfish.lightnovelreader.api.web.discovery.DiscoveryEnvironment
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject

data class DiscoveryResultsState(
    val title: String,
    val sourceName: String = "",
    val books: List<SourceDiscoveryBook> = emptyList(),
    val definitions: List<DiscoveryFilter> = emptyList(),
    val filters: Map<String, String> = emptyMap(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val hasMore: Boolean = false,
    val error: DiscoveryError? = null,
    val scroll: DiscoveryScroll = DiscoveryScroll(),
    val resetId: Long = 0,
    val errorField: String? = null,
    val errorPermission: DiscoveryPermission? = null,
    val errorDiagnostic: hnovel.execution.ExecutionResult.Failure? = null,
    val errorHttpStatus: Int? = null,
)

/** One ViewModel per navigation entry. Only lightweight identity/filter values survive process death. */
@HiltViewModel
class DiscoveryResultsViewModel internal constructor(
    private val registry: WebSourceRegistry,
    accounts: SourceSessionManager,
    private val saved: SavedStateHandle,
    val route: Route.Main.DiscoveryResults,
) : ViewModel() {
    @Inject constructor(registry: WebSourceRegistry, accounts: SourceSessionManager, saved: SavedStateHandle) :
        this(registry, accounts, saved, saved.toRoute<Route.Main.DiscoveryResults>())

    val sourceId = Identifier(route.namespace, route.sourceId)
    private val mutableState = MutableStateFlow(DiscoveryResultsState(route.title, filters =
        kotlin.runCatching { Json.decodeFromString<Map<String, String>>(saved["discovery.filters"] ?: route.filtersJson) }
            .getOrDefault(emptyMap())))
    val state = mutableState.asStateFlow()
    private var version: DiscoveryVersion? = null
    private var active = false
    private var session: DiscoverySession? = null
    private var pending: Job? = null
    private var serial = 0L
    private var environment = DiscoveryEnvironment()
    private var draftValues = emptyMap<String, String>()
    private var refreshCatalog = false

    init {
        viewModelScope.launch {
            // Application startup restores sources before any Activity. Missing here is not "still restoring".
            combine(registry.sources, accounts.changes) { sources, generations ->
                sources.firstOrNull { it.metadata.id == sourceId }?.version(generations)
            }.distinctUntilChanged().collect { next ->
                if (version != null) draftValues = emptyMap()
                version = next
                cancelLoad()
                session = null
                mutableState.value = DiscoveryResultsState(route.title,
                    sourceName = next?.metadata?.item?.name.orEmpty(), filters = state.value.filters,
                    resetId = state.value.resetId + 1,
                    error = if (next == null) DiscoveryError.Unavailable else null)
                if (active && next != null) loadMore()
            }
        }
    }

    private val foreground = indi.renakoni.nextvol.data.web.ForegroundSourceRequest()

    fun setActive(value: Boolean, retainBrowser: Boolean = false) {
        active = value
        foreground.setActive(value, retainBrowser)
        if (!value && (!retainBrowser || !foreground.verifying)) cancelLoad()
        else if (!state.value.loaded && state.value.error == null && version != null) loadMore()
    }

    fun refresh() {
        indi.renakoni.nextvol.data.image.SourceImageRetryEvents.request(sourceId)
        cancelLoad()
        session = null
        refreshCatalog = true
        // Keep visible cards alive so failed covers can consume the explicit recovery action.
        mutableState.value = state.value.copy(loaded = false, hasMore = false,
            error = null, scroll = DiscoveryScroll(), resetId = state.value.resetId + 1)
        if (active) loadMore()
    }

    fun environment(value: DiscoveryEnvironment) {
        if (environment == value) return
        environment = value
        cancelLoad()
        session = null
        mutableState.value = state.value.copy(loaded = false, error = null)
    }

    fun filter(values: Map<String, String>) {
        val next = filterValues(state.value.definitions, values)
        if (next == state.value.filters) return
        // Old books belong to the previous query, including while a new request fails or is cancelled.
        mutableState.value = state.value.copy(filters = next, books = emptyList())
        saveFilters(next)
        refresh()
    }

    fun scroll(position: DiscoveryScroll) { mutableState.value = state.value.copy(scroll = position) }

    private fun saveFilters(values: Map<String, String>) { saved["discovery.filters"] = Json.encodeToString(values) }

    private fun cancelLoad() {
        serial++
        pending?.cancel()
        pending = null
        mutableState.value = state.value.copy(loading = false)
    }

    fun loadMore() {
        if (!active || state.value.loading || (state.value.loaded && !state.value.hasMore)) return
        val token = ++serial
        mutableState.value = state.value.copy(loading = true, error = null)
        pending = viewModelScope.launch(foreground) {
            var failureField: String? = null
            var permissionFailure: DiscoveryPermission? = null
            var diagnosticFailure: hnovel.execution.ExecutionResult.Failure? = null
            var httpStatus: Int? = null
            val result = discoveryRequest {
                if (session == null) {
                    val source = registry.discovery(sourceId).getOrElse { return@discoveryRequest Err(it) }
                        .forSession(route.sessionId, draftValues + state.value.filters, environment)
                    var target = route.target
                    var catalogValues = emptyMap<String, String>()
                    // Source-search actions have no discovery filters. Catalogue JS would be unused
                    // and may persist infoMap; raw explore targets still need it even without a category ID.
                    if ((source.hasCategories || source.hasInteractions) && !target.startsWith(DISCOVERY_SEARCH_PREFIX)) {
                        val catalog = (if (route.categoryId == null) source.homepageCatalog(refreshCatalog) else source.catalog(refreshCatalog))
                            .getOrElse { failureField = source.failureField; permissionFailure = source.permissionFailure; diagnosticFailure = source.diagnosticFailure; httpStatus = source.httpStatus; return@discoveryRequest Err(it) }
                        if (token != serial) return@discoveryRequest Err(DiscoveryError.Unavailable)
                        refreshCatalog = false
                        catalogValues = catalog.values
                        if (route.categoryId != null) target = catalog.categories.singleOrNull { it.id == route.categoryId }
                            ?.target?.target?.takeIf(String::isNotBlank) ?: return@discoveryRequest Err(DiscoveryError.InvalidRequest)
                    }
                    val opened = source.open(SourceDiscoveryTarget(sourceId, target))
                    val values = filterValues(opened.filters, catalogValues + state.value.filters)
                    opened.reset(values)
                    if (token != serial) return@discoveryRequest Err(DiscoveryError.Unavailable)
                    session = opened
                    draftValues = catalogValues
                    saveFilters(values)
                    mutableState.value = state.value.copy(definitions = opened.filters, filters = values)
                }
                requireNotNull(session).loadMore().onErr { failureField = session?.failureField; permissionFailure = session?.permissionFailure; diagnosticFailure = session?.diagnosticFailure; httpStatus = session?.httpStatus }
            }
            if (token != serial || !active) return@launch
            result.onOk { mutableState.value = state.value.copy(books = it.books, loading = false,
                loaded = true, hasMore = it.nextCursor != null, errorField = null, errorPermission = null, errorDiagnostic = null, errorHttpStatus = null) }
                .onErr { mutableState.value = state.value.copy(loading = false, error = it, errorField = failureField, errorPermission = permissionFailure, errorDiagnostic = diagnosticFailure, errorHttpStatus = httpStatus) }
        }
    }
}
