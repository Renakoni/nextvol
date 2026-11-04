package hnovel.speech

import hnovel.network.BrokerRequest
import hnovel.network.BrokerResponse
import hnovel.network.ResourceKind
import kotlinx.serialization.json.*

/** Only subscription configuration and protocol mapping; SDK ownership belongs to the Android host. */
class AzureSpeechConfiguration(metadata: JsonObject) {
    private val parameters = microsoftPackageParameters(metadata.getValue("engine").jsonObject.string("param"))
    private val voice = MicrosoftPackageVoice(metadata.getValue("voice").jsonObject)
    val apiKey: String get() = parameters.string("apiKey")
    val region: String get() = parameters.string("region")
    val voiceName: String get() = voice.name

    fun ssml(text: String, rate: Float, volume: Float): String = voice.ssml(text, rate, volume)

    suspend fun voices(execute: suspend (BrokerRequest) -> BrokerResponse): List<JsonObject> =
        microsoftVoiceCatalog(onlineSpeechJson(execute(BrokerRequest("azure-voices",
            "https://$region.tts.speech.microsoft.com/cognitiveservices/voices/list",
            headers = mapOf("Ocp-Apim-Subscription-Key" to apiKey, "Content-Type" to "application/json"),
            kind = ResourceKind.Api, followRedirects = false, maxResponseBytes = MAX_AUDIO_BYTES))).jsonArray)

    override fun toString() = "AzureSpeechConfiguration"
}

internal fun azureSpeechPackageUrl(parameters: String): String {
    val region = microsoftPackageParameters(parameters).string("region")
    return if (region.isEmpty()) "https://speech.microsoft.com" else "https://$region.tts.speech.microsoft.com/cognitiveservices/v1"
}
