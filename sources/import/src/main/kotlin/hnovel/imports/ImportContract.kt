package hnovel.imports

import kotlinx.serialization.Serializable
import java.security.MessageDigest

const val LEGADO_PROFILE = "legado-text-da17bb2"
const val EXTENSION_PROFILE = "advanced-sample-20260908"
const val AUTO_PROFILE = "auto"

@Serializable
data class ImportOrigin(val kind: Kind, val location: String? = null, val finalUrl: String? = null) {
    @Serializable enum class Kind { Paste, File, Url }
    override fun toString() = "ImportOrigin(kind=$kind)"
}

/** Definition data only: no account, progress, current tab or runtime instance. */
@Serializable
data class SourceDefinition(
    val sourceId: String,
    val format: String,
    val profile: String,
    val importKey: String,
    val displayName: String,
    val enabled: Boolean,
    val enabledExplore: Boolean,
    val origin: ImportOrigin,
    val contentDigest: String,
    val revision: Long,
    val rawJson: String,
) {
    override fun toString() = "SourceDefinition(revision=$revision)"
    fun reference() = DefinitionReference(sourceId, revision)
}

data class DefinitionReference(val sourceId: String, val revision: Long)

enum class ImportCode {
    InvalidJson, DuplicateField, TooDeep, TooLarge, TooMany, InvalidShape,
    MissingIdentity, InvalidField, UnsupportedFormat, UnsupportedProfile, UnsupportedType,
    ReadFailed, DownloadFailed, StorageUnavailable, StorageQuota, Conflict, StalePreview,
    DuplicateSelection, InvalidSelection,
}

data class ImportIssue(val index: Int?, val code: ImportCode, val field: String? = null)
data class ImportNotice(val code: String, val field: String? = null)

/** Constructed by the parser. JSON remains immutable text rather than exposing its backing map. */
class SourceCandidate internal constructor(
    val index: Int,
    val format: String,
    val profile: String,
    val importKey: String,
    val displayName: String,
    val enabled: Boolean,
    val enabledExplore: Boolean,
    val rawJson: String,
    val origin: ImportOrigin,
    val notices: List<ImportNotice>,
    val existing: DefinitionReference?,
    val possibleMatches: List<DefinitionReference>,
    val duplicateIndexes: List<Int>,
) {
    override fun toString() = "SourceCandidate(index=$index)"
}

class ImportPreview internal constructor(val candidates: List<SourceCandidate>, val issues: List<ImportIssue>)

/** Nothing is selected implicitly. Replace and MapIdentity are guarded by the previewed revision. */
sealed interface ImportDecision {
    data object Add : ImportDecision
    data class Replace(val expected: DefinitionReference) : ImportDecision
    data class MapIdentity(val expected: DefinitionReference) : ImportDecision
}
data class ImportSelection(val index: Int, val decision: ImportDecision)
enum class ImportOutcome { Added, Replaced, Unchanged, Failed }
data class ImportItemResult(val index: Int, val outcome: ImportOutcome,
    val reference: DefinitionReference? = null, val error: ImportCode? = null)
data class ImportCommit(val items: List<ImportItemResult>, val error: ImportCode? = null)

data class ImportLimits(val maxBytes: Int = 32 * 1024 * 1024, val maxEntries: Int = 8192,
    val maxDepth: Int = 64, val maxStoredBytes: Int = 64 * 1024 * 1024, val maxStoredEntries: Int = 8192) {
    init { require(maxBytes > 0 && maxEntries > 0 && maxDepth in 1..128 && maxStoredBytes > 0 && maxStoredEntries > 0) }
}

internal class ImportFailure(val code: ImportCode, val field: String? = null,
    val line: Int? = null, val column: Int? = null) : RuntimeException(code.name)
internal fun digest(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** Used only when adding; an explicit identity mapping retains the stored host ID. */
internal fun newSourceId(profile: String, key: String) = digest("${profile.length}:$profile${key.length}:$key")
