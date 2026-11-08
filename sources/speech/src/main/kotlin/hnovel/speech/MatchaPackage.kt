package hnovel.speech

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Paths are relative to one installed model directory; the native engine inspects the model type. */
@Serializable
data class SherpaVoiceConfig(
    val modelType: String,
    val model: String,
    val vocoder: String = "",
    val voices: String = "",
    val tokens: String = "",
    val lexicons: List<String> = emptyList(),
    val dataDir: String = "",
    val dictDir: String = "",
    val ruleFsts: List<String> = emptyList(),
    val ruleFars: List<String> = emptyList(),
    val lang: String = "",
    val numThreads: Int = 4,
    val provider: String = "cpu",
    val speakerId: Int = 0,
    val noiseScale: Float = .667f,
    val noiseScaleW: Float = .8f,
    val lengthScale: Float = 1f,
    val speed: Float = 1f,
    val pitch: Float = 1f,
    val volume: Float = 1f,
)

enum class MatchaPackageError { UnsupportedModel, Parameters, UnsafePath, Ambiguous, MissingResource }

data class MatchaPackagePlan(
    val id: String,
    val name: String,
    val directory: String,
    val resources: List<SpeechPackageResource>,
    val configuration: SherpaVoiceConfig,
)

data class MatchaPackagePreview(val plan: MatchaPackagePlan? = null, val error: MatchaPackageError? = null, val detail: String = "")

internal class OfflinePackageFailure(val error: MatchaPackageError, val detail: String) : IllegalArgumentException(detail)
internal fun configurationFailure(error: MatchaPackageError, detail: String): Nothing = throw OfflinePackageFailure(error, detail)

/** Static mapping only: parameters and extendUI defaults are data, never executable expressions. */
fun previewMatchaVoice(preview: SpeechPackagePreview, engine: SpeechPackageEngine, voice: SpeechPackageVoice): MatchaPackagePreview {
    if (preview.error != null || engine.code != "sherpa" || engine.type !in setOf(null, "inner")) {
        return MatchaPackagePreview(error = MatchaPackageError.UnsupportedModel)
    }
    return try {
        val raw = voice.withImportDefaults(engine.code).raw.values
        for (key in listOf("param", "extendUI")) {
            if (raw[key] != null && raw[key] !is SpeechPackageValue.Scalar) configurationFailure(MatchaPackageError.Parameters, key)
        }
        val parameters = resolveSherpaParameters(raw["param"].text(), raw["extendUI"].text())
        val folder = parameters.string("modelDir").ifBlank { voice.code }
        if (!safeSpeechPackagePath(folder) || voice.code.isBlank() || voice.code.any(Char::isISOControl)) {
            configurationFailure(MatchaPackageError.UnsafePath, "modelDir")
        }
        if (engine.voices.count { it.code == voice.code } != 1) configurationFailure(MatchaPackageError.Ambiguous, "voice")
        val directory = "sherpa/$folder"
        val selected = preview.resources.filter { it.path.startsWith("$directory/") }.sortedBy { it.path }
        if (selected.any { it.bytes < 0 }) configurationFailure(MatchaPackageError.MissingResource, directory)
        val paths = selected.map { it.path.removePrefix("$directory/") }
        val configuration = discoverSherpaVoiceConfig(paths, parameters).copy(
            speed = voiceNumber(raw, "speed", allowZero = true),
            pitch = voiceNumber(raw, "pitch"),
            volume = voiceNumber(raw, "volume", allowZero = true),
        )
        MatchaPackagePreview(MatchaPackagePlan("sherpa\u0000" + voice.code, voice.name, directory, selected, configuration))
    } catch (failure: OfflinePackageFailure) {
        MatchaPackagePreview(error = failure.error, detail = failure.detail)
    }
}

