package hnovel.speech

import hnovel.network.BrokerRequest
import hnovel.network.BrokerResponse
import hnovel.network.ResourceKind
import hnovel.rules.RequestOptionsJson
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.net.URLEncoder
import java.util.concurrent.ThreadLocalRandom

internal const val MULTITTS_WEB_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"

/** F6.i and F6.h use the web RPC, including its configurable Host, rather than the Cloud APIs. */
internal fun googleWebSpeechUrl(code: String, parameters: String): String {
    val host = try {
        RequestOptionsJson.parse(parameters).jsonObject.string("Host").takeIf(String::isNotEmpty)
    } catch (_: IllegalArgumentException) { null }
    return if (code == "ggtrans")
        "https://${host ?: "translate.google.com"}/_/TranslateWebserverUi/data/batchexecute?rpcids=jQ1olc"
    else "https://${host ?: "gemini.google.com"}/_/BardChatUi/data/batchexecute?rpcids=XqA3Ic"
}

internal class GoogleTranslateSpeechClient(metadata: JsonObject,
    execute: suspend (BrokerRequest) -> BrokerResponse,
) : OnlineSpeechBackend {
    private val voice = metadata.getValue("voice").jsonObject.string("code")
    private val rpc = GoogleWebSpeechClient("jQ1olc", "https://translate.google.com",
        googleWebSpeechUrl("ggtrans", metadata.getValue("engine").jsonObject.string("param")), execute)

    override suspend fun synthesize(text: String, rate: Float, volume: Float): ByteArray =
        rpc.synthesize(buildJsonArray { add(text); add(voice); add(JsonNull); add(JsonNull) })
}

internal class GeminiSpeechClient(metadata: JsonObject,
    execute: suspend (BrokerRequest) -> BrokerResponse,
) : OnlineSpeechBackend {
    private val voice = metadata.getValue("voice").jsonObject
    private val rpc = GoogleWebSpeechClient("XqA3Ic", "https://gemini.google.com",
        googleWebSpeechUrl("gemini", metadata.getValue("engine").jsonObject.string("param")), execute)

    override suspend fun synthesize(text: String, rate: Float, volume: Float): ByteArray = rpc.synthesize(buildJsonArray {
        add(JsonNull); add(text); add(voice.string("code")); add(JsonNull)
        add((voice.string("sampleRate").toIntOrNull() ?: 16000) / 24000)
    })
}

private class GoogleWebSpeechClient(
    private val rpcId: String,
    private val origin: String,
    private val url: String,
    private val execute: suspend (BrokerRequest) -> BrokerResponse,
) {
    private val audioPattern = Regex(""""wrb.fr","$rpcId",("(?:[^"\\]++|\\.)*")""")
    private var forwardedFor = forwardedFor()

    suspend fun synthesize(arguments: JsonArray): ByteArray {
        val payload = buildJsonArray { add(buildJsonArray { add(buildJsonArray {
            add(rpcId); add(arguments.toString()); add(JsonNull); add("generic")
        }) }) }
        try {
            val response = execute(BrokerRequest("google-web-speech", url, method = "POST",
                headers = mapOf("Origin" to origin, "Referer" to "$origin/",
                    "Content-Type" to "application/x-www-form-urlencoded;charset=UTF-8",
                    "User-Agent" to MULTITTS_WEB_USER_AGENT, "X-Forwarded-For" to forwardedFor),
                body = "f.req=" + URLEncoder.encode(payload.toString(), "UTF-8"), kind = ResourceKind.Api,
                maxResponseBytes = MAX_AUDIO_BYTES * 2))
            onlineSpeechStatus(response)
            val parts = audioPattern.findAll(response.body.toString(Charsets.UTF_8)).map { match ->
                // Gemini escapes Base64 padding inside both JSON layers (\\u003d).
                val payload = Json.parseToJsonElement(match.groupValues[1]).jsonPrimitive.content
                val encoded = Json.parseToJsonElement(payload).jsonArray.first().jsonPrimitive.content
                decodeSpeechDataUri("base64://" + encoded)
            }.toList()
            if (parts.isEmpty()) throw HttpSpeechException(HttpSpeechError.InvalidAudio)
            return onlineSpeechBytes(parts)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            forwardedFor = forwardedFor()
            throw failure as? HttpSpeechException ?: HttpSpeechException(HttpSpeechError.InvalidAudio)
        }
    }

    private fun forwardedFor(): String = ThreadLocalRandom.current().let {
        "${it.nextInt(128) + 8}.${it.nextInt(128) + 32}.${it.nextInt(256)}.${it.nextInt(256)}"
    }
}
