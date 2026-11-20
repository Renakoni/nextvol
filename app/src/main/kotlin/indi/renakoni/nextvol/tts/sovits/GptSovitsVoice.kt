package indi.renakoni.nextvol.tts.sovits

import hnovel.speech.OfflineVoiceConfig
import org.json.JSONObject
import java.io.File

enum class GptSovitsVersion(internal val directory: String) {
    V2Pro("v2Pro"), V2ProPlus("v2ProPlus")
}

/** An installed voice references the separately installed shared encoders. */
data class GptSovitsVoice(
    val root: File,
    val version: GptSovitsVersion,
    val character: String,
    val referenceAudio: File,
    val referenceText: String,
    val runtime: File,
    val referenceLanguage: SovitsReferenceLanguage = SovitsReferenceLanguage.detect(referenceText),
    val options: SovitsSynthesisOptions = SovitsSynthesisOptions(),
) {
    init {
        require(character.isNotBlank() && character !in setOf(".", "..") &&
            character.none { it == '/' || it == '\\' || it == '\u0000' })
        require(referenceText.isNotBlank())
    }
    internal val model get() = File(root, "CharacterModels/${version.directory}/$character/tts_models")
}

internal fun validateSovitsConfiguration(configuration: OfflineVoiceConfig, paths: Collection<String>) {
    require("CharacterModels/${sovitsCharacterPath(sovitsModelCode(configuration))}/prompt_wav.json" in paths)
}

internal fun installedSovitsVoice(root: File, configuration: OfflineVoiceConfig, runtime: File): GptSovitsVoice {
    val code = sovitsCharacterPath(sovitsModelCode(configuration))
    val version = GptSovitsVersion.entries.single { it.directory == code.substringBefore('/') }
    val character = File(root, "CharacterModels/$code")
    val reference = JSONObject(File(character, "prompt_wav.json").readText()).getJSONObject("Normal")
    val language = reference.getString("language")
    val filename = reference.getString("wav")
    require(filename.matches(Regex("reference\\.[a-z0-9]{1,8}")))
    return GptSovitsVoice(root, version, code.substringAfter('/'), File(character, "prompt_wav/$filename"),
        reference.getString("text"), runtime, SovitsReferenceLanguage.entries.single { it.code == language }, sovitsOptions(configuration))
}

private fun sovitsCharacterPath(code: String): String {
    require(code.matches(Regex("v2Pro(?:Plus)?\\.[a-zA-Z0-9_-]{1,80}")))
    return code.replace('.', '/')
}
