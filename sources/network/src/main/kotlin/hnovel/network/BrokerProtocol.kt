package hnovel.network

import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Display/approval identity only. Never retains paths, query parameters, fragments or credentials. */
fun sourceOrigin(address: String): String? = address.toHttpUrlOrNull()?.takeIf {
    it.username.isEmpty() && it.password.isEmpty() && it.host.length <= 253
}?.let(NetworkPolicy::origin)?.takeIf { it.length <= 512 }

@Serializable data class OriginDenial(val origin: String, val kind: ResourceKind) {
    init { require(sourceOrigin(origin) == origin) }
}

/** Assigned by the host, never accepted as an authority claim from a script request. */
data class SourceScope(val namespace: String, val sourceId: String, val profile: String, val accountGeneration: Long = 0) {
    init { require(namespace.isNotBlank() && sourceId.isNotBlank() && profile.isNotBlank() && accountGeneration >= 0) }
    internal fun components(account: Boolean) = listOf(namespace, sourceId, profile) + if (account) listOf(accountGeneration.toString()) else emptyList()
}

@Serializable enum class ResourceKind { Document, Image, Script, Api, Import }
@Serializable enum class CacheMode { Disabled, ReadThrough, Only }
@Serializable enum class RequestStage { Parse, Permission, Queue, Connect, Response, Storage }
@Serializable enum class ResponseKind { Http, BrowserDocument }
@Serializable enum class BrowserChallengeKind { Cloudflare, SiteVerification, Login }
@Serializable enum class FailureCode {
    InvalidRequest, UnknownOption, ScriptRequired, BrowserRequired, OriginDenied, AddressDenied,
    RedirectLimit, RedirectBodyDenied, Timeout, Network, ResponseTooLarge, CacheMiss, StorageQuota, StorageUnavailable, Dns,
    RouteUnavailable, RouteUnsupported, Certificate,
}

@Serializable data class BrokerRequest(
    val id: String,
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val charset: String = "UTF-8",
    val responseCharset: String? = null,
    val retry: Int = 0,
    val timeoutMillis: Long = 30000,
    val kind: ResourceKind = ResourceKind.Document,
    val cache: CacheMode = CacheMode.Disabled,
    val followRedirects: Boolean = true,
    val maxResponseBytes: Int? = null,
    val browser: BrowserOptions? = null,
    val responseAsHex: Boolean = false,
    /** The host will inspect the response after loginCheckJs, without fetching it again. */
    val deferChallenge: Boolean = false,
) {
    override fun toString() = "BrokerRequest(method=$method, kind=$kind)"
}

data class NativeBrowserCookieSeed(val version: Long, val cookies: List<String>)

@Serializable data class BrokerResponse(val status: Int, val finalUrl: String, val headers: Map<String, List<String>>,
    val body: ByteArray, val charset: String, val redirects: Int, val fromCache: Boolean = false,
    val message: String = "", val protocol: String = "http/1.1", val sentAt: Long = 0, val receivedAt: Long = 0,
    val declaredCharset: String? = null, val method: String = "GET", val textAsHex: Boolean = false,
    val kind: ResponseKind = ResponseKind.Http) {
    fun text(): String = if (textAsHex) buildString(body.size * 2) {
        body.forEach { byte -> val value = byte.toInt() and 255; append("0123456789abcdef"[value ushr 4]); append("0123456789abcdef"[value and 15]) }
    } else body.toString(java.nio.charset.Charset.forName(charset))
    override fun toString() = "BrokerResponse(status=$status, bytes=${body.size}, redirects=$redirects, fromCache=$fromCache)"
}

@Serializable sealed interface BrokerResult {
    @Serializable data class Success(val response: BrokerResponse) : BrokerResult
    @Serializable data class Failure(val stage: RequestStage, val code: FailureCode, val attempt: Int = 0,
        val denial: OriginDenial? = null, val challenge: BrowserChallengeKind? = null,
        val verificationRequest: BrokerRequest? = null, val certificate: CertificateProblem? = null,
        val retryable: Boolean = false) : BrokerResult
}

@Serializable sealed interface CompiledRequest {
    @Serializable data class Ready(val request: BrokerRequest) : CompiledRequest
    @Serializable data class Rejected(val code: FailureCode) : CompiledRequest
}

/** BookState is host-owned catalogue/reading state; no script bridge exposes this area. */
@Serializable enum class StorageArea { Config, Account, Cache, BookState }
@Serializable data class StorageRequest(val area: StorageArea, val key: String, val value: String? = null, val ttlMillis: Long? = null) {
    override fun toString() = "StorageRequest(area=$area)"
}
@Serializable sealed interface StorageResult {
    @Serializable data class Value(val value: String?) : StorageResult { override fun toString() = "StorageValue(present=${value != null})" }
    @Serializable data class Failure(val code: FailureCode) : StorageResult
}

data class BrokerLimits(val concurrency: Int = DEFAULT_CONCURRENCY, val minIntervalMillis: Long = 0,
    val maxResponseBytes: Int = DEFAULT_MAX_RESPONSE_BYTES, val maxRequestBytes: Int = 1024 * 1024,
    val maxStorageBytes: Long = 2 * 1024 * 1024, val maxStorageEntries: Int = 1024,
    val maxCacheBytes: Int = 8 * 1024 * 1024, val cacheTtlMillis: Long = 60000,
    val maxRedirects: Int = 10, val maxRetry: Int = 3, val maxTimeoutMillis: Long = 60000,
    val maxBookStorageBytes: Long = 128 * 1024 * 1024, val maxBookStorageEntries: Int = 16384) {
    init { require(concurrency > 0 && minIntervalMillis >= 0 && maxResponseBytes > 0 && maxRequestBytes > 0 &&
        maxStorageBytes > 0 && maxStorageEntries > 0 && maxCacheBytes > 0 && cacheTtlMillis > 0 && maxRedirects >= 0 && maxRetry >= 0 && maxTimeoutMillis > 0 && maxBookStorageBytes > 0 && maxBookStorageEntries > 0) }
    companion object {
        const val DEFAULT_CONCURRENCY = 8
        // Some reading pages embed a multi-megabyte catalogue beside a short chapter.
        const val DEFAULT_MAX_RESPONSE_BYTES = 16 * 1024 * 1024
    }
}

/** A fresh instance for each rule invocation; it is never the session's persistent configuration. */
class RequestVariables(initial: Map<String, String> = emptyMap(), private val maxBytes: Int = 65536, private val maxEntries: Int = 256) {
    private val values = initial.toMutableMap()
    init { require(initial.size <= maxEntries && initial.entries.sumOf { it.key.length.toLong() + it.value.length } * 2 <= maxBytes) }
    fun get(key: String): String? = values[key]
    fun put(key: String, value: String): StorageResult {
        val size = values.entries.sumOf { it.key.length.toLong() + it.value.length } - (values[key]?.let { it.length + key.length } ?: 0) + key.length + value.length
        if (size * 2 > maxBytes || key !in values && values.size >= maxEntries) return StorageResult.Failure(FailureCode.StorageQuota)
        values[key] = value
        return StorageResult.Value(value)
    }
    fun snapshot(): Map<String, String> = values.toMap()
}

internal class BrokerFailure(val stage: RequestStage, val code: FailureCode, val deniedOrigin: String? = null) : java.io.IOException(code.name)

/** The execution owner serializes request dispatch and local response commits with revocation. */
fun interface RequestCommitGuard {
    fun commit(action: () -> Unit)
}
