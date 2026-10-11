package hnovel.content

import hnovel.execution.*
import hnovel.network.SourceSession
import hnovel.rules.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** One book/row evaluation. State is copied between rows and committed only after its stage succeeds. */
internal class RuleEvaluation(private val identity: ExecutionIdentity, private val authority: ExecutionAuthority,
    private val session: SourceSession, private val runner: RuleTaskRunner, private val library: String?,
    var bookId: String? = null, var chapterId: String? = null, var book: ScriptState = ScriptState(),
    var chapter: ScriptState = ScriptState(), var baseUrl: String, val keyword: String = "", var page: Int = 1,
    private val calls: java.util.concurrent.atomic.AtomicInteger = java.util.concurrent.atomic.AtomicInteger(),
    private val headerRule: String = "", private val interactive: Boolean = false, private val trace: ContentTrace = ContentTrace.None,
    private val sourceLoginUrl: String = "", private val sourceComment: String? = null,
    private val verification: (hnovel.network.BrokerResult.Failure) -> SourceVerification? = { null },
    private val maxRuleCalls: Int = 65536, private val sourceName: String = "", private val sourceLastUpdateTime: Long = 0,
    private val memory: ScriptMemory = ScriptMemory()) {
    var discovery: JsonObject? = null
    var requestUserAgent: String? = null
    var currentRequest: hnovel.network.BrokerRequest? = null
    var nextChapterUrl: String? = null
    var missingCacheRead = false
        private set
    var readOnly = false
    var onMessage: ((String) -> Unit)? = null
    // Human login/verification shares the enclosing login operation's five-minute budget.
    private val limits = ExecutionLimits(timeoutMillis = if (interactive) 300000 else 30000, maxOutputBytes = 196608,
        maxRequests = 64, maxDataBytes = 16 * 1024 * 1024)

    fun fork(bookId: String? = this.bookId, chapterId: String? = this.chapterId) =
        RuleEvaluation(identity, authority, session, runner, library, bookId, chapterId, book.copy(), chapter.copy(), baseUrl, keyword, page, calls, headerRule, interactive, trace, sourceLoginUrl, sourceComment, verification, maxRuleCalls, sourceName, sourceLastUpdateTime, memory)
            .also { it.discovery = discovery; it.nextChapterUrl = nextChapterUrl; it.requestUserAgent = requestUserAgent; it.readOnly = readOnly; it.onMessage = onMessage }

    suspend fun headers(): Map<String, String> {
        if (headerRule.isBlank()) return emptyMap()
        val marked = headerRule.trimStart().let { it.startsWith("@js:", true) || it.startsWith("<js>", true) }
        val text = if (!marked) headerRule else try { script(headerRule, RuleValue.Empty, "header").text() }
            catch (failure: SourceContentException) {
                if (failure.code !in setOf(ContentError.InvalidRule, ContentError.UnsupportedDependency)) throw failure
                return emptyMap()
            }
        val value = try { RequestOptionsJson.optionalHeaders(JsonPrimitive(text)) }
            catch (_: RequestOptionsException) { throw SourceContentException(ContentError.Limit, "header") }
        return value.mapValues { it.value.jsonPrimitive.content }
    }

    suspend fun value(rule: String, input: RuleValue, field: String, output: OutputKind = OutputKind.Text,
        unescape: Boolean = true, scriptTemplates: Boolean = true): RuleValue {
        val task = ExecutionTask.Rule(rule, input, output, RuleLocation(field), bookId, chapterId,
            keyword, page, baseUrl, library, book.inherited + chapter.inherited, book.variables,
            chapter.variables, book.metadata, chapter.metadata, book.bigVariables, chapter.bigVariables,
            unescapeHtml = unescape, sourceHeaderRule = if (field == "header") "" else headerRule, discovery = discovery,
            sourceLoginUrl = sourceLoginUrl, sourceComment = sourceComment, nextChapterUrl = nextChapterUrl,
            scriptTemplates = scriptTemplates, readOnly = readOnly)
        val executed = execute(task, field, input.toString().length)
        if (discovery != null) discovery = executed.discovery ?: throw SourceContentException(ContentError.InvalidRule, field)
        book = book.copy(metadata = executed.book ?: book.metadata,
            variables = applyWrites(book.variables, executed.bookWrites),
            bigVariables = applyWrites(book.bigVariables, executed.bookBigWrites),
            inherited = if (chapterId == null) book.inherited + executed.writes else book.inherited)
        chapter = chapter.copy(metadata = executed.chapter ?: chapter.metadata,
            variables = applyWrites(chapter.variables, executed.chapterWrites),
            bigVariables = applyWrites(chapter.bigVariables, executed.chapterBigWrites),
            inherited = if (chapterId != null) chapter.inherited + executed.writes else chapter.inherited)
        return executed.value
    }

    // This trusted task cannot run source code or mutate snapshots. It still uses the same identity,
    // cancellation, invocation count and trace boundary as rule evaluation.
    suspend fun markup(html: String): RuleValue =
        execute(ExecutionTask.ContentMarkup(html, formatted = true), "ruleContent.parts", html.length).value

    suspend fun overviews(inputs: List<RuleValue>, nameRule: String, urlRule: String, coverRule: String,
        field: String): List<Triple<String, String, String>> {
        // Charge the possible field evaluations, not just their shared IPC envelope.
        val count = inputs.size * listOf(nameRule, urlRule, coverRule).count { it.isNotBlank() }
        if (count > 1 && calls.addAndGet(count - 1) >= maxRuleCalls)
            throw SourceContentException(ContentError.Limit, "$field.overviews")
        val task = ExecutionTask.BookOverviews(inputs, nameRule, urlRule, baseUrl,
            book.metadata["name"]?.jsonPrimitive?.content.orEmpty(), field, coverRule)
        return execute(task, "$field.overviews", inputs.sumOf { it.toString().length }).value.items().map {
            val fields = it.items()
            Triple(fields[0].text(), fields[1].text(), fields[2].text())
        }
    }

    suspend fun searchBooks(inputs: List<RuleValue>, rules: Map<String, String>): List<Map<String, String>> {
        val count = inputs.size * rules.values.count { it.isNotBlank() }
        if (count > 1 && calls.addAndGet(count - 1) >= maxRuleCalls)
            throw SourceContentException(ContentError.Limit, "ruleSearch.books")
        val task = ExecutionTask.SearchBooks(inputs, rules, baseUrl, book.metadata["name"]?.jsonPrimitive?.content.orEmpty())
        return execute(task, "ruleSearch.books", inputs.sumOf { it.toString().length }).value.items().map {
            ExecutionTask.SearchBooks.FIELDS.zip(it.items().map { value -> value.text() }).toMap()
        }
    }

    suspend fun discoveryReadPlan(urls: List<String>, header: String, rules: List<String>, field: String = "discovery.previewPlan"): Boolean =
        execute(ExecutionTask.DiscoveryReadPlan(urls, header, rules), field,
            (urls + rules + header).sumOf { it.length }).value.text() == "true"

    private suspend fun execute(task: ExecutionTask, field: String, inputChars: Int): ExecutedRule {
        currentCoroutineContext().ensureActive()
        if (!authority.accepts(identity)) throw SourceContentException(ContentError.Unavailable, field)
        if (calls.incrementAndGet() > maxRuleCalls) throw SourceContentException(ContentError.Limit, field)
        // Lists carry complete API objects; chapter text also needs room for UTF-8 and the
        // paragraph envelope. Keep metadata fields small and the whole-chapter bound in RuleSource.
        val limits = when (field) {
            // These hooks return a whole response, before the actual field selectors run.
            "loginCheckJs", "ruleBookInfo.init" -> this.limits.copy(maxOutputBytes = 16 * 1024 * 1024)
            // A single JSON response can contain thousands of complete chapter objects.
            // Retain their fields until per-chapter rules run, within the existing wire bound.
            "ruleToc.chapterList" -> this.limits.copy(maxOutputBytes = 16 * 1024 * 1024, maxDataBytes = BridgeWire.MAX_REPLY_BYTES)
            "ruleSearch.bookList", "ruleExplore.bookList" ->
                this.limits.copy(maxOutputBytes = 2 * 1024 * 1024, maxDataBytes = BridgeWire.MAX_REPLY_BYTES)
            "ruleExplore.overviews", "ruleSearch.books" -> this.limits.copy(maxOutputBytes = 4 * 1024 * 1024)
            "ruleContent.content", "ruleContent.images", "ruleContent.replaceRegex", "ruleContent.parts" ->
                this.limits.copy(maxOutputBytes = 2 * 1024 * 1024)
            else -> this.limits
        }
        val started = System.nanoTime()
        var networkFailure: hnovel.network.BrokerResult.Failure? = null
        var requestLimitExceeded = false
        var responseLimitExceeded = false
        val result = SourceExecutionBroker(identity, authority, session, limits, baseUrl, keyword, page,
            allowInteraction = interactive, sourceName = sourceName, sourceLastUpdateTime = sourceLastUpdateTime,
            requestUserAgent = requestUserAgent.takeUnless { field == "header" }, currentRequest = currentRequest, memory = memory,
            onMessage = onMessage, retryContext = currentCoroutineContext()[hnovel.network.RequestRetryContext]).use {
            val executed = try { runner.execute(identity, task, limits, it) }
            catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
            catch (failure: Exception) {
                // Host-side library loading can fail before the worker receives the task.
                if (it.requestFailure != null || it.requestLimitExceeded || it.responseLimitExceeded) ExecutionResult.Failure(FailureCode.BridgeDenied) else throw failure
            }
            executed.also { _ ->
                if (it.interactionRequired) throw SourceContentException(ContentError.LoginRequired, field,
                    diagnostic = executed as? ExecutionResult.Failure)
                networkFailure = it.requestFailure
                requestLimitExceeded = it.requestLimitExceeded
                responseLimitExceeded = it.responseLimitExceeded
                missingCacheRead = missingCacheRead || it.missingCacheRead
            }
        }
        val failure = result as? ExecutionResult.Failure
        val dependency = failure?.takeIf { it.code == FailureCode.UnsupportedDependency }?.dependency
        val failureField = if (dependency != null && failure.ruleError?.location?.field == "jsLib") "jsLib"
            else if (task is ExecutionTask.BookOverviews || task is ExecutionTask.SearchBooks) failure?.ruleError?.location?.field ?: field else field
        trace.record(ContentTraceEvent("rule", failureField, (System.nanoTime() - started) / 1_000_000,
            inputChars, (result as? ExecutionResult.Success)?.output?.length ?: 0,
            if (result is ExecutionResult.Failure && result.code == FailureCode.BridgeDenied)
                if (requestLimitExceeded) "RequestLimit" else if (responseLimitExceeded) "ResponseLimit" else networkFailure?.code?.name ?: result.code.name
            else (result as? ExecutionResult.Failure)?.code?.name ?: "Success",
            (result as? ExecutionResult.Failure)?.ruleError?.code,
            (result as? ExecutionResult.Failure)?.ruleError?.location?.offset, failure))
        currentCoroutineContext().ensureActive()
        if (!authority.accepts(identity)) throw SourceContentException(ContentError.Unavailable, field)
        return when (result) {
            is ExecutionResult.Failure -> throw SourceContentException(when (result.code) {
                FailureCode.Revoked, FailureCode.InvalidIdentity -> ContentError.Unavailable
                FailureCode.Timeout, FailureCode.InputLimit, FailureCode.OutputLimit -> ContentError.Limit
                FailureCode.BridgeDenied -> if (requestLimitExceeded || responseLimitExceeded) ContentError.Limit else networkFailure?.code?.contentError() ?: ContentError.PermissionDenied
                FailureCode.UnsupportedDependency -> ContentError.UnsupportedDependency
                else -> ContentError.InvalidRule
            }, failureField, networkFailure?.denial.takeIf { result.code == FailureCode.BridgeDenied }, dependency,
                networkFailure?.takeIf { result.code == FailureCode.BridgeDenied && !requestLimitExceeded && !responseLimitExceeded }?.let(verification), result, storageFailure = networkFailure?.code.takeIf {
                    result.code == FailureCode.BridgeDenied && !requestLimitExceeded && !responseLimitExceeded
                })
            is ExecutionResult.Success -> Json.decodeFromString(ExecutedRule.serializer(), result.output)
        }
    }

    suspend fun text(rule: String, input: RuleValue, field: String, unescape: Boolean = true): String =
        if (rule.isBlank()) "" else value(rule, input, field, unescape = unescape).text()

    suspend fun url(rule: String, input: RuleValue, field: String): String =
        if (rule.isBlank()) "" else value(rule, input, field, OutputKind.Url).text()

    suspend fun script(code: String, input: RuleValue, field: String): RuleValue =
        value(if (code.startsWith("@js:", true) || code.startsWith("<js>", true)) code else "@js:$code", input, field, OutputKind.Element,
            scriptTemplates = false)

    fun bookField(name: String, value: String) { book = book.copy(metadata = JsonObject(book.metadata + (name to JsonPrimitive(value)))) }
    fun chapterField(name: String, value: JsonElement) { chapter = chapter.copy(metadata = JsonObject(chapter.metadata + (name to value))) }

    private fun applyWrites(before: Map<String, String>, writes: Map<String, String?>) = before.toMutableMap().apply {
        writes.forEach { (key, value) -> if (value == null) remove(key) else put(key, value) }
    }.toMap()
}

internal fun RuleValue.items(): List<RuleValue> = when (this) {
    is RuleValue.Items -> values
    RuleValue.Empty -> emptyList()
    else -> listOf(this)
}
internal fun RuleValue.text(): String = when (this) {
    is RuleValue.Text -> value
    is RuleValue.Node -> content
    is RuleValue.Items -> values.joinToString("\n") { it.text() }
    is RuleValue.Captures -> groups.joinToString("\n")
    RuleValue.Empty -> ""
}

internal fun scriptBody(code: String): String = code.trim().let {
    when {
        it.startsWith("@js:", true) -> it.substring(4)
        it.startsWith("<js>", true) && it.endsWith("</js>", true) -> it.substring(4, it.length - 5)
        else -> it
    }
}
