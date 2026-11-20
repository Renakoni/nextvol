package indi.renakoni.nextvol.ui.tts

import hnovel.speech.SpeechExtensionControl
import indi.renakoni.nextvol.tts.offline.OfflineAvailability
import indi.renakoni.nextvol.tts.sovits.SovitsSynthesisOptions
import kotlinx.serialization.Serializable

@Serializable
data class VoiceDraft(
    val name: String, val code: String, val locale: String, val parameters: String, val extensions: String,
    val speed: String, val pitch: String, val volume: String, val sampleRate: String,
    val gender: String, val type: String, val note: String, val description: String, val avatar: String,
    val enabled: Boolean,
    val sovits: SovitsOptionsDraft? = null,
) {
    fun fields(): VoiceFields {
        require(name.isNotBlank() && code.matches(Regex("[-_a-zA-Z0-9.:]+")))
        val numbers = if (sovits == null) listOf(speed, volume, pitch) else listOf(volume, pitch)
        require(numbers.all { it.matches(Regex("[0-9](\\.[0-9]+)?")) })
        require(pitch.toFloat() in 0.1f..2f)
        require(sampleRate.matches(Regex("[0-9]{4,5}")))
        require(listOf(parameters, extensions, note, description, avatar).all { it.length <= 64 * 1024 })
        val language = locale.ifEmpty {
            if (name.matches(Regex("^[-+_a-zA-Z0-9() ]+\\d*$"))) "en-US"
            else java.util.Locale.getDefault().let { "${it.language.lowercase()}-${it.country.uppercase()}" }
        }
        return VoiceFields(name, code, language, parameters, extensions,
            speed.toFloat(), pitch.toFloat(), volume.toFloat(), sampleRate.toInt(),
            gender.toInt(), type.toInt(), note, description, avatar.trim(), enabled, sovits?.options(speed))
    }

    companion object
}

@Serializable
data class SovitsOptionsDraft(
    val temperature: String = "1.0", val topK: String = "15",
    val topP: String = "1.0", val repetitionPenalty: String = "1.35",
) {
    fun options(speed: String) = SovitsSynthesisOptions(speed.toFloat(), temperature.toFloat(), topK.toInt(),
        topP.toFloat(), repetitionPenalty.toFloat())
}

data class VoiceFields(
    val name: String, val code: String, val locale: String, val parameters: String, val extensions: String,
    val speed: Float, val pitch: Float, val volume: Float, val sampleRate: Int,
    val gender: Int, val type: Int, val note: String, val description: String, val avatar: String,
    val enabled: Boolean,
    val sovits: SovitsSynthesisOptions? = null,
)

data class VoiceEditorState(
    val draft: VoiceDraft? = null, val emptyCode: Boolean = false,
    val busy: Boolean = false, val error: Int? = null, val availability: OfflineAvailability? = null,
    val packageAvatar: String? = null,
    val controls: List<SpeechExtensionControl>? = emptyList(),
    val loadingControls: Boolean = false,
)
