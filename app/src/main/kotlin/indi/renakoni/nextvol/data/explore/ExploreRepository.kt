package indi.renakoni.nextvol.data.explore

import com.github.michaelbull.result.*
import hnovel.content.ContentError
import hnovel.content.SourceContentException
import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.data.book.bind
import indi.renakoni.nextvol.data.web.*
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.discovery.DiscoveryError
import io.nightfish.lightnovelreader.api.web.discovery.DiscoveryPermission
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import io.nightfish.lightnovelreader.api.web.search.SearchType
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExploreRepository @Inject constructor(private val registry: WebSourceRegistry) {
    suspend fun open(id: Identifier): Result<SourceSearch, SourceSearchFailure> {
        val metadata = registry.sources.value.firstOrNull { it.metadata.id == id }?.metadata
            ?: return Err(SourceSearchFailure(DiscoveryError.Unavailable))
        if (SourceCapability.Search !in metadata.capabilities) return Err(SourceSearchFailure(DiscoveryError.Unsupported))
        return try {
            when (val resolution = registry.resolve(id)) {
                is SourceResolution.Ready -> resolution.runtime.execute {
                    val runtime = resolution.runtime
                    if (SourceCapability.Search !in runtime.metadata.capabilities) Err(SourceSearchFailure(DiscoveryError.Unsupported))
                    else runtime.search.searchTypes.let { types ->
                        if (types.isEmpty()) Err(SourceSearchFailure(DiscoveryError.Unsupported)) else Ok(SourceSearch(runtime, types))
                    }
                }
                is SourceResolution.Missing -> Err(SourceSearchFailure(DiscoveryError.Unavailable))
                is SourceResolution.Unavailable -> Err(SourceSearchFailure(DiscoveryError.InvalidRules))
            }
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            Err(searchFailure(failure))
        }
    }
}

/** Captures one source generation; history supplies text and never chooses the source. */
class SourceSearch internal constructor(private val runtime: SourceRuntime, val types: List<SearchType>) {
    internal val hasPages get() = runtime.hasSearchPages

    internal fun page(type: SearchType, keyword: String, page: Int, query: String? = null) = runtime.searchPage(type, keyword, page, query).map { result ->
        result.copy(books = result.books.map { item ->
            val book = SourceBookId(runtime.id, item.bookId)
            SearchResult.MultipleBook(book.storageKey, item.information?.takeIf { it.id == item.bookId }?.let(book::bind))
        }, scores = result.scores.mapKeys { (id, _) -> SourceBookId(runtime.id, id).storageKey },
            previewIds = result.previewIds.mapTo(hashSetOf()) { SourceBookId(runtime.id, it).storageKey },
            evidence = result.evidence.mapKeys { (id, _) -> SourceBookId(runtime.id, id).storageKey })
    }

    fun search(type: SearchType, keyword: String) = runtime.search.search(type, keyword).map { result ->
        when (result) {
            is SearchResult.SingleBook -> SearchResult.SingleBook(SourceBookId(runtime.id, result.bookId).storageKey)
            is SearchResult.MultipleBook -> SourceBookId(runtime.id, result.bookId).let { book ->
                SearchResult.MultipleBook(book.storageKey, result.information?.takeIf { it.id == result.bookId }?.let(book::bind))
            }
            else -> result
        }
    }

    suspend fun suggestions(history: List<String>, keyword: String) = runtime.execute {
        runtime.search.getSearchSuggestions(history, keyword)
    }
}

data class SourceSearchFailure(val error: DiscoveryError, val field: String? = null, val permission: DiscoveryPermission? = null,
    val diagnostic: hnovel.execution.ExecutionResult.Failure? = null, val httpStatus: Int? = null)

/** Render typed failures without displaying exception messages, URLs or source credentials. */
internal fun searchFailure(failure: Throwable): SourceSearchFailure = when (failure) {
    is SourceRequestException -> SourceSearchFailure(failure.error,
        permission = failure.denial?.let { DiscoveryPermission(it.origin, it.kind.name) })
    is SourceContentException -> SourceSearchFailure(when (failure.code) {
        ContentError.MissingCapability -> DiscoveryError.Unsupported
        ContentError.LoginRequired -> DiscoveryError.AuthenticationRequired
        ContentError.BrowserRequired -> if (failure.verification?.kind == hnovel.network.BrowserChallengeKind.Login)
            DiscoveryError.AuthenticationRequired else DiscoveryError.VerificationRequired
        ContentError.PermissionDenied -> DiscoveryError.PermissionDenied
        ContentError.AddressDenied -> DiscoveryError.AddressDenied
        ContentError.Dns -> DiscoveryError.Dns
        ContentError.RouteUnavailable -> DiscoveryError.RouteUnavailable
        ContentError.RouteUnsupported -> DiscoveryError.RouteUnsupported
        ContentError.Certificate -> DiscoveryError.Certificate
        ContentError.Network -> DiscoveryError.Network
        ContentError.Limit -> DiscoveryError.Limit
        ContentError.Unavailable -> DiscoveryError.Unavailable
        else -> DiscoveryError.InvalidRules
    }, failure.field, failure.denial?.let { DiscoveryPermission(it.origin, it.kind.name) }, failure.diagnostic, failure.httpStatus)
    is java.io.IOException -> SourceSearchFailure(DiscoveryError.Network)
    else -> SourceSearchFailure(DiscoveryError.Unavailable)
}
