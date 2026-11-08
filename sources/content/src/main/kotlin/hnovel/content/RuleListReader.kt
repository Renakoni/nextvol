package hnovel.content

import hnovel.execution.*
import hnovel.rules.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** List parsing and capability caches belong to one source revision/account and its existing operation boundary. */
internal class RuleListReader(
    private val spec: RuleSourceDefinition,
    private val identity: ExecutionIdentity,
    private val authority: ExecutionAuthority,
    private val store: RuleBookStore,
    private val trace: ContentTrace,
    private val evaluation: () -> RuleEvaluation,
    private val saveInformation: suspend (String, BookRecord, PageDocument) -> BookRecord,
    private val operation: suspend (String, suspend () -> List<RuleBook>) -> List<RuleBook>,
) {
    private val bookUrlRule = ":\\A(?:${spec.bookUrlPattern})\\z"
    private var parallelPreviewParsing: Boolean? = null
    private var searchBatchPlan: Boolean? = null

    suspend fun canParsePreviewsConcurrently(): Boolean {
        parallelPreviewParsing?.let { return it }
        val result = spec.library == null && spec.loginCheck.isBlank() &&
            spec.explore.string("bookList").isNotBlank() && canDeferBookFields(spec.explore) && try {
                evaluation().discoveryReadPlan(listOf("/"), "", listOf(
                    spec.explore.string("bookList").removePrefix("-").removePrefix("+"),
                    spec.explore.string("name"), spec.explore.string("bookUrl"), spec.explore.string("nextPageUrl"),
                    bookUrlRule.takeIf { spec.bookUrlPattern.isNotBlank() }.orEmpty()))
            } catch (failure: SourceContentException) {
                if (failure.code == ContentError.Unavailable) throw failure
                false
            }
        parallelPreviewParsing = result
        return result
    }

    // Fixed row selectors are independent. Dynamic rules may write variables used by later rows or URLs.
    fun canDeferBookFields(fields: JsonObject) = listOf("name", "author", "kind", "wordCount",
        "lastChapter", "intro", "coverUrl", "updateTime", "bookUrl").all { name ->
        ExecutionTask.BookOverviews.supports(fields.string(name))
    }

    private suspend fun canBatchSearch(context: RuleEvaluation): Boolean {
        searchBatchPlan?.let { return it }
        val static = spec.library == null && spec.loginUrl.isBlank() && spec.loginUi.isBlank() && spec.loginCheck.isBlank() &&
            spec.search.values.all { (it as? JsonPrimitive)?.contentOrNull?.let(ExecutionTask.BookOverviews::supports) == true }
        return (static && context.discoveryReadPlan(listOf(spec.searchUrl), spec.header,
            spec.search.keys.map(spec.search::string), "search.readPlan")).also { searchBatchPlan = it }
    }

    suspend fun booksFromPage(context: RuleEvaluation, document: PageDocument, fields: JsonObject,
        field: String, overview: Boolean = false, previewLimit: Int? = null): List<RuleBook> {
        val rule = fields.string("bookList")
        val isBookUrl = spec.bookUrlPattern.isNotBlank() && context.value(bookUrlRule,
            RuleValue.Text(document.url), "bookUrlPattern", OutputKind.Elements).items().isNotEmpty()
        if (rule.isBlank() || isBookUrl) {
            if (context.readOnly) {
                // A list can redirect to a detail page with stateful information rules. Only an
                // actual match re-enters the original serialized path; other lists stay independent.
                trace.record(ContentTraceEvent("concurrency", field, 0, result = "SerialDetailFallback"))
                return operation(field) {
                    context.readOnly = false
                    try { booksFromPage(context, document, fields, field, overview, previewLimit) }
                    finally { context.readOnly = true }
                }
            }
            val id = sourceLink(document.url, document.url)
            if (overview && spec.information.string("init").isBlank() && canDeferBookFields(spec.information)) {
                val row = context.fork(bookId = id)
                row.bookField("bookUrl", id)
                val book = bookFields(row, document.input(), spec.information, "ruleBookInfo", RuleBook(id), overview = true)
                if (book.title.isBlank()) throw SourceContentException(ContentError.EmptyContent, "ruleBookInfo.name")
                val old = store.read(id)
                if (old?.informationLoaded != true || old.revision != identity.revision) {
                    val record = BookRecord(identity.revision, book.preservingValidMetadata(old?.book))
                    if (record != old) store.write(record)
                }
                return listOf(book)
            }
            val seed = RuleBook(id, state = context.book).preservingValidMetadata(store.read(id)?.book)
            val record = saveInformation(id, BookRecord(identity.revision, seed), document)
            return listOf(record.book)
        }
        val items = responseRule(document.httpErrorStatus) {
            context.value(rule.removePrefix("-").removePrefix("+"), document.input(), "$field.bookList", OutputKind.Elements).items()
        }
        val lightweight = overview && canDeferBookFields(fields)
        val batchSearch = field == "ruleSearch" && !overview && canBatchSearch(context)
        val searchRules = if (batchSearch) ExecutionTask.SearchBooks.FIELDS.associateWith(fields::string) else emptyMap()
        val books = mutableListOf<RuleBook>()
        val previews = mutableMapOf<String, BookPreview>()
        val candidates = if (lightweight && rule.startsWith('-')) items.asReversed() else items
        var batchStart = 0
        var batch = emptyList<Triple<String, String, String>>()
        var searchBatch = emptyList<Map<String, String>>()
        for ((index, item) in candidates.withIndex()) {
            val row = context.fork()
            if (batchSearch && index >= batchStart + searchBatch.size) {
                batchStart = index
                searchBatch = context.searchBooks(candidates.subList(index,
                    minOf(index + ExecutionTask.SearchBooks.MAX_ROWS, candidates.size)), searchRules)
            }
            val extracted = if (batchSearch) searchBatch[index - batchStart] else null
            if (lightweight && index >= batchStart + batch.size) {
                batchStart = index
                val count = minOf(ExecutionTask.BookOverviews.MAX_ROWS, previewLimit?.minus(books.size) ?: Int.MAX_VALUE,
                    candidates.size - index)
                batch = context.overviews(candidates.subList(index, index + count), fields.string("name"),
                    fields.string("bookUrl"), fields.string("coverUrl"), field)
            }
            val parsed = if (lightweight) {
                val title = batch[index - batchStart].first
                row.bookField("name", title)
                val cover = try {
                    batch[index - batchStart].third.let { if (it.isBlank()) "" else sourceLink(context.baseUrl, it) }
                } catch (failure: SourceContentException) {
                    if (failure.code != ContentError.InvalidRule) throw failure
                    ""
                }
                row.bookField("coverUrl", cover)
                RuleBook("", title = title, coverUrl = cover, state = row.book)
            } else bookFields(row, item, fields, field, RuleBook(""), extracted = extracted)
            if (parsed.title.isBlank()) continue
            val rawUrl = if (lightweight) batch[index - batchStart].second
                else extracted?.getValue("bookUrl") ?: row.url(fields.string("bookUrl"), item, "$field.bookUrl")
            val id = sourceLink(document.url, rawUrl.ifBlank { document.url })
            if (lightweight && id in previews) continue
            row.bookId = id; row.bookField("bookUrl", id)
            books += parsed.copy(id = id, tocUrl = id, state = row.book)
            if (lightweight) {
                previews[id] = BookPreview(item, context.baseUrl, identity.accountGeneration)
                if (previewLimit != null && books.size >= previewLimit) break
            }
        }
        val ordered = (if (!lightweight && rule.startsWith('-')) books.reversed() else books).distinctBy { it.id }
        // Parallel previews may finish after a full information load. Check and commit together
        // so a late preview cannot replace that richer record or write after source retirement.
        currentCoroutineContext().ensureActive()
        authority.authorized(identity) {
            store.write(ordered.mapNotNull { book ->
                val old = store.read(book.id)
                if (old?.informationLoaded != true || old.revision != identity.revision)
                    BookRecord(identity.revision, book.preservingValidMetadata(old?.book), preview = previews[book.id])
                        .takeUnless { it == old } else null
            })
        }
        return ordered
    }
}
