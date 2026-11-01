package indi.renakoni.nextvol.tts

import hnovel.speech.SpeechSearchVoice
import indi.renakoni.nextvol.tts.matcha.InstalledOfflineVoice
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal fun SavedHttpSpeechSource.searchVoice(selected: Boolean): SpeechSearchVoice {
    val fields = speakerData()
    fun text(key: String) = (fields[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    return SpeechSearchVoice(definition.name, text("code"), text("desc"), text("note"), text("locale"),
        text("param"), text("extendUI"), text("gender").toIntOrNull() ?: 0,
        text("type").toIntOrNull() ?: 0, text("sampleRate").toIntOrNull() ?: 16000, selected)
}

internal fun InstalledOfflineVoice.searchVoice(selected: Boolean): SpeechSearchVoice =
    SpeechSearchVoice(name, configuration?.code.orEmpty(), metadata.description, metadata.note,
        configuration?.locale.orEmpty(), (configuration?.rawParameters ?: configuration?.parameters).orEmpty(),
        configuration?.extendUI.orEmpty(), configuration?.gender ?: 0, configuration?.type ?: 0,
        configuration?.sampleRate ?: 16000, selected)
