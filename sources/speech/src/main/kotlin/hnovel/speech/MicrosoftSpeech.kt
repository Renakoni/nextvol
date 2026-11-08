package hnovel.speech

import hnovel.execution.BridgeWire
import hnovel.network.BrokerRequest
import hnovel.network.BrokerResponse
import hnovel.network.ResourceKind
import kotlinx.serialization.json.*
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.roundToInt

/** Compatibility with two audited Translator recipes, not permission to run arbitrary Java code. */
internal class MicrosoftSpeechConfiguration private constructor(
    val voice: String, val language: String, val key: ByteArray, val headers: Map<String, String>,
    val authUrl: String = MicrosoftSpeechClient.AUTH_URL,
    val outputFormat: String = "audio-24khz-160kbitrate-mono-mp3",
    val packageVoice: MicrosoftPackageVoice? = null,
) {
    companion object {
        fun fromPackage(metadata: JsonObject): MicrosoftSpeechConfiguration {
            val engine = metadata.getValue("engine").jsonObject
            val parameters = microsoftPackageParameters(engine.string("param"))
            val speaker = MicrosoftPackageVoice(metadata.getValue("voice").jsonObject)
            val locale = Locale.getDefault()
            return MicrosoftSpeechConfiguration(speaker.name, speaker.language,
                Base64.getDecoder().decode("oik6PdDdMnOXemTbwvMn9de/h9lFnfBaCWbGMMZqqoSaQaqUOqjVGm5NqsmjcBI1x+sS9ugjB55HEJWRiFXYFw=="),
                mapOf("Accept-Language" to locale.language, "X-ClientVersion" to "4.0.542a 2b44aba7",
                    "X-UserId" to "697359b46d2cc641", "X-HomeGeographicRegion" to "${locale.language}-${locale.country}",
                    "X-ClientTraceId" to UUID.randomUUID().toString(), "User-Agent" to "okhttp/4.5.0",
                    "Content-Type" to "application/json; charset=utf-8"),
                microsoftPackageAuthUrl(parameters),
                parameters["audioFormat"]?.jsonPrimitive?.contentOrNull ?: "audio-24khz-48kbitrate-mono-mp3",
                speaker)
        }

        private const val FIXED_VOICE = "3a395afea23c8c4068dab8e305f2255838410becd7ab65c480722e0e5a0a3365"
        private const val HEADER_VOICE = "0e307b3c9f8956211e668a68ca097460141caef163f226b5f4a23a191f0f408d"
        private val parameters = listOf("(voiceName = )'([^']+)'", "(lang = )'([^']+)'",
            "(java\\.base64DecodeToByteArray\\()'([^']+)'", "('X-UserId':)'([^']+)'", "('X-ClientTraceId':)'([^']+)'").map(::Regex)

        fun recognize(source: HttpSpeechDefinition): MicrosoftSpeechConfiguration? {
            if (source.text("jsLib").isNotBlank() || source.text("loginCheckJs").isNotBlank()) return null
            val script = source.url.trim().replace("\r\n", "\n")
            var normalized = script
            for (parameter in parameters) normalized = parameter.replace(normalized) { it.groupValues[1] + "'<value>'" }
            val shape = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            if (shape != FIXED_VOICE && shape != HEADER_VOICE) return null
            if (shape == FIXED_VOICE && source.text("header").isNotBlank()) return null
            return try {
                var voice = parameters[0].find(script)!!.groupValues[2]
                if (shape == HEADER_VOICE && source.text("header").isNotBlank()) {
                    val header = Json.parseToJsonElement(BridgeWire.validate(source.text("header").toByteArray(Charsets.UTF_8))).jsonObject
                    header["voice"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let { voice = it }
                }
                val language = if (shape == FIXED_VOICE) parameters[1].find(script)!!.groupValues[2] else voice.take(5)
                require(voice.matches(Regex("(?:zh|en)-[A-Za-z]{2}-[A-Za-z0-9-]{1,100}")))
                require(language.matches(Regex("(?:zh|en)-[A-Za-z]{2}")))
                val key = Base64.getDecoder().decode(parameters[2].find(script)!!.groupValues[2])
                require(key.size in 1..1024)
                val headers = Regex("'headers':\\{(.*?)\\}").find(script)!!.groupValues[1]
                val values = Regex("'([^']+)':'([^']*)'").findAll(headers).associate { it.groupValues[1] to it.groupValues[2] }
                require(values.values.all { value -> value.all { it.code in 32..126 } })
                MicrosoftSpeechConfiguration(voice, language, key, values)
            } catch (_: IllegalArgumentException) { null }
        }
    }
}

/** Session-local token; nothing is written to script caches, disk or diagnostics. Calls are serialized by the owner. */
internal class MicrosoftSpeechClient(
    private val configuration: MicrosoftSpeechConfiguration,
    private val execute: suspend (BrokerRequest) -> BrokerResponse,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private class Token(val region: String, val authorization: String, val receivedAt: Long)
    private var token: Token? = null

    suspend fun synthesize(text: String, rate: Float, volume: Float = 1f): ByteArray {
        var active = token?.takeIf { clock() - it.receivedAt in 0 until 480_000 } ?: authenticate().also { token = it }
        var response = audio(active, text, rate, volume)
        // Expired credentials get one refresh. Do not retry rate limits or arbitrary service errors.
        if (response.status == 401) {
            token = null
            active = authenticate().also { token = it }
            response = audio(active, text, rate, volume)
        }
        if (response.status == 401) token = null
        if (response.status !in 200..299) throw HttpSpeechException(httpStatusError(response.status), statusCode = response.status)
        return response.body
    }

    private suspend fun authenticate(): Token {
        val response = execute(BrokerRequest("microsoft-token", configuration.authUrl, method = "POST", body = "",
            headers = configuration.headers + ("X-MT-Signature" to signature(configuration.key)), kind = ResourceKind.Api,
            followRedirects = false, maxResponseBytes = 64 * 1024))
        if (response.status !in 200..299) throw HttpSpeechException(httpStatusError(response.status), statusCode = response.status)
        return try {
            val result = Json.parseToJsonElement(BridgeWire.validate(response.body, 64 * 1024)).jsonObject
            val region = result.getValue("r").jsonPrimitive.content
            val authorization = result.getValue("t").jsonPrimitive.content
            require(region.matches(Regex("[a-z][a-z0-9-]{0,62}")))
            require(authorization.length in 1..16384 && authorization.all { it.code in 32..126 })
            Token(region, authorization, clock())
        } catch (_: IllegalArgumentException) { throw HttpSpeechException(HttpSpeechError.InvalidSource) }
    }

    private suspend fun audio(token: Token, text: String, rate: Float, volume: Float) = execute(BrokerRequest("microsoft-audio",
        "https://${token.region}.tts.speech.microsoft.com/cognitiveservices/v1", method = "POST",
        headers = mapOf("Authorization" to token.authorization, "Content-Type" to "application/ssml+xml",
            "X-Microsoft-OutputFormat" to configuration.outputFormat),
        body = configuration.packageVoice?.ssml(text, rate, volume) ?: ssml(configuration.voice, configuration.language, text, rate), kind = ResourceKind.Api,
        followRedirects = false, maxResponseBytes = MAX_AUDIO_BYTES))

    companion object {
        const val AUTH_ORIGIN = "https://dev.microsofttranslator.com:443"
        const val AUTH_URL = "https://dev.microsofttranslator.com/apps/endpoint?api-version=1.0"

        internal fun signature(key: ByteArray, now: Date = Date(), id: String = UUID.randomUUID().toString().replace("-", "")): String {
            val date = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
            }.format(now).lowercase(Locale.ROOT) + "GMT"
            val input = ("MSTranslatorAndroidApp" + URLEncoder.encode(AUTH_URL.removePrefix("https://"), "UTF-8") + date + id)
                .lowercase(Locale.ROOT).toByteArray(Charsets.UTF_8)
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
            return "MSTranslatorAndroidApp::${Base64.getEncoder().encodeToString(mac.doFinal(input))}::$date::$id"
        }

        internal fun ssml(voice: String, language: String, text: String, rate: Float): String {
            val escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            val percent = ((rate - 1f) * 100).roundToInt()
            return "<speak xmlns=\"http://www.w3.org/2001/10/synthesis\" version=\"1.0\" xml:lang=\"$language\">" +
                "<voice name=\"$voice\"><prosody rate=\"$percent%\">$escaped</prosody></voice></speak>"
        }
    }
}
