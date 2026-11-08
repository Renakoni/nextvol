package indi.renakoni.nextvol.tts

import com.baidu.speechsynthesizer.utility.SpeechDecoder
import hnovel.network.BrokerRequest
import hnovel.network.BrokerWebSocket
import hnovel.speech.BaiduOnlineSpeechClient
import hnovel.speech.HttpSpeechError
import hnovel.speech.HttpSpeechException
import hnovel.speech.OnlineSpeechBackend
import kotlinx.serialization.json.JsonObject
import java.util.UUID

internal class BaiduOnlineSpeechBackend(
    metadata: JsonObject,
    webSocket: suspend (BrokerRequest, (BrokerWebSocket) -> ByteArray) -> ByteArray,
) : OnlineSpeechBackend by BaiduOnlineSpeechClient(metadata, webSocket, { receive ->
    object : BaiduOnlineSpeechClient.Decoder {
        private val id = UUID.randomUUID().toString()
        private val decoder = SpeechDecoder(receive)
        override fun decode(bytes: ByteArray, index: Int) {
            if (decoder.decodeWithCallback(id, bytes, decoder, index) == -3)
                throw HttpSpeechException(HttpSpeechError.InvalidAudio)
        }
        override fun close() { decoder.release(id) }
    }
})
