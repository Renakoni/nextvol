package indi.renakoni.nextvol.data.explore

import io.nightfish.lightnovelreader.api.web.search.SearchResult
import io.nightfish.lightnovelreader.api.web.search.SearchType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Optional host adapter capability alongside the streaming SearchProvider contract. */
internal interface PagedSearchProvider {
    /** Pages of one user query share [query]; a new query uses a new value. */
    suspend fun searchPage(type: SearchType, keyword: String, page: Int, query: String? = null): SearchPage

    /** Incremental batches for one page; ordinary adapters still return one complete batch. */
    fun searchPageUpdates(type: SearchType, keyword: String, page: Int, query: String? = null): Flow<SearchPage> =
        flow { emit(searchPage(type, keyword, page, query)) }
}

data class SearchPage(
    val books: List<SearchResult.MultipleBook>, val nextPage: Int?,
    val complete: Boolean = true, val failure: Throwable? = null,
    /** Adapter-local recall order, retained for existing single-source consumers. */
    val scores: Map<String, Int> = emptyMap(),
    /** These items contain catalogue previews; the host should still load their full metadata. */
    val previewIds: Set<String> = emptySet(),
    val evidence: Map<String, SearchEvidence> = emptyMap(),
)
