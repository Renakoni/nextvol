package indi.renakoni.nextvol.tts.names

/** A voice-selection hint, not an assertion about a character's attributes. */
enum class SpeechNameHint { Male, Female, Unknown }

enum class SpeechNameHintBasis { Model, MaleRule, FemaleRule, None }

data class SpeechNameClassification(val hint: SpeechNameHint, val basis: SpeechNameHintBasis)

internal fun classifyNameRules(name: String, maleNameRegex: String, femaleNameRegex: String): SpeechNameClassification = when {
    maleNameRegex.isNotEmpty() && Regex(maleNameRegex).matches(name) ->
        SpeechNameClassification(SpeechNameHint.Male, SpeechNameHintBasis.MaleRule)
    femaleNameRegex.isNotEmpty() && Regex(femaleNameRegex).matches(name) ->
        SpeechNameClassification(SpeechNameHint.Female, SpeechNameHintBasis.FemaleRule)
    else -> SpeechNameClassification(SpeechNameHint.Unknown, SpeechNameHintBasis.None)
}
