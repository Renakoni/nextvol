package hnovel.speech

import hnovel.network.BrokerResponse
import kotlinx.serialization.json.*

/** One protocol exchange. The owning client handles segmentation, authorization and cancellation. */
interface OnlineSpeechBackend : AutoCloseable {
    suspend fun synthesize(text: String, rate: Float, volume: Float): ByteArray
    suspend fun voices(): List<JsonObject> = throw HttpSpeechException(HttpSpeechError.UnsupportedDependency)
    val updatedEngineParameters: String? get() = null
    fun cancel() {}
    override fun close() {}
}

fun supportsSpeechVoiceCatalog(code: String): Boolean =
    code in setOf("azure", "doubao", "guagua", "clipchamp", "peiyinya", "vivo")

internal val dedicatedOnlineSpeechEngines = setOf("ggtrans", "huoshan", "gemini", "xfpeiyin", "peiyinya",
    "azure", "microsoft", "doubao", "clipchamp", "guagua", "msspeech", "vivo", "bdotts", "msctts", "readspk")

internal fun onlineSpeechPackageUrl(code: String, parameters: String): String = when (code) {
    "ggtrans", "gemini" -> googleWebSpeechUrl(code, parameters)
    "huoshan" -> HuoshanSpeechClient.CRX_URL
    "xfpeiyin" -> XfPeiyinSpeechClient.SIGN_URL
    "peiyinya" -> PeiyinyaSpeechClient.ENDPOINT
    "azure" -> azureSpeechPackageUrl(parameters)
    "microsoft" -> microsoftSubscriptionPackageUrl("")
    "doubao" -> "https://ws-samantha.doubao.com/samantha/audio/tts"
    "clipchamp" -> "https://app.clipchamp.com/v2/azure-cognitive/auth-token"
    "guagua" -> "https://www.gstudios.com.cn"
    "msspeech" -> SpeechStudioSpeechClient.ENDPOINT
    "vivo" -> "https://tts-v2.vivo.com.cn/tts"
    "bdotts" -> "https://tts.baidu.com/ws/sdktts"
    "msctts" -> "https://${microsoftPackageParameters(parameters).string("region").ifEmpty { "sh" }}.voicecloud.cn/msp.do"
    "readspk" -> ReadSpeakerSpeechClient.ENDPOINT
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
