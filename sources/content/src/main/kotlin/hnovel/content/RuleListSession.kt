package hnovel.content

import hnovel.execution.ScriptMemory
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class RuleListPage(val books: List<RuleBook>, val nextCursor: String?, val nextPage: Int?,
    val freshBooks: List<RuleBook> = books)
internal data class RuleListResult(val books: List<RuleBook>, val url: String, val nextUrl: String?, val httpErrorStatus: Int? = null)

/** One query/filter snapshot. Remote URLs stay opaque to callers and never become source settings. */
class RuleListSession internal constructor(private val field: String, private val explicit: Boolean,
    private val trace: ContentTrace,
    private val validate: () -> Unit = {},
    private val load: suspend (Int, String?, ScriptMemory) -> RuleListResult) {
    private val mutex = Mutex()
    private var memory = ScriptMemory()
    private var nextPage: Int? = 1
    private var nextUrl: String? = null
    private var previousPage: Int? = null
    private var previousCursor: String? = null
    private var previousResult: RuleListPage? = null
    private val urls = mutableSetOf<String>()
    private val books = mutableSetOf<String>()

    suspend fun page(cursor: String? = null): RuleListPage = mutex.withLock {
        currentCoroutineContext().ensureActive()
        validate()
        if (previousResult != null && cursor == previousCursor) return@withLock previousResult!!
        val number = when {
            cursor == null -> 1
            explicit && cursor == nextUrl -> nextPage
            !explicit -> cursor.toIntOrNull()
            else -> null
        } ?: throw SourceContentException(ContentError.InvalidRule, "$field.page")
        read(number, cursor)
    }

    /** The host search API retains page numbers; this session owns their remote continuations. */
    suspend fun page(number: Int): RuleListPage = mutex.withLock {
        currentCoroutineContext().ensureActive()
        validate()
        if (previousPage == number) return@withLock previousResult!!
        read(number, if (explicit) nextUrl else number.takeUnless { it == 1 }?.toString())
    }

    private suspend fun read(number: Int, cursor: String?): RuleListPage {
        if (number < 1) throw SourceContentException(ContentError.InvalidRule, "$field.page")
        if (nextPage == null || (explicit || previousPage != null) && number != nextPage)
            throw SourceContentException(ContentError.InvalidRule, "$field.page")
        val draft = memory.copy()
        val result = load(number, if (explicit) nextUrl else null, draft)
        val repeatedBooks = result.books.isNotEmpty() && result.books.all { it.id in books }
        val repeatedCursor = explicit && result.nextUrl != null && (result.nextUrl == result.url || result.nextUrl in urls)
        val more = if (explicit) result.nextUrl != null else result.books.isNotEmpty()
        // An HTTP rejection cannot establish the end of a list, even if its continuation loops.
        if (result.books.isEmpty() && result.httpErrorStatus != null && (!more || repeatedCursor))
            throw SourceContentException(ContentError.Network, "$field.bookList", httpStatus = result.httpErrorStatus)
        val reason = when {
            repeatedCursor -> "RepeatedCursor"
            repeatedBooks -> "RepeatedBooks"
            !more -> "End"
            result.books.isEmpty() -> "FilteredEmpty"
            else -> "Continue"
        }
        trace.record(ContentTraceEvent("pagination", field, 0, outputSize = result.books.size, result = reason))
        memory = draft
        // Host-side filtering must not turn new hidden rows into an apparent repeated page.
        val freshBooks = result.books.filterNot { it.id in books }
        books += result.books.map { it.id }
        urls += result.url
        nextPage = (number + 1).takeIf { number < Int.MAX_VALUE && more && !repeatedBooks && !repeatedCursor }
        nextUrl = result.nextUrl.takeIf { nextPage != null }
        previousPage = number
        previousCursor = cursor
        return RuleListPage(result.books, if (explicit) nextUrl else nextPage?.toString(), nextPage, freshBooks)
            .also { previousResult = it }
    }
}
