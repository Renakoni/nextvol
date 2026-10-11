package indi.renakoni.nextvol.defaultplugin.wenku8.search

import indi.renakoni.nextvol.data.bangumi.BangumiRelatedSubject
import indi.renakoni.nextvol.data.bangumi.BangumiSubject
import indi.renakoni.nextvol.data.explore.SearchEvidence
import indi.renakoni.nextvol.data.explore.SearchRanking
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

internal data class SearchExpansion(
    val matches: List<Pair<Wenku8SearchEntry, Int>> = emptyList(),
    val names: List<String> = emptyList(), val hasMore: Boolean = false,
    val evidence: Map<String, SearchEvidence> = emptyMap(),
)

@Singleton
class BangumiSearchExpansion @Inject constructor(private val api: BangumiSearchApi) {
    @OptIn(ExperimentalCoroutinesApi::class)
    internal fun search(keyword: String, page: Int, catalog: List<Wenku8SearchEntry>): Flow<SearchExpansion> = channelFlow {
        val index = catalog.flatMap { entry -> entry.names.map { it to entry } }.groupBy({ it.first }, { it.second })
        val query = Wenku8SearchText.key(keyword)
        val normalized = SearchRanking.normalize(keyword)
        val relations = mutableMapOf<Int, Deferred<List<BangumiRelatedSubject>>>()
        val resolved = ConcurrentHashMap.newKeySet<Int>()
        val fallback = linkedMapOf<Int, String>()
        var hasMore = false
        fun searchPage(tag: Boolean) = flow {
            try {
                val result = api.search(keyword, (page - 1) * 10, tag)
                emit(tag to result)
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
            }
        }
        data class Candidate(val id: Int, val names: List<String>, val depth: Int, val root: Int, val score: Int,
            val evidence: SearchEvidence)
        withTimeoutOrNull(16_000) {
            suspend fun related(id: Int): List<BangumiRelatedSubject> {
                // Both channels share in-flight reads and the six-request budget; never await under the lock.
                val request = synchronized(relations) {
                    relations[id] ?: if (relations.size >= 6) null else async {
                        try { api.related(id) }
                        catch (error: Exception) { currentCoroutineContext().ensureActive(); emptyList() }
                    }.also { relations[id] = it }
                }
                return request?.await().orEmpty()
            }
            merge(searchPage(false), searchPage(true)).collect { (tag, result) ->
                hasMore = hasMore || (page * 10 < result.total && result.data.isNotEmpty())
                val queue = ArrayDeque<Candidate>()
                val expanded = mutableSetOf<Int>()
                // Resolve single volumes before walking unrelated manga/series relationships.
                val ordered = result.data.sortedWith(compareByDescending<BangumiSubject> { it.isNovel }
                    .thenBy { it.series })
                suspend fun match(candidate: Candidate) {
                    val books = candidate.names.flatMap { index[Wenku8SearchText.key(it)].orEmpty() }.distinctBy { it.id }
                    if (books.isNotEmpty()) {
                        resolved += candidate.root
                        send(SearchExpansion(matches = books.map { it to candidate.score },
                            evidence = books.associate { book ->
                                val exactMapping = (Wenku8SearchText.names(book.title) + book.aliases).any { name ->
                                    candidate.names.any { SearchRanking.normalize(it) == SearchRanking.normalize(name) }
                                }
                                book.id to if (candidate.evidence == SearchEvidence.VerifiedAlias && !exactMapping)
                                    SearchEvidence.Loose else candidate.evidence
                            }))
                    }
                }
                for (subject in ordered) {
                    val names = (listOf(subject.nameCn, subject.name) + subject.values("别名")).filter(String::isNotBlank).distinct()
                    val exact = names.any { Wenku8SearchText.key(it) == query }
                    val verified = names.any { SearchRanking.normalize(it) == normalized }
                    val candidate = Candidate(subject.id, names, 0, subject.id, if (exact) 5 else if (tag) 10 else 30,
                        if (verified) SearchEvidence.VerifiedAlias else if (tag) SearchEvidence.Tag else SearchEvidence.Loose)
                    match(candidate)
                    if (subject.isNovel && subject.series && subject.nameCn.isNotBlank() && (tag || exact))
                        fallback[subject.id] = subject.nameCn
                    if ((tag && subject.isNovel) || exact || tag && ordered.none { it.isNovel }) queue += candidate
                }
                // Keep accepting the other channel while this one walks optional relationships.
                launch { while (queue.isNotEmpty()) {
                    currentCoroutineContext().ensureActive()
                    val candidate = queue.removeFirst()
                    if (candidate.depth >= 2 || !expanded.add(candidate.id)) continue
                    val children = related(candidate.id).filter { it.type == 1 && it.relation in setOf("系列", "前传", "续集", "书籍") }
                        .sortedBy { if (it.relation == "系列") 0 else if (it.relation == "续集") 1 else 2 }
                        .map { Candidate(it.id, listOf(it.nameCn, it.name).filter(String::isNotBlank),
                            candidate.depth + 1, candidate.root, candidate.score + 1, SearchEvidence.Related) }
                    children.forEach { match(it) }
                    children.asReversed().forEach { queue.addFirst(it) }
                } }
            }
        }
        send(SearchExpansion(names = fallback.filterKeys { it !in resolved }.values.distinct().take(2),
            hasMore = hasMore))
    }.flowOn(Dispatchers.IO)
}
