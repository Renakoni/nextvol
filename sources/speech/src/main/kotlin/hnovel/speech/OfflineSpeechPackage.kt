package hnovel.speech

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class OfflineEngineConfig(
    val code: String,
    val name: String,
    val type: String = "inner",
    val parameters: String = "",
    val note: String = "",
) {
    override fun toString() = "OfflineEngineConfig(code=$code)"
}

@Serializable
data class OfflineVoiceConfig(
    val engine: String,
    val code: String,
    val parameters: String = "",
    val locale: String = "",
    val sampleRate: Int = 16000,
    val speed: Float = 1f,
    val pitch: Float = 1f,
    val volume: Float = 1f,
    val type: Int = 0,
    val sherpa: SherpaVoiceConfig? = null,
    val gender: Int = 0,
    val engineParameters: String = "",
    val rawParameters: String? = null,
    val extendUI: String = "",
)

@Serializable
data class OfflineVoiceMetadata(val note: String = "", val description: String = "", val avatar: String = "")

data class OfflinePackagePlan(
    val id: String,
    val name: String,
    val directory: String,
    val resources: List<SpeechPackageResource>,
    val configuration: OfflineVoiceConfig,
    val engineConfiguration: OfflineEngineConfig = OfflineEngineConfig(configuration.engine, configuration.engine),
    val metadata: OfflineVoiceMetadata = OfflineVoiceMetadata(),
    val avatar: SpeechPackageResource? = null,
)

data class OfflinePackagePreview(val plan: OfflinePackagePlan? = null, val error: MatchaPackageError? = null, val detail: String = "")

private val offlineEngineCodes = setOf("audio", "acapela", "bdetts", "cereproc", "google", "isstts", "larktts",
    "msctts", "sherpa", "vcstts", "vocalizer", "weread", "samsung", "xiaomi", "microsoft", "sougou")

/** Maps package data to the embedded engine's resource layout, without loading code or models. */
fun previewOfflineVoice(preview: SpeechPackagePreview, engine: SpeechPackageEngine, voice: SpeechPackageVoice): OfflinePackagePreview {
    if (preview.error != null || engine.code !in offlineEngineCodes || engine.type !in setOf(null, "inner")) {
        return OfflinePackagePreview(error = MatchaPackageError.UnsupportedModel)
    }
    return try {
        if (!safeSpeechPackagePath(voice.code)) configurationFailure(MatchaPackageError.UnsafePath, "voice")
        if (engine.voices.count { it.code == voice.code } != 1) configurationFailure(MatchaPackageError.Ambiguous, "voice")
        fun engineText(key: String): String {
            val value = engine.raw?.values?.get(key) ?: return ""
            if (value !is SpeechPackageValue.Scalar) configurationFailure(MatchaPackageError.Parameters, "engine.$key")
            return value.text().orEmpty()
        }
        val engineConfiguration = OfflineEngineConfig(engine.code, engine.name, engine.type ?: "inner",
            engineText("param"), engineText("note"))
        val raw = voice.withImportDefaults(engine.code).raw.values
        fun scalar(key: String): String {
            val value = raw[key] ?: return ""
            if (value !is SpeechPackageValue.Scalar) configurationFailure(MatchaPackageError.Parameters, key)
            return value.text().orEmpty()
        }
        fun integer(key: String): Int {
            val text = scalar(key)
            return if (text.isBlank()) 0 else text.toIntOrNull() ?: configurationFailure(MatchaPackageError.Parameters, key)
        }
        val configuration = OfflineVoiceConfig(engine.code, voice.code,
            resolveOfflineParameters(scalar("param"), scalar("extendUI")), scalar("locale"), integer("sampleRate"),
            voiceNumber(raw, "speed", allowZero = true), voiceNumber(raw, "pitch"), voiceNumber(raw, "volume", allowZero = true), integer("type"),
            gender = integer("gender"), rawParameters = scalar("param"), extendUI = scalar("extendUI"))
        val metadata = OfflineVoiceMetadata(scalar("note"), scalar("desc"), scalar("avatar"))
        val avatarPath = speechPackageAvatarPath(engine.code, metadata.avatar)
        val avatar = preview.resources.singleOrNull { it.path == avatarPath }
        if (engine.code == "microsoft" && configuration.type != 0) {
            configurationFailure(MatchaPackageError.UnsupportedModel, "online voice")
        }
        if (engine.code == "sherpa") {
            val mapped = previewMatchaVoice(preview, engine, voice)
            val plan = mapped.plan ?: return OfflinePackagePreview(error = mapped.error, detail = mapped.detail)
            OfflinePackagePreview(OfflinePackagePlan(plan.id, plan.name, plan.directory, plan.resources,
                configuration.copy(sherpa = plan.configuration), engineConfiguration, metadata, avatar))
        } else {
            val directory = engine.code
            val available = preview.resources.filter { it.path.startsWith("$directory/") && !it.path.startsWith("$directory/avatar/") }.sortedBy { it.path }
            if (available.any { !safeSpeechPackagePath(it.path) }) configurationFailure(MatchaPackageError.UnsafePath, "resource")
            if (available.map { it.path }.toSet().size != available.size) configurationFailure(MatchaPackageError.Ambiguous, "resource")
            val selected = offlineResources(configuration, available)
            if (selected.isEmpty() || selected.any { it.bytes < 0 }) configurationFailure(MatchaPackageError.MissingResource, directory)
            OfflinePackagePreview(OfflinePackagePlan(engine.code + "\u0000" + voice.code, voice.name, directory, selected, configuration, engineConfiguration, metadata, avatar))
        }
    } catch (failure: OfflinePackageFailure) {
        OfflinePackagePreview(error = failure.error, detail = failure.detail)
    }
}

