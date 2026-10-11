package hnovel.rhino

import org.mozilla.javascript.*
import kotlinx.serialization.json.*
import hnovel.rules.*

/** Synchronous data-only port. The host binds authority; scripts cannot supply a source ticket. */
fun interface HostBridge {
    fun call(name: String, args: List<JsonElement>): JsonElement
    companion object { val None = HostBridge { _, _ -> error("No host broker") } }
}

private class BridgeRejected(value: Any, val hostCall: ScriptHostCall?) : JavaScriptException(value, "host-bridge", 1)
private class RequestRejected(value: Any, val hostCall: ScriptHostCall?) : JavaScriptException(value, "request-options", 1)

internal val bridgeLimitKey = Any()
internal val scriptLibraryKey = Any()
internal val scriptDeadlineKey = Any()

private class ScriptBridge(private val bridge: HostBridge, private val rules: ScriptRuleHelpers, private val requests: ScriptRequestTemplates, archives: ArchiveDecoder) {
    private val resources = ScriptResources(bridge, requests, archives)
    private val fonts = ScriptFonts(resources::download)
    private val knownMethods = mutableSetOf("java.setContent", "java.put", "java.toast", "java.longToast",
        "cache.getFile", "cache.putFile", "request.prepare", "request.speech", "request.headers", "response.view")

    private fun describe(name: String, args: Array<out Any?>): ScriptHostCall? =
        if (name !in knownMethods) null else ScriptHostCall(name, args.size, args.take(8).map { value -> when (value) {
            null -> ScriptArgumentType.Null
            is Undefined -> ScriptArgumentType.Undefined
            is Boolean -> ScriptArgumentType.Boolean
            is Number -> ScriptArgumentType.Number
            is CharSequence -> ScriptArgumentType.String
            is NativeArray -> ScriptArgumentType.Array
            is org.mozilla.javascript.Function -> ScriptArgumentType.Function
            is Scriptable -> ScriptArgumentType.Object
            else -> ScriptArgumentType.Unknown
        } })

