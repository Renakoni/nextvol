package indi.renakoni.nextvol.tts

import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi

const val DEFAULT_SPEECH_SCHEME = "default"

@Serializable
data class SpeechSchemeMusic(val enabled: Boolean = false, val selected: Set<String> = emptySet(), val volume: Float = 0.5f)

@Serializable
data class SpeechScheme(val id: String, val name: String = "",
    @OptIn(ExperimentalSerializationApi::class) @EncodeDefault val settings: SpeechSettings = SpeechSettings())

@Serializable
data class SpeechRuntimeSettings(val engineCacheCapacity: Int = 10, val maxCacheVoiceNum: Int = 50,
    val diagnosticLogging: Boolean = false, val recordText: Boolean = false, val recordVoice: Boolean = false,
    val filterLanguage: Boolean = false)

@Serializable
data class SpeechSchemes(
    @OptIn(ExperimentalSerializationApi::class) @EncodeDefault
    val items: List<SpeechScheme> = listOf(SpeechScheme(DEFAULT_SPEECH_SCHEME)),
    val defaultId: String = DEFAULT_SPEECH_SCHEME,
    val bindings: Map<String, String> = emptyMap(),
    val runtime: SpeechRuntimeSettings = SpeechRuntimeSettings(),
) {
    fun effectiveId(bookId: String?) = bindings[bookId] ?: defaultId

    fun settings(id: String): SpeechSettings = items.first { it.id == id }.effectiveSettings(runtime)

    fun forBook(bookId: String?): SpeechSettings {
        val id = effectiveId(bookId)
        return items.first { it.id == id }.effectiveSettings(runtime, bookId)
    }

    internal fun update(id: String, change: (SpeechSettings) -> SpeechSettings): SpeechSchemes {
        val updated = change(settings(id)).validated()
        return copy(items = items.map { if (it.id == id) it.copy(settings = updated.schemeContent()) else it },
            runtime = updated.runtimeSettings())
    }

    internal fun delete(id: String, replacement: String): SpeechSchemes {
        require(id != replacement && items.any { it.id == replacement })
        return copy(items = items.filterNot { it.id == id }, defaultId = if (defaultId == id) replacement else defaultId,
            bindings = bindings.mapValues { if (it.value == id) replacement else it.value })
    }
}
