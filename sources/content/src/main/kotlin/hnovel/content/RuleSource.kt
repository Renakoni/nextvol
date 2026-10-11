package hnovel.content

import hnovel.execution.*
import hnovel.imports.SourceDefinition
import hnovel.imports.EXTENSION_PROFILE
import hnovel.network.*
import hnovel.rules.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

private const val DIRECTORY_MAX_CHAPTERS = 50_000
private const val DIRECTORY_MAX_PAGES = 2048
private const val DIRECTORY_TIMEOUT_MILLIS = 600_000L
private const val MAX_SEARCH_MEMORIES = 8

/** One registered revision/account. Source retirement revokes its ticket; it never chooses another source. */
class RuleSource(val definition: SourceDefinition, private val identity: ExecutionIdentity,
    private val authority: ExecutionAuthority, private val session: SourceSession,
    private val runner: RuleTaskRunner, private val trace: ContentTrace = ContentTrace.None,
    private val discoveryEnabled: Boolean = definition.enabledExplore) : AutoCloseable {
    internal val spec = RuleSourceDefinition(definition)
    private val bookUrlRule = ":\\A(?:${spec.bookUrlPattern})\\z"
    private val store = RuleBookStore(session, authority, identity)
    private val previewDocuments = DiscoveryPreviewDocuments()
    internal fun clearDiscoveryPreviews() = previewDocuments.clear()
    private val serial = SourceWorkQueue()
    private var previewPlan: Pair<List<String>, Boolean>? = null
    private var parallelPreviewParsing: Boolean? = null
    private var searchBatchPlan: Boolean? = null
    private var prefetchedDirectoryId: String? = null
    private var completedInformation: Pair<String, Long>? = null
    private var completedDirectory: Pair<String, Long>? = null
    // Search memory belongs to a caller's query. This instance is replaced for another account or revision.
    private val searchMemory = object : LinkedHashMap<String, ScriptMemory>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ScriptMemory>) = size > MAX_SEARCH_MEMORIES
    }
    val canSearch get() = spec.searchUrl.isNotBlank()
    val canLogin get() = spec.loginUrl.isNotBlank() || spec.loginUi.isNotBlank()
    /** Whole-operation replay requires declarative reads with no user hooks or shared script state.
     * Actual extracted URLs and HTTP methods are checked again before each request. */
    val canReplayDownloads get() = spec.library == null && !spec.browserRead &&
        listOf(spec.loginCheck, spec.coverDecode, spec.information.string("init"),
            spec.toc.string("preUpdateJs"), spec.toc.string("formatJs"), spec.content.string("webJs"),
            spec.content.string("sourceRegex"), spec.content.string("imageDecode")).all { it.isBlank() } &&
        (listOf(spec.header) + listOf(spec.information, spec.toc, spec.content).flatMap { fields ->
            fields.keys.map(fields::string)
        }).all(ExecutionTask.BookOverviews::supports)
    private val discoveryCapabilities by lazy { RuleDiscoveryClassifier.capabilities(spec) }
    val canFeed get() = discoveryEnabled && discoveryCapabilities.hasFeed
    val canCategorize get() = discoveryEnabled && discoveryCapabilities.hasCategories
    val canDiscover get() = discoveryEnabled && (spec.exploreUrl.isNotBlank() ||
        spec.homepageModules.isNotBlank() || spec.exploreScreen.isNotBlank())

    fun openDiscovery(sessionId: String, values: Map<String, String> = emptyMap(),
        environment: RuleDiscoveryEnvironment = RuleDiscoveryEnvironment()) = RuleDiscoverySession(this, sessionId, values, environment)

    internal fun discoveryState(key: String): String? = authority.authorized(identity) {
        val result = session.read(StorageRequest(StorageArea.Config, "discovery/$key"))
        if (result !is StorageResult.Value) throw SourceContentException(ContentError.Storage, "discovery.$key")
        result.value
    }

    internal fun saveDiscoveryState(key: String, value: String) = authority.authorized(identity) {
        val path = if (key == "configuration") "variable" else "discovery/$key"
        if (session.write(StorageRequest(StorageArea.Config, path, value)) !is StorageResult.Value)
            throw SourceContentException(ContentError.Storage, "discovery.$key")
    }

    internal suspend fun discoveryPage(context: RuleEvaluation, url: String): List<RuleBook> {
        val document = fetch(context, url, "exploreUrl", acceptErrorResponse = spec.explore.string("bookList").isScriptRule())
        return booksFromPage(context, document, spec.explore, "ruleExplore", overview = true)
            .also { checkEmptyResponse(it, document, "ruleExplore.bookList") }
    }

    internal fun listSession(fields: JsonObject, field: String,
        load: suspend (Int, String?, ScriptMemory) -> RuleListResult) =
        RuleListSession(field, fields.string("nextPageUrl").isNotBlank(), trace, {
            if (!authority.accepts(identity)) throw SourceContentException(ContentError.Unavailable, field)
        }, load)

    internal suspend fun listPage(context: RuleEvaluation, url: String, urlField: String,
        fields: JsonObject, field: String, overview: Boolean = false, previewLimit: Int? = null): RuleListResult {
        val plan = prepareList(context, url, urlField, fields, field, overview, previewLimit)
        return plan.read().invoke()
    }

    private data class ListPagePlan(val context: RuleEvaluation, val url: String, val urlField: String,
        val fields: JsonObject, val field: String, val overview: Boolean, val previewLimit: Int?,
        val request: BrokerRequest, val key: DiscoveryPreviewDocuments.Key?, val generation: Long, val concurrent: Boolean)

    private suspend fun prepareList(context: RuleEvaluation, url: String, urlField: String,
        fields: JsonObject, field: String, overview: Boolean, previewLimit: Int?, concurrent: Boolean = false): ListPagePlan {
        val reusable = overview && context.page == 1 && spec.loginCheck.isBlank() && canDeferBookFields(fields)
        val request = prepareRequest(context, url, urlField, ResourceKind.Document)
        val key = if (reusable) previewKey(context, request) else null
        return ListPagePlan(context, url, urlField, fields, field, overview, previewLimit, request, key, previewDocuments.generation(), concurrent)
    }

    /** Transport is independent; the caller keeps stateful hooks and parsing in one source turn. */
    private suspend fun ListPagePlan.read(): suspend () -> RuleListResult {
        val cached = key?.takeIf { generation == previewDocuments.generation() && previewKey(context, request) == it }
            ?.let(previewDocuments::get)
        val response = if (cached == null) send(context, request, urlField, key?.route) else null
        val responseKey = if (previewLimit != null && cached == null && key != null)
            previewKey(context, request)?.takeIf { it.route === key.route && (!concurrent || it == key) } else null
        return {
            val document = cached?.document?.also {
                context.baseUrl = it.ruleUrl
                context.requestUserAgent = cached.userAgent
                trace.record(ContentTraceEvent("cache", urlField, 0, result = "PreviewReuse"))
            } ?: responseDocument(context, url, urlField, request, requireNotNull(response),
                acceptErrorResponse = fields.string("bookList").isScriptRule())
            finish(document, responseKey)
        }
    }

    private suspend fun ListPagePlan.finish(document: PageDocument, responseKey: DiscoveryPreviewDocuments.Key?): RuleListResult {
        val books = booksFromPage(context, document, fields, field, overview, previewLimit)
        val rule = fields.string("nextPageUrl")
        // Ordinary URL rules intentionally fall back to the current URL on empty output.
        // Optional continuations must distinguish that empty output from a real repeated link.
        val next = responseRule(document.httpErrorStatus.takeIf { books.isEmpty() }) {
            if (rule.isBlank()) null else context.text(rule, document.input(), "$field.nextPageUrl").trim()
                .takeIf { it.isNotBlank() && it != "null" }?.let { sourceLink(document.url, it) }
        }
        if (responseKey != null && books.isNotEmpty() &&
            document.successfulResponse && document.url == request.url) {
            // A response may establish cookies, but later rule side effects must not rebind its identity.
            currentCoroutineContext().ensureActive()
            authority.authorized(identity) {
                if (previewKey(context, request) == responseKey)
                    previewDocuments.put(responseKey, document, context.requestUserAgent, generation)
            }
        }
        return RuleListResult(books, document.url, next, document.httpErrorStatus)
    }

    internal suspend fun canReadPreviewsConcurrently(urls: List<String>): Boolean = operation("discovery.previewPlan") {
        previewPlan?.takeIf { it.first == urls }?.let { return@operation it.second }
        // Request inputs are snapshotted before dispatch. Stateful response scripts still
        // keep their hook, row state and library evaluation together under serial.
        val result = try { evaluation().discoveryReadPlan(urls, spec.header, emptyList()) }
        catch (failure: SourceContentException) {
            if (failure.code == ContentError.Unavailable) throw failure
            false
        }
        previewPlan = urls.toList() to result
        result
    }

    private suspend fun canParsePreviewsConcurrently(): Boolean {
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

    /** Prepare the complete independent group before any read. A generated POST or interactive
     * request keeps the entire group sequential; already prepared read-only expressions have no writes. */
    internal suspend fun prepareConcurrentPreviews(urls: List<String>, context: () -> RuleEvaluation): List<suspend () -> RuleListResult>? =
        operation("ruleExplore") {
            if (!canDiscover || spec.explore.isEmpty()) throw SourceContentException(ContentError.MissingCapability, "ruleExplore")
            var missingCacheRead = false
            val plans = urls.map { url ->
                val evaluation = context()
                try { Result.success(prepareList(evaluation, url, "exploreUrl", spec.explore, "ruleExplore", overview = true, previewLimit = 6, concurrent = true)) }
                catch (failure: SourceContentException) { Result.failure(failure) }
                finally { missingCacheRead = missingCacheRead || evaluation.missingCacheRead }
            }
            // A preceding login hook may initialize a missing account ID. Do not freeze its
            // absence into every URL in a cold-start group. Recheck on the next preview load.
            if (missingCacheRead) return@operation emptyList()
            if (plans.any { it.getOrNull()?.request?.let { request ->
                request.method != "GET" || request.body != null || request.responseAsHex || request.cache != CacheMode.Disabled ||
                    request.browser?.let { options -> options.interactive || options.html != null || options.webCookie != null ||
                        options.script.isNotBlank() || options.sourceRegex.isNotBlank() || options.overrideUrl || options.verificationCode } == true
            } == true }) return@operation emptyList()
            val parallelParsing = canParsePreviewsConcurrently()
            trace.record(ContentTraceEvent("concurrency", "ruleExplore", 0, plans.size,
                result = if (parallelParsing) "ParallelParsing" else "SerialParsing"))
            if (parallelParsing) runner.prepareIndependent(identity, plans.count { it.isSuccess })
            plans.mapIndexed { index, prepared ->
                var first = true
                val load: suspend () -> RuleListResult = {
                    withContext(Dispatchers.IO) {
                        if (!first) operation("ruleExplore") { listPage(context(), urls[index], "exploreUrl", spec.explore, "ruleExplore", true, 6) }
                        else {
                            first = false
                            val plan = prepared.getOrThrow()
                            val started = System.nanoTime()
                            val finish = checkedOperation("ruleExplore", 60000) { plan.read() }
                            // Transport and parsing share one execution budget; waiting for another
                            // stateful parsing turn must not spend it before this turn begins.
                            val remaining = 60000 - (System.nanoTime() - started) / 1_000_000
                            if (remaining <= 0) throw SourceContentException(ContentError.Limit, "ruleExplore")
                            if (parallelParsing) checkedOperation("ruleExplore", remaining) {
                                plan.context.readOnly = true
                                finish()
                            } else operation("ruleExplore", remaining) { finish() }
                        }
                    }
                }
                load
            }
        }.takeIf { it.isNotEmpty() }

    private fun previewKey(context: RuleEvaluation, request: BrokerRequest): DiscoveryPreviewDocuments.Key? {
        if (request.method != "GET" || request.body != null || request.cache != CacheMode.Disabled ||
            request.kind != ResourceKind.Document || request.responseAsHex ||
            request.browser?.let { it.interactive || it.html != null || it.webCookie != null || it.script.isNotBlank() ||
                it.sourceRegex.isNotBlank() || it.overrideUrl || it.verificationCode } == true ||
            session.permissionFailureDetail(request.url) != null) return null
        val route = session.responseRoute() ?: return null
        val cookies = runCatching { session.responseCookies(request.url).sorted() }.getOrNull() ?: return null
        val headers = runCatching { session.responseHeaders(request) }.getOrNull() ?: return null
        val environment = context.discovery?.let { JsonObject(it - "sessionId") }
        return DiscoveryPreviewDocuments.Key(request.copy(headers = request.headers.toMap()), route, cookies, headers, environment)
    }

    fun openSearchPages(keyword: String): RuleListSession = listSession(spec.search, "ruleSearch") { page, url, memory ->
        operation("ruleSearch") {
            if (!canSearch) throw SourceContentException(ContentError.MissingCapability, "searchUrl")
            listPage(evaluation(keyword = keyword, page = page, memory = memory), url ?: spec.searchUrl,
                "searchUrl", spec.search, "ruleSearch")
        }
    }

    suspend fun openDiscoveryBrowser(url: String, html: String? = null, script: String = "", title: String = ""): Unit =
        operation("discovery.browser", timeoutMillis = 300000) {
            val context = evaluation(interactive = true)
            request(context, url, "discovery.browser", browser = BrowserOptions(interactive = true, html = html, script = script, title = title))
        }
    init {
        require(identity.sourceId == definition.sourceId && identity.profile == definition.profile && identity.revision == definition.contentDigest)
        require(session.scope.sourceId == identity.sourceId && session.scope.namespace == identity.namespace &&
            session.scope.profile == identity.profile && session.scope.accountGeneration == identity.accountGeneration)
        session.configureSource(spec.baseUrl, spec.cookiesEnabled, spec.browserRead, spec.concurrentRate,
            spec.localStorageRetention, defaultUserAgent = DESKTOP_USER_AGENT, preferBrowserUserAgent = true)
        session.traceRequests(if (trace === ContentTrace.None) hnovel.network.RequestTrace.None else hnovel.network.RequestTrace { event ->
            trace.record(ContentTraceEvent("requestDiagnostic", "request", 0, result = event.evidence.name, requestDiagnostic = event))
        })
    }

    private var cachedLoginForm: LoginForm? = null
    private data class LoginReadingSnapshot(val book: RuleBook, val chapter: RuleChapter?)
    private var loginReading: LoginReadingSnapshot? = null

    /** A panel owns its form and execution ticket, while sharing the current account storage. */
    fun openLoginSession(reading: LoginReadingContext? = null): RuleSource {
        if (!authority.accepts(identity)) throw SourceContentException(ContentError.Unavailable, "login")
        val snapshot = reading?.let { position ->
            val record = store.read(position.bookId)?.takeIf { it.revision == identity.revision }
                ?: throw SourceContentException(ContentError.Unavailable, "login.book")
            val chapters = record.chapters.filterNot { it.isVolume }
            val index = chapters.indexOfFirst { it.id == position.chapterId }
            if (position.chapterId != null && index < 0)
                throw SourceContentException(ContentError.Unavailable, "login.chapter")
            // Reader progress is normalized, not Legado's character offset (durChapterPos).
            val metadata = record.book.state.metadata + mapOf(
                "readingProgress" to JsonPrimitive(position.readingProgress)) + if (index < 0) emptyMap() else mapOf(
                "durChapterIndex" to JsonPrimitive(index), "durChapterTitle" to JsonPrimitive(chapters[index].title))
            LoginReadingSnapshot(record.book.copy(state = record.book.state.copy(metadata = JsonObject(metadata))),
                chapters.getOrNull(index))
        }
        val ticket = authority.issue(identity.sourceId, identity.profile, identity.revision, identity.namespace, identity.accountGeneration)
        return RuleSource(definition, ticket, authority, session, runner, trace, discoveryEnabled).also { it.loginReading = snapshot }
    }

    suspend fun loginForm(): LoginForm = operation("loginUi") {
        (cachedLoginForm ?: loadLoginForm()).withValues(loginValues())
    }

    private fun loginInfo(): String? {
        val stored = session.read(StorageRequest(StorageArea.Account, StorageRequestKey.LOGIN_INFO)) as? StorageResult.Value
            ?: throw SourceContentException(ContentError.Storage, "loginUi.values")
        return stored.value
    }

    private fun loginValues(): Map<String, String> = LoginInfo.stringFields(loginInfo()).orEmpty()
        .filter { (key, value) -> key.length <= 128 && value.jsonPrimitive.content.length <= 4096 }
        .mapValues { it.value.jsonPrimitive.content }

    private fun saveLoginValues(values: Map<String, String>) = authority.authorized(identity) {
        val original = loginInfo()
        val merged = LoginInfo.merge(original, values)
        if (merged != original)
            check(session.write(StorageRequest(StorageArea.Account, StorageRequestKey.LOGIN_INFO, merged)) is StorageResult.Value)
    }

    private fun loginContext(values: Map<String, String>, interactive: Boolean): RuleEvaluation =
        evaluation(loginReading?.book, loginReading?.chapter, interactive = interactive).also {
        // Reuse the existing bounded interaction envelope; the login form owns this draft.
        // No exploration catalogue is evaluated and no Android objects cross the worker boundary.
        it.discovery = buildJsonObject {
            put("sessionId", "login"); put("values", JsonObject(values.mapValues { JsonPrimitive(it.value) }))
            put("interactive", interactive); put("noBook", loginReading == null)
            put("noChapter", loginReading?.chapter == null); put("readingPanel", loginReading != null)
        }
    }

    private fun loginPrelude(): String = if (spec.loginUrl.trim().startsWith("http://", true) ||
        spec.loginUrl.trim().startsWith("https://", true)) "" else scriptBody(spec.loginUrl)

    private suspend fun loginUiValue(context: RuleEvaluation, code: String, field: String): JsonElement {
        val value = context.script("result=JSON.parse(result);\n${loginPrelude()}\nJSON.stringify(eval(${JsonPrimitive(scriptBody(code))}));",
            RuleValue.Text(context.discovery!!.getValue("values").toString()), field).text()
        return Json.parseToJsonElement(value)
    }

    private suspend fun loadLoginForm(): LoginForm {
        val extended = definition.profile == EXTENSION_PROFILE
        val context = loginContext(loginValues().entries.take(128).associate { it.toPair() }, interactive = false)
        val raw = spec.loginUi.trim()
        val ui = if (raw.startsWith("@js:", true) || raw.startsWith("<js>", true)) {
            if (!extended) throw SourceContentException(ContentError.InvalidRule, "loginUi")
            val result = loginUiValue(context, raw, "loginUi")
            if (result is JsonPrimitive && result.isString) result.content else result.toString()
        } else raw
        val form = LoginForm.parse(ui, spec.loginUrl, extended).withValues(loginValues())
        context.discovery = JsonObject(context.discovery!! + ("values" to JsonObject(form.values.mapValues { JsonPrimitive(it.value) })))
        val rendered = form.copy(fields = form.fields.mapIndexed { index, field ->
            if (field.viewName == null) field else {
                val value = loginUiValue(context, field.viewName, "loginUi[$index].viewName") as? JsonPrimitive
                    ?: throw SourceContentException(ContentError.InvalidRule, "loginUi[$index].viewName")
                if (value == JsonNull || value.content.length > 256) throw SourceContentException(ContentError.InvalidRule, "loginUi[$index].viewName")
                field.copy(label = value.content)
            }
        })
        if (context.discovery!!["saveSeconds"]?.let { it != JsonNull } == true)
            throw SourceContentException(ContentError.InvalidRule, "loginUi.infoMap.save")
        return rendered.also { cachedLoginForm = it }
    }

    suspend fun login(values: Map<String, String>, action: String? = null, formId: String? = null): LoginActionResult = operation("loginUrl", timeoutMillis = 300000) {
        if (formId != null && cachedLoginForm?.id != formId)
            throw SourceContentException(ContentError.Unavailable, "loginUi")
        val form = (cachedLoginForm ?: loadLoginForm()).withValues(loginValues())
        // Trusted callers without a UI snapshot retain the unique-name API. UI submissions
        // always carry a form ID and can only address an opaque control ID from that form.
        val fieldAction = action?.let { id ->
            val field = form.fields.singleOrNull { if (formId == null) it.name == id else it.id == id }
                ?: throw SourceContentException(ContentError.InvalidRule, "loginUi.action")
            if (!field.enabled) throw SourceContentException(ContentError.Unavailable, "loginUi.action")
            field.action ?: throw SourceContentException(ContentError.InvalidRule, "loginUi.action")
        }
        form.validate(values)
        val submitted = form.values + values
        form.validate(submitted, allowAdditional = true)
        val info = JsonObject(submitted.mapValues { JsonPrimitive(it.value) }).toString()
        saveLoginValues(submitted)
        val context = loginContext(submitted, interactive = true)
        val messages = ArrayDeque<String>()
        context.onMessage = { message ->
            message.trim().take(4096).takeIf { it.isNotEmpty() }?.let {
                if (messages.size == 16) messages.removeFirst()
                messages.addLast(it)
            }
        }
        if (form.browserUrl != null && action == null) {
            val pending = if (spec.browserRead) (session.read(StorageRequest(StorageArea.Account,
                StorageRequestKey.BROWSER_PENDING_URL)) as? StorageResult.Value)?.value else null
            val response = session.execute(BrokerRequest("login", pending ?: form.browserUrl, headers = context.headers(),
                timeoutMillis = 60000, browser = BrowserOptions(interactive = true)),
                RequestCommitGuard { authority.authorized(identity, it) })
            when (response) {
                is BrokerResult.Failure -> throw SourceContentException(response.code.contentError(), "loginUrl", response.denial,
                    verification = verification(response))
                is BrokerResult.Success -> checkStatus(response.response.status, "loginUrl", response.response.kind == ResponseKind.BrowserDocument)
            }
            // Closing a website preserves its session; HTTP 200 alone does not verify a login.
            authority.authorized(identity) {
                check(session.write(StorageRequest(StorageArea.Account, "login/status", "session")) is StorageResult.Value)
                if (pending != null) check(session.write(StorageRequest(StorageArea.Account, StorageRequestKey.BROWSER_PENDING_URL)) is StorageResult.Value)
            }
            return@operation LoginActionResult()
        }
        val code = if (action == null) "if(typeof login!=='function')throw new Error('login missing');login();true;"
            else fieldAction!!
        if (code.startsWith("http://", true) || code.startsWith("https://", true)) {
            val response = request(context, code, "loginUi.action", browser = BrowserOptions(interactive = true))
            checkStatus(response.status, "loginUi.action", response.kind == ResponseKind.BrowserDocument)
        } else context.script("result=JSON.parse(result);\n${loginPrelude()}\n${scriptBody(code)}", RuleValue.Text(info),
            if (action == null) "loginUrl" else "loginUi.action")
        val state = context.discovery!!
        if (state["saveSeconds"]?.let { it != JsonNull } == true) throw SourceContentException(ContentError.InvalidRule, "loginUi.infoMap.save")
        val actions = (state["actions"] as? JsonArray).orEmpty()
        val refreshTargets = mutableSetOf<LoginRefreshTarget>()
        for (item in actions) {
            val command = item.jsonObject
            when (command.string("kind")) {
                "refresh" -> Unit
                "refreshBookInfo", "refreshBookToc", "refreshContent" -> {
                    if (loginReading == null || command.getValue("args").jsonArray.isNotEmpty())
                        throw SourceContentException(ContentError.InvalidRule, "loginUi.action.refresh")
                    refreshTargets += when (command.string("kind")) {
                        "refreshBookInfo" -> LoginRefreshTarget.BookInformation
                        "refreshBookToc" -> LoginRefreshTarget.Directory
                        else -> LoginRefreshTarget.Content
                    }
                }
                "showBrowser" -> {
                    val args = command.getValue("args").jsonArray
                    if (args.size !in 1..4) throw SourceContentException(ContentError.InvalidRule, "loginUi.action.browser")
                    fun arg(index: Int) = args.getOrNull(index)?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
                    val config = arg(3)?.let { Json.parseToJsonElement(it).jsonObject }
                    if (config?.keys?.any { it != "title" } == true) throw SourceContentException(ContentError.InvalidRule, "loginUi.action.browser")
                    val response = request(context, arg(0)!!, "loginUi.action.browser", browser = BrowserOptions(interactive = true,
                        html = arg(1), script = arg(2).orEmpty(), title = config?.string("title").orEmpty()))
                    checkStatus(response.status, "loginUi.action.browser", response.kind == ResponseKind.BrowserDocument)
                }
                else -> throw SourceContentException(ContentError.InvalidRule, "loginUi.action")
            }
        }
        val changed = state.getValue("values").jsonObject.mapValues { it.value.jsonPrimitive.content }
        form.validate(changed, allowAdditional = true)
        if (changed != submitted) saveLoginValues(changed.filter { (key, value) -> submitted[key] != value })
        val refreshDiscovery = actions.any { it.jsonObject.string("kind") == "refresh" }
        if (refreshDiscovery) cachedLoginForm = null
        if (action == null) authority.authorized(identity) { check(session.write(StorageRequest(StorageArea.Account, "login/status", "authenticated")) is StorageResult.Value) }
        LoginActionResult(refreshTargets.toSet(), messages.toList(), refreshDiscovery)
    }

    /** Pages called with the same [query] share `cache.*Memory`; without one, memory lasts for this page only. */
    suspend fun search(keyword: String, page: Int = 1, query: String? = null): List<RuleBook> = operation("ruleSearch") {
        if (!canSearch) throw SourceContentException(ContentError.MissingCapability, "searchUrl")
        val memory = query?.let { synchronized(searchMemory) { searchMemory.getOrPut("$it\n$keyword") { ScriptMemory() } } }
        val context = evaluation(keyword = keyword, page = page, memory = memory ?: ScriptMemory())
        val document = fetch(context, spec.searchUrl, "searchUrl",
            acceptErrorResponse = spec.search.string("bookList").isScriptRule())
        booksFromPage(context, document, spec.search, "ruleSearch")
            .also { checkEmptyResponse(it, document, "ruleSearch.bookList") }
    }

    /** Executes a selected discovery URL through the same production list pipeline as search. */
    suspend fun discovery(url: String, page: Int = 1): List<RuleBook> = operation("ruleExplore") {
        if (url.isBlank() || spec.explore.isEmpty()) throw SourceContentException(ContentError.MissingCapability, "ruleExplore")
        val context = evaluation(page = page)
        discoveryPage(context, url)
    }

    // Fixed row selectors are independent. Dynamic rules may write variables used by later rows or URLs.
    private fun canDeferBookFields(fields: JsonObject) = listOf("name", "author", "kind", "wordCount",
        "lastChapter", "intro", "coverUrl", "updateTime", "bookUrl").all { name ->
        ExecutionTask.BookOverviews.supports(fields.string(name))
    }

    private suspend fun canBatchSearch(context: RuleEvaluation): Boolean {
        searchBatchPlan?.let { return it }
        val static = spec.library == null && !canLogin && spec.loginCheck.isBlank() &&
            spec.search.values.all { (it as? JsonPrimitive)?.contentOrNull?.let(ExecutionTask.BookOverviews::supports) == true }
        return (static && context.discoveryReadPlan(listOf(spec.searchUrl), spec.header,
            spec.search.keys.map(spec.search::string), "search.readPlan")).also { searchBatchPlan = it }
    }

    private suspend fun booksFromPage(context: RuleEvaluation, document: PageDocument, fields: JsonObject,
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

    suspend fun canonicalBookId(bookId: String): String = operation("bookAlias") {
        store.canonicalId(sourceLink(spec.baseUrl, bookId))
    }

    /** Read saved metadata without executing rules or requesting a document. */
    suspend fun cachedInformation(bookId: String): RuleBook? = withContext(Dispatchers.IO) {
        store.read(sourceLink(spec.baseUrl, bookId))?.book
    }

    suspend fun information(bookId: String): RuleBook {
        val requestedAt = currentCoroutineContext()[SourceWorkRequest]?.startedAtNanos ?: System.nanoTime()
        return operation("ruleBookInfo", timeoutMillis = DIRECTORY_TIMEOUT_MILLIS) {
            val id = store.canonicalId(sourceLink(spec.baseUrl, bookId))
            val old = store.read(id)
            // Reuse only a successful refresh completed while this call was queued.
            // No detached task, cross-account sharing, or persistent freshness window.
            val completed = completedInformation
            if (old?.informationLoaded == true && old.revision == identity.revision &&
                completed?.first == id && completed.second - requestedAt >= 0) {
                trace.record(ContentTraceEvent("cache", "ruleBookInfo", 0, result = "QueuedRefreshReuse"))
                old.book
            } else {
                prefetchedDirectoryId = null
                saveInformation(id, old, inferUpdate = true).book
            }
        }
    }

    private suspend fun saveInformation(id: String, old: BookRecord?, supplied: PageDocument? = null,
        inferUpdate: Boolean = false, reuseInformation: Boolean = inferUpdate): BookRecord {
        var refreshed = information(id, old, supplied)
        // Sources without an update marker still participate in host background update checks.
        // A script-provided bookUrl is an alias proposal, not permission to move unreadable content.
        val loadDirectory = refreshed.book.id != id ||
            (inferUpdate && refreshed.book.latestChapter.isBlank() && refreshed.book.updateTime.isBlank())
        var directoryLoaded = false
        if (loadDirectory) try {
            refreshed = directory(refreshed)
            directoryLoaded = true
            if (old?.chapters?.map { it.id } != refreshed.chapters.map { it.id })
                refreshed = refreshed.copy(book = refreshed.book.copy(observedUpdate = System.currentTimeMillis()))
        } catch (failure: PartialDirectoryException) {
            // Alias migration still requires a fully validated destination catalogue.
            if (refreshed.book.id != id) throw failure.failure
            refreshed = refreshed.copy(partialDirectory = retainLongerPartialDirectory(refreshed.partialDirectory, failure).snapshot)
        }
        store.write(refreshed, id)
        prefetchedDirectoryId = if (directoryLoaded) refreshed.book.id else null
        val completedAt = System.nanoTime()
        // A proposed alias still needs its own detail parse; it can propose another identity.
        completedInformation = if (reuseInformation && refreshed.book.id == id) id to completedAt else null
        completedDirectory = if (directoryLoaded) refreshed.book.id to completedAt else null
        return refreshed
    }

    suspend fun directory(bookId: String): List<RuleChapter> {
        val requestedAt = currentCoroutineContext()[SourceWorkRequest]?.startedAtNanos ?: System.nanoTime()
        return operation("ruleToc", timeoutMillis = DIRECTORY_TIMEOUT_MILLIS) {
            val id = store.canonicalId(sourceLink(spec.baseUrl, bookId))
            val book = record(id, reuseInformation = true)
            val prefetched = prefetchedDirectoryId == book.book.id
            val completed = completedDirectory
            val reused = completed?.first == book.book.id && completed.second - requestedAt >= 0
            prefetchedDirectoryId = null
            try {
                if (prefetched || reused) {
                    if (reused) trace.record(ContentTraceEvent("cache", "ruleToc", 0, result = "QueuedRefreshReuse"))
                    book.chapters
                } else directory(book).also {
                    store.write(it)
                    completedDirectory = it.book.id to System.nanoTime()
                }.chapters
            } catch (failure: PartialDirectoryException) {
                val retained = retainLongerPartialDirectory(book.partialDirectory, failure)
                store.write(book.copy(partialDirectory = retained.snapshot))
                throw retained
            }
        }
    }

    private fun retainLongerPartialDirectory(previous: DirectorySnapshot?, failure: PartialDirectoryException): PartialDirectoryException {
        if (previous == null || previous.chapters.size <= failure.chapters.size) return failure
        val cached = previous.chapters.map { it.id to it.isVolume }
        val incoming = failure.chapters.map { it.id to it.isVolume }
        // Reverse catalogues retain a suffix. Keep the matching snapshot and its script state together.
        return if (cached.take(incoming.size) == incoming || cached.takeLast(incoming.size) == incoming)
            PartialDirectoryException(previous, failure.failure) else failure
    }

    suspend fun content(bookId: String, chapterId: String): RuleContent = operation("ruleContent", timeoutMillis = DIRECTORY_TIMEOUT_MILLIS) {
        val id = sourceLink(spec.baseUrl, bookId)
        val saved = record(id)
        var record = saved
        var usingPartialDirectory = false
        if (record.chapters.none { it.id == chapterId && !it.isVolume }) {
            record.partialDirectory?.takeIf { snapshot -> snapshot.chapters.any { it.id == chapterId && !it.isVolume } }?.let {
                record = record.copy(book = record.book.copy(state = it.state), chapters = it.chapters)
                usingPartialDirectory = true
            }
        }
        if (record.chapters.isEmpty()) record = directory(record)
        val index = record.chapters.indexOfFirst { it.id == chapterId && !it.isVolume }
        if (index < 0) throw SourceContentException(ContentError.Unavailable, "chapter")
        val chapter = record.chapters[index]
        val previous = record.chapters.take(index).lastOrNull { !it.isVolume }?.id
        val next = record.chapters.drop(index + 1).firstOrNull { !it.isVolume }?.id
        val context = evaluation(record.book, chapter)
        context.nextChapterUrl = next
        val rule = spec.content.string("content")
        if (rule.isBlank()) throw SourceContentException(ContentError.MissingCapability, "ruleContent.content")
        val queue = ArrayDeque<String>().apply { add(chapter.id) }
        val visited = linkedSetOf<String>()
        val pages = mutableListOf<String>()
        var firstPage: PageDocument? = null
        var completePageList = false
        var title = chapter.title
        while (queue.isNotEmpty()) {
            val url = queue.removeFirst()
            // A repeated link terminates a page chain; never read the next logical chapter here.
            if (url == next || url in visited) continue
            visit(visited, url, "ruleContent.nextContentUrl")
            val browser = if (spec.content.string("webJs").isNotBlank() || spec.content.string("sourceRegex").isNotBlank())
                BrowserOptions(script = spec.content.string("webJs"), sourceRegex = spec.content.string("sourceRegex"), nativeWebsite = true) else null
            // Script-led content rules can use a placeholder chapter URL and perform their
            // own requests. Like WebBook, pass the HTTP response body into those scripts.
            val document = fetch(context, url, "ruleContent.content", browser, acceptErrorResponse = rule.isScriptRule())
            // Redirects cannot turn a continuation (or the first page) into the next logical chapter.
            if (document.url == next) continue
            if (!document.inline && document.url != url && !visited.add(document.url)) throw SourceContentException(ContentError.RepeatedPage, "ruleContent.nextContentUrl")
            if (firstPage == null) firstPage = document
            val html = context.text(rule, document.input(), "ruleContent.content", unescape = false)
            // BookContent formats and decodes each page before whole-chapter replacement.
            // Reuse the ported HtmlFormatter and resolve images before joining different page bases.
            val normalized = context.text("""
                @js:(function(){var doc=org.jsoup.Jsoup.parse(result,baseUrl);doc.outputSettings().prettyPrint(false);
                    var body=doc.body();body.select('script,style,noscript').remove();body.select('img[src]').forEach(function(img){
                    img.attr('src',img.absUrl('src'))});return java.htmlFormat(body.html())})()
            """.trimIndent(), RuleValue.Text(html), "ruleContent.images")
            pages += normalized
            if (pages.sumOf { it.length.toLong() } > 512000) throw SourceContentException(ContentError.Limit, "ruleContent.content")
            if (!completePageList) {
                val following = links(context, spec.content.string("nextContentUrl"), document, "ruleContent.nextContentUrl")
                // Without the next catalogue row, an unknown link might be the next chapter, not another page.
                if (usingPartialDirectory && next == null && following.any { it != document.url && it !in visited })
                    throw SourceContentException(ContentError.Unavailable, "ruleToc.nextTocUrl")
                completePageList = context.page == 1 && following.size > 1
                (if (completePageList) following else following.take(1)).forEach(queue::addLast)
            }
            context.page++
        }
        if (pages.isEmpty()) throw SourceContentException(ContentError.EmptyContent, "ruleContent.content")
        // BookContent evaluates title and whole-chapter replacement on the first page,
        // after every content page has written its book/chapter variables.
        val initial = checkNotNull(firstPage)
        context.baseUrl = initial.ruleUrl
        context.page = 1
        val extracted = try { context.text(spec.content.string("title"), initial.input(), "ruleContent.title") }
        catch (failure: SourceContentException) {
            if (failure.code != ContentError.InvalidRule) throw failure
            ""
        }
        extracted.takeIf { it.isNotBlank() }?.let { title = it; context.chapterField("title", JsonPrimitive(it)) }
        var merged = pages.joinToString("\n")
        val replacement = spec.content.string("replaceRegex")
        if (replacement.isNotBlank()) merged = context.text(replacement,
            RuleValue.Text(merged.lines().joinToString("\n") { it.trim() }), "ruleContent.replaceRegex", unescape = false)
        val parts = context.markup(merged).items().map {
            Json.decodeFromString(ContentPart.serializer(), it.text())
        }.map { part -> if (part.image != null) part.copy(image = sourceLink(context.baseUrl, part.image)) else part }
        if (parts.isEmpty()) throw SourceContentException(ContentError.EmptyContent, "ruleContent.content")
        title = context.chapter.metadata["title"]?.jsonPrimitive?.content ?: title
        val updated = chapter.copy(title = title, state = context.chapter)
        val updatedChapters = record.chapters.toMutableList().apply { set(index, updated) }
        if (usingPartialDirectory) store.write(saved.copy(partialDirectory = DirectorySnapshot(updatedChapters, context.book)))
        else store.write(record.copy(book = record.book.copy(state = context.book), chapters = updatedChapters))
        RuleContent(chapter.id, title, parts, previous, next)
    }

    suspend fun image(bookId: String, url: String, cover: Boolean): ByteArray {
        // Rule preparation still owns the worker; waiting for image bytes does not hold the book queue.
        val (context, request, knownCover) = operation("image") {
            val id = sourceLink(spec.baseUrl, bookId)
            val book = store.read(id)?.book ?: RuleBook(id)
            val context = evaluation(book)
            Triple(context, prepareRequest(context, sourceLink(id, url), "image", ResourceKind.Image),
                cover && book.coverUrl.isNotBlank() && book.coverUrl == url)
        }
        val response = executeRequest(request, "image", publicImage = knownCover && request.method == "GET" &&
            request.body == null && request.browser == null)
        val decode = if (cover) spec.coverDecode else spec.content.string("imageDecode")
        if (decode.isBlank()) return response.body
        return operation("imageDecode") {
            // A single numeric array avoids one serialized RuleValue object per image byte.
            val input = JsonArray(response.body.map { JsonPrimitive(it.toInt()) }).toString()
            if (input.length > 196608) throw SourceContentException(ContentError.Limit, "imageDecode")
            val bytes = context.script("var src=${JsonPrimitive(response.finalUrl)};result=JSON.parse(result);" +
                "JSON.stringify(eval(${JsonPrimitive(scriptBody(decode))}))",
                RuleValue.Text(input), if (cover) "coverDecodeJs" else "ruleContent.imageDecode")
            val values = Json.parseToJsonElement(bytes.text()).jsonArray.map { it.jsonPrimitive.intOrNull
                ?: throw SourceContentException(ContentError.InvalidRule, "imageDecode") }
            if (values.any { it !in -128..255 }) throw SourceContentException(ContentError.InvalidRule, "imageDecode")
            values.map { it.toByte() }.toByteArray()
        }
    }

    private suspend fun information(id: String, old: BookRecord?, supplied: PageDocument? = null): BookRecord {
        var initial = old?.book?.let { if (old.revision == identity.revision) it else it.copy(state = ScriptState()) } ?: RuleBook(id)
        val preview = old?.preview?.takeIf { old.revision == identity.revision && it.accountGeneration == identity.accountGeneration }
        if (preview != null) {
            val listing = evaluation(initial)
            listing.baseUrl = preview.baseUrl
            initial = bookFields(listing, preview.input, spec.explore, "ruleExplore", initial)
        }
        val context = evaluation(initial)
        val document = supplied ?: fetch(context, id, "ruleBookInfo")
        context.baseUrl = document.ruleUrl
        val init = spec.information.string("init")
        val input = if (init.isBlank()) document.input() else context.value(init, document.input(), "ruleBookInfo.init", OutputKind.Element)
        var book = bookFields(context, input, spec.information, "ruleBookInfo", initial)
        if (book.title.isBlank()) throw SourceContentException(ContentError.EmptyContent, "ruleBookInfo.name")
        val toc = context.url(spec.information.string("tocUrl"), input, "ruleBookInfo.tocUrl")
        val proposedId = context.book.metadata["bookUrl"]?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?: throw SourceContentException(ContentError.InvalidRule, "ruleBookInfo.bookUrl")
        val canonicalId = store.canonicalId(sourceLink(document.url, proposedId))
        val tocUrl = sourceLink(document.url, toc.ifBlank { canonicalId })
        context.bookField("bookUrl", canonicalId)
        context.bookField("tocUrl", tocUrl)
        val changed = old?.informationLoaded != true || initial.latestChapter != book.latestChapter || initial.updateTime != book.updateTime
        book = book.copy(id = canonicalId, tocUrl = tocUrl, state = context.book,
            observedUpdate = if (changed) System.currentTimeMillis() else initial.observedUpdate)
        return BookRecord(identity.revision, book, true, document, old?.takeIf { it.revision == identity.revision }?.chapters.orEmpty(),
            partialDirectory = old?.takeIf { it.revision == identity.revision && it.book.id == canonicalId && it.book.tocUrl == tocUrl }?.partialDirectory)
    }

    private suspend fun bookFields(context: RuleEvaluation, input: RuleValue, rules: JsonObject, prefix: String, seed: RuleBook,
        overview: Boolean = false, extracted: Map<String, String>? = null): RuleBook {
        val priorTitle = context.book.metadata["name"]?.jsonPrimitive?.content ?: seed.title
        val priorAuthor = context.book.metadata["author"]?.jsonPrimitive?.content ?: seed.author
        // BookList creates an empty SearchBook before evaluating its first field.
        context.bookField("name", priorTitle)
        suspend fun field(name: String, prior: String, metadata: String = name): String {
            val rule = rules.string(name)
            val valueFromRule = extracted?.getValue(name) ?: try {
                if (name == "kind" && rule.isNotBlank()) context.value(rule, input, "$prefix.$name", OutputKind.TextList)
                    .items().joinToString(",") { it.text() }
                else context.text(rule, input, "$prefix.$name")
            } catch (failure: SourceContentException) {
                // BookList/BookInfo tolerate optional metadata errors. Keep the trace, and
                // still surface cancellation, limits, missing dependencies and login/permissions.
                if (name !in setOf("kind", "wordCount", "updateTime", "lastChapter", "intro", "coverUrl") || failure.code != ContentError.InvalidRule) throw failure
                ""
            }
            val value = valueFromRule.ifBlank {
                context.book.metadata[metadata]?.jsonPrimitive?.content ?: prior
            }
            context.bookField(metadata, value)
            return value
        }
        suspend fun coverUrl(): String = try {
            field("coverUrl", seed.coverUrl).let { if (it.isBlank()) "" else sourceLink(context.baseUrl, it) }
        } catch (failure: SourceContentException) {
            // Legado treats cover extraction as optional; a bad cover must not discard a novel.
            if (failure.code != ContentError.InvalidRule) throw failure
            ""
        }
        val title = field("name", seed.title)
        if (prefix != "ruleBookInfo" && title.isBlank()) return seed.copy(title = title, state = context.book)
        if (overview) {
            val cover = coverUrl()
            context.bookField("coverUrl", cover)
            return seed.copy(title = title, coverUrl = cover, state = context.book)
        }
        val author = field("author", seed.author)
        val preserveNames = prefix == "ruleBookInfo" && rules.string("canReName").isBlank()
        val finalTitle = if (preserveNames && priorTitle.isNotBlank()) priorTitle else title
        val finalAuthor = if (preserveNames && priorAuthor.isNotBlank()) priorAuthor else author
        context.bookField("name", finalTitle); context.bookField("author", finalAuthor)
        val kind = field("kind", seed.tags.joinToString(","))
        val wordCount = field("wordCount", seed.wordCount)
        val latest = field("lastChapter", seed.latestChapter, "latestChapterTitle")
        val intro = field("intro", seed.description)
        val cover = coverUrl()
        context.bookField("coverUrl", cover)
        val time = field("updateTime", seed.updateTime)
        return seed.copy(title = finalTitle, author = finalAuthor, description = intro, coverUrl = cover,
            tags = if (kind.isBlank()) seed.tags else kind.split('\n', ','), wordCount = wordCount,
            latestChapter = latest, updateTime = time, state = context.book).preservingValidMetadata(seed)
    }

    private suspend fun directory(initial: BookRecord): BookRecord {
        // A web-novel catalogue may contain tens of thousands of rows. Its budget is
        // independent of a single chapter's pagination and includes all per-row rules.
        val context = evaluation(initial.book, maxRuleCalls = DIRECTORY_MAX_CHAPTERS * 8 + DIRECTORY_MAX_PAGES * 4)
        spec.toc.string("preUpdateJs").takeIf { it.isNotBlank() }?.let { context.script(it, RuleValue.Empty, "ruleToc.preUpdateJs") }
        val rule = spec.toc.string("chapterList")
        if (rule.isBlank()) throw SourceContentException(ContentError.MissingCapability, "ruleToc.chapterList")
        val tocUrl = context.book.metadata["tocUrl"]?.jsonPrimitive?.content ?: initial.book.tocUrl
        val queue = ArrayDeque<String>().apply { add(sourceLink(initial.book.id, tocUrl)) }
        val visited = linkedSetOf<String>()
        val chapters = mutableListOf<RuleChapter>()
        var completePageList = false
        var interrupted: SourceContentException? = null
        while (queue.isNotEmpty()) {
            val url = queue.removeFirst()
            if (url in visited) continue
            visit(visited, url, "ruleToc.nextTocUrl", DIRECTORY_MAX_PAGES)
            val document = try {
                initial.document?.takeIf { visited.size == 1 && it.url == url }
                    ?: fetch(context, url, "ruleToc.chapterList")
            } catch (failure: SourceContentException) {
                if (failure.code != ContentError.Network || chapters.none { !it.isVolume }) throw failure
                interrupted = failure
                break
            }
            context.baseUrl = document.ruleUrl
            if (!document.inline && document.url != url && !visited.add(document.url)) continue
            val items = context.value(rule.removePrefix("-").removePrefix("+"), document.input(), "ruleToc.chapterList", OutputKind.Elements).items()
            if (items.size + chapters.size > DIRECTORY_MAX_CHAPTERS) throw SourceContentException(ContentError.Limit, "ruleToc.chapterList")
            for ((index, item) in items.withIndex()) {
                val row = context.fork(chapterId = "pending:$index")
                row.chapterField("baseUrl", JsonPrimitive(document.url))
                row.chapterField("bookUrl", JsonPrimitive(initial.book.id))
                val title = row.text(spec.toc.string("chapterName"), item, "ruleToc.chapterName")
                if (title.isBlank()) continue
                row.chapterField("title", JsonPrimitive(title))
                val raw = row.text(spec.toc.string("chapterUrl"), item, "ruleToc.chapterUrl")
                row.chapterField("url", JsonPrimitive(raw))
                val time = row.text(spec.toc.string("updateTime"), item, "ruleToc.updateTime")
                row.chapterField("updateTime", JsonPrimitive(time))
                row.chapterField("tag", JsonPrimitive(time))
                val volume = row.text(spec.toc.string("isVolume"), item, "ruleToc.isVolume").truth()
                val id = if (volume && raw.isBlank()) "volume:" + digest("$url:$index:$title") else try {
                    sourceLink(document.url, raw.ifBlank { url })
                } catch (failure: SourceContentException) {
                    if (failure.code != ContentError.InvalidRule) throw failure
                    // Reading retains locked/placeholder links in the catalogue. Validate them
                    // when requested so one non-HTTP link cannot discard all readable chapters.
                    raw
                }
                row.chapterId = id
                row.chapterField("url", JsonPrimitive(raw.ifBlank { if (volume) title + index else url }))
                row.chapterField("isVolume", JsonPrimitive(volume))
                val vip = row.text(spec.toc.string("isVip"), item, "ruleToc.isVip").truth()
                val pay = row.text(spec.toc.string("isPay"), item, "ruleToc.isPay").truth()
                row.chapterField("isVip", JsonPrimitive(vip)); row.chapterField("isPay", JsonPrimitive(pay))
                chapters += RuleChapter(id, title, volume, vip, pay, time, row.chapter)
                context.book = row.book
            }
            if (!completePageList) {
                val next = links(context, spec.toc.string("nextTocUrl"), document, "ruleToc.nextTocUrl")
                    .filter { it != document.url }
                // Legado expands an initial page list once; a single link follows the next-page chain.
                completePageList = context.page == 1 && next.size > 1
                (if (completePageList) next else next.take(1)).forEach(queue::addLast)
            }
            context.page++
        }
        // The pinned default keeps the last occurrence, then restores the requested source order.
        val reverse = (context.book.metadata["readConfig"] as? JsonObject)?.get("reverseToc")?.jsonPrimitive?.booleanOrNull == true
        val ordered = (if (rule.startsWith('-')) chapters else chapters.reversed()).distinctBy { it.id }
            .let { if (reverse) it else it.reversed() }
        if (ordered.none { !it.isVolume }) throw SourceContentException(ContentError.EmptyContent, "ruleToc.chapterList")
        context.book = context.book.copy(metadata = JsonObject(context.book.metadata + ("totalChapterNum" to JsonPrimitive(ordered.size))))
        val formatted = mutableListOf<RuleChapter>()
        var gInt: JsonElement = JsonPrimitive(0)
        for ((index, chapter) in ordered.withIndex()) {
            currentCoroutineContext().ensureActive()
            val row = context.fork(chapterId = chapter.id); row.chapter = chapter.state
            row.chapterField("index", JsonPrimitive(index))
            val format = spec.toc.string("formatJs")
            val title = if (format.isBlank()) chapter.title else try {
                val value = row.script("""
                    (function(){var index=${index + 1},title=${JsonPrimitive(chapter.title)},gInt=$gInt;
                    var formatted=eval(${JsonPrimitive(scriptBody(format))});
                    return JSON.stringify({title:formatted==null?title:String(formatted),gInt:gInt});})()
                """.trimIndent(), RuleValue.Text(chapter.title), "ruleToc.formatJs").text()
                val state = Json.parseToJsonElement(value).jsonObject
                gInt = state.getValue("gInt")
                state.getValue("title").jsonPrimitive.content
            } catch (failure: SourceContentException) {
                if (failure.code != ContentError.InvalidRule) throw failure
                chapter.title
            }
            row.chapterField("title", JsonPrimitive(title))
            formatted += chapter.copy(title = title, state = row.chapter)
            context.book = row.book
        }
        interrupted?.let { throw PartialDirectoryException(DirectorySnapshot(formatted, context.book), it) }
        return initial.copy(book = initial.book.copy(state = context.book), chapters = formatted, document = null, partialDirectory = null)
    }

    private suspend fun record(id: String, reuseInformation: Boolean = false): BookRecord {
        val old = store.read(id)
        return if (old?.informationLoaded == true && old.revision == identity.revision) old
            else saveInformation(store.canonicalId(id), old, reuseInformation = reuseInformation)
    }
    internal fun evaluation(book: RuleBook? = null, chapter: RuleChapter? = null, keyword: String = "", page: Int = 1,
        interactive: Boolean = false, maxRuleCalls: Int = 65536, memory: ScriptMemory = ScriptMemory()): RuleEvaluation {
        val result = RuleEvaluation(identity, authority, session, runner, spec.library, book?.id, chapter?.id,
            book?.state ?: ScriptState(), chapter?.state ?: ScriptState(), book?.id ?: spec.baseUrl, keyword, page,
            headerRule = spec.header, interactive = interactive, trace = trace, sourceLoginUrl = spec.loginUrl,
            sourceComment = spec.comment, verification = ::verification, maxRuleCalls = maxRuleCalls,
            sourceName = definition.displayName, sourceLastUpdateTime = spec.lastUpdateTime, memory = memory)
        book?.let {
            result.bookField("bookUrl", it.id)
            if ("name" !in result.book.metadata) result.bookField("name", it.title)
            if ("tocUrl" !in result.book.metadata) result.bookField("tocUrl", it.tocUrl)
        }
        chapter?.let {
            if ("url" !in result.chapter.metadata) result.chapterField("url", JsonPrimitive(it.id))
            result.chapterField("title", JsonPrimitive(it.title)); result.chapterField("bookUrl", JsonPrimitive(book!!.id))
        }
        return result
    }
    private suspend fun request(context: RuleEvaluation, url: String, field: String, kind: ResourceKind = ResourceKind.Document,
        browser: BrowserOptions? = null): BrokerResponse = send(context, prepareRequest(context, url, field, kind, browser), field)

    /** Executes a prepared rule request and records the User-Agent its response was fetched with. */
    private suspend fun send(context: RuleEvaluation, request: BrokerRequest, field: String, route: SourceNetworkRoute? = null): BrokerResponse {
        val response = executeRequest(request, field, route = route)
        context.requestUserAgent = if (response.protocol == "data") null else session.requestUserAgent(response.finalUrl, request.headers)
        return response
    }

    private suspend fun prepareRequest(context: RuleEvaluation, url: String, field: String, kind: ResourceKind,
        browser: BrowserOptions? = null): BrokerRequest {
        currentCoroutineContext()[RequestRetryContext]?.let { retry ->
            val static = RequestCompiler().compile("replay", url, context.baseUrl)
            if (!ExecutionTask.BookOverviews.supports(url) || static !is CompiledRequest.Ready ||
                static.request.method !in setOf("GET", "HEAD") || static.request.browser != null) retry.disallowReplay()
        }
        val prepared = context.script("host.call('request.prepare',result)[0]", RuleValue.Text(url), field).text()
        val compiled = RequestCompiler().compile("content", prepared, context.baseUrl, context.keyword, context.page, context.headers(), kind)
        val request = when (compiled) {
            is CompiledRequest.Ready -> compiled.request
            is CompiledRequest.Rejected -> throw SourceContentException(if (compiled.code == hnovel.network.FailureCode.BrowserRequired)
                ContentError.BrowserRequired else ContentError.InvalidRule, field)
        }
        return request.copy(browser = browser ?: request.browser)
    }

    private suspend fun executeRequest(request: BrokerRequest, field: String, publicImage: Boolean = false,
        route: SourceNetworkRoute? = null): BrokerResponse {
        val started = System.nanoTime()
        val guard = RequestCommitGuard { authority.authorized(identity, it) }
        val result = if (publicImage) session.loadImage(request, guard)
            else if (route != null) session.executeOnRoute(request, guard, route)
            else session.execute(if (request.kind == ResourceKind.Image) request.copy(cache = CacheMode.Disabled) else request, guard)
        trace.record(ContentTraceEvent("network", field, (System.nanoTime() - started) / 1_000_000,
            request.body?.length ?: 0, (result as? BrokerResult.Success)?.response?.body?.size ?: 0,
            when (result) { is BrokerResult.Success -> when {
                result.response.kind == ResponseKind.BrowserDocument -> "BrowserDocument"
                result.response.protocol == "data" -> "Inline"
                else -> "HTTP_${result.response.status}"
            }; is BrokerResult.Failure -> result.code.name }))
        if (!authority.accepts(identity)) throw SourceContentException(ContentError.Unavailable, field)
        val response = when (result) {
            is BrokerResult.Success -> result.response
            is BrokerResult.Failure -> throw SourceContentException(result.code.contentError(), field, result.denial,
                verification = verification(result), retry = if (result.retryable) RequestRetryHint() else null,
                storageFailure = result.code.takeIf { it == hnovel.network.FailureCode.StorageQuota || it == hnovel.network.FailureCode.StorageUnavailable })
        }
        if (request.kind == ResourceKind.Image && response.status !in 200..299)
            throw SourceContentException(ContentError.Network, field, httpStatus = httpErrorStatus(response.status),
                retry = retryHint(request, response))
        return response
    }
    private suspend fun fetch(context: RuleEvaluation, url: String, field: String, browser: BrowserOptions? = null,
        acceptErrorResponse: Boolean = false, prepared: BrokerRequest? = null, route: SourceNetworkRoute? = null): PageDocument {
        val request = prepared ?: prepareRequest(context, url, field, ResourceKind.Document, browser)
        val response = send(context, request, field, route)
        return responseDocument(context, url, field, request, response, acceptErrorResponse)
    }

    private suspend fun responseDocument(context: RuleEvaluation, url: String, field: String, request: BrokerRequest,
        response: BrokerResponse, acceptErrorResponse: Boolean): PageDocument {
        val inline = response.protocol == "data"
        context.baseUrl = if (inline) url else response.finalUrl
        if (spec.loginCheck.isBlank()) {
            if (!acceptErrorResponse) checkStatus(response.status, field, response.kind == ResponseKind.BrowserDocument,
                retryHint(request, response))
            return PageDocument(response.text(), response.finalUrl, inline, context.baseUrl,
                response.status in 200..299 || response.status == 0 && response.kind == ResponseKind.BrowserDocument,
                httpErrorStatus(response.status))
        }
        // The pinned hook receives and returns StrResponse, including retry responses from java.connect.
        val snapshot = response.scriptSnapshot(binary = false)
        // Only this hook may explicitly re-execute the request it checks; the refetch does not run the hook again.
        context.currentRequest = request
        val checked = try { responseRule(httpErrorStatus(response.status)) {
            context.script("""
                result=host.call('response.view',JSON.parse(result));
                result=eval(${JsonPrimitive(scriptBody(spec.loginCheck))});
                JSON.stringify({body:result.getBody(),url:result.getUrl(),status:result.code(),
                    browserDocument:typeof result.isBrowserDocument==='function' && result.isBrowserDocument()});
            """.trimIndent(), RuleValue.Text(snapshot.toString()), "loginCheckJs").text()
        } } finally { context.currentRequest = null }
        val value = Json.parseToJsonElement(checked).jsonObject
        val status = value.getValue("status").jsonPrimitive.int
        if (!acceptErrorResponse) checkStatus(status, "loginCheckJs", value["browserDocument"]?.jsonPrimitive?.boolean == true)
        val finalUrl = sourceLink(response.finalUrl, value.getValue("url").jsonPrimitive.content)
        context.baseUrl = if (inline) url else finalUrl
        return PageDocument(value.getValue("body").jsonPrimitive.content, finalUrl, inline, context.baseUrl,
            httpErrorStatus = httpErrorStatus(status))
    }

    private fun verification(failure: BrokerResult.Failure): SourceVerification? {
        val certificate = failure.certificate
        if (failure.code == hnovel.network.FailureCode.Certificate && certificate != null) {
            return SourceVerification(null, certificate) {
                operation("certificate.verification") {
                    authority.authorized(identity) { session.approveCertificate(certificate) }
                }
            }
        }
        val kind = failure.challenge ?: return null
        val request = failure.verificationRequest ?: return null
        if (failure.code != hnovel.network.FailureCode.BrowserRequired) return null
        val origin = runCatching {
            val address = java.net.URL(request.url)
            java.net.URL(address.protocol.lowercase(), address.host.lowercase(),
                if (address.port == address.defaultPort) -1 else address.port, "/").toString()
        }.getOrNull()
        return SourceVerification(kind, origin = origin) {
            operation("browser.verification", timeoutMillis = 300000) {
                // Recovery opens immediately and finishes when the original extraction is ready.
                // Ordinary account/login windows keep their explicit completion behavior.
                val options = request.browser ?: BrowserOptions()
                val result = session.execute(request.copy(browser = options.copy(interactive = true,
                    title = definition.displayName, script = options.script.ifBlank { "document.documentElement.outerHTML" })),
                    RequestCommitGuard { authority.authorized(identity, it) })
                if (result is BrokerResult.Failure)
                    throw SourceContentException(result.code.contentError(), "browser.verification", result.denial,
                        verification = if (result.certificate != null) verification(result) else null,
                        storageFailure = result.code.takeIf { it == hnovel.network.FailureCode.StorageQuota || it == hnovel.network.FailureCode.StorageUnavailable })
                if (result is BrokerResult.Success)
                    checkStatus(result.response.status, "browser.verification", result.response.kind == ResponseKind.BrowserDocument)
                // A newer failed request must retain its own fallback target.
                authority.authorized(identity) {
                    // Completing a website flow preserves a session, not a universal authentication verdict.
                    check(session.write(StorageRequest(StorageArea.Account, "login/status", "session")) is StorageResult.Value)
                    val key = StorageRequest(StorageArea.Account, StorageRequestKey.BROWSER_PENDING_URL)
                    if ((session.read(key) as? StorageResult.Value)?.value == request.url) session.write(key)
                }
            }
        }
    }

    private fun retryHint(request: BrokerRequest, response: BrokerResponse): RequestRetryHint? =
        if (request.method in setOf("GET", "HEAD") && request.browser == null && response.kind == ResponseKind.Http &&
            response.status in setOf(429, 502, 503, 504)) RequestRetryHint(retryAfterMillis(response)) else null

    private fun checkStatus(status: Int, field: String, browserDocument: Boolean = false, retry: RequestRetryHint? = null) {
        if (browserDocument && status == 0) return
        // A public page can return 401/403 for a WAF or other access policy. Only an
        // explicit login request establishes an authentication failure for this account.
        if (status == 401 && (field == "loginUrl" || field.startsWith("loginUi."))) {
            authority.authorized(identity) { session.write(StorageRequest(StorageArea.Account, "login/status", "required")) }
            throw SourceContentException(ContentError.LoginRequired, field, httpStatus = httpErrorStatus(status))
        }
        if (status !in 200..299) throw SourceContentException(ContentError.Network, field, httpStatus = httpErrorStatus(status), retry = retry)
    }

    private fun httpErrorStatus(status: Int) = status.takeIf { it in 100..599 && it !in 200..299 }

    private fun checkEmptyResponse(books: List<RuleBook>, document: PageDocument, field: String) {
        if (books.isEmpty()) document.httpErrorStatus?.let { checkStatus(it, field) }
    }

    /** Scripts may recover from rejected responses. Keep the response context only when parsing fails;
     * syntax, permission, cancellation and resource failures retain their own recovery path. */
    private suspend fun <T> responseRule(status: Int?, block: suspend () -> T): T = try { block() }
    catch (failure: SourceContentException) {
        if (status != null && failure.code == ContentError.InvalidRule && failure.diagnostic?.code == hnovel.execution.FailureCode.ScriptRuntime)
            throw SourceContentException(ContentError.Network, failure.field, diagnostic = failure.diagnostic, httpStatus = status)
        throw failure
    }
    private suspend fun links(context: RuleEvaluation, rule: String, document: PageDocument, field: String): List<String> =
        if (rule.isBlank()) emptyList() else context.value(rule, document.input(), field, OutputKind.TextList).items()
            .map { it.text().trim() }.filter { it.isNotEmpty() }.map { sourceLink(document.url, it) }.distinct()
    private fun visit(visited: MutableSet<String>, url: String, field: String, maxPages: Int = 64) {
        if (!visited.add(url)) throw SourceContentException(ContentError.RepeatedPage, field)
        if (visited.size > maxPages) throw SourceContentException(ContentError.Limit, field)
    }
    // Host storage and orchestration use IO. The caller's priority dispatcher owns the outer
    // request only; nested timeout jobs must not compete with their parent for its last permit.
    internal suspend fun <T : Any> operation(field: String, timeoutMillis: Long = 60000, block: suspend () -> T): T = withContext(Dispatchers.IO) {
        val queuedAt = System.nanoTime()
        serial.withLock {
            trace.record(ContentTraceEvent("queue", field, (System.nanoTime() - queuedAt) / 1_000_000, result = "Serial"))
            if (field != "ruleBookInfo" && field != "ruleToc" && field != "bookAlias") {
                completedInformation = null; completedDirectory = null
            }
            try { checkedOperation(field, timeoutMillis, block) }
            catch (failure: Throwable) { completedInformation = null; completedDirectory = null; throw failure }
        }
    }
    private suspend fun <T : Any> checkedOperation(field: String, timeoutMillis: Long, block: suspend () -> T): T {
        if (!authority.accepts(identity)) throw SourceContentException(ContentError.Unavailable, field)
        if (field.startsWith("login") || field.startsWith("browser.") || field in setOf("discovery.action", "discovery.browser"))
            previewDocuments.clear()
        return try { withTimeoutOrNull(timeoutMillis) { block().also { currentCoroutineContext().ensureActive()
            if (!authority.accepts(identity)) throw SourceContentException(ContentError.Unavailable, field) } }
            ?: throw SourceContentException(ContentError.Limit, field) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: SourceContentException) { throw failure }
        catch (_: Exception) { throw SourceContentException(if (!authority.accepts(identity)) ContentError.Unavailable else ContentError.InvalidRule, field) }
    }
    override fun close() { authority.revoke(identity); previewDocuments.clear() }
}

private fun PageDocument.input() = RuleValue.Text(body)
private fun String.isScriptRule() = trimStart().let { it.startsWith("@js:", true) || it.startsWith("<js>", true) }
private fun String.truth() = isNotBlank() && this != "null" && trim().lowercase() !in setOf("false", "no", "not", "0", "0.0")
