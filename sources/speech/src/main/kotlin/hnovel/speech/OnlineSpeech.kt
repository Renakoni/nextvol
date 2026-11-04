package hnovel.speech

import hnovel.network.BrokerResponse
import kotlinx.serialization.json.*

/** One protocol exchange. The owning client handles segmentation, authorization and cancellation. */
internal interface OnlineSpeechBackend {
    suspend fun synthesize(text: String, rate: Float, volume: Float): ByteArray
    suspend fun voices(): List<JsonObject> = throw HttpSpeechException(HttpSpeechError.UnsupportedDependency)
}

internal val dedicatedOnlineSpeechEngines = setOf("ggtrans", "huoshan", "namiai", "filmora", "gemini", "xfpeiyin", "peiyinya")

internal fun onlineSpeechPackageUrl(code: String, parameters: String): String = when (code) {
    "ggtrans", "gemini" -> googleWebSpeechUrl(code, parameters)
    "huoshan" -> HuoshanSpeechClient.CRX_URL
    "namiai" -> NamiSpeechClient.URL
    "filmora" -> FilmoraSpeechClient.ENDPOINT
    "xfpeiyin" -> XfPeiyinSpeechClient.SIGN_URL
    "peiyinya" -> PeiyinyaSpeechClient.ENDPOINT
    else -> throw IllegalArgumentException("engine")
}

internal fun onlineSpeechStatus(response: BrokerResponse) {
    if (response.status !in 200..299)
        throw HttpSpeechException(httpStatusError(response.status), statusCode = response.status)
}

internal fun onlineSpeechJson(response: BrokerResponse): JsonElement {
    onlineSpeechStatus(response)
    return Json.parseToJsonElement(response.text())
}

internal fun onlineSpeechAudio(response: BrokerResponse): ByteArray {
    onlineSpeechStatus(response)
    val contentType = response.headers.entries.firstOrNull { it.key.equals("content-type", true) }
        ?.value?.firstOrNull().orEmpty().lowercase()
    if (response.body.isEmpty() || contentType.contains("json"))
        throw HttpSpeechException(HttpSpeechError.InvalidAudio)
    return response.body
}
