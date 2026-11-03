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

    companion object { private const val STRUCTURE_CHARS = ":：\"“”「」[]|" }
}

enum class SpeechReplacementProblem { Pattern, Replacement, Budget }

data class SpeechReplacementFailure(val ruleId: String, val ruleName: String, val problem: SpeechReplacementProblem)

internal class SpeechReplacementException(val failure: SpeechReplacementFailure) : Exception()
internal class SpeechRegexException(val problem: SpeechReplacementProblem) : Exception()

/** MultiTTS's editor tests only its draft, including disabled rules, without structural processing. */
internal suspend fun previewSpeechReplacement(text: String, rule: SpeechReplacementRule,
    replaceRegex: (suspend (String, String, String) -> List<ReplacementPart>)? = null,
    checkCancelled: () -> Unit): String {
    if (text.isEmpty() || rule.source.isEmpty()) return text
    return SpeechReplacements(listOf(rule.copy(activate = true)), replaceRegex, checkCancelled = checkCancelled)
        .apply(SpeechMappedText.original(text), rule.wholeText).text
}

/** Compiled only for explicit preview or playback. Loading and saving settings never execute rules. */
internal class SpeechReplacements(rules: List<SpeechReplacementRule>,
    private val replaceRegex: (suspend (String, String, String) -> List<ReplacementPart>)? = null,
    private val trace: SpeechPlanTrace? = null,
    private val checkCancelled: () -> Unit) {
    private val stages = rules.filter { it.activate && (it.regex || it.source.isNotEmpty()) }
        .groupBy { it.wholeText }.mapValues { (_, rules) -> rules.partition { it.regex } }

    suspend fun apply(input: SpeechMappedText, wholeText: Boolean): SpeechMappedText {
        val (regex, literal) = stages[wholeText] ?: return input
        var output = replacementStage { literals(input, literal, wholeText) }
        for (rule in regex) output = replacementStage {
            execute(rule) { budget ->
                val parts = replaceRegex?.invoke(output.text, rule.source, rule.target)
                    ?: RegexReplacement.replace(output.text, rule.source, rule.target, budget, checkCancelled)
                val result = SpeechMappedText.Builder(output.text.length)
                for (part in parts) {
                    checkCancelled()
                    val text = part.text
                    if (text == null) result.append(output, part.start, part.end)
                    else {
                        val range = output.range(part.start, part.end)
                        result.append(text, range)
                        trace?.replacement(rule, wholeText, range,
                            output.text.substring(part.start, part.end), text)
                    }
                }
                result.build(output)
            }
        }
        return output
    }

    private inline fun <T> replacementStage(block: () -> T): T = try {
        block()
    } finally {
        // Formatting and publishing logs must not consume the replacement rule's time budget.
        trace?.flushReplacements()
    }

    private fun literals(input: SpeechMappedText, active: List<SpeechReplacementRule>, wholeText: Boolean): SpeechMappedText {
        if (active.isEmpty() || input.text.isEmpty()) return input
        val budgets = active.map { RuleBudget() }
        val next = IntArray(active.size) { -1 }
        fun find(index: Int, from: Int) {
            execute(active[index], budgets[index]) {
                next[index] = input.text.indexOf(active[index].source, from)
            }
        }
        active.indices.forEach { find(it, 0) }
        if (next.all { it < 0 }) return input
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
                val range = input.range(start, end)
                result.append(rule.target, range)
                trace?.replacement(rule, wholeText, range, rule.source, rule.target)
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
