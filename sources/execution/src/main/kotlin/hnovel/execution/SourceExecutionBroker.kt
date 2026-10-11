package hnovel.execution

import hnovel.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*

/** Host-owned per-invocation capability. A script cannot choose its session, identity or grants. */
class SourceExecutionBroker(val identity: ExecutionIdentity, private val authority: ExecutionAuthority,
    private val session: SourceSession, val limits: ExecutionLimits,
    private val baseUrl: String = "", private val keyword: String = "", private val page: Int = 1,
    private val allowInteraction: Boolean = false, private val speakText: String? = null,
    private val speakSpeed: Int = 10, private val sourceName: String = "", private val sourceLastUpdateTime: Long = 0,
    private val requestUserAgent: String? = null, currentRequest: BrokerRequest? = null,
    private val memory: ScriptMemory = ScriptMemory(), private val onMessage: ((String) -> Unit)? = null,
    private val retryContext: RequestRetryContext? = null) : AutoCloseable {
    private val currentRequest = currentRequest?.let { it.copy(headers = it.headers.toMap()) }
    // Waiting for a person must not increase a single HTTP request's network budget.
    private val requestTimeoutMillis = limits.timeoutMillis.coerceAtMost(60000)
    private val lifetime = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var requests = 0
    private val storageReads = mutableSetOf<Pair<StorageArea, String>>()
    private var closed = false
    var interactionRequired = false
        private set
    /** Host-owned reason for the latest bridge call only; a script cannot manufacture it.
     * Consumers use it only for BridgeDenied, never to override handled errors or successful rules. */
    @Volatile var requestFailure: BrokerResult.Failure? = null
        private set
    @Volatile var requestLimitExceeded = false
        private set
    @Volatile var responseLimitExceeded = false
        private set
    @Volatile var missingCacheRead = false
        private set

    init {
        require(identity.namespace == session.scope.namespace && identity.sourceId == session.scope.sourceId &&
            identity.profile == session.scope.profile && identity.accountGeneration == session.scope.accountGeneration)
        check(authority.accepts(identity) && !session.closed)
        authority.bindSession(identity, session)
    }

    /** The script-visible context and request compiler must describe the same invocation. */
    fun matchesTaskContext(task: ExecutionTask): Boolean = when (task) {
        is ExecutionTask.Script -> baseUrl == task.baseUrl && keyword == task.key && page == task.page &&
            speakText == task.speakText && speakSpeed == task.speakSpeed
        is ExecutionTask.Rule -> speakText == null && baseUrl == task.baseUrl && keyword == task.key && page == task.page
        is ExecutionTask.BookOverviews -> speakText == null && baseUrl == task.baseUrl
        is ExecutionTask.SearchBooks -> speakText == null && baseUrl == task.baseUrl
        else -> true
    }

    private fun <T> authorized(block: () -> T): T = authority.authorized(identity) {
        synchronized(this) {
            check(!closed && !session.closed) { "Execution retired" }
            block()
        }
    }

    suspend fun call(name: String, args: List<JsonElement>): JsonElement {
        requestFailure = null
        requestLimitExceeded = false
        responseLimitExceeded = false
        if (name != "request.withHeaders") return callWithHeaders(name, args, emptyMap())
        require(args.size == 3)
        val operation = args[0].jsonPrimitive.content
        require(operation in setOf("java.ajax", "java.ajaxAll", "java.connect", "java.cacheFile", "java.downloadFile", "java.importScript",
            "java.webView", "java.webViewGetSource", "java.webViewGetOverrideUrl", "java.startBrowser", "java.startBrowserAwait", "java.getVerificationCode", "browser.refetch", "java.getUserAgent"))
        return callWithHeaders(operation, args[1].jsonArray, headerMap(args[2]))
    }

    private suspend fun callWithHeaders(name: String, args: List<JsonElement>, sourceHeaders: Map<String, String>): JsonElement {
        if (name == "java.getUserAgent") {
            authorized { require(args.isEmpty()) }
            val value = requestUserAgent ?: ownedWork {
                session.requestUserAgent(baseUrl.ifBlank { session.sourceUrl }, sourceHeaders)
            }
            return authorized { JsonPrimitive(value) }
        }
        // BookSource.getKey only returns its URL. Per-image text replacement can read it
        // hundreds of times without making any requests or accessing persistent storage.
        if (name in setOf("source.getKey", "source.getBookSourceName", "source.getLastUpdateTime", "request.userAgent")) return authorized {
            require(args.isEmpty())
            when (name) {
                "source.getBookSourceName" -> JsonPrimitive(sourceName)
                "source.getLastUpdateTime" -> JsonPrimitive(sourceLastUpdateTime)
                "request.userAgent" -> requestUserAgent?.let(::JsonPrimitive) ?: JsonNull
                else -> JsonPrimitive(session.sourceUrl.ifBlank { baseUrl })
            }
        }
        // Memory values stay in this host object: no network, storage or request budget is used.
        if (name in setOf("cache.putMemory", "cache.getFromMemory", "cache.deleteMemory")) return authorized {
            require(args.size == if (name == "cache.putMemory") 2 else 1)
            val key = args[0].jsonPrimitive.content
            when (name) {
                "cache.getFromMemory" -> memory.get(key)?.let(::JsonPrimitive) ?: JsonNull
                "cache.putMemory" -> {
                    // Like cache.put, primitives are stored as text and null removes the key.
                    val value = args[1]
                    if (value == JsonNull) memory.delete(key) else memory.put(key, value.jsonPrimitive.content)
                    JsonNull
                }
                else -> { memory.delete(key); JsonNull }
            }
        }
        // List rules repeatedly consult the same settings. Charge each storage key once,
        // but read it again so writes, removals and TTL expiry remain visible.
        if (name in setOf("cache.get", "cache.getFile", "source.get", "source.getVariable")) return authorized {
            require(args.size == if (name == "source.getVariable") 0 else 1)
            val key = if (name == "source.getVariable") "variable"
                else (if (name == "cache.getFile") "file:" else "value:") + args[0].jsonPrimitive.content
            val area = if (name.startsWith("cache.")) StorageArea.Cache else StorageArea.Config
            val storageKey = area to key
            if (storageKey !in storageReads) {
                reserveRequest()
                storageReads += storageKey
            }
            val stored = session.read(StorageRequest(area, key))
            check(stored is StorageResult.Value) { "Storage read failed" }
            if (name == "cache.get" && stored.value.isNullOrEmpty()) missingCacheRead = true
            stored.value?.let(::JsonPrimitive) ?: if (!name.startsWith("cache.")) JsonPrimitive("") else JsonNull
        }
        val requestNumber = reserveRequest()
        return ownedWork {
            when (name) {
                "java.toast", "java.longToast" -> {
                    require(args.size == 1 && args.single() is JsonPrimitive && args.single().jsonPrimitive.isString)
                    session.showMessage(args.single().jsonPrimitive.content, name == "java.longToast",
                        RequestCommitGuard { action -> authorized(action) })
                    authorized { onMessage?.invoke(args.single().jsonPrimitive.content) }
                    JsonNull
                }
                "java.getWebViewUA" -> {
                    require(args.isEmpty())
                    JsonPrimitive(session.webViewUserAgent())
                }
                "java.getVerificationCode" -> {
                    if (!allowInteraction) {
                        interactionRequired = true
                        error("Foreground source login required")
                    }
                    require(args.size == 1)
                    val url = java.net.URI(baseUrl).resolve(args.single().jsonPrimitive.content).toString()
                    val response = fetch(BrokerRequest("verification-$requestNumber", url, headers = sourceHeaders,
                        browser = BrowserOptions(interactive = true, verificationCode = true)))
                    val value = response.text()
                    check(response.status in 200..299 && value.isNotBlank() && value.length <= 4096)
                    JsonPrimitive(value)
                }
                "java.startBrowser", "java.startBrowserAwait", "browser.refetch" -> {
                    if (!allowInteraction) {
                        interactionRequired = true
                        error("Foreground source login required")
                    }
                    require(if (name == "browser.refetch") args.size == 1
                        else args.size == 2 || name == "java.startBrowserAwait" && args.size == 3)
                    val request = compiled(requestNumber, args[0].jsonPrimitive.content, sourceHeaders)
                        .copy(id = "browser-$requestNumber", kind = ResourceKind.Document)
                    val options = if (name == "browser.refetch") null else BrowserOptions(interactive = true,
                        title = args[1].jsonPrimitive.content.also { require(it.length <= 1024) })
                    val response = fetch(request.copy(browser = options))
                    // When the worker will refetch, only signal completion; the rendered body is unused.
                    val refetch = name == "java.startBrowserAwait" && (args.getOrNull(2)?.jsonPrimitive?.boolean ?: true)
                    if (name == "java.startBrowser" || refetch) JsonNull else response.scriptSnapshot(true)
                }
                "java.webView", "java.webViewGetSource", "java.webViewGetOverrideUrl" -> {
                    require(args.size == if (name == "java.webView") 3 else 4)
                    fun string(index: Int) = args.getOrNull(index)?.takeUnless { it == JsonNull }?.jsonPrimitive?.content.orEmpty()
                    val url = string(1).ifBlank { baseUrl }
                    val options = BrowserOptions(script = string(2), html = string(0).takeIf { it.isNotBlank() },
                        sourceRegex = string(3), overrideUrl = name == "java.webViewGetOverrideUrl", nativeWebsite = string(0).isBlank())
                    JsonPrimitive(fetch(BrokerRequest("script-$requestNumber", url, headers = sourceHeaders, browser = options)).text())
                }
                "source.getLoginInfo", "source.getLoginInfoMap", "source.getLoginHeader", "source.getLoginHeaderMap" -> authorized {
                    require(args.isEmpty())
                    val key = if (name.contains("LoginInfo")) StorageRequestKey.LOGIN_INFO else StorageRequestKey.LOGIN_HEADERS
                    val stored = session.read(StorageRequest(StorageArea.Account, key))
                    check(stored is StorageResult.Value)
                    if (name == "source.getLoginInfoMap") LoginInfo.stringFields(stored.value) ?: JsonNull
                    else if (name.endsWith("Map")) stored.value?.let(Json::parseToJsonElement) ?: JsonNull
                    else stored.value?.let(::JsonPrimitive) ?: JsonNull
                }
                "source.putLoginInfo", "source.putLoginHeader", "source.removeLoginInfo", "source.removeLoginHeader" -> authorized {
                    val removing = name.contains("remove")
                    require(args.size == if (removing) 0 else 1)
                    val info = name.endsWith("Info")
                    val value = if (removing) null else args.single().jsonPrimitive.also { require(it.isString) }.content.also { text ->
                        if (info) LoginInfo.validate(text) else {
                            require(text.length <= 16384)
                            val data = Json.parseToJsonElement(BridgeWire.validate(text.toByteArray())).jsonObject
                            require(data.size <= 32 && data.all { (key, value) ->
                                key.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) &&
                                    value is JsonPrimitive && value.isString && value.content.all { it == '\t' || it.code in 32..126 }
                            })
                        }
                    }
                    val key = if (info) StorageRequestKey.LOGIN_INFO else StorageRequestKey.LOGIN_HEADERS
                    check(session.write(StorageRequest(StorageArea.Account, key, value)) is StorageResult.Value)
                    if (!info) {
                        val url = session.sourceUrl.ifBlank { baseUrl }
                        if (removing) session.removeCookie(url)
                        else Json.parseToJsonElement(value!!).jsonObject.entries.firstOrNull { it.key.equals("Cookie", true) }
                            ?.let { session.setCookie(url, it.value.jsonPrimitive.content) }
                    }
                    if (info && !removing) JsonPrimitive(true) else JsonNull
                }
                "cookie.setWebCookie" -> {
                    require(args.size == 2 && args.all { it is JsonPrimitive && it.isString })
                    val value = args[1].jsonPrimitive.content
                    require(value.length <= 65536)
                    fetch(BrokerRequest("cookie-$requestNumber", args[0].jsonPrimitive.content,
                        browser = BrowserOptions(nativeWebsite = true, webCookie = value)))
                    JsonNull
                }
                "cookie.getCookie", "java.getCookie", "cookie.getKey", "cookie.setCookie", "cookie.replaceCookie", "cookie.removeCookie" -> authorized {
                    val required = if (name in setOf("cookie.getKey", "cookie.setCookie", "cookie.replaceCookie")) 2 else 1
                    require(args.size == required || name == "java.getCookie" && args.size == 2)
                    val url = args[0].jsonPrimitive.content
                    when (name) {
                        "cookie.getCookie", "java.getCookie", "cookie.getKey" -> {
                            val key = args.getOrNull(1)?.takeUnless { it == JsonNull }?.jsonPrimitive?.also { require(it.isString) }?.content
                            val value = session.cookie(url)
                            JsonPrimitive(if (key == null) value else value.split(';').map { it.trim().split('=', limit = 2) }
                                .firstOrNull { it.size == 2 && it[0] == key }?.get(1).orEmpty())
                        }
                        "cookie.removeCookie" -> { session.removeCookie(url); JsonNull }
                        else -> { session.setCookie(url, args[1].jsonPrimitive.content, name == "cookie.setCookie"); JsonNull }
                    }
                }
                "resource.storeArchive", "resource.readArchive" -> archiveResource(name, args)
                "java.importScript", "java.cacheFile", "java.downloadFile", "java.readFile", "java.readTxtFile", "java.deleteFile" ->
                    resource(name, args, requestNumber, sourceHeaders)
                "java.androidId" -> authorized {
                    require(args.isEmpty())
                    val value = session.installationIdentifier()
                    check(value is StorageResult.Value && value.value != null) { "Source identifier unavailable" }
                    JsonPrimitive(value.value)
                }
                "java.ajax" -> {
                    require(args.size == 1)
                    val url = (args[0] as? JsonArray)?.firstOrNull() ?: args[0]
                    JsonPrimitive(fetch(compiled(requestNumber, url.jsonPrimitive.content, sourceHeaders)).text())
                }
                "java.getStrResponse" -> {
                    // Only the current request's default execution is supported. Never take a URL
                    // or re-run loginCheckJs from this data-only host call.
                    require(args.size <= 2 && args.all { it == JsonNull })
                    val request = checkNotNull(currentRequest) { "No current request" }
                    if (request.browser?.interactive == true && !allowInteraction) {
                        interactionRequired = true
                        error("Foreground source login required")
                    }
                    fetch(request.copy(id = "refetch-$requestNumber", retry = 0, cache = CacheMode.Disabled),
                        limits.scriptDataLimit).scriptSnapshot(false)
                }
                "java.connect" -> {
                    require(args.size in 1..2)
                    val headers = args.getOrNull(1)?.takeUnless { it == JsonNull }?.let {
                        check(it is JsonPrimitive && it.isString)
                        // Use the same bounded JSON parser as other reverse IPC input.
                        headerMap(BridgeWire.arguments("[${it.content}]".toByteArray()).single())
                    } ?: sourceHeaders
                    val request = compiled(requestNumber, args[0].jsonPrimitive.content, headers)
                    fetch(request, limits.scriptDataLimit).scriptSnapshot(false)
                }
                "java.ajaxAll" -> {
                    require(args.size == 1)
                    val urls = args[0].jsonArray
                    val requests = urls.mapIndexed { index, url ->
                        compiled(if (index == 0) requestNumber else reserveRequest(), url.jsonPrimitive.content, sourceHeaders)
                    }
                    // Reserve and compile the complete batch before dispatch; a rejected budget
                    // cannot send a prefix. Session permits bound actual network concurrency.
                    val responseBytes = java.util.concurrent.atomic.AtomicLong(2)
                    val decoding = Semaphore(4)
                    coroutineScope {
                        val pending = requests.map { request -> async {
                            decoding.withPermit {
                                fetch(request, limits.scriptDataLimit).scriptSnapshot(false).also {
                                    if (responseBytes.addAndGet(it.toString().toByteArray().size.toLong() + 1) >
                                        (limits.maxDataBytes ?: BridgeWire.MAX_BYTES)) {
                                        responseLimitExceeded = true
                                        error("Batch response too large")
                                    }
                                }
                            }
                        } }
                        JsonArray(pending.awaitAll())
                    }
                }
                "java.get", "java.head", "java.post" -> {
                    val post = name == "java.post"
                    require(args.size == if (post) 3 else 2)
                    val request = BrokerRequest("script-$requestNumber", args[0].jsonPrimitive.content,
                        method = name.substringAfter('.').uppercase(), headers = headerMap(args[if (post) 2 else 1]),
                        body = if (post) args[1].jsonPrimitive.content else null, followRedirects = false, kind = ResourceKind.Api)
                    fetch(request, limits.scriptDataLimit).scriptSnapshot(true)
                }
                "cache.put", "cache.putFile", "source.put", "cache.delete", "source.setVariable", "source.putVariable" -> authorized {
                    val variable = name in setOf("source.setVariable", "source.putVariable")
                    val deletion = name == "cache.delete"
                    require(args.size == (if (variable || deletion) 1 else 2) || name in setOf("cache.put", "cache.putFile") && args.size == 3)
                    val key = if (variable) "variable"
                        else (if (name == "cache.putFile") "file:" else "value:") + args[0].jsonPrimitive.content
                    val value = if (deletion) null else args[if (variable) 0 else 1].let {
                        if (it == JsonNull) null else it.jsonPrimitive.content
                    }
                    val ttl = if (name in setOf("cache.put", "cache.putFile")) Math.multiplyExact(args.getOrNull(2)?.jsonPrimitive?.int?.toLong() ?: 0, 1000) else null
                    val area = if (name.startsWith("cache.")) StorageArea.Cache else StorageArea.Config
                    check(session.write(StorageRequest(area, key, value, ttl)) is StorageResult.Value) { "Storage write failed" }
                    if (deletion) check(session.write(StorageRequest(StorageArea.Cache,
                        "file:" + args[0].jsonPrimitive.content)) is StorageResult.Value) { "Storage write failed" }
                    if (name == "source.put") JsonPrimitive(value.orEmpty()) else JsonNull
                }
                else -> error("Unknown bridge operation")
            }
        }
    }

    private fun compiled(number: Int, rule: String, headers: Map<String, String>): BrokerRequest {
        val compiled = RequestCompiler().compile("script-$number", rule, baseUrl, keyword, page, headers, kind = ResourceKind.Api,
            speakText = speakText, speakSpeed = speakSpeed, expandTemplates = speakText == null)
        check(compiled is CompiledRequest.Ready) { "Request requires an unsupported option" }
        return compiled.request
    }

    private fun headerMap(value: JsonElement): Map<String, String> = value.jsonObject.mapValues { (_, item) ->
        check(item is JsonPrimitive && item.isString)
        item.content
    }

    private suspend fun fetch(request: BrokerRequest, maxResponseBytes: Int = limits.maxDataBytes ?: BridgeWire.MAX_BYTES): BrokerResponse {
        val result = session.execute(request.copy(timeoutMillis = requestTimeoutMillis, maxResponseBytes = minOf(maxResponseBytes, BrokerLimits.DEFAULT_MAX_RESPONSE_BYTES)), RequestCommitGuard { action -> authorized(action) })
        if (result is BrokerResult.Failure) requestFailure = result
        check(result is BrokerResult.Success) { "Broker request failed" }
        return result.response
    }

    private fun checkPermission(url: String, kind: ResourceKind = ResourceKind.Document) {
        session.permissionFailureDetail(url, kind)?.let { failure ->
            requestFailure = failure
            error("Resource permission denied")
        }
    }

    /** Logical source/account resources. A script path is never passed to the host filesystem. */
    private suspend fun resource(name: String, args: List<JsonElement>, number: Int, sourceHeaders: Map<String, String>): JsonElement {
        require(args.size in 1..if (name in setOf("java.downloadFile", "java.cacheFile", "java.readTxtFile")) 2 else 1)
        val first = args[0].jsonPrimitive.content
        if (first.removePrefix("/").startsWith("archives/") && name in setOf("java.readFile", "java.readTxtFile", "java.deleteFile"))
            return archiveResource(name, args)
        fun key(path: String): String {
            require(path.matches(Regex("/?resources/[0-9a-f]{64}\\.[a-zA-Z0-9]{1,12}"))) { "Invalid resource path" }
            return "resource:" + path.removePrefix("/")
        }
        fun read(path: String): JsonObject? = authorized {
            val stored = session.read(StorageRequest(StorageArea.Config, key(path)))
            check(stored is StorageResult.Value) { "Resource storage unavailable" }
            stored.value?.let { Json.parseToJsonElement(it).jsonObject }
        }
        fun checkGrants(record: JsonObject) = authorized {
            for (field in listOf("url", "finalUrl")) checkPermission(record.getValue(field).jsonPrimitive.content)
        }
        fun bytes(record: JsonObject) = java.util.Base64.getDecoder().decode(record.getValue("bytes").jsonPrimitive.content)
        fun text(record: JsonObject, charset: String? = null): String = bytes(record).toString(java.nio.charset.Charset.forName(
            charset ?: record.getValue("charset").jsonPrimitive.content))
        if (name in setOf("java.readFile", "java.readTxtFile", "java.deleteFile") || name == "java.importScript" && !first.startsWith("http", true)) {
            val record = read(first)
            if (name == "java.deleteFile") return authorized {
                check(session.write(StorageRequest(StorageArea.Config, key(first))) is StorageResult.Value)
                JsonPrimitive(record != null)
            }
            if (record == null) {
                check(name != "java.importScript") { "Script resource missing" }
                return if (name == "java.readFile") JsonNull else JsonPrimitive("")
            }
            checkGrants(record)
            if (name == "java.readFile") return JsonArray(bytes(record).map { JsonPrimitive(it.toInt()) })
            return JsonPrimitive(text(record, args.getOrNull(1)?.jsonPrimitive?.content).also {
                check(name != "java.importScript" || it.isNotBlank()) { "Script resource empty" }
            })
        }
        val rawUrl = if (name == "java.downloadFile" && args.size == 2) args[1].jsonPrimitive.content else first
        val optionStart = Regex(",\\s*(?=\\{)").find(rawUrl)
        val options = optionStart?.let { BridgeWire.arguments(("[" + rawUrl.substring(it.range.last + 1) + "]").toByteArray()).single().jsonObject }
        val type = options?.get("type")?.jsonPrimitive?.content
        if (name == "java.downloadFile" && args.size == 2 && type == null) return JsonPrimitive("")
        val suffix = type ?: rawUrl.substring(0, optionStart?.range?.first ?: rawUrl.length).substringBefore('?').substringAfterLast('/', "").substringAfterLast('.', "bin")
        require(suffix.matches(Regex("[a-zA-Z0-9]{1,12}"))) { "Invalid resource type" }
        val rule = if (options != null) rawUrl.substring(0, optionStart!!.range.first) + "," + JsonObject(options - "type") else rawUrl
        val request = compiled(number, rule, sourceHeaders).copy(kind = ResourceKind.Script)
        authorized { checkPermission(request.url, request.kind) }
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(rawUrl.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val path = "/resources/$hash.$suffix"
        val cache = name == "java.cacheFile" || name == "java.importScript"
        val seconds = if (name == "java.cacheFile") args.getOrNull(1)?.jsonPrimitive?.long ?: 0 else 0
        require(seconds >= 0)
        val cacheDuration = Math.multiplyExact(seconds, 1000)
        if (cache) read(path)?.let { record ->
            val until = record["cacheUntil"]?.jsonPrimitive?.long
            if (until != null && (until == 0L || until > System.currentTimeMillis())) {
                checkGrants(record)
                return JsonPrimitive(text(record).also { check(name != "java.importScript" || it.isNotBlank()) })
            }
        }
        val response = if (name == "java.downloadFile" && args.size == 2) {
            val hex = first
            require(hex.length % 2 == 0 && hex.length <= BridgeWire.MAX_BYTES * 2)
            val data = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            BrokerResponse(200, request.url, emptyMap(), data, "UTF-8", 0)
        } else fetch(request).also { check(it.status in 200..299) { "Resource download failed" } }
        val record = buildJsonObject {
            put("url", request.url); put("finalUrl", response.finalUrl); put("charset", response.charset)
            put("bytes", java.util.Base64.getEncoder().encodeToString(response.body))
            if (cache) put("cacheUntil", if (seconds == 0L) 0 else Math.addExact(System.currentTimeMillis(), cacheDuration))
        }
        authorized { check(session.write(StorageRequest(StorageArea.Config, key(path), record.toString())) is StorageResult.Value) { "Resource quota exceeded" } }
        return if (name == "java.downloadFile") JsonPrimitive(path) else JsonPrimitive(response.text().also {
            check(name != "java.importScript" || it.isNotBlank()) { "Script resource empty" }
        })
    }

    /** One account-storage record per extracted archive makes publication and quota failure atomic. */
    private fun archiveResource(name: String, args: List<JsonElement>): JsonElement = authorized {
        require(args.size == if (name == "resource.storeArchive") 2 else 1)
        val path = args[0].jsonPrimitive.content.removePrefix("/")
        fun read(key: String): JsonObject? {
            val value = session.read(StorageRequest(StorageArea.Config, key))
            check(value is StorageResult.Value)
            return value.value?.let { Json.parseToJsonElement(it).jsonObject }
        }
        fun grants(record: JsonObject) {
            for (field in listOf("url", "finalUrl")) checkPermission(record.getValue(field).jsonPrimitive.content)
        }
        if (name == "resource.storeArchive") {
            require(path.matches(Regex("resources/[0-9a-f]{64}\\.[a-zA-Z0-9]{1,12}")))
            val original = read("resource:$path") ?: error("Archive missing")
            grants(original)
            val files = args[1].jsonObject
            require(files.size <= 256)
            var size = 0L
            files.forEach { (entry, value) ->
                hnovel.rhino.ArchiveDecoder.validatePath(entry)
                require(value.jsonPrimitive.isString)
                size += java.util.Base64.getDecoder().decode(value.jsonPrimitive.content).size
                require(size <= BridgeWire.MAX_BYTES)
            }
            // Each extraction owns a distinct record, even for the same downloaded URL.
            val directory = "archives/" + java.security.MessageDigest.getInstance("SHA-256")
                .digest(java.util.UUID.randomUUID().toString().toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            val record = buildJsonObject {
                put("url", original.getValue("url")); put("finalUrl", original.getValue("finalUrl")); put("files", files)
            }
            check(session.write(StorageRequest(StorageArea.Config, "resource:$directory", record.toString())) is StorageResult.Value)
            return@authorized JsonPrimitive("/$directory")
        }
        val match = Regex("(archives/[0-9a-f]{64})(?:/(.+))?").matchEntire(path) ?: error("Invalid archive path")
        val directory = match.groupValues[1]
        val entry = match.groupValues[2].takeIf(String::isNotEmpty)
        entry?.let(hnovel.rhino.ArchiveDecoder::validatePath)
        val record = read("resource:$directory")
        if (name == "java.deleteFile") {
            if (record == null) return@authorized JsonPrimitive(false)
            val files = record.getValue("files").jsonObject
            if (entry != null && entry !in files) return@authorized JsonPrimitive(false)
            val updated = if (entry == null) null else JsonObject(record + ("files" to JsonObject(files - entry))).toString()
            check(session.write(StorageRequest(StorageArea.Config, "resource:$directory", updated)) is StorageResult.Value)
            return@authorized JsonPrimitive(true)
        }
        if (record == null) return@authorized JsonNull
        grants(record)
        val files = record.getValue("files").jsonObject
        if (name == "resource.readArchive") { require(entry == null); return@authorized files }
        require(entry != null)
        val content = files[entry] ?: return@authorized JsonNull
        val bytes = java.util.Base64.getDecoder().decode(content.jsonPrimitive.content)
        if (name == "java.readFile") JsonArray(bytes.map { JsonPrimitive(it.toInt()) }) else JsonPrimitive(bytes.toString(Charsets.UTF_8))
    }

    /** Downloads data only; library code is evaluated exclusively in the isolated worker. */
    suspend fun loadLibrary(definition: String): List<String> = ownedWork {
        requestFailure = null
        var size = 0L
        SourceLibraryDefinition.urls(definition).map { url ->
            val requestNumber = reserveRequest()
            checkPermission(url, ResourceKind.Script)
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            val key = "library:$digest"
            val cached = authorized { session.read(StorageRequest(StorageArea.Config, key)) }
            check(cached is StorageResult.Value) { "Library cache unavailable" }
            val entry = cached.value?.let { Json.parseToJsonElement(it).jsonObject } ?: run {
                val response = session.execute(BrokerRequest("library-$requestNumber", url,
                    timeoutMillis = requestTimeoutMillis, kind = ResourceKind.Script), RequestCommitGuard { action -> authorized(action) })
                if (response is BrokerResult.Failure) requestFailure = response
                check(response is BrokerResult.Success && response.response.status in 200..299) { "Library download failed" }
                val code = response.response.text()
                if (code.length.toLong() + 1 + size > SourceLibraryDefinition.MAX_CHARS) throw LibraryTooLarge()
                buildJsonObject { put("url", response.response.finalUrl); put("code", code) }.also { entry ->
                    authorized {
                        check(session.write(StorageRequest(StorageArea.Config, key, entry.toString())) is StorageResult.Value) {
                            "Library cache quota exceeded"
                        }
                    }
                }
            }
            checkPermission(entry.getValue("url").jsonPrimitive.content, ResourceKind.Script)
            entry.getValue("code").jsonPrimitive.content.also { code ->
                size += code.length.toLong() + 1
                if (size > SourceLibraryDefinition.MAX_CHARS) throw LibraryTooLarge()
            }
        }
    }

    private fun reserveRequest(): Int = authorized {
        if (++requests > limits.maxRequests) {
            requestLimitExceeded = true
            error("Request budget exceeded")
        }
        requests
    }

    private suspend fun <T> ownedWork(block: suspend () -> T): T {
        authorized { }
        val work = lifetime.async(retryContext ?: kotlin.coroutines.EmptyCoroutineContext) { block() }
        return try { work.await().let { value -> authorized { value } } } finally { work.cancel() }
    }

    @Synchronized override fun close() {
        closed = true
        lifetime.cancel()
    }
}
