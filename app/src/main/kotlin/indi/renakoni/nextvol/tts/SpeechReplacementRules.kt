package indi.renakoni.nextvol.tts

import hnovel.rules.RuleBudget
import hnovel.rules.RuleBudgetExceeded
import hnovel.rules.RegexReplacement
import hnovel.rules.ReplacementPart
import kotlinx.serialization.Serializable
import java.util.UUID
import java.util.regex.PatternSyntaxException

@Serializable
data class SpeechReplacementRule(
    val id: String = UUID.randomUUID().toString(),
    val group: String = "",
    val name: String = "",
    val source: String = "",
    val target: String = "",
    val regex: Boolean = false,
    val activate: Boolean = true,
) {
    val label: String get() = name.ifEmpty { "$source → $target" }
    // MultiTTS N6.a.c classifies the literal rule strings, including regex syntax.
    val wholeText: Boolean get() = source.any { it in STRUCTURE_CHARS } || target.any { it in STRUCTURE_CHARS }

    private companion object { const val STRUCTURE_CHARS = ":：\"“”「」[]|" }
}

enum class SpeechReplacementProblem { Pattern, Replacement, Budget }

data class SpeechReplacementFailure(val ruleId: String, val ruleName: String, val problem: SpeechReplacementProblem)

internal class SpeechReplacementException(val failure: SpeechReplacementFailure) : Exception()
internal class SpeechRegexException(val problem: SpeechReplacementProblem) : Exception()

/** Compiled only for explicit preview or playback. Loading and saving settings never execute rules. */
internal class SpeechReplacements(rules: List<SpeechReplacementRule>,
    private val replaceRegex: (suspend (String, String, String) -> List<ReplacementPart>)? = null,
    private val checkCancelled: () -> Unit) {
    private val rules = rules.filter { it.activate }

    suspend fun apply(input: SpeechMappedText, wholeText: Boolean): SpeechMappedText {
        val stage = rules.filter { it.wholeText == wholeText }
        var output = literals(input, stage.filterNot { it.regex })
        for (rule in stage.filter { it.regex }) output = execute(rule) { budget ->
            val parts = replaceRegex?.invoke(output.text, rule.source, rule.target)
                ?: RegexReplacement.replace(output.text, rule.source, rule.target, budget, checkCancelled)
            val result = SpeechMappedText.Builder(output.text.length)
            val mappingBudget = RuleBudget()
            for (part in parts) {
                checkCancelled()
                val text = part.text
                if (text == null) result.append(output, part.start, part.end)
                else result.append(text, output.range(part.start, part.end))
                mappingBudget.checkSize(result.length, mappingBudget.limits.maxOutputChars)
            }
            result.build(output)
        }
        return output
    }

    private fun literals(input: SpeechMappedText, rules: List<SpeechReplacementRule>): SpeechMappedText {
        val active = rules.filter { it.source.isNotEmpty() }
        if (active.isEmpty() || input.text.isEmpty()) return input
        val budgets = active.map { RuleBudget() }
        val next = IntArray(active.size) { -1 }
        fun find(index: Int, from: Int) {
            execute(active[index], budgets[index]) {
                next[index] = input.text.indexOf(active[index].source, from)
            }
        }
        active.indices.forEach { find(it, 0) }
        val result = SpeechMappedText.Builder(input.text.length)
        var end = 0
        var lastRule: Int? = null
        while (true) {
            checkCancelled()
            // Earliest input match wins; ties retain list order. Inserted text is not rescanned.
            val index = active.indices.filter { next[it] >= 0 }.minByOrNull { next[it] } ?: break
            val rule = active[index]
            execute(rule, budgets[index]) { budget ->
                val start = next[index]
                result.append(input, end, start)
                end = start + rule.source.length
                budget.checkSize(result.length + rule.target.length, budget.limits.maxOutputChars)
                result.append(rule.target, input.range(start, end))
            }
            lastRule = index
            active.indices.filter { next[it] in 0 until end }.forEach { find(it, end) }
        }
        lastRule?.let { index -> execute(active[index], budgets[index]) { budget ->
            budget.checkSize(result.length + input.text.length - end, budget.limits.maxOutputChars)
        } }
        result.append(input, end, input.text.length)
        return result.build(input)
    }

    private inline fun <T> execute(rule: SpeechReplacementRule, budget: RuleBudget = RuleBudget(), block: (RuleBudget) -> T): T {
        try {
            checkCancelled()
            budget.check()
            return block(budget)
        } catch (failure: SpeechRegexException) { fail(rule, failure.problem) }
        catch (_: PatternSyntaxException) { fail(rule, SpeechReplacementProblem.Pattern) }
        catch (_: RuleBudgetExceeded) { fail(rule, SpeechReplacementProblem.Budget) }
        catch (_: StackOverflowError) { fail(rule, SpeechReplacementProblem.Budget) }
        catch (_: IllegalArgumentException) { fail(rule, SpeechReplacementProblem.Replacement) }
        catch (_: IndexOutOfBoundsException) { fail(rule, SpeechReplacementProblem.Replacement) }
    }

    private fun fail(rule: SpeechReplacementRule, problem: SpeechReplacementProblem): Nothing =
        throw SpeechReplacementException(SpeechReplacementFailure(rule.id,
            listOf(rule.group, rule.label).filter { it.isNotEmpty() }.joinToString(" / "), problem))
}
