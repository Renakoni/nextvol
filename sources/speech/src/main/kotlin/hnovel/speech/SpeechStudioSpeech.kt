package hnovel.speech

import hnovel.network.BrokerRequest
import hnovel.network.BrokerResponse
import hnovel.network.ResourceKind
import kotlinx.serialization.json.*
import java.util.concurrent.ThreadLocalRandom

/** F6.n uses Speech Studio's free-trial JSON protocol, independently of Azure subscriptions. */
internal class SpeechStudioSpeechClient(
    metadata: JsonObject,
    private val execute: suspend (BrokerRequest) -> BrokerResponse,
) : OnlineSpeechBackend {
    private val speaker = metadata.getValue("voice").jsonObject
    private val parameters = microsoftPackageParameters(metadata.getValue("engine").jsonObject.string("param"))
    // The current free-trial service rejects the shipped MP3 default; its player uses raw 24 kHz PCM.
    private val outputFormat = parameters.string("audioFormat").ifEmpty { "raw-24khz-16bit-mono-pcm" }
    private val forwardedFor = ThreadLocalRandom.current().let {
        "${it.nextInt(128) + 8}.${it.nextInt(128) + 32}.${it.nextInt(256)}.${it.nextInt(256)}"
    }
    private val values = arrayOf<String?>(null, null, "Default", "general", "1.0")
    private val voice: MicrosoftPackageVoice
    private val prefix: String

    init {
        val extensions = nativeSpeechExtensions(speaker.string("extendUI"))
        for ((index, part) in speaker.string("param").split(',').take(values.size).withIndex()) {
            val value = part.trim { Character.isWhitespace(it) }
            if (value.isEmpty()) break
            values[index] = if (value.startsWith('@')) extensions[value] else value
        }
        val name = values[0].orEmpty()
        voice = MicrosoftPackageVoice(JsonObject(speaker + ("param" to JsonPrimitive(
            listOf(name, values[2], values[3], values[4]).joinToString(",")))))
        val locale = name.substringBeforeLast('-')
        val display = name.substringAfterLast('-')
        val map = buildJsonObject {
            putJsonArray("VoiceNameToIdMapItems") { add(buildJsonObject {
                put("Id", values[1].toString())
                put("Name", "Microsoft Server Speech Text to Speech Voice ($locale, $display)")
                put("ShortName", name); put("Locale", locale); put("VoiceType", "StandardVoice")
            }) }
        }
        prefix = "<!--ID=B7267351-473F-409D-9765-754A8EBCDE05;Version=1|$map-->\n" +
            "<!--ID=5B95B1CC-2C7B-494F-B746-CF22A0E779B7;Version=1|" +
            "{\"Locales\":{\"en-US\":{\"AutoApplyCustomLexiconFiles\":[{}]}}}-->\n"
    }

    override suspend fun synthesize(text: String, rate: Float, volume: Float): ByteArray {
        val body = buildJsonObject {
            // F6.n.c reads this setting but F6.n.d accidentally always uses the fallback.
            put("ttsAudioFormat", outputFormat)
            put("offsetInPlainText", 0); put("lengthInPlainText", 300)
            putJsonObject("properties") { put("SpeakTriggerSource", "AccTuningPagePlayButton") }
            put("ssml", prefix + voice.ssml(text, rate, volume))
        }
        val audio = onlineSpeechAudio(execute(BrokerRequest("msspeech", ENDPOINT, method = "POST", body = body.toString(),
            headers = mapOf("Origin" to "https://speech.microsoft.com", "Content-Type" to "application/json",
                "X-Forwarded-For" to forwardedFor,
                "customvoiceconnectionid" to "dc90b220-dc53-11ed-b935-955285f5bd8ea",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"),
            kind = ResourceKind.Api, followRedirects = false, maxResponseBytes = MAX_AUDIO_BYTES)))
        return microsoftFormatAudio(audio, outputFormat)
    }

    companion object {
        const val ENDPOINT = "https://southeastasia.api.speech.microsoft.com/accfreetrial/texttospeech/acc/v3.0-beta1/vcg/speak"
    }
}