    fun call(cx: Context, scope: Scriptable, name: String, args: Array<out Any>): Any? {
            val maxChars = cx.getThreadLocal(bridgeLimitKey) as Int
            if (name.length > 256) throw ResultTooLarge()
            val realm = ScriptRealm.current(cx)
            val pureTool = name.startsWith("java.") && name.removePrefix("java.") in ScriptTools.methods
            return try {
                if (name.startsWith("java.") && name.substringAfter("java.") in fonts.methods)
                    return fonts.call(cx, scope, name.substringAfter("java."), args)
                val data = BoundedJsonResult(maxChars).encode(realm.arrayIn(scope, args.copyOf()))
                val arguments = Json.parseToJsonElement(data).jsonArray
                if (name == "response.view") {
                    require(arguments.size == 1)
                    ScriptResponses.validate(arguments.single(), maxChars)
                    return ScriptResponses.create(cx, scope, arguments.single().jsonObject, false)
                }
                if (name == "java.toURL") return ScriptUrls.create(cx, scope, arguments)
                if (name.removePrefix("java.") in ScriptCryptoObjects.factories && name.startsWith("java."))
                    return ScriptCryptoObjects.create(cx, scope, name.removePrefix("java."), arguments)
                val result = if (name == "request.prepare") JsonArray(requests.prepare(cx, "java.connect", arguments))
                else if (name == "request.speech") requests.speech(cx, arguments)
                else if (name == "request.headers") { require(arguments.isEmpty()); requests.headers(cx) }
                else if (name == "java.readTxtFile") resources.text(cx, arguments)
                else if (name.startsWith("java.") && name.substringAfter("java.") in resources.methods) resources.archive(cx, name.substringAfter("java."), arguments)
                else if (rules.supports(name, arguments)) rules.call(cx, name, arguments,
                    (args.getOrNull(if (name == "java.setContent") 0 else 1) as? ScriptDomElement)?.element?.ruleNode())
                else if (pureTool) ScriptTools.call(name.removePrefix("java."), arguments) else requests.call(cx, bridge, name, arguments)
                if (Thread.currentThread().isInterrupted) throw ScriptCancelled()
                val networkResponse = name in setOf("java.ajaxAll", "java.connect", "java.startBrowserAwait", "java.getStrResponse") ||
                    name in setOf("java.get", "java.head", "java.post") && args.size >= 2
                if (networkResponse) ScriptResponses.validate(result, maxChars)
                val converted = if (networkResponse) null else JsonScriptData(cx, scope, maxChars).convert(result)
                when {
                    name in setOf("source.getLoginInfoMap", "source.getLoginHeaderMap") && result != JsonNull ->
                        ScriptData.map(cx, scope, result.jsonObject.mapValues { it.value.jsonPrimitive.content }.toMutableMap())
                    name == "java.getElement" || name == "java.getElements" -> rules.elementView(cx, scope, result)
                    name == "java.ajaxAll" -> realm.arrayIn(scope, result.jsonArray.map { ScriptResponses.create(cx, scope, it.jsonObject, false) }.toTypedArray())
                    name == "java.startBrowserAwait" -> ScriptResponses.create(cx, scope, result.jsonObject, true, browserCompleted = true)
                    name in setOf("java.connect", "java.getStrResponse") -> ScriptResponses.create(cx, scope, result.jsonObject, false)
                    name in setOf("java.get", "java.head", "java.post") && args.size >= 2 -> ScriptResponses.create(cx, scope, result.jsonObject, true)
                    else -> converted
                }
            }
                catch (_: ArchiveSizeLimitExceeded) { throw ResultTooLarge(describe(name, args)) }
                catch (large: ResultTooLarge) { if (large.hostCall == null) large.hostCall = describe(name, args); throw large }
                catch (unsupported: UnsupportedResult) { if (unsupported.hostCall == null) unsupported.hostCall = describe(name, args); throw unsupported }
                catch (cancelled: java.util.concurrent.CancellationException) { throw ScriptCancelled() }
                catch (_: RequestOptionsException) {
                    if (Thread.currentThread().isInterrupted) throw ScriptCancelled()
                    throw RequestRejected(realm.errorIn(scope, "invalid request options"), describe(name, args))
                }
                catch (rejected: RequestRejected) { throw rejected }
                // request.prepare can evaluate nested source code. Preserve its script/bridge
                // exception instead of relabelling a caught network error followed by a source error.
                catch (error: JavaScriptException) { throw error }
                catch (_: Exception) {
                    if (Thread.currentThread().isInterrupted) throw ScriptCancelled()
                    if (pureTool || name.removePrefix("java.") in ScriptCryptoObjects.factories) throw JavaScriptException(realm.errorIn(scope, "invalid tool argument"), "script-tool", 1)
                    throw BridgeRejected(realm.errorIn(scope, "host bridge denied"), describe(name, args))
                }
    }

