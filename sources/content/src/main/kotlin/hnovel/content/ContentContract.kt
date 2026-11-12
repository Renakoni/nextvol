package hnovel.content

import hnovel.execution.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Android supplies the isolated service; JVM fixtures execute the same worker and broker protocol. */
fun interface RuleTaskRunner {
    /** Begin bounded process startup without awaiting readiness or evaluating source code. */
    suspend fun prepareIndependent(identity: ExecutionIdentity, count: Int) {}

    suspend fun execute(identity: ExecutionIdentity, task: ExecutionTask, limits: ExecutionLimits,
        broker: SourceExecutionBroker): ExecutionResult
}

enum class ContentError { Unavailable, LoginRequired, MissingCapability, BrowserRequired, PermissionDenied,
    Network, InvalidRule, EmptyContent, RepeatedPage, Limit, Storage, AddressDenied, Dns, UnsupportedDependency,
    RouteUnavailable, RouteUnsupported, Certificate }

/** Same interpretation for direct requests and host-denied script requests. No URL crosses this boundary. */
fun hnovel.network.FailureCode.contentError(): ContentError = when (this) {
    hnovel.network.FailureCode.OriginDenied -> ContentError.PermissionDenied
    hnovel.network.FailureCode.AddressDenied -> ContentError.AddressDenied
    hnovel.network.FailureCode.Dns -> ContentError.Dns
    hnovel.network.FailureCode.RouteUnavailable -> ContentError.RouteUnavailable
    hnovel.network.FailureCode.RouteUnsupported -> ContentError.RouteUnsupported
    hnovel.network.FailureCode.ResponseTooLarge -> ContentError.Limit
    hnovel.network.FailureCode.Timeout -> ContentError.Network
    hnovel.network.FailureCode.BrowserRequired -> ContentError.BrowserRequired
    hnovel.network.FailureCode.Certificate -> ContentError.Certificate
    hnovel.network.FailureCode.StorageQuota, hnovel.network.FailureCode.StorageUnavailable -> ContentError.Storage
    else -> ContentError.Network
}

/** Recovery is a host-owned action, never a script-provided URL or authority claim. */
class SourceVerification(val kind: hnovel.network.BrowserChallengeKind?,
    val certificate: hnovel.network.CertificateProblem? = null,
    /** Exact HTTP origin; an unknown origin cannot share another request's verification. */
    val origin: String? = null,
    private val action: suspend () -> Unit) {
    suspend fun complete() = action()
    override fun toString() = "SourceVerification(kind=$kind)"
}

/** Logs expose stable codes/fields; verification is an opaque action bound to the failed account. */
open class SourceContentException(val code: ContentError, val field: String,
    val denial: hnovel.network.OriginDenial? = null, val dependency: hnovel.rules.ScriptDependency? = null,
    val verification: SourceVerification? = null, val diagnostic: hnovel.execution.ExecutionResult.Failure? = null,
    val httpStatus: Int? = null, val retry: hnovel.network.RequestRetryHint? = null,
    val storageFailure: hnovel.network.FailureCode? = null) : Exception("${code.name}: $field")

/** Readable chapters do not turn a failed catalogue into a successful refresh. */
class PartialDirectoryException internal constructor(internal val snapshot: DirectorySnapshot,
    val failure: SourceContentException) : SourceContentException(failure.code, failure.field, failure.denial,
    failure.dependency, failure.verification, failure.diagnostic, failure.httpStatus, failure.retry, failure.storageFailure) {
    val chapters: List<RuleChapter> get() = snapshot.chapters
    init { initCause(failure) }
}

@Serializable data class ScriptState(
    val metadata: JsonObject = JsonObject(emptyMap()),
    val variables: Map<String, String> = emptyMap(),
    val bigVariables: Map<String, String> = emptyMap(),
    val inherited: Map<String, String> = emptyMap(),
)

@Serializable data class RuleBook(val id: String, val title: String = "", val author: String = "",
    val description: String = "", val coverUrl: String = "", val tocUrl: String = id,
    val tags: List<String> = emptyList(), val wordCount: String = "", val latestChapter: String = "",
    val updateTime: String = "", val state: ScriptState = ScriptState(), val observedUpdate: Long = 0,
    val lastValidWordCount: String = "", val lastValidUpdateTime: String = "")

@Serializable data class RuleChapter(val id: String, val title: String, val isVolume: Boolean = false,
    val isVip: Boolean = false, val isPay: Boolean = false, val updateTime: String = "",
    val state: ScriptState = ScriptState())

@Serializable data class ContentPart(val text: String? = null, val image: String? = null)
data class RuleContent(val id: String, val title: String, val parts: List<ContentPart>,
    val previous: String?, val next: String?)

@Serializable internal data class PageDocument(val body: String, val url: String, val inline: Boolean = false, val ruleUrl: String = url,
    @kotlinx.serialization.Transient val successfulResponse: Boolean = false,
    @kotlinx.serialization.Transient val httpErrorStatus: Int? = null)
@Serializable internal data class BookPreview(val input: hnovel.rules.RuleValue, val baseUrl: String, val accountGeneration: Long)
@Serializable internal data class DirectorySnapshot(val chapters: List<RuleChapter>, val state: ScriptState)
@Serializable internal data class BookRecord(val revision: String, val book: RuleBook,
    val informationLoaded: Boolean = false, val document: PageDocument? = null,
    val chapters: List<RuleChapter> = emptyList(), val preview: BookPreview? = null,
    val partialDirectory: DirectorySnapshot? = null)
