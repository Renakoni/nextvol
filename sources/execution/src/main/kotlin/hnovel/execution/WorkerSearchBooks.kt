package hnovel.execution

import hnovel.rhino.HostBridge
import hnovel.rules.*
import kotlinx.serialization.json.Json

/** Extract raw fields only. The host retains metadata fallbacks, URL validation and store commits. */
internal object WorkerSearchBooks {
    fun evaluate(task: ExecutionTask.SearchBooks, identity: ExecutionIdentity, limits: ExecutionLimits): ExecutionResult {
        if (task.inputs.size !in 1..ExecutionTask.SearchBooks.MAX_ROWS ||
            task.rules.keys.any { it !in ExecutionTask.SearchBooks.FIELDS } ||
            task.rules.values.any { !ExecutionTask.BookOverviews.supports(it) })
            return ExecutionResult.Failure(FailureCode.InvalidTask)
        val started = System.nanoTime()
        val rows = mutableListOf<RuleValue>()
        for (input in task.inputs) {
            val fields = mutableListOf<RuleValue>()
            var title = task.fallbackTitle
            for (name in ExecutionTask.SearchBooks.FIELDS) {
                var value = ""
                val rule = task.rules[name].orEmpty()
                if (rule.isNotBlank() && (name == "name" || title.isNotBlank())) {
                    val remaining = limits.timeoutMillis - (System.nanoTime() - started) / 1_000_000
                    if (remaining <= 0) return ExecutionResult.Failure(FailureCode.Timeout)
                    val kind = when (name) {
                        "bookUrl" -> OutputKind.Url
                        "kind" -> OutputKind.TextList
                        else -> OutputKind.Text
                    }
                    when (val result = WorkerRuleEvaluator.evaluate(ExecutionTask.Rule(rule, input, kind,
                        RuleLocation("ruleSearch.$name"), baseUrl = task.baseUrl), identity,
                        limits.copy(timeoutMillis = remaining, maxOutputBytes = minOf(limits.maxOutputBytes, 196608)),
                        HostBridge.None, null)) {
                        is ExecutionResult.Failure -> if (name in setOf("name", "author", "bookUrl") ||
                            result.code != FailureCode.RuleRuntime) return result
                        is ExecutionResult.Success -> {
                            val extracted = Json.decodeFromString(ExecutedRule.serializer(), result.output).value
                            value = if (extracted is RuleValue.Items) extracted.values.joinToString(",") { (it as RuleValue.Text).value }
                                else (extracted as? RuleValue.Text)?.value.orEmpty()
                        }
                    }
                }
                if (name == "name") title = value.ifBlank { task.fallbackTitle }
                fields += RuleValue.Text(value)
            }
            rows += RuleValue.Items(fields)
        }
        val encoded = Json.encodeToString(ExecutedRule.serializer(), ExecutedRule(RuleValue.Items(rows), emptyMap()))
        return if (encoded.toByteArray(Charsets.UTF_8).size > limits.maxOutputBytes) ExecutionResult.Failure(FailureCode.OutputLimit)
            else ExecutionResult.Success(encoded)
    }
}
