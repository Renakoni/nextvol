package hnovel.speech

import hnovel.rules.RequestOptionsJson
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

enum class HttpSpeechPackageError { UnsupportedEngine, UnsupportedRequest, RequiresParameters, InvalidConfiguration }

class HttpSpeechPackageImport(
    val sources: List<HttpSpeechDefinition> = emptyList(),
    val error: HttpSpeechPackageError? = null,
    val hasLocalAudioSettings: Boolean = false,
    val engine: SpeechEngineConfig? = null,
)

/** Converts request data only. Scripts still run exclusively through HttpSpeechClient after explicit playback. */
fun previewHttpSpeechPackage(engine: SpeechPackageEngine): HttpSpeechPackageImport {
    fun failed(error: HttpSpeechPackageError) = HttpSpeechPackageImport(error = error)
    if (!engine.isOnline) return failed(HttpSpeechPackageError.UnsupportedEngine)
    val definition = engine.raw ?: return failed(HttpSpeechPackageError.InvalidConfiguration)
    val template = try {
        if (engine.type == "http") definition.values["url"].text()?.trim().orEmpty()
        else if (engine.code == "edgetts") EdgeSpeechClient.ENDPOINT
        else if (engine.code in dedicatedOnlineSpeechEngines)
            onlineSpeechPackageUrl(engine.code, definition.values["param"].text().orEmpty())
        else microsoftPackageAuthUrl(microsoftPackageParameters(definition.values["param"].text().orEmpty()))
    } catch (_: IllegalArgumentException) { return failed(HttpSpeechPackageError.InvalidConfiguration) }
    if (template.isEmpty() || engine.voices.any { it.code.isBlank() })
        return failed(HttpSpeechPackageError.InvalidConfiguration)
    if (engine.voices.any { voice ->
        listOf("param", "extendUI").any { voice.raw.values[it]?.let { value -> value !is SpeechPackageValue.Scalar } == true }
    }) return failed(HttpSpeechPackageError.RequiresParameters)
    val rawHeader = definition.values["header"]
    if (rawHeader != null && rawHeader !is SpeechPackageValue.Scalar)
        return failed(HttpSpeechPackageError.UnsupportedRequest)
    val header = rawHeader.text().orEmpty()
    if (header.isNotBlank()) {
        val headers = try { RequestOptionsJson.parse(header) as? JsonObject } catch (_: IllegalArgumentException) { null }
        if (headers == null)
            return failed(HttpSpeechPackageError.UnsupportedRequest)
    }
    return try {
        val sources = engine.voices.filter(engine::isOnlineVoice).map { imported ->
            val voice = imported.withImportDefaults(engine.code)
            HttpSpeechDefinition.parse(buildJsonObject {
                put("name", voice.name.ifBlank { voice.code })
                put("url", template)
                if (header.isNotBlank()) put("header", header)
                put("multiTts", buildJsonObject {
                    put("engine", definition.jsonData())
                    put("voice", voice.raw.jsonData())
                })
            })
        }.distinctBy { it.id }
        val configuration = SpeechEngineConfig(engine.code, engine.name, engine.type ?: "inner",
            definition.values["param"].text().orEmpty(), definition.values["note"].text().orEmpty(), template, header,
            raw = definition.jsonData().jsonObject)
        HttpSpeechPackageImport(sources, engine = configuration, hasLocalAudioSettings = engine.voices.any { voice ->
            listOf("speed", "volume", "pitch").any { field ->
                voice.raw.values[field].text()?.toDoubleOrNull()?.let { it != 1.0 } == true
            }
        })
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: IllegalArgumentException) { failed(HttpSpeechPackageError.InvalidConfiguration) }
    // YAML preview is iterative, but the existing HTTP JSON representation uses recursive serialization.
    catch (_: StackOverflowError) { failed(HttpSpeechPackageError.InvalidConfiguration) }
}

internal fun SpeechPackageValue.jsonData(): JsonElement = when (this) {
    is SpeechPackageValue.Scalar -> text()?.let(::JsonPrimitive) ?: JsonNull
    is SpeechPackageValue.Sequence -> JsonArray(values.map { it.jsonData() })
    is SpeechPackageValue.Mapping -> JsonObject(values.mapValues { it.value.jsonData() })
}