/** Also reconstructs resource roles for installed catalogs that predate explicit model configuration. */
fun discoverSherpaVoiceConfig(paths: Collection<String>, parameters: JsonObject = JsonObject(emptyMap())): SherpaVoiceConfig {
    if (paths.any { !safeSpeechPackagePath(it) }) configurationFailure(MatchaPackageError.UnsafePath, "resource")
    if (paths.size != paths.toSet().size) configurationFailure(MatchaPackageError.Ambiguous, "resource")
    val files = paths.toSortedSet()
    fun file(path: String): String {
        if (!safeSpeechPackagePath(path)) configurationFailure(MatchaPackageError.UnsafePath, path)
        if (path !in files) configurationFailure(MatchaPackageError.MissingResource, path)
        return path
    }
    fun choose(candidates: List<String>, role: String, required: Boolean = false): String {
        if (candidates.size > 1) configurationFailure(MatchaPackageError.Ambiguous, role)
        return candidates.singleOrNull() ?: if (required) configurationFailure(MatchaPackageError.MissingResource, role) else ""
    }
    fun named(key: String, candidates: List<String>, required: Boolean = false): String =
        parameters.string(key).takeIf(String::isNotBlank)?.let(::file) ?: choose(candidates, key, required)
    fun folder(key: String, candidates: List<String>): String {
        val value = parameters.string(key).ifBlank { choose(candidates, key) }
        if (value.isNotEmpty()) {
            if (!safeSpeechPackagePath(value)) configurationFailure(MatchaPackageError.UnsafePath, key)
            if (files.none { it.startsWith("$value/") }) configurationFailure(MatchaPackageError.MissingResource, value)
        }
        return value
    }
    fun list(key: String, candidates: List<String>): List<String> {
        val supplied = parameters[key]?.takeUnless { it == JsonNull } ?: return candidates
        val values = when (supplied) {
            is JsonArray -> supplied.map { (it as? JsonPrimitive)?.contentOrNull ?: configurationFailure(MatchaPackageError.Parameters, key) }
            is JsonPrimitive -> supplied.content.split(',').filter(String::isNotBlank)
            else -> configurationFailure(MatchaPackageError.Parameters, key)
        }
        return values.map(::file).distinct()
    }
    val graphs = files.filter { it.endsWith(".onnx", ignoreCase = true) }
    val vocoder = named("vocoder", graphs.filter { it.substringAfterLast('/').startsWith("hifigan", ignoreCase = true) })
    val models = graphs.filter { it != vocoder }
    // Bundles can contain both float and quantized copies. Prefer the float copy of the same graph.
    val quantized = Regex("[.-](int8|fp16|quantized)(?=\\.onnx$)", RegexOption.IGNORE_CASE)
    val preferred = models.filter { candidate ->
        val original = candidate.replace(quantized, "")
        original == candidate || original !in models
    }
    val model = named("model", preferred, required = true)
    val directories = files.flatMap { path -> path.split('/').dropLast(1).runningFold("") { prefix, part ->
        if (prefix.isEmpty()) part else "$prefix/$part"
    }.drop(1) }.distinct().sorted()
    return SherpaVoiceConfig(
        modelType = parameters.string("modelType").ifBlank { "auto" },
        model = model,
        vocoder = vocoder,
        voices = named("voices", files.filter { it.substringAfterLast('/') == "voices.bin" }),
        tokens = named("tokens", files.filter { it.substringAfterLast('/') == "tokens.txt" }, required = true),
        lexicons = list("lexicons", files.filter { it.substringAfterLast('/').startsWith("lexicon") }),
        dataDir = folder("dataDir", directories.filter { it.substringAfterLast('/').startsWith("espeak-") }),
        dictDir = folder("dictDir", directories.filter { it.substringAfterLast('/') == "dict" }),
        ruleFsts = list("ruleFsts", files.filter { it.endsWith(".fst") }),
        ruleFars = list("ruleFars", files.filter { it.endsWith(".far") }),
        lang = parameters.string("lang"),
        numThreads = parameters.integer("threadNumber", 4, minimum = 1),
        provider = parameters.string("provider").ifBlank { "cpu" },
        speakerId = parameters.integer("speakerId", 0, minimum = 0),
        noiseScale = parameters.number("noiseScale", if (vocoder.isEmpty()) .667f else 1f, allowZero = true),
        noiseScaleW = parameters.number("noiseScaleW", .8f, allowZero = true),
        lengthScale = parameters.number("lengthScale", 1f),
    )
}

fun resolveSherpaParameters(param: String?, extendUI: String?): JsonObject {
    val parameters = if (param.isNullOrBlank()) JsonObject(emptyMap()) else speechParameterJson(param, "param") as? JsonObject
        ?: configurationFailure(MatchaPackageError.Parameters, "param")
    val defaults = offlineControlDefaults(extendUI)
    return JsonObject(parameters.mapValues { (key, value) ->
        if (value is JsonPrimitive && value.isString && value.content.startsWith('@')) {
            defaults[value.content.drop(1)]
                // Older packages create the speaker selector only after inspecting the model.
                ?: if (key == "speakerId" && extendUI.isNullOrBlank()) JsonPrimitive(0)
                else configurationFailure(MatchaPackageError.Parameters, key)
        } else value
    })
}

internal fun JsonObject.string(key: String): String {
    val value = get(key)?.takeUnless { it == JsonNull } ?: return ""
    return (value as? JsonPrimitive)?.contentOrNull ?: configurationFailure(MatchaPackageError.Parameters, key)
}

private fun JsonObject.integer(key: String, default: Int, minimum: Int): Int {
    val text = string(key)
    if (text.isBlank()) return default
    return text.toIntOrNull()?.takeIf { it >= minimum } ?: configurationFailure(MatchaPackageError.Parameters, key)
}

private fun JsonObject.number(key: String, default: Float, allowZero: Boolean = false): Float {
    val text = string(key)
    if (text.isBlank()) return default
    return text.toFloatOrNull()?.takeIf { it.isFinite() && (it > 0 || allowZero && it == 0f) }
        ?: configurationFailure(MatchaPackageError.Parameters, key)
}

internal fun voiceNumber(raw: Map<String, SpeechPackageValue>, key: String, allowZero: Boolean = false): Float {
    val value = raw[key] ?: return 1f
    if (value !is SpeechPackageValue.Scalar) configurationFailure(MatchaPackageError.Parameters, key)
    val text = value.text() ?: return 1f
    return JsonObject(mapOf(key to JsonPrimitive(text))).number(key, 1f, allowZero)
}

fun safeSpeechPackagePath(path: String): Boolean = path.isNotEmpty() && path.length <= 1024 &&
    path.none { it == '\\' || it == ':' || it == ',' || it.isISOControl() } &&
    path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }
