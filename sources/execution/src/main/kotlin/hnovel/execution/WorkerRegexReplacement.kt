package hnovel.execution

import hnovel.rules.*
import kotlinx.serialization.json.Json
import java.util.regex.PatternSyntaxException

/** Pure replacement only: no scripts, library state, source variables or host bridge. */
internal object WorkerRegexReplacement {
    fun evaluate(task: ExecutionTask.RegexReplacement, limits: ExecutionLimits): ExecutionResult = try {
        val parts = hnovel.rules.RegexReplacement.replace(task.input, task.source, task.target,
            RuleBudget(RuleLimits(timeoutMillis = limits.timeoutMillis)))
        val output = Json.encodeToString(parts)
        if (output.toByteArray(Charsets.UTF_8).size > limits.maxOutputBytes) ExecutionResult.Failure(FailureCode.OutputLimit)
        else ExecutionResult.Success(output)
    } catch (_: PatternSyntaxException) { failure(RuleStage.Parse) }
    catch (_: RuleBudgetExceeded) { ExecutionResult.Failure(FailureCode.Timeout) }
    catch (_: StackOverflowError) { ExecutionResult.Failure(FailureCode.Timeout) }
    catch (_: IllegalArgumentException) { failure(RuleStage.Replace) }
    catch (_: IndexOutOfBoundsException) { failure(RuleStage.Replace) }

    private fun failure(stage: RuleStage) = ExecutionResult.Failure(FailureCode.RuleRuntime,
        RuleError(stage, RuleLocation("replacement"), "InvalidReplacement"))
}