private fun offlineResources(config: OfflineVoiceConfig, available: List<SpeechPackageResource>): List<SpeechPackageResource> {
    val prefix = config.engine + "/"
    fun required(path: String): SpeechPackageResource {
        if (!safeSpeechPackagePath(path)) configurationFailure(MatchaPackageError.UnsafePath, path)
        return available.singleOrNull { it.path == prefix + path }
            ?: configurationFailure(MatchaPackageError.MissingResource, path)
    }
    fun tree(path: String): List<SpeechPackageResource> {
        if (!safeSpeechPackagePath(path)) configurationFailure(MatchaPackageError.UnsafePath, path)
        return available.filter { it.path.startsWith(prefix + path + "/") }.ifEmpty {
            configurationFailure(MatchaPackageError.MissingResource, path)
        }
    }
    return when (config.engine) {
        "audio" -> listOf(required(config.parameters))
        "xiaomi", "samsung", "microsoft", "vocalizer" -> tree(config.code)
        "cereproc" -> {
            val selected = tree(config.code).toMutableList()
            available.find { it.path == prefix + "Product_Records.json" }?.let { selected += it }
            if (config.parameters.isNotBlank()) {
                val options = try { Json.parseToJsonElement(config.parameters) as? JsonObject }
                    catch (_: IllegalArgumentException) { null }
                    ?: configurationFailure(MatchaPackageError.Parameters, "param")
                options["licenseFile"]?.let { value ->
                    val path = (value as? JsonPrimitive)?.contentOrNull
                        ?: configurationFailure(MatchaPackageError.Parameters, "licenseFile")
                    if (path.isNotBlank()) selected += required(path)
                }
            }
            selected.distinctBy { it.path }
        }
        "sougou" -> listOf(required("dict_zh.dat"), required("engdict.dat"), required("acoustic/" + config.code + ".bin"))
        "msctts" -> {
            val fields = config.parameters.split(',').map(String::trim)
            val voice = fields.getOrNull(2)?.takeIf(String::isNotBlank) ?: config.code
            val appId = fields.first().takeIf(String::isNotBlank) ?: available.mapNotNull { resource ->
                val path = resource.path.removePrefix(prefix).split('/')
                path.takeIf { it.size == 2 && it[1] == "$voice.jet" }?.first()
            }.singleOrNull() ?: configurationFailure(MatchaPackageError.Parameters, "appId")
            listOf(required("$appId/common.jet"), required("$appId/$voice.jet"))
        }
        "larktts" -> if (config.parameters == "1") {
            listOf(required("larktrf.trf"), required("libspeaker_xiaowu.so"), required("acoustic/${config.code}.trf"))
        } else {
            listOf(required("libTtsVoiceCommon.so"), required("acoustic/${config.code}.irf"))
        }
        "vcstts" -> {
            val path = "acoustic/${config.code}"
            val model = available.singleOrNull { it.path == prefix + path }?.let(::listOf) ?: tree(path)
            model + tree("idst")
        }
        "bdetts" -> {
            val fields = config.parameters.split(',').map(String::trim)
            val appId = fields.first().ifBlank { "90013" }
            val text = fields.getOrNull(4)?.takeIf(String::isNotBlank) ?: "text"
            val selected = mutableListOf(required("$appId/$text.dat"), required("$appId/licence.dat"),
                required("$appId/acoustic/" + config.code + ".voc.dat"))
            fields.getOrNull(2)?.takeIf(String::isNotBlank)?.let { selected += required("$appId/acoustic/$it.ext.dat") }
            available.find { it.path == prefix + "$appId/acoustic/" + config.code + ".res.dat" }?.let { selected += it }
            selected
        }
        // Other engines share dictionaries and runtime data at different levels of their directory.
        // Keep that declared resource tree intact until their loader selects the voice.
        else -> available
    }
}

internal fun offlineControlDefaults(extendUI: String?): Map<String, JsonPrimitive> {
    return speechExtensionControls(extendUI.orEmpty()).associate { it.code to it.value }
}

fun resolveOfflineParameters(param: String, extendUI: String): String {
    val defaults = offlineControlDefaults(extendUI)
    fun reference(value: String): JsonPrimitive? {
        if (!value.startsWith('@')) return null
        return defaults[value.drop(1)] ?: configurationFailure(MatchaPackageError.Parameters, value)
    }
    if (param.trimStart().startsWith('{') || param.trimStart().startsWith('[')) {
        fun resolve(value: JsonElement): JsonElement = when (value) {
            is JsonObject -> JsonObject(value.mapValues { resolve(it.value) })
            is JsonArray -> JsonArray(value.map { resolve(it) })
            is JsonPrimitive -> if (value.isString) reference(value.content) ?: value else value
        }
        // Older Sherpa packages declare @sid before a model query creates the control.
        val parsed = speechParameterJson(param, "param")
        val compatible = if (extendUI.isBlank() && parsed is JsonObject && parsed["speakerId"] == JsonPrimitive("@sid"))
            JsonObject(parsed + ("speakerId" to JsonPrimitive(0))) else parsed
        return resolve(compatible).toString()
    }
    return param.split(',').joinToString(",") { reference(it.trim())?.content ?: it }
}
