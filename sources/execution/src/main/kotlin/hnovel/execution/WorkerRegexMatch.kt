package hnovel.execution

import hnovel.rules.RuleError
import hnovel.rules.RuleStage
import java.util.regex.PatternSyntaxException

/** Full match only. Android ICU is not interruptible; the host enforces the process deadline. */
internal object WorkerRegexMatch {
    fun evaluate(task: ExecutionTask.RegexMatch, limits: ExecutionLimits): ExecutionResult = try {
        val output = Regex(task.pattern).matches(task.input).toString()
        if (output.length > limits.maxOutputBytes) ExecutionResult.Failure(FailureCode.OutputLimit)
        else ExecutionResult.Success(output)
    } catch (error: PatternSyntaxException) {
        ExecutionResult.Failure(FailureCode.RuleRuntime,
            RuleError(RuleStage.Parse, task.location.copy(offset = error.index), error.description))
    } catch (_: StackOverflowError) {
        ExecutionResult.Failure(FailureCode.Timeout)
    }
}
