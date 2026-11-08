package indi.renakoni.nextvol.tts.names

/** A voice-selection hint, not an assertion about a character's attributes. */
enum class SpeechNameHint { Male, Female, Unknown }

enum class SpeechNameHintBasis { Model, MaleRule, FemaleRule, None }

data class SpeechNameClassification(val hint: SpeechNameHint, val basis: SpeechNameHintBasis)
