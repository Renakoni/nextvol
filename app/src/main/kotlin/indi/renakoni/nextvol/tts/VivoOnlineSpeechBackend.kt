package indi.renakoni.nextvol.tts

import android.os.Build
import com.vivo.speechsdk.module.opus.Opus
import hnovel.network.BrokerRequest
import hnovel.network.BrokerResponse
import hnovel.network.BrokerWebSocket
import hnovel.speech.OnlineSpeechBackend
import hnovel.speech.VivoSpeechClient
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonObject

internal class VivoOnlineSpeechBackend(
    metadata: JsonObject,
    execute: suspend (BrokerRequest) -> BrokerResponse,
    webSocket: suspend (BrokerRequest, (BrokerWebSocket) -> ByteArray) -> ByteArray,
) : OnlineSpeechBackend {
    private val client = VivoSpeechClient(metadata, execute, webSocket, { sampleRate ->
        Opus.initOpusDecoder(sampleRate)
        object : VivoSpeechClient.Decoder {
            override fun decode(bytes: ByteArray): ByteArray = Opus.decode(bytes, sampleRate, 1)
            override fun close() = Opus.destroyDecoder()
        }
    }, mapOf("android_version" to Build.VERSION.SDK_INT.toString(), "model" to Build.MODEL,
        "product" to Build.PRODUCT, "system_version" to Build.DISPLAY))

    override suspend fun synthesize(text: String, rate: Float, volume: Float): ByteArray =
        permit.withPermit { client.synthesize(text, rate, volume) }

    override suspend fun voices() = client.voices()

    companion object { private val permit = Semaphore(1) }
}
