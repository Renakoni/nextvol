package hnovel.speech

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.util.TreeSet

internal fun describeSpeechPackage(
    name: String,
    documents: List<SpeechPackageDocument>,
    resources: List<SpeechPackageResource>,
    checkCancelled: () -> Unit,
): SpeechPackagePreview {
    val kinds = linkedSetOf<SpeechPackageKind>()
    val definitions = linkedMapOf<String, SpeechPackageValue.Mapping>()
    val voices = linkedMapOf<String, MutableList<SpeechPackageVoice>>()
    val directories = mutableMapOf<String, String>()
    val otherFields = mapOf("roles" to SpeechPackageKind.Roles, "replaces" to SpeechPackageKind.Replacements,
        "voice_pool" to SpeechPackageKind.VoicePool)
    for (document in documents) {
        checkCancelled()
        val filename = document.path.substringAfterLast('/').substringBeforeLast('.').lowercase()
        var recognized = false
        otherFields[filename]?.let { kinds += it; recognized = true }
        val root = document.value as? SpeechPackageValue.Mapping
        if (root == null) {
            packageCheck(filename !in setOf("engines", "config"), SpeechPackageError.InvalidStructure)
            if (!recognized) kinds += SpeechPackageKind.Unknown
            continue
        }
        otherFields.forEach { (field, kind) ->
            if (field in root.values || filename == field) { kinds += kind; recognized = true }
        }
        root.values["engines"]?.let { engines ->
            recognized = true
            kinds += SpeechPackageKind.Voices
            val rows = when {
                engines is SpeechPackageValue.Sequence -> engines.values
                engines is SpeechPackageValue.Scalar && engines.text() == null -> emptyList()
                else -> throw SpeechPackageFailure(SpeechPackageError.InvalidStructure)
            }
            for (row in rows) {
                checkCancelled()
                val engine = row as? SpeechPackageValue.Mapping
                    ?: throw SpeechPackageFailure(SpeechPackageError.InvalidStructure)
                val code = engine.values["code"].text()
                packageCheck(!code.isNullOrBlank(), SpeechPackageError.InvalidStructure)
                packageCheck(definitions.put(code!!, engine) == null, SpeechPackageError.InvalidStructure)
            }
        }
        val config = filename == "config" || root.tag?.endsWith(".Config") == true ||
            !recognized && root.values.isNotEmpty() && root.values.values.all {
                it is SpeechPackageValue.Sequence || it is SpeechPackageValue.Scalar && it.text() == null
            }
        if (config) {
            recognized = true
            kinds += SpeechPackageKind.Voices
            for ((code, value) in root.values) {
                checkCancelled()
                packageCheck(code.isNotBlank(), SpeechPackageError.InvalidStructure)
                directories[code] = document.path.substringBeforeLast('/', "")
                val rows = when {
                    value is SpeechPackageValue.Sequence -> value.values
                    value is SpeechPackageValue.Scalar && value.text() == null -> emptyList()
                    else -> throw SpeechPackageFailure(SpeechPackageError.InvalidStructure)
                }
                packageCheck(code !in voices, SpeechPackageError.InvalidStructure)
                val group = mutableListOf<SpeechPackageVoice>()
                voices[code] = group
                for (row in rows) {
                    checkCancelled()
                    val voice = row as? SpeechPackageValue.Mapping
                        ?: throw SpeechPackageFailure(SpeechPackageError.InvalidStructure)
                    val voiceCode = voice.values["code"].text().orEmpty()
                    group += SpeechPackageVoice(voiceCode, voice.values["name"].text() ?: voiceCode, voice)
                }
            }
        }
        if (!recognized) kinds += SpeechPackageKind.Unknown
    }
    val resourcePaths = TreeSet(resources.map { it.path })
    val engines = (definitions.keys + voices.keys).map { code ->
        checkCancelled()
        val prefix = directories[code]?.takeIf(String::isNotEmpty)?.plus("/").orEmpty()
        val definition = definitions[code]
        val speakers = voices[code].orEmpty().toList()
        val type = definition?.values?.get("type").text()
        val notices = linkedSetOf<SpeechPackageNotice>()
        val required = mutableListOf<SpeechPackageResourceReference>()
        if (definition == null) notices += SpeechPackageNotice.MissingEngine
        if (speakers.isEmpty()) notices += SpeechPackageNotice.MissingVoices
        if (speakers.any { it.code.isBlank() }) notices += SpeechPackageNotice.ConfigurationRequired
        if (type == "http") {
            if (definition?.values?.get("url").text().isNullOrBlank()) notices += SpeechPackageNotice.MissingRequest
            notices += SpeechPackageNotice.ImportPending
        } else notices += SpeechPackageNotice.UnsupportedEngine
        if (code == "sherpa") speakers.forEach { speaker ->
            checkCancelled()
            val parameter = speaker.raw.values["param"].text()
            val parameters = if (parameter.isNullOrBlank()) null else try {
                Json.parseToJsonElement(parameter) as? JsonObject
            } catch (_: IllegalArgumentException) { null }
            if (!parameter.isNullOrBlank() && parameters == null) notices += SpeechPackageNotice.RuntimeParameters
            else {
                val modelDir = parameters?.get("modelDir")?.takeUnless { it == JsonNull }
                if (modelDir != null && (modelDir !is JsonPrimitive || !modelDir.isString)) {
                    notices += SpeechPackageNotice.ConfigurationRequired
                } else {
                    val folder = (modelDir as? JsonPrimitive)?.content?.takeIf(String::isNotBlank) ?: speaker.code
                    if (folder.isBlank()) {
                        notices += SpeechPackageNotice.ConfigurationRequired
                        return@forEach
                    }
                    val path = "${prefix}sherpa/$folder"
                    val present = resourcePaths.ceiling("$path/")?.startsWith("$path/") == true
                    required += SpeechPackageResourceReference(path, present)
                    if (!present) notices += SpeechPackageNotice.MissingResource
                }
            }
        }
        val missing = notices.any { it in setOf(SpeechPackageNotice.MissingEngine, SpeechPackageNotice.MissingVoices,
            SpeechPackageNotice.MissingRequest, SpeechPackageNotice.ConfigurationRequired) }
        SpeechPackageEngine(code, definition?.values?.get("name").text() ?: code, type, definition, speakers,
            when { missing -> SpeechPackageSupport.NeedsConfiguration; type == "http" -> SpeechPackageSupport.HttpAdapter
                else -> SpeechPackageSupport.Unsupported }, notices.toList(), required.distinct())
    }
    if (kinds.isEmpty()) kinds += SpeechPackageKind.Unknown
    return SpeechPackagePreview(name, kinds, documents, engines, resources)
}
