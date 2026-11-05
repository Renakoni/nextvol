package hnovel.speech

import hnovel.network.BrokerRequest
import hnovel.network.BrokerResponse
import hnovel.network.ResourceKind
import kotlinx.serialization.json.*

/** Only subscription configuration and protocol mapping; SDK ownership belongs to the Android host. */
class AzureSpeechConfiguration(metadata: JsonObject) {
    private val engine = metadata.getValue("engine").jsonObject
    private val speaker = metadata.getValue("voice").jsonObject
    private val subscription = if (engine.string("code") == "microsoft") microsoftSubscriptionParameters(speaker.string("param")) else null
    private val parameters = if (subscription == null) microsoftPackageParameters(engine.string("param")) else JsonObject(emptyMap())
    private val voice = if (subscription == null) MicrosoftPackageVoice(speaker) else null
    // r4.b also prefixes subscription keys with its embedded-license marker. The online SDK needs the bare key.
    val apiKey: String get() = subscription?.getOrNull(1)?.removePrefix("Key:") ?: parameters.string("apiKey")
    val region: String get() = subscription?.let { it.getOrElse(2) { "eastus" } } ?: parameters.string("region")
    val voiceName: String get() = if (subscription != null) subscription.firstOrNull().orEmpty() else requireNotNull(voice).name

    fun ssml(text: String, rate: Float, volume: Float): String = voice?.ssml(text, rate, volume)
        ?: ("<speak version=\"1.0\" xmlns=\"http://www.w3.org/2001/10/synthesis\" " +
            "xml:lang=\"${xmlSpeech(speaker.string("locale").ifEmpty { "en-US" })}\">" +
            "<voice name=\"${xmlSpeech(speaker.string("code"))}\"><prosody " +
            "volume=\"${(volume * 75 - 50).toInt()}%\" rate=\"${(rate * 75 - 50).toInt()}%\" pitch=\"+0Hz\">" +
            "${xmlSpeech(text)}</prosody></voice></speak>")

    suspend fun voices(execute: suspend (BrokerRequest) -> BrokerResponse): List<JsonObject> =
        microsoftVoiceCatalog(onlineSpeechJson(execute(BrokerRequest("azure-voices",
            "https://$region.tts.speech.microsoft.com/cognitiveservices/voices/list",
            headers = mapOf("Ocp-Apim-Subscription-Key" to apiKey, "Content-Type" to "application/json"),
            kind = ResourceKind.Api, followRedirects = false, maxResponseBytes = MAX_AUDIO_BYTES))).jsonArray)
}

internal fun azureSpeechPackageUrl(parameters: String): String {
    val region = microsoftPackageParameters(parameters).string("region")
    return if (region.isEmpty()) "https://speech.microsoft.com" else "https://$region.tts.speech.microsoft.com/cognitiveservices/v1"
}

/** r4.b reads speaker param as SDK name, subscription key, then region; engine param is unrelated. */
private fun microsoftSubscriptionParameters(parameters: String): List<String> =
    java.util.regex.Pattern.compile("\n").split(parameters).map { it.trim { char -> Character.isWhitespace(char) } }

fun microsoftSubscriptionPackageUrl(parameters: String): String =
    "https://${microsoftSubscriptionParameters(parameters).getOrElse(2) { "eastus" }}.tts.speech.microsoft.com/cognitiveservices/v1"
