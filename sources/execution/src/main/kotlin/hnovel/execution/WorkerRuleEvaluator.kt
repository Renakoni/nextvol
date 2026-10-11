package hnovel.execution

import hnovel.rhino.*
import hnovel.rules.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Typed output and invocation-local writes; the host decides whether to persist those writes. */
@Serializable data class ExecutedRule(val value: RuleValue, val writes: Map<String, String>,
    val bookWrites: Map<String, String?> = emptyMap(), val chapterWrites: Map<String, String?> = emptyMap(),
    val bookBigWrites: Map<String, String?> = emptyMap(), val chapterBigWrites: Map<String, String?> = emptyMap(),
    val book: JsonObject? = null, val chapter: JsonObject? = null, val discovery: JsonObject? = null,
    val directoryRows: List<DirectoryRow> = emptyList(), val directoryInputs: Int = 0)

/** Selectors, regex and scripts all run inside the worker's hard process deadline. */
internal object WorkerRuleEvaluator {
    fun bindSourceVariables(context: RuleContext, bridge: HostBridge) {
        if (bridge === HostBridge.None) return
        context.readSourceVariable = { key -> bridge.call("source.get", listOf(JsonPrimitive(key))).jsonPrimitive.contentOrNull }
        context.putSourceVariable = { key, value -> bridge.call("source.put", listOf(JsonPrimitive(key), JsonPrimitive(value))) }
    }
    fun evaluate(task: ExecutionTask.Rule, identity: ExecutionIdentity, limits: ExecutionLimits,
        bridge: HostBridge, library: ScriptLibrary?, archives: ArchiveDecoder = ArchiveDecoder.Zip,
        compilations: ScriptCompilationCache? = null): ExecutionResult {
        val context = RuleContext(identity.sourceId, task.bookId, task.chapterId, task.baseUrl,
            task.sourceVariables, task.bookVariables, task.chapterVariables, task.bookBigVariables, task.chapterBigVariables)
        context.bookMetadata = task.book.toString()
        context.chapterMetadata = task.chapter.toString()
        bindSourceVariables(context, bridge)
        val discovery = task.discovery?.let(::ScriptDiscovery)
        var scriptFailure: ScriptResult.Failure? = null
        val evaluator = RuleEvaluator(unescapeHtml = task.unescapeHtml, scriptTemplates = task.scriptTemplates) { request, current, budget ->
            budget.check()
            val frame = ScriptFrame(identity.sourceId, identity.profile, task.bookId, task.chapterId,
                emptyMap(), task.key, task.page, task.baseUrl, current, task.input, budget, task.book, task.chapter, task.chineseConverter,
                sourceHeaderRule = task.sourceHeaderRule, discovery = discovery, sourceLoginUrl = task.sourceLoginUrl,
                sourceComment = task.sourceComment, nextChapterUrl = task.nextChapterUrl, scriptInput = request.input)
            when (val result = RhinoScriptEngine(bridge, ScriptLimits(timeoutMillis = limits.timeoutMillis, maxResultChars = limits.maxOutputBytes,
                maxBridgeChars = limits.scriptDataLimit), archives, compilations)
                .evaluate(request.script, frame, library)) {
                is ScriptResult.Success -> value(Json.parseToJsonElement(result.json))
                is ScriptResult.Failure -> { scriptFailure = result; throw RuleScriptFailure(result.code.name) }
            }
        }
        val budget = RuleBudget(RuleLimits(timeoutMillis = limits.timeoutMillis,
            maxInputChars = ExecutionWire.MAX_INPUT_BYTES, maxOutputChars = limits.maxOutputBytes))
        return when (val result = evaluator.evaluate(task.rule, task.input, context, task.output, task.location, budget)) {
            is RuleResult.Success -> {
                fun changed(snapshot: String?, initial: JsonObject) = snapshot?.let { Json.parseToJsonElement(it).jsonObject }?.takeIf { it != initial }
                val json = Json.encodeToString(ExecutedRule.serializer(), ExecutedRule(result.value, context.writes(), context.bookWrites, context.chapterWrites, context.bookBigWrites, context.chapterBigWrites,
                    changed(context.bookMetadata, task.book), changed(context.chapterMetadata, task.chapter), discovery?.snapshot))
                if (json.toByteArray(Charsets.UTF_8).size > limits.maxOutputBytes) ExecutionResult.Failure(FailureCode.OutputLimit)
                else ExecutionResult.Success(json)
            }
            is RuleResult.Failure -> ExecutionResult.Failure(when (scriptFailure?.code) {
                hnovel.rhino.FailureCode.Timeout -> FailureCode.Timeout
                hnovel.rhino.FailureCode.Cancelled -> FailureCode.Cancelled
                hnovel.rhino.FailureCode.Syntax -> FailureCode.ScriptSyntax
                hnovel.rhino.FailureCode.BridgeDenied -> FailureCode.BridgeDenied
                hnovel.rhino.FailureCode.RequestSyntax -> FailureCode.RequestSyntax
                hnovel.rhino.FailureCode.UnsupportedDependency -> FailureCode.UnsupportedDependency
                hnovel.rhino.FailureCode.ResultTooLarge -> FailureCode.OutputLimit
                null -> if (result.error.stage == RuleStage.Budget) FailureCode.Timeout else FailureCode.RuleRuntime
                else -> FailureCode.ScriptRuntime
            }, scriptFailure?.dependency?.let { dependency -> result.error.copy(
                location = if (scriptFailure?.inLibrary == true) RuleLocation("jsLib") else result.error.location,
                code = "UnsupportedDependency.${dependency.name}") }
                ?: if (scriptFailure?.code == hnovel.rhino.FailureCode.RequestSyntax) result.error.copy(stage = RuleStage.Parse) else result.error,
                scriptFailure?.dependency, scriptFailure?.hostCall)
        }
    }

    private fun value(json: JsonElement): RuleValue = when (json) {
        JsonNull -> RuleValue.Empty
        is JsonPrimitive -> RuleValue.Text(json.content)
        is JsonArray -> RuleValue.Items(json.map {
            if (it is JsonPrimitive && !it.isString) RuleValue.Node(it.toString(), InputKind.Json) else value(it)
        })
        is JsonObject -> RuleValue.Node(json.toString(), InputKind.Json)
    }
}
