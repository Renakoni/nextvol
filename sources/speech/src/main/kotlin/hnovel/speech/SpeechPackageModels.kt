package hnovel.speech

/** YAML values and explicit tags are data, including scripts and Java type names. */
sealed interface SpeechPackageValue {
    val tag: String?
    class Scalar(val value: String, override val tag: String?, val plain: Boolean) : SpeechPackageValue {
        override fun toString() = "Scalar(tag=$tag)"
    }
    class Sequence(val values: List<SpeechPackageValue>, override val tag: String?) : SpeechPackageValue {
        override fun toString() = "Sequence(size=${values.size})"
    }
    class Mapping(val values: Map<String, SpeechPackageValue>, override val tag: String?) : SpeechPackageValue {
        override fun toString() = "Mapping(size=${values.size})"
    }
}

enum class SpeechPackageKind { Voices, Roles, Replacements, VoicePool, Unknown }
enum class SpeechPackageSupport { HttpAdapter, NeedsConfiguration, Unsupported }
enum class SpeechPackageNotice {
    MissingEngine, MissingVoices, MissingRequest, ConfigurationRequired, UnsupportedEngine,
    RuntimeParameters, MissingResource, ImportPending,
}
enum class SpeechPackageError {
    ReadFailed, UnsupportedFile, InvalidArchive, DuplicatePath,
    InvalidYaml, YamlAlias, DuplicateKey, InvalidStructure,
}

class SpeechPackageDocument(val path: String, val value: SpeechPackageValue) {
    override fun toString() = "SpeechPackageDocument(path=$path)"
}

data class SpeechPackageResource(val path: String, val bytes: Long)
data class SpeechPackageResourceReference(val path: String, val present: Boolean)

class SpeechPackageVoice(val code: String, val name: String, val raw: SpeechPackageValue.Mapping) {
    override fun toString() = "SpeechPackageVoice(code=$code)"
}

class SpeechPackageEngine(
    val code: String,
    val name: String,
    val type: String?,
    val raw: SpeechPackageValue.Mapping?,
    val voices: List<SpeechPackageVoice>,
    val support: SpeechPackageSupport,
    val notices: List<SpeechPackageNotice>,
    val requiredResources: List<SpeechPackageResourceReference>,
) {
    override fun toString() = "SpeechPackageEngine(code=$code, voices=${voices.size}, support=$support)"
}

/** A preview never contains installed source IDs, an import decision, or verified service results. */
class SpeechPackagePreview(
    val name: String,
    val kinds: Set<SpeechPackageKind> = emptySet(),
    val documents: List<SpeechPackageDocument> = emptyList(),
    val engines: List<SpeechPackageEngine> = emptyList(),
    val resources: List<SpeechPackageResource> = emptyList(),
    val error: SpeechPackageError? = null,
) {
    override fun toString() = "SpeechPackagePreview(kinds=$kinds, engines=${engines.size}, error=$error)"
}

internal class SpeechPackageFailure(val error: SpeechPackageError) : RuntimeException(error.name)
internal fun packageCheck(condition: Boolean, error: SpeechPackageError) {
    if (!condition) throw SpeechPackageFailure(error)
}

internal fun SpeechPackageValue?.text(): String? {
    val scalar = this as? SpeechPackageValue.Scalar ?: return null
    if (scalar.tag == "tag:yaml.org,2002:null" || scalar.tag == null && scalar.plain &&
        scalar.value.lowercase() in setOf("null", "~", "")) return null
    return scalar.value
}