    fun install(context: Context, scope: Scriptable, frame: ScriptFrame) {
        val realm = ScriptRealm.current(context)
        fun method(target: ScriptableObject, name: String, action: (Context, Scriptable, Array<out Any>) -> Any?) {
            target.defineProperty(name, realm.method(scope, action), ScriptableObject.READONLY or ScriptableObject.PERMANENT)
        }
        fun objectFor(name: String, methods: List<String>): ScriptableObject {
            val target = realm.objectIn(scope)
            knownMethods += methods.map { "$name.$it" }
            methods.forEach { member -> method(target, member) { cx, activeScope, args -> call(cx, activeScope, "$name.$member", args) } }
            scope.put(name, scope, target)
            return target
        }
        val host = objectFor("host", emptyList())
        method(host, "call") { cx, activeScope, args ->
            require(args.isNotEmpty() && args[0] is CharSequence) { "bridge name required" }
            call(cx, activeScope, args[0].toString(), args.drop(1).toTypedArray())
        }
        val javaBridge = objectFor("java", listOf("ajax", "ajaxAll", "connect", "get", "head", "post", "getCookie", "androidId",
            "getString", "getStringList", "getElement", "getElements", "importScript", "cacheFile", "downloadFile",
            "readFile", "readTxtFile", "deleteFile", "toURL", "webView", "webViewGetSource", "webViewGetOverrideUrl",
            "startBrowser", "startBrowserAwait", "getVerificationCode", "getWebViewUA", "getUserAgent", "getStrResponse", "getUrl") + ScriptTools.methods + ScriptCryptoObjects.factories + fonts.methods + resources.methods)
        method(javaBridge, "setContent") { cx, activeScope, args ->
            call(cx, activeScope, "java.setContent", args)
            javaBridge
        }
        method(javaBridge, "put") { cx, activeScope, args ->
            require(args.size == 2)
            // AnalyzeRule.put(String, String) uses Rhino's String conversion, including
            // the one-item array returned by a book ID regular-expression match.
            call(cx, activeScope, "java.put", arrayOf(Context.toString(args[0]), Context.toString(args[1])))
        }
        listOf("toast", "longToast").forEach { name ->
            method(javaBridge, name) { cx, activeScope, args ->
                require(args.size == 1)
                // Convert before the data bridge: native Legado accepts objects, including cycles.
                call(cx, activeScope, "java.$name", arrayOf(Context.toString(args[0])))
                Undefined.instance
            }
        }
        // Legado log is also an identity expression. Keep source text out of host diagnostics.
        method(javaBridge, "log") { _, _, args ->
            require(args.size == 1)
            args[0]
        }
        val cache = objectFor("cache", listOf("get", "put", "delete", "putMemory", "getFromMemory", "deleteMemory"))
        for (name in listOf("getFile", "putFile")) method(cache, name) { cx, activeScope, args ->
            require(if (name == "getFile") args.size == 1 else args.size in 2..3)
            val strings = if (name == "getFile") 1 else 2
            // CacheManager's file API takes Java strings, including comma-joined JS arrays.
            val values = args.mapIndexed { index, value ->
                if (index < strings) { require(value != null); Context.toString(value) } else value
            }.toTypedArray()
            call(cx, activeScope, "cache.$name", values)
        }
        objectFor("cookie", listOf("getCookie", "getKey", "setCookie", "replaceCookie", "removeCookie", "setWebCookie"))
        val source = objectFor("source", listOf("get", "put", "getVariable", "setVariable", "putVariable", "getKey", "getBookSourceName", "getLastUpdateTime", "getLoginInfo", "getLoginInfoMap",
            "putLoginInfo", "removeLoginInfo", "getLoginHeader", "getLoginHeaderMap", "putLoginHeader", "removeLoginHeader"))
        source.defineProperty("id", frame.sourceId, ScriptableObject.READONLY)
        source.defineProperty("profile", frame.profile, ScriptableObject.READONLY)
        for (name in listOf("key", "bookSourceUrl")) source.defineProperty(name, java.util.function.Supplier<Any?> {
            call(Context.getCurrentContext(), scope, "source.getKey", emptyArray())
        }, null, ScriptableObject.PERMANENT)
        for ((name, getter) in listOf("bookSourceName" to "getBookSourceName", "lastUpdateTime" to "getLastUpdateTime"))
            source.defineProperty(name, java.util.function.Supplier<Any?> {
                call(Context.getCurrentContext(), scope, "source.$getter", emptyArray())
            }, null, ScriptableObject.PERMANENT)
        // Definitions can explicitly eval this prelude from search/discovery. Reading it does not
        // initiate login, and the worker never receives a mutable Android Source object.
        source.defineProperty("loginUrl", frame.sourceLoginUrl, ScriptableObject.READONLY or ScriptableObject.PERMANENT)
        method(source, "getLoginUrl") { _, _, args -> require(args.isEmpty()); frame.sourceLoginUrl }
        method(source, "getHeader") { _, _, args -> require(args.isEmpty()); frame.sourceHeaderRule }
        source.defineProperty("bookSourceComment", frame.sourceComment, ScriptableObject.READONLY or ScriptableObject.PERMANENT)
        method(source, "getBookSourceComment") { _, _, args -> require(args.isEmpty()); frame.sourceComment }
        method(javaBridge, "getSource") { _, _, args -> require(args.isEmpty()); source }
        frame.discovery?.install(context, scope, javaBridge, source)
        if (frame.discovery != null) method(javaBridge, "removeCookie") { cx, active, args ->
            call(cx, active, "cookie.removeCookie", args)
        }
    }
}

data class ScriptFrame(val sourceId: String, val profile: String, val bookId: String? = null, val chapterId: String? = null,
    val variables: Map<String, JsonElement> = emptyMap(), val key: String = "", val page: Int = 1,
    val baseUrl: String = "", val ruleContext: RuleContext? = null, val ruleInput: RuleValue? = null,
    val ruleBudget: RuleBudget? = null, val book: JsonObject = JsonObject(emptyMap()),
    val chapter: JsonObject = JsonObject(emptyMap()), val chineseConverter: Int = 0, val sourceHeaderRule: String = "",
    val discovery: ScriptDiscovery? = null, val sourceLoginUrl: String = "", val sourceComment: String? = null,
    val nextChapterUrl: String? = null, val speakText: String? = null, val speakSpeed: Int = 10,
    val scriptInput: RuleValue? = null)

