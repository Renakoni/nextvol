package hnovel.speech

import hnovel.execution.*
import hnovel.network.*
import hnovel.rules.RequestOptionsJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.util.Base64

enum class HttpSpeechError { InvalidSource, Script, UnsupportedDependency, LoginRequired, Authentication, PermissionDenied, Network, Timeout, TooLarge, InvalidAudio }

/** Contains stable errors and origin-only permission requests, never scripts, credentials or response bodies. */
class HttpSpeechException(val error: HttpSpeechError, val statusCode: Int? = null,
    val networkCode: hnovel.network.FailureCode? = null, val deniedOrigins: List<String> = emptyList()) : Exception(error.name)

/** One selected source/session; the platform runner owns isolated execution, never the media service. */
class HttpSpeechClient(
    private val source: HttpSpeechDefinition,
    private val owner: SourceBroker,
    private val grants: List<NetworkGrant>,
    private val authority: ExecutionAuthority,
    private val run: suspend (ExecutionIdentity, ExecutionTask, ExecutionLimits, SourceExecutionBroker) -> ExecutionResult,
) : AutoCloseable {
    private val session = owner.open(SourceScope("speech", source.id, "http-tts"), grants)
    private val baseUrl = sourceOrigin(source.url.substringBefore(",").trim()) ?: grants.firstOrNull()?.origin.orEmpty()
    private val mutex = Mutex()
    private val limits = ExecutionLimits(timeoutMillis = 60_000, maxOutputBytes = 4 * 1024 * 1024, maxRequests = 16,
        maxDataBytes = 8 * 1024 * 1024)

    init {
        session.configureSource(baseUrl, source.raw["enabledCookieJar"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.boolean ?: true,
            concurrentRate = source.text("concurrentRate"))
    }

    private val identity = authority.issue(source.id, "http-tts", source.revision, "speech")
    private val microsoft = source.microsoft?.let { MicrosoftSpeechClient(it, ::executeMicrosoft) }
    private val edge = if (source.nativeEngine == "edgetts") EdgeSpeechClient(
        requireNotNull(source.multiTts).getValue("voice").jsonObject, ::executeEdge) else null
    @Volatile private var microsoftSession: SourceSession? = null
    private var audioSink: ((ByteArray, Int, Int) -> Unit)? = null

    // A client owns one immutable source revision; static bindings need parsing only once.
    private val multiTtsRequest: JsonObject? by lazy {
        source.multiTts?.let { metadata ->
            val voice = metadata.getValue("voice").jsonObject
            fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
            val defaults = mutableMapOf<String, JsonPrimitive>()
            voice.text("extendUI").takeIf(String::isNotEmpty)?.let { extension ->
                RequestOptionsJson.parse(extension).jsonArray.forEach { entry ->
                    if (entry == JsonNull) return@forEach
                    val item = entry.jsonObject
                    if (item.text("name").isNotEmpty()) {
                        defaults["@${item["code"]?.jsonPrimitive?.contentOrNull}"] =
                            item["value"]?.jsonPrimitive?.contentOrNull?.let(::JsonPrimitive) ?: JsonNull
                    }
                }
            }
            val parameters = voice.text("param").takeIf(String::isNotEmpty)
                ?.let { java.util.regex.Pattern.compile(",").split(it).toList() }.orEmpty().map { parameter ->
                    val value = parameter.trim { Character.isWhitespace(it) }
                    if (value.startsWith('@')) defaults[value] ?: JsonNull else JsonPrimitive(value)
                }
            val headers = source.text("header").takeIf(String::isNotBlank)?.let { RequestOptionsJson.parse(it).jsonObject }
                .orEmpty().mapValues { (_, value) ->
                    require(value != JsonNull)
                    JsonPrimitive(if (value is JsonPrimitive) value.content else value.toString())
                }
            buildJsonObject {
                put("url", source.url); put("headers", JsonObject(headers))
                put("voiceCode", voice.text("code")); put("params", JsonArray(parameters))
            }
        }
    }

    suspend fun synthesize(text: String, rate: Float? = null, volume: Float = 1f,
        receive: ((ByteArray, Int, Int) -> Unit)? = null): ByteArray = mutex.withLock {
        if (text.isBlank() || text.length > 65536) throw HttpSpeechException(HttpSpeechError.InvalidSource)
        if (source.text("loginCheckJs").isNotBlank()) throw HttpSpeechException(HttpSpeechError.UnsupportedDependency)
        try {
            audioSink = receive
            withTimeout(60_000) {
                val speed = source.scriptSpeed(rate)
                val audio = edge?.synthesize(text, rate ?: speed / 10f, volume, receive)
                    ?: microsoft?.synthesize(text, rate ?: speed / 10f, volume, receive != null) ?: request(text, speed, volume)
                currentCoroutineContext().ensureActive()
                if (!authority.accepts(identity)) throw CancellationException("Speech source retired")
                audio
            }
        } catch (_: TimeoutCancellationException) { throw HttpSpeechException(HttpSpeechError.Timeout) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (!authority.accepts(identity)) throw CancellationException("Speech source retired")
            throw failure as? HttpSpeechException ?: HttpSpeechException(HttpSpeechError.InvalidSource)
        } finally { audioSink = null }
    }

    private suspend fun executeEdge(request: BrokerRequest, receive: (BrokerWebSocket) -> ByteArray): ByteArray = try {
        session.webSocket(request, RequestCommitGuard { authority.authorized(identity, it) }, receive)
    } catch (failure: BrokerWebSocketException) {
        throw HttpSpeechException(failure.statusCode?.let(::httpStatusError) ?: failure.failure.code.speechError(),
            statusCode = failure.statusCode, networkCode = failure.failure.code,
            deniedOrigins = listOfNotNull(failure.failure.denial?.origin))
    }

    private suspend fun executeMicrosoft(request: BrokerRequest): BrokerResponse {
        val guard = RequestCommitGuard { authority.authorized(identity, it) }
        val authUrl = requireNotNull(source.microsoft).authUrl
        val received = if (request.url == authUrl) session.execute(request, guard) else {
            // The audited protocol derives only a regional Microsoft endpoint after successful auth.
            // This grant stays in the adapter's session; scripts never receive regional grants.
            if (grants.none { sourceOrigin(it.origin) == sourceOrigin(authUrl) })
                throw HttpSpeechException(HttpSpeechError.PermissionDenied)
            var regional: SourceSession? = null
            guard.commit {
                val origin = requireNotNull(sourceOrigin(request.url))
                regional = microsoftSession?.takeIf { it.sourceUrl == origin }
                if (regional == null) {
                    microsoftSession?.close()
                    regional = owner.open(SourceScope("speech", source.id, "microsoft"), listOf(NetworkGrant(origin))).also {
                        it.configureSource(origin, cookiesEnabled = false, concurrentRate = source.text("concurrentRate"))
                        microsoftSession = it
                    }
                }
            }
            val sink = audioSink
            if (sink == null) checkNotNull(regional).execute(request, guard)
            else checkNotNull(regional).stream(request, guard, sink)
        }
        return when (received) {
            is BrokerResult.Success -> received.response
            is BrokerResult.Failure -> throw HttpSpeechException(received.code.speechError(), networkCode = received.code,
                deniedOrigins = listOfNotNull(received.denial?.origin))
        }
    }

    private suspend fun request(text: String, speed: Int, volume: Float): ByteArray {
        val multiTts = multiTtsRequest
        val task = if (multiTts != null) {
            require(volume.isFinite() && volume in 0f..2f)
            ExecutionTask.Script("host.call('request.multitts',result)", result = JsonObject(multiTts + buildJsonObject {
                put("speakSpeed", speed * 5); put("speakVolume", volume * 50)
            }), baseUrl = baseUrl, speakText = text, speakSpeed = speed)
        } else ExecutionTask.Script(
            "({request:host.call('request.speech',result),headers:host.call('request.headers')})",
            result = JsonPrimitive(source.url), baseUrl = baseUrl, sourceHeaderRule = source.text("header"),
            libraryCode = source.text("jsLib").takeIf { it.isNotBlank() }, sourceLoginUrl = source.text("loginUrl"),
            speakText = text, speakSpeed = speed,
        )
        val output = SourceExecutionBroker(identity, authority, session, limits, baseUrl = baseUrl, speakText = text, speakSpeed = speed).use { broker ->
            val result = run(identity, task, limits, broker)
            if (broker.interactionRequired) throw HttpSpeechException(HttpSpeechError.LoginRequired)
            when (result) {
                is ExecutionResult.Success -> result.output
                is ExecutionResult.Failure -> {
                    val status = broker.speechStatus.takeIf { result.code == hnovel.execution.FailureCode.BridgeDenied }
                    throw HttpSpeechException(when (result.code) {
                        hnovel.execution.FailureCode.Timeout -> HttpSpeechError.Timeout
                        hnovel.execution.FailureCode.InputLimit, hnovel.execution.FailureCode.OutputLimit -> HttpSpeechError.TooLarge
                        hnovel.execution.FailureCode.UnsupportedDependency -> HttpSpeechError.UnsupportedDependency
                        hnovel.execution.FailureCode.BridgeDenied -> status?.let(::httpStatusError)
                            ?: broker.requestFailure?.code?.speechError() ?: HttpSpeechError.Script
                        else -> HttpSpeechError.Script
                    }, statusCode = status, networkCode = broker.requestFailure?.code,
                        deniedOrigins = session.deniedOrigins.map { it.origin })
                }
            }
        }
        currentCoroutineContext().ensureActive()
        if (!authority.accepts(identity)) throw CancellationException("Speech source retired")
        val result = Json.parseToJsonElement(BridgeWire.validate(output.toByteArray(Charsets.UTF_8), limits.maxOutputBytes)).jsonObject
        val request = if (multiTts != null) {
            val url = result.getValue("url").jsonPrimitive.content
            if (url.startsWith("base64://")) return inlineAudio(url)
            MultiTtsRequest.compile(result, "speech")
        } else {
            val prepared = result.getValue("request").jsonObject
            val rule = prepared.getValue("rule").jsonPrimitive.content
            if (rule.startsWith("data:", true)) return inlineAudio(rule)
            val compiled = RequestCompiler().compile("speech", rule, baseUrl, speakText = text, speakSpeed = speed,
                headers = result.getValue("headers").jsonObject.mapValues { it.value.jsonPrimitive.content }, kind = ResourceKind.Api,
                expandTemplates = prepared.getValue("templates").jsonPrimitive.boolean,
                templateValues = prepared.getValue("values").jsonObject.mapValues { it.value.jsonPrimitive.content })
            (compiled as? CompiledRequest.Ready)?.request ?: throw HttpSpeechException(HttpSpeechError.InvalidSource)
        }
        if (request.browser != null) throw HttpSpeechException(HttpSpeechError.UnsupportedDependency)
        val guard = RequestCommitGuard { authority.authorized(identity, it) }
        val bounded = request.copy(maxResponseBytes = MAX_AUDIO_BYTES)
        val sink = audioSink
        val response = when (val received = if (sink == null) session.execute(bounded, guard) else session.stream(bounded, guard, sink)) {
            is BrokerResult.Success -> received.response
            is BrokerResult.Failure -> throw HttpSpeechException(received.code.speechError(), networkCode = received.code,
                deniedOrigins = listOfNotNull(received.denial?.origin))
        }
        currentCoroutineContext().ensureActive()
        if (!authority.accepts(identity)) throw CancellationException("Speech source retired")
        if (response.status !in 200..299) throw HttpSpeechException(httpStatusError(response.status), statusCode = response.status)
        return response.body.also { if (sink == null) checkSpeechAudioContainer(it) }
    }

    private fun inlineAudio(value: String): ByteArray {
        val bytes = decodeSpeechDataUri(value)
        val sink = audioSink ?: return bytes.also(::checkSpeechAudioContainer)
        sink(bytes, 0, bytes.size)
        return byteArrayOf()
    }

    /** Retire execution immediately without closing sockets on the caller's thread.
     * The owner also cancels the synthesis coroutine to interrupt pending requests. */
    fun cancel() { authority.revoke(identity) }

    /** Network teardown must run off the main thread, after synthesis has stopped. */
    override fun close() {
        cancel()
        try { session.close() } finally { microsoftSession?.close() }
    }
}

internal const val MAX_AUDIO_BYTES = 2 * 1024 * 1024

/** Reject text/error payloads. The platform must still validate the audio track before committing a file. */
internal fun checkSpeechAudioContainer(bytes: ByteArray) {
    fun starts(value: String, offset: Int = 0) = bytes.size >= offset + value.length &&
        value.indices.all { bytes[offset + it].toInt() and 255 == value[it].code }
    val sync = bytes.size >= 4 && bytes[0].toInt() and 255 == 255 && bytes[1].toInt() and 224 == 224
    if (bytes.size < 12 || !(starts("RIFF") && starts("WAVE", 8) || starts("ID3") || sync ||
            starts("OggS") || starts("fLaC") || starts("ftyp", 4))) throw HttpSpeechException(HttpSpeechError.InvalidAudio)
}

/** Audio data may exceed the ordinary URL limit. Decode it here without enlarging book-source URLs. */
internal fun decodeSpeechDataUri(value: String): ByteArray {
    val encoded = if (value.startsWith("base64://")) value.substring(9).filterNot { it in " \t\r\n" } else {
        val comma = value.indexOf(',')
        if (comma !in 5..128 || !value.substring(0, comma).endsWith(";base64", true))
            throw HttpSpeechException(HttpSpeechError.InvalidAudio)
        value.substring(comma + 1)
    }
    if (encoded.length > (MAX_AUDIO_BYTES + 2) / 3 * 4) throw HttpSpeechException(HttpSpeechError.TooLarge)
    val bytes = try { Base64.getDecoder().decode(encoded) }
        catch (_: IllegalArgumentException) { throw HttpSpeechException(HttpSpeechError.InvalidAudio) }
    if (bytes.isEmpty()) throw HttpSpeechException(HttpSpeechError.InvalidAudio)
    if (bytes.size > MAX_AUDIO_BYTES) throw HttpSpeechException(HttpSpeechError.TooLarge)
    return bytes
}

private fun hnovel.network.FailureCode.speechError() = when (this) {
    hnovel.network.FailureCode.InvalidRequest, hnovel.network.FailureCode.UnknownOption,
    hnovel.network.FailureCode.ScriptRequired -> HttpSpeechError.InvalidSource
    hnovel.network.FailureCode.BrowserRequired -> HttpSpeechError.UnsupportedDependency
    hnovel.network.FailureCode.Timeout -> HttpSpeechError.Timeout
    hnovel.network.FailureCode.OriginDenied, hnovel.network.FailureCode.AddressDenied -> HttpSpeechError.PermissionDenied
    hnovel.network.FailureCode.ResponseTooLarge -> HttpSpeechError.TooLarge
    else -> HttpSpeechError.Network
}

internal fun httpStatusError(status: Int) = if (status == 401 || status == 403) HttpSpeechError.Authentication else HttpSpeechError.Network
