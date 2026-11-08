package indi.renakoni.nextvol.ui.tts

import hnovel.speech.SpeechExtensionControl
import indi.renakoni.nextvol.tts.offline.OfflineAvailability
import kotlinx.serialization.Serializable

@Serializable
data class VoiceDraft(
    val name: String, val code: String, val locale: String, val parameters: String, val extensions: String,
    val speed: String, val pitch: String, val volume: String, val sampleRate: String,
    val gender: String, val type: String, val note: String, val description: String, val avatar: String,
    val enabled: Boolean,
) {
    fun fields(): VoiceFields {
        require(name.isNotBlank() && code.matches(Regex("[-_a-zA-Z0-9.:]+")))
        require(listOf(speed, volume, pitch).all { it.matches(Regex("[0-9](\\.[0-9]+)?")) })
        require(pitch.toFloat() in 0.1f..2f)
        require(sampleRate.matches(Regex("[0-9]{4,5}")))
        require(listOf(parameters, extensions, note, description, avatar).all { it.length <= 64 * 1024 })
        if (locale.isNotEmpty()) {
            val parts = locale.split('-')
            require(parts.size == 2)
            java.util.Locale(parts[0], parts[1]).let { require(it.isO3Language.isNotEmpty() && it.isO3Country.isNotEmpty()) }
        }
        val language = locale.ifEmpty {
            if (name.matches(Regex("^[-+_a-zA-Z0-9() ]+\\d*$"))) "en-US"
            else java.util.Locale.getDefault().let { "${it.language.lowercase()}-${it.country.uppercase()}" }
        }
        return VoiceFields(name, code, language, parameters, extensions,
            speed.toFloat(), pitch.toFloat(), volume.toFloat(), sampleRate.toInt(),
            gender.toInt(), type.toInt(), note, description, avatar.trim(), enabled)
    }

    companion object
}

data class VoiceFields(
    val name: String, val code: String, val locale: String, val parameters: String, val extensions: String,
    val speed: Float, val pitch: Float, val volume: Float, val sampleRate: Int,
    val gender: Int, val type: Int, val note: String, val description: String, val avatar: String,
    val enabled: Boolean,
)

data class VoiceEditorState(
    val draft: VoiceDraft? = null, val emptyCode: Boolean = false,
    val busy: Boolean = false, val error: Int? = null, val availability: OfflineAvailability? = null,
    val packageAvatar: String? = null,
    val controls: List<SpeechExtensionControl>? = emptyList(),
    val loadingControls: Boolean = false,
)