data class ScriptLimits(val instructionLimit: Int? = null, val maxResultChars: Int = 256 * 1024,
    val maxScriptChars: Int = 256 * 1024, val maxBridgeChars: Int = DEFAULT_BRIDGE_CHARS,
    val maxInterpreterStackDepth: Int = 1000, val timeoutMillis: Long = 5000) {
    init { require((instructionLimit == null || instructionLimit > 0) && maxResultChars > 0 && maxScriptChars > 0 && maxBridgeChars > 0 &&
        maxInterpreterStackDepth in 1..1000 && timeoutMillis in 1..300000) }
    companion object { const val DEFAULT_BRIDGE_CHARS = 64 * 1024 }
}

sealed interface ScriptResult {
    /** A bounded JSON payload; no Rhino object can escape through a successful result. */
    data class Success(val json: String) : ScriptResult {
        override fun toString() = "ScriptSuccess(chars=${json.length})"
    }
    data class Failure(val code: FailureCode, val message: String, val dependency: ScriptDependency? = null,
        val inLibrary: Boolean = false, val hostCall: ScriptHostCall? = null) : ScriptResult
}
enum class FailureCode { Timeout, Cancelled, Syntax, Runtime, ResultTooLarge, UnsupportedResult, BridgeDenied, RequestSyntax, UnsupportedDependency }

internal class ScriptBudgetExceeded : Error()
private class ScriptCancelled : Error()
private class ScriptSyntaxError : Error()

/** Batch-local interpreted programs only; every execution still gets its own invocation bindings. */
class ScriptCompilationCache {
    internal val scripts = mutableMapOf<String, Script>()
    private var chars = 0
    internal fun put(code: String, script: Script) {
        if (chars + code.length <= 256 * 1024) {
            scripts[code] = script
            chars += code.length
        }
    }
}

internal fun evaluateGlobal(context: Context, scope: Scriptable, code: String, name: String,
    cache: ScriptCompilationCache? = null): Any? {
    val compiled = cache?.scripts?.get(code) ?: try { context.compileString(code, name, 1, null).also { cache?.put(code, it) } }
        catch (_: EvaluatorException) { throw ScriptSyntaxError() }
    return compiled.exec(context, scope)
}

/** The instruction observer checks deadlines/cancellation; native work also retains the process deadline. */
class RhinoScriptEngine(private val bridge: HostBridge, private val limits: ScriptLimits = ScriptLimits(), private val archives: ArchiveDecoder = ArchiveDecoder.Zip,
    private val compilations: ScriptCompilationCache? = null) {
    fun evaluate(source: String, frame: ScriptFrame, library: ScriptLibrary? = null): ScriptResult =
        if (library == null) evaluateOwned(source, frame, null)
        else synchronized(library) { evaluateOwned(source, frame, library) }

    private fun evaluateOwned(source: String, frame: ScriptFrame, library: ScriptLibrary?): ScriptResult {
        if (library != null && (library.closed || library.sourceId != frame.sourceId || library.profile != frame.profile))
            return ScriptResult.Failure(FailureCode.BridgeDenied, "invalid library owner")
        if (library != null && library.scripts.sumOf { it.length.toLong() + 1 } > limits.maxScriptChars)
            return ScriptResult.Failure(FailureCode.ResultTooLarge, "library too large")
        if (source.length > limits.maxScriptChars) return ScriptResult.Failure(FailureCode.ResultTooLarge, "script too large")
        // ContextFactory.call reuses an already-entered Context, whose observer we do not own.
        if (Context.getCurrentContext() != null) return ScriptResult.Failure(FailureCode.Runtime, "nested execution context")
        val started = System.nanoTime()
        fun checkDeadline() {
            if (Thread.currentThread().isInterrupted) throw ScriptCancelled()
            if ((System.nanoTime() - started) / 1_000_000 >= limits.timeoutMillis) throw ScriptBudgetExceeded()
            try { frame.ruleBudget?.check(0) }
            catch (_: RuleBudgetExceeded) { throw ScriptBudgetExceeded() }
        }
        val factory = object : ContextFactory() {
            private var instructions = 0L
            override fun makeContext(): Context = super.makeContext().apply {
                languageVersion = Context.VERSION_ES6
                optimizationLevel = -1
                maximumInterpreterStackDepth = limits.maxInterpreterStackDepth
                instructionObserverThreshold = minOf(1000, limits.instructionLimit ?: 1000)
                setClassShutter { false }
            }

            override fun observeInstructionCount(cx: Context, instructionCount: Int) {
                // Rhino charges native RegExp scanning here too. A fixed total rejects normal
                // large pages; MD3 uses this callback to check cancellation, not a total count.
                checkDeadline()
                instructions += instructionCount
                if (limits.instructionLimit != null && instructions > limits.instructionLimit) throw ScriptBudgetExceeded()
            }
        }
        return try {
            factory.call { context ->
                checkDeadline()
                val realm = library?.realm ?: ScriptRealm(context)
                ScriptRealm.install(context, realm)
                context.putThreadLocal(bridgeLimitKey, limits.maxBridgeChars)
                context.putThreadLocal(scriptDeadlineKey, ::checkDeadline)
                if (library?.realm == null) {
                    ScriptParsers.install(context, realm.global)
                    ScriptJavaPackages.install(context, realm.global)
                }
                if (library != null) context.putThreadLocal(scriptLibraryKey, library)
                val scope = if (library == null) realm.global else {
                    val shared = library.scope ?: NativeObject().apply {
                        prototype = realm.global
                        // Like the reference, initialization has no invocation bindings or host capabilities.
                        library.scripts.forEachIndexed { index, code ->
                            evaluateGlobal(context, this, code, "source-library-$index")
                        }
                        sealObject()
                        library.scope = this
                        library.realm = realm
                    }
                    NativeObject().apply { prototype = shared }
                }
                val ruleContext = frame.ruleContext ?: RuleContext(frame.sourceId, frame.bookId, frame.chapterId, frame.baseUrl)
                val bookData = ruleContext.bookMetadata?.let { Json.parseToJsonElement(it).jsonObject } ?: frame.book
                val chapterData = ruleContext.chapterMetadata?.let { Json.parseToJsonElement(it).jsonObject } ?: frame.chapter
                val book = ScriptMetadata.create(context, scope, bookData, frame.bookId, false, ruleContext.bookValues, ruleContext.bookWrites, ruleContext.bookBigValues, ruleContext.bookBigWrites, frame.chineseConverter,
                    "book" !in ruleContext.metadataVariablesInitialized) { ruleContext.metadataVariablesInitialized.add("book") }
                val chapter = ScriptMetadata.create(context, scope, chapterData, frame.chapterId, true, ruleContext.chapterValues, ruleContext.chapterWrites, ruleContext.chapterBigValues, ruleContext.chapterBigWrites, frame.chineseConverter,
                    "chapter" !in ruleContext.metadataVariablesInitialized) { ruleContext.metadataVariablesInitialized.add("chapter") }
                ruleContext.initializeMetadataVariables = {
                    ScriptableObject.getProperty(book, "variableMap")
                    ScriptableObject.getProperty(chapter, "variableMap")
                }
                ruleContext.bookMetadata = bookData.toString()
                ruleContext.chapterMetadata = chapterData.toString()
                ruleContext.readSpecialVariable = { key ->
                    when {
                        key == "bookName" && ruleContext.hasBook -> Context.toString(ScriptableObject.getProperty(book, "name"))
                        key == "title" && ruleContext.hasChapter -> Context.toString(ScriptableObject.getProperty(chapter, "title"))
                        else -> null
                    }
                }
                ruleContext.putMetadataVariable = { key, value ->
                    ScriptableObject.callMethod(context, if (ruleContext.hasChapter) chapter else book, "putVariable", arrayOf(key, value))
                }
                scope.put("book", scope, book)
                scope.put("chapter", scope, chapter)
                scope.put("title", scope, chapterData["title"]?.jsonPrimitive?.contentOrNull)
                scope.put("nextChapterUrl", scope, frame.nextChapterUrl)
                // The rule input is already inside the worker; reverse host-call limits do not apply.
                val inputLimit = frame.ruleBudget?.limits?.maxInputChars ?: limits.maxBridgeChars
                val rules = ScriptRuleHelpers(scope, frame.copy(ruleContext = ruleContext), limits)
                // BaseSource discovery callbacks have no rule input and may declare their own result.
                if (frame.discovery?.snapshot?.get("noResult")?.jsonPrimitive?.boolean != true) {
                    scope.put("result", scope, if (frame.scriptInput != null)
                        rules.inputView(context, scope, frame.scriptInput, inputLimit)
                    else JsonScriptData(context, scope, inputLimit).convert(frame.variables["result"] ?: JsonNull))
                }
                scope.put("key", scope, frame.key)
                if (frame.speakText != null) {
                    scope.put("speakText", scope, frame.speakText)
                    scope.put("speakSpeed", scope, frame.speakSpeed)
                }
                scope.put("page", scope, frame.page)
                scope.put("baseUrl", scope, ruleContext.contentBaseUrl)
                context.putThreadLocal(bridgeLimitKey, limits.maxBridgeChars)
                scope.put("src", scope, rules.sourceValue(context))
                ScriptBridge(bridge, rules, ScriptRequestTemplates(scope, frame), archives).install(context, scope, frame)
                val value = try { evaluateGlobal(context, scope, source, "source-script", compilations) }
                    finally {
                        ruleContext.initializeMetadataVariables = null
                        ruleContext.readSpecialVariable = null
                        ruleContext.putMetadataVariable = null
                    }
                frame.discovery?.capture()
                ruleContext.bookMetadata = ScriptMetadata.capture(book, limits.maxBridgeChars).toString()
                ruleContext.chapterMetadata = ScriptMetadata.capture(chapter, limits.maxBridgeChars).toString()
                checkDeadline()
                val json = BoundedJsonResult(limits.maxResultChars).encode(value)
                checkDeadline()
                ScriptResult.Success(json)
            }
        } catch (_: ScriptBudgetExceeded) { ScriptResult.Failure(FailureCode.Timeout, "execution budget exceeded") }
          catch (_: ScriptCancelled) { ScriptResult.Failure(FailureCode.Cancelled, "script cancelled") }
          catch (_: SerializationCancelled) { ScriptResult.Failure(FailureCode.Cancelled, "script cancelled") }
          catch (failure: ResultTooLarge) { ScriptResult.Failure(FailureCode.ResultTooLarge, "result too large", hostCall = failure.hostCall) }
          catch (failure: UnsupportedResult) { ScriptResult.Failure(FailureCode.UnsupportedResult, "result is not JSON data", hostCall = failure.hostCall) }
          catch (_: WrappedException) { ScriptResult.Failure(FailureCode.BridgeDenied, "host bridge denied") }
          catch (_: ScriptSyntaxError) { ScriptResult.Failure(FailureCode.Syntax, "syntax error") }
          catch (_: EvaluatorException) { ScriptResult.Failure(FailureCode.Runtime, "script failed") }
          catch (_: StackOverflowError) { ScriptResult.Failure(FailureCode.Runtime, "script stack exhausted") }
          catch (failure: BridgeRejected) { ScriptResult.Failure(FailureCode.BridgeDenied, "host bridge denied", hostCall = failure.hostCall) }
          catch (failure: RequestRejected) { ScriptResult.Failure(FailureCode.RequestSyntax, "invalid request options", hostCall = failure.hostCall) }
          catch (_: JavaScriptException) { ScriptResult.Failure(FailureCode.Runtime, "script failed") }
          catch (error: EcmaError) {
              // Classify only engine-generated missing bindings. Source-created Errors, ordinary
              // missing variables, typeof probes and caught fallbacks retain normal JS semantics.
              val dependency = if (error.name == "ReferenceError") ScriptDependency.entries.firstOrNull {
                  error.errorMessage == ScriptRuntime.getMessageById("msg.is.not.defined", it.binding)
              } else null
              if (dependency == null) ScriptResult.Failure(FailureCode.Runtime, "script failed")
              else ScriptResult.Failure(FailureCode.UnsupportedDependency, "runtime dependency unavailable", dependency,
                  error.sourceName()?.startsWith("source-library-") == true)
          }
          catch (_: Exception) { ScriptResult.Failure(FailureCode.Runtime, "script failed") }
    }
}
