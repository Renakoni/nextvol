package hnovel.speech

import kotlinx.serialization.json.*
import java.security.MessageDigest

/** The only native profile currently shipped. Paths are relative to one model directory. */
object ChineseMatchaProfile {
    val files: Map<String, Long> = linkedMapOf(
        "model-steps-3.onnx" to 128L * 1024 * 1024,
        "hifigan_v2.onnx" to 32L * 1024 * 1024,
        "tokens.txt" to 1024L * 1024,
        "lexicon.txt" to 32L * 1024 * 1024,
        "date.fst" to 32L * 1024 * 1024,
        "number.fst" to 32L * 1024 * 1024,
        "phone.fst" to 32L * 1024 * 1024,
    ) + listOf("jieba.dict.utf8", "hmm_model.utf8", "user.dict.utf8", "idf.utf8", "stop_words.utf8")
        .associate { "dict/$it" to 16L * 1024 * 1024 }

    // Restrict this first importer to the graph pair validated with the shipped backend.
    val models = mapOf(
        "model-steps-3.onnx" to "afcf35609e5698e8a06bd30482ac1e47dfbbb9003d10a2eb837541b42572ff02",
        "hifigan_v2.onnx" to "a41d404cce7924493540238da5b30a4bc14b6ddaf1a37f3c79fa4f59548c19f0",
    )
}

enum class MatchaPackageError { UnsupportedModel, Parameters, UnsafePath, Ambiguous, MissingResource }

data class MatchaPackagePlan(
    val id: String,
    val name: String,
    val directory: String,
    val resources: List<SpeechPackageResource>,
)

data class MatchaPackagePreview(val plan: MatchaPackagePlan? = null, val error: MatchaPackageError? = null, val detail: String = "")

/** Static mapping only: never loads an ONNX graph or executes a package parameter. */
fun previewMatchaVoice(preview: SpeechPackagePreview, engine: SpeechPackageEngine, voice: SpeechPackageVoice): MatchaPackagePreview {
    fun failure(error: MatchaPackageError, detail: String = "") = MatchaPackagePreview(error = error, detail = detail)
    if (preview.error != null || engine.code != "sherpa" || engine.type != "inner") return failure(MatchaPackageError.UnsupportedModel)
    val raw = voice.raw.values
    for (key in listOf("param", "locale", "extendUI", "speed", "pitch", "volume", "sampleRate", "type")) {
        if (raw[key] != null && raw[key] !is SpeechPackageValue.Scalar) return failure(MatchaPackageError.Parameters, key)
    }
    val param = raw["param"].text()
    val parameters = if (param.isNullOrBlank()) JsonObject(emptyMap()) else try {
        Json.parseToJsonElement(param) as? JsonObject ?: return failure(MatchaPackageError.Parameters, "param")
    } catch (_: IllegalArgumentException) { return failure(MatchaPackageError.Parameters, "param") }
    val defaults = mapOf("speakerId" to 0.0, "threadNumber" to 2.0, "noiseScale" to 1.0, "lengthScale" to 1.0)
    for ((key, value) in parameters) {
        if (value == JsonNull) continue
        val primitive = value as? JsonPrimitive ?: return failure(MatchaPackageError.Parameters, key)
        val supported = when (key) {
            "modelDir" -> primitive.isString
            "provider" -> primitive.content == "cpu"
            in defaults -> primitive.doubleOrNull == defaults[key]
            else -> false
        }
        if (!supported) return failure(MatchaPackageError.Parameters, key)
    }
    if (!raw["extendUI"].text().isNullOrBlank()) return failure(MatchaPackageError.Parameters, "extendUI")
    for ((key, expected) in mapOf("speed" to 1.0, "pitch" to 1.0, "volume" to 1.0, "sampleRate" to 22050.0, "type" to 0.0)) {
        val value = raw[key].text() ?: continue
        if (value.toDoubleOrNull() != expected) return failure(MatchaPackageError.Parameters, key)
    }
    val locale = raw["locale"].text()
    if (locale != null && locale !in setOf("zh-CN", "zh")) return failure(MatchaPackageError.UnsupportedModel)
    val folder = (parameters["modelDir"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) ?: voice.code
    if (!safeSpeechPackagePath(folder) || voice.code.isBlank() || voice.code.length > 256) return failure(MatchaPackageError.UnsafePath)
    if (engine.voices.count { it.code == voice.code } != 1) return failure(MatchaPackageError.Ambiguous)
    val directory = "${engine.code}/$folder"
    val selected = preview.resources.filter { it.path.startsWith("$directory/") }
    if (selected.groupBy { it.path }.any { it.value.size != 1 }) return failure(MatchaPackageError.Ambiguous)
    val graphs = selected.filter { it.path.endsWith(".onnx", ignoreCase = true) }.map { it.path.removePrefix("$directory/") }.toSet()
    if (graphs.any { it !in ChineseMatchaProfile.models }) return failure(MatchaPackageError.UnsupportedModel)
    val resources = ChineseMatchaProfile.files.map { (path, limit) ->
        val resource = selected.find { it.path == "$directory/$path" }
        if (resource == null || resource.bytes !in 1..limit) return failure(MatchaPackageError.MissingResource, path)
        resource
    }
    val id = MessageDigest.getInstance("SHA-256").digest("matcha-v1\u0000${engine.code}\u0000${voice.code}".toByteArray())
        .joinToString("") { "%02x".format(it) }
    return MatchaPackagePreview(MatchaPackagePlan(id, voice.name.take(256), directory, resources))
}

fun safeSpeechPackagePath(path: String): Boolean = path.isNotEmpty() && path.length <= 1024 &&
    path.none { it == '\\' || it == ':' || it == ',' || it.isISOControl() } &&
    path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }
