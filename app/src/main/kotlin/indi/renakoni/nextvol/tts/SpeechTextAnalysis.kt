package indi.renakoni.nextvol.tts

import hnovel.execution.ExecutionAuthority
import hnovel.execution.ExecutionLimits
import hnovel.execution.ExecutionResult
import hnovel.execution.ExecutionTask
import indi.renakoni.nextvol.sourceexecution.AndroidIsolatedExecutor
import indi.renakoni.nextvol.tts.names.NewWordAlgorithm
import indi.renakoni.nextvol.tts.names.SpeechNameClassification
import indi.renakoni.nextvol.tts.names.SpeechNameClassifier
import indi.renakoni.nextvol.tts.names.SpeechNameHint
import indi.renakoni.nextvol.tts.names.SpeechNewWordDiscovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Reader
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import javax.inject.Inject

data class SpeechTextAnalysisCandidate(
    val id: String,
    val name: String,
    val value: Int,
    val classification: SpeechNameClassification,
    val matchesNamePattern: Boolean,
    val selected: Boolean = false,
    val aliases: List<String> = emptyList(),
    val gender: SpeechNameHint = if (classification.hint == SpeechNameHint.Female) SpeechNameHint.Female else SpeechNameHint.Male,
    val existingRoleId: String? = null,
    val mergedRoleIds: Set<String> = emptySet(),
    val edited: Boolean = false,
    val originalAliases: List<String> = aliases,
)

data class SpeechTextAnalysisResult(val algorithm: NewWordAlgorithm, val candidates: List<SpeechTextAnalysisCandidate>)

class SpeechTextAnalysis @Inject constructor(
    private val discovery: SpeechNewWordDiscovery,
    private val classifier: SpeechNameClassifier,
    private val repository: SpeechSettingsRepository,
    private val executor: AndroidIsolatedExecutor,
    private val authority: ExecutionAuthority,
) {
    suspend fun analyze(reader: Reader, algorithm: NewWordAlgorithm, onlyNew: Boolean,
        personNameRegex: String?, maxAutoChecked: Int, maleNameRegex: String, femaleNameRegex: String, schemeId: String? = null):
        SpeechTextAnalysisResult = reader.use { input ->
        withContext(Dispatchers.Default) {
            val analysis = discovery.analyze(input, algorithm, onlyNew)
            val pattern = personNameRegex ?: discovery.defaultNamePattern()
            val rules = (if (schemeId == null) repository.get() else repository.get(schemeId)).roleRules.filter { it.mode != 0 && it.mode != 1 && !it.pattern.startsWith("lang:") }
            val identity = authority.issue("speech-text-analysis", "builtin", "1", namespace = "tts")
            suspend fun matches(name: String, regex: String): Boolean =
                when (val result = executor.execute(identity, ExecutionTask.RegexMatch(name, regex),
                    ExecutionLimits(timeoutMillis = 2500, maxRequests = 0))) {
                    is ExecutionResult.Success -> result.output.toBooleanStrict()
                    is ExecutionResult.Failure -> {
                        val error = result.ruleError
                        if (error != null) throw PatternSyntaxException(error.code, regex, error.location.offset)
                        throw IllegalStateException(result.code.toString())
                    }
                }
            try {
                val candidates = analysis.candidates.map { word ->
                    ensureActive()
                    val classification = classifier.classify(word.name, maleNameRegex, femaleNameRegex)
                    val matching = pattern.isNotEmpty() && matches(word.name, pattern)
                    val existing = rules.firstOrNull { rule ->
                        word.name in rule.literalPeople() ||
                            rule.activate && rule.pattern.isNotEmpty() && matches(word.name, rule.pattern)
                    }
                    SpeechTextAnalysisCandidate(word.name, word.name, word.value, classification, matching,
                        aliases = existing?.person?.names.orEmpty().filter { it != word.name }, existingRoleId = existing?.id)
                }
                ensureActive()
                SpeechTextAnalysisResult(analysis.algorithm, selectSpeechAnalysisCandidates(candidates, maxAutoChecked))
            } finally { authority.revoke(identity) }
        }
    }

    suspend fun save(group: String, candidates: List<SpeechTextAnalysisCandidate>, schemeId: String? = null) {
        val change: (SpeechSettings) -> SpeechSettings = { mergeSpeechAnalysisRoles(it, group, candidates) }
        if (schemeId == null) repository.update(change) else repository.update(schemeId, change)
    }
}

internal fun selectSpeechAnalysisCandidates(candidates: List<SpeechTextAnalysisCandidate>, maxAutoChecked: Int):
    List<SpeechTextAnalysisCandidate> {
    var selected = 0
    return (candidates.filter { it.matchesNamePattern } + candidates.filterNot { it.matchesNamePattern }).map {
        val checked = it.matchesNamePattern && it.existingRoleId == null && selected < maxAutoChecked
        if (checked) selected++
        it.copy(selected = checked)
    }
}

/** Statistics stay attached to the target word; a native score cannot be summed as a frequency. */
fun mergeSpeechAnalysisCandidates(candidates: List<SpeechTextAnalysisCandidate>, targetId: String): List<SpeechTextAnalysisCandidate> {
    val target = candidates.firstOrNull { it.id == targetId } ?: return candidates
    val names = (target.aliases + target.name).toMutableSet()
    val removedAliases = if (target.edited) target.originalAliases.toSet() - names else emptySet()
    val remaining = candidates.filter { it.id != targetId }.toMutableList()
    val sources = mutableListOf<SpeechTextAnalysisCandidate>()
    while (true) {
        val source = remaining.firstOrNull { it.name !in removedAliases &&
            (it.aliases + it.name).any(names::contains) } ?: break
        remaining.remove(source)
        sources += source
        names += source.aliases + source.name
    }
    val aliases = (target.aliases + sources.flatMap { listOf(it.name) + it.aliases })
        .filter { it.isNotBlank() && it != target.name && it !in removedAliases }.distinct()
    val mergedRoleIds = target.mergedRoleIds + sources.flatMap { it.mergedRoleIds + listOfNotNull(it.existingRoleId) }
    return candidates.filterNot { it in sources }.map {
        if (it.id == targetId) it.copy(aliases = aliases, mergedRoleIds = mergedRoleIds,
            edited = target.edited || sources.isNotEmpty(), selected = target.selected || sources.any { source -> source.selected }) else it
    }
}

/** One settings write publishes the entire selection, retaining other rules and all book voice pairs. */
internal fun mergeSpeechAnalysisRoles(settings: SpeechSettings, group: String,
    candidates: List<SpeechTextAnalysisCandidate>): SpeechSettings {
    val rules = settings.roleRules.toMutableList()
    for (candidate in candidates.filter { it.selected }) {
        val names = (listOf(candidate.name) + candidate.aliases).map(String::trim).filter(String::isNotEmpty).distinct()
        if (names.isEmpty()) continue
        val pool = if (candidate.gender == SpeechNameHint.Female) SPEECH_POOL_FEMALE else SPEECH_POOL_MALE
        val personRules = rules.filter { it.mode != 0 && it.mode != 1 && !it.pattern.startsWith("lang:") }
        val overlapping = personRules.filter { it.id == candidate.existingRoleId ||
            it.id in candidate.mergedRoleIds && it.literalPeople().isEmpty() || it.literalPeople().any { name -> name in names } }
        val previous = overlapping.firstOrNull { it.person != null && it.id == candidate.existingRoleId }
            ?: overlapping.firstOrNull { it.person?.name == candidate.name }
            ?: overlapping.firstOrNull { it.person != null }
            ?: overlapping.firstOrNull { it.literalPeople().size == 1 }
            ?: overlapping.firstOrNull { it.id == candidate.existingRoleId }
            ?: overlapping.firstOrNull()
        val conflict = if (previous == null) personRules.firstOrNull { it.group == group && it.name == candidate.name } else null
        // A known member of an imported group already has a rule; don't replace the whole group with one person.
        val grouped = previous?.takeIf { it.person == null && it.literalPeople().size != 1 }
        if (previous != null && previous.person == null && candidate.name == candidate.id && candidate.aliases.isEmpty() &&
            (previous.speaker?.voice == pool || previous.speaker?.voice !in setOf(SPEECH_POOL_MALE, SPEECH_POOL_FEMALE))) continue
        val personRule = previous.takeUnless { grouped != null }
        val person = personRule?.person?.name ?: personRule?.literalPeople()?.singleOrNull() ?: candidate.name.trim()
        val mergedPeople = overlapping.filter { it != personRule && (it.person != null || it.literalPeople().size == 1) }
        val aliases = (personRule?.literalPeople().orEmpty().takeUnless { candidate.edited }.orEmpty() + names +
            mergedPeople.flatMap { it.literalPeople() })
            .filter { it != person }.distinct()
        val displayName = personRule?.name?.takeIf { candidate.name == candidate.id } ?: candidate.name.trim()
        val merged = (personRule?.copy(name = displayName, regex = (listOf(person) + aliases).joinToString("|", "(", ")", transform = Pattern::quote),
            person = SpeechRolePerson(person, aliases), speaker = personRule.speaker?.let {
                if (it.voice in setOf(SPEECH_POOL_MALE, SPEECH_POOL_FEMALE)) it.copy(voice = pool) else it
            }) ?: SpeechRoleRule(name = candidate.name.trim(), group = group,
            regex = (listOf(person) + aliases).joinToString("|", "(", ")", transform = Pattern::quote),
            isAbstract = true, speaker = SpeechVoiceRef(voice = pool), person = SpeechRolePerson(person, aliases)))
            .let { if (conflict != null) it.copy(id = conflict.id) else it }
        val position = (overlapping.firstOrNull() ?: conflict)?.let(rules::indexOf) ?: rules.size
        rules.removeAll((mergedPeople + listOfNotNull(personRule, conflict)).toSet())
        rules.add(position, merged)
    }
    return settings.copy(roleRules = rules)
}

/** Original saved groups use plain name alternatives. Arbitrary regexes are never inferred as alias lists. */
private fun SpeechRoleRule.literalPeople(): List<String> {
    person?.let { return it.names }
    val expression = pattern.let { if (it.startsWith('(') && it.endsWith(')')) it.substring(1, it.length - 1) else it }
    val names = mutableListOf<String>()
    val name = StringBuilder()
    var quoted = false
    var index = 0
    while (index < expression.length) {
        when {
            quoted && expression.startsWith("\\E", index) -> { quoted = false; index += 2 }
            quoted -> name.append(expression[index++])
            expression.startsWith("\\Q", index) -> { quoted = true; index += 2 }
            expression.startsWith("\\\\", index) -> { name.append('\\'); index += 2 }
            expression[index] == '|' -> { names += name.toString(); name.clear(); index++ }
            expression[index] in "\\.^$*+?{}[]()" -> return emptyList()
            else -> name.append(expression[index++])
        }
    }
    names += name.toString()
    return names.takeIf { !quoted && it.none(String::isEmpty) }.orEmpty()
}
