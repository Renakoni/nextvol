package indi.renakoni.nextvol.tts

import hnovel.rules.RegexReplacement
import hnovel.rules.ReplacementPart
import hnovel.rules.RuleBudget
import hnovel.rules.RuleBudgetExceeded
import kotlinx.serialization.Serializable
import java.util.UUID
import java.util.regex.PatternSyntaxException

@Serializable
data class SpeechRoleRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val regex: String = "",
    val group: String = "",
    val activate: Boolean = true,
    val mode: Int = 2,
    val isAbstract: Boolean = false,
    val matchBefore: Boolean = true,
    val matchAfter: Boolean = true,
    val speaker: SpeechVoiceRef? = null,
    val bookId: String? = null,
    val note: String = "",
) {
    val pattern: String get() = regex.ifEmpty { name }
    val label: String get() = name.ifEmpty { regex }
}

/** Abstract matches keep the actual person; pool allocation consumes this identity separately. */
@Serializable
data class SpeechRoleMatch(val person: String, val ruleId: String, val ruleName: String,
    val isAbstract: Boolean, val voiceRef: SpeechVoiceRef?)

data class SpeechRoleFailure(val ruleId: String, val ruleName: String, val problem: SpeechReplacementProblem)
internal class SpeechRoleException(val failure: SpeechRoleFailure) : Exception()

internal data class SpeechRoleText(val content: SpeechMappedText, val match: SpeechRoleMatch? = null)

/** MultiTTS O6.f: direction-specific candidates, context template, then the first matching rule. */
internal class SpeechRoleRules(rules: List<SpeechRoleRule>, bookId: String,
    private val contextPattern: String = "__ROLE__",
    private val replaceRegex: (suspend (String, String, String) -> List<ReplacementPart>)? = null,
    private val checkCancelled: () -> Unit) {
    private val active = rules.filter { it.activate && it.pattern.isNotEmpty() && (it.bookId == null || it.bookId == bookId) }
    private val roles = active.filter { it.mode != 0 && it.mode != 1 && !it.pattern.startsWith("lang:") }
    private val budget = RuleBudget()

    suspend fun resolve(text: String, direction: SpeechContextDirection): SpeechRoleMatch? {
        val rules = roles.filter { if (direction == SpeechContextDirection.Next) it.matchAfter else it.matchBefore }
        if (rules.isEmpty()) return null
        if (direction == SpeechContextDirection.Explicit) return person(text, rules)

        val pattern = rules.joinToString("|") { "(${it.pattern})" }
        val parts = try {
            find(text, pattern, rules.first())
        } catch (failure: SpeechRoleException) {
            // Diagnose only a failed combined pattern, without executing rules while loading/saving.
            if (failure.failure.problem == SpeechReplacementProblem.Pattern) {
                for (rule in rules) find("", rule.pattern, rule)
            }
            throw failure
        }
        val candidates = parts.filter { it.start < it.end }.map { text.substring(it.start, it.end) }
            .let { if (direction == SpeechContextDirection.Previous) it.asReversed() else it }
        for ((index, candidate) in candidates.withIndex()) {
            var context = text
            execute(rules.first()) {
                for ((other, name) in candidates.withIndex()) {
                    checkCancelled()
                    budget.check(context.length.toLong())
                    context = context.replace(name, if (other == index) "__ROLE__" else "__OTHER__")
                    budget.checkSize(context.length, budget.limits.maxOutputChars)
                }
            }
            if (find(context, contextPattern, rules.first()).isNotEmpty()) return person(candidate, rules)
        }
        return null
    }

    /** P.f.i: later text rules only see intervals not already claimed by an earlier rule. */
    suspend fun splitText(input: SpeechMappedText): List<SpeechRoleText> {
        var spans = listOf(SpeechRoleText(input))
        for (rule in active.filter { it.mode == 0 && !it.pattern.startsWith("lang:") }) {
            val next = mutableListOf<SpeechRoleText>()
            for (span in spans) {
                if (span.match != null) {
                    next += span
                    continue
                }
                var copied = 0
                for (part in find(span.content.text, rule.pattern, rule)) {
                    if (part.start > copied) next += SpeechRoleText(span.content.slice(copied, part.start))
                    if (part.start < part.end) next += SpeechRoleText(span.content.slice(part.start, part.end), rule.match(rule.name))
                    copied = part.end
                }
                if (copied < span.content.text.length) next += SpeechRoleText(span.content.slice(copied, span.content.text.length))
            }
            spans = next
            if (spans.all { it.match != null }) break
        }
        return spans
    }

    private suspend fun person(name: String, rules: List<SpeechRoleRule>): SpeechRoleMatch? {
        for (rule in rules) {
            if (find(name, rule.pattern, rule).isNotEmpty()) return rule.match(name)
        }
        return null
    }

    private fun SpeechRoleRule.match(person: String) = SpeechRoleMatch(person, id, label, isAbstract, speaker)

    private suspend fun find(text: String, pattern: String, rule: SpeechRoleRule): List<ReplacementPart> = execute(rule) {
        budget.checkSize(text.length, budget.limits.maxInputChars)
        budget.checkSize(pattern.length, budget.limits.maxRuleChars)
        val parts = replaceRegex?.invoke(text, pattern, "x")
            ?: RegexReplacement.replace(text, pattern, "x", budget, checkCancelled)
        budget.check(parts.size.toLong())
        parts.filter { it.text != null }
    }

    private suspend fun <T> execute(rule: SpeechRoleRule, block: suspend () -> T): T {
        try {
            checkCancelled()
            budget.check()
            return block()
        } catch (failure: SpeechRegexException) { fail(rule, failure.problem) }
        catch (_: PatternSyntaxException) { fail(rule, SpeechReplacementProblem.Pattern) }
        catch (_: RuleBudgetExceeded) { fail(rule, SpeechReplacementProblem.Budget) }
        catch (_: StackOverflowError) { fail(rule, SpeechReplacementProblem.Budget) }
    }

    private fun fail(rule: SpeechRoleRule, problem: SpeechReplacementProblem): Nothing =
        throw SpeechRoleException(SpeechRoleFailure(rule.id,
            listOf(rule.group, rule.label).filter { it.isNotEmpty() }.joinToString(" / "), problem))
}
