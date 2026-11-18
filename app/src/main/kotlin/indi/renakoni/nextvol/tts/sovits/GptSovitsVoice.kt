package indi.renakoni.nextvol.tts.sovits

import java.io.File

enum class GptSovitsVersion(internal val directory: String) {
    V2Pro("v2Pro"), V2ProPlus("v2ProPlus")
}

/** A prepared local voice. Shared encoders live in RuntimeAssets alongside CharacterModels. */
data class GptSovitsVoice(
    val root: File,
    val version: GptSovitsVersion,
    val character: String,
    val referenceAudio: File,
    val referenceText: String,
) {
    init {
        require(character.isNotBlank() && character !in setOf(".", "..") &&
            character.none { it == '/' || it == '\\' || it == '\u0000' })
        require(referenceText.isNotBlank())
    }
    internal val model get() = File(root, "CharacterModels/${version.directory}/$character/tts_models")
    internal val runtime get() = File(root, "RuntimeAssets")
}
