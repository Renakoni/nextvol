package hnovel.speech

import hnovel.network.BrokerRequest
import hnovel.network.BrokerResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PeiyinyaSpeechTest {
    @Test fun encryptedResponseIsUnwrappedBeforeFollowingAudioUrlAndMalformedDataIsRejected() = runBlocking {
        val requests = mutableListOf<BrokerRequest>()
        val metadata = buildJsonObject {
            putJsonObject("engine") { put("code", "peiyinya") }
            putJsonObject("voice") { put("param", "a0e21652e632971e") }
        }
        val audio = "ID3fixture".toByteArray()
        var malformed = false
        val client = OnlineSpeechClient(metadata, { request ->
            requests += request
            val body = if (request.url == PeiyinyaSpeechClient.ENDPOINT)
                (if (malformed) "invalid" else RESPONSE).toByteArray() else audio
            BrokerResponse(200, request.url, emptyMap(), body, "UTF-8", 0)
        })
        assertArrayEquals(audio, client.synthesize("你好，\"配音\"。\nA real sentence.", 1f, 1f, null))
        assertEquals(2, requests.size)
        assertEquals("POST", requests[0].method)
        assertEquals("application/x-www-form-urlencoded", requests[0].headers["Content-Type"])
        assertTrue(requests[0].body!!.startsWith("req="))
        assertTrue(requests[0].body!!.contains("&sec="))
        assertEquals("https://audio.example/peiyinya.mp3", requests[1].url)
        malformed = true
        val failure = try { client.synthesize("失败", 1f, 1f, null); error("Expected failure") }
            catch (failure: HttpSpeechException) { failure }
        assertEquals(HttpSpeechError.InvalidAudio, failure.error)
        assertEquals(3, requests.size)
    }

    @Test fun catalogPreservesDistinctIdsAndEmotionParameters() {
        val item = """{"zbid":"voice-id","speakercode":"voice","speakername":"名字（风格）","gender":"2",
            "zbdesp":"描述","country":"UK","diction":"en","emotion":"[{\"code\":\"neutral\",\"desc\":\"通用\"},{\"code\":\"happy\",\"desc\":\"开心\"}]"}"""
        val response = Json.parseToJsonElement("""{"model":{"ttsZhuboAllList":[{"zhuboList":[$item,$item]}]}}""").jsonObject
        val voice = peiyinyaVoices(response).single()
        assertEquals("voice-id,@emotion,@emotionDegree", voice.string("param"))
        assertEquals("名字", voice.string("name"))
        assertEquals("描述（风格）", voice.string("desc"))
        assertEquals("en-GB", voice.string("locale"))
        assertEquals(mapOf("@emotion" to "neutral", "@emotionDegree" to "50"), nativeSpeechExtensions(voice.string("extendUI")))
    }

    companion object {
        // Fixed independently generated RSA/AES response; contains no live service data.
        private const val RESPONSE = "resp=GNM3WPOT1glfZKyO3KoxytIDlorJiFut8xOsXiRgDvsu%2BSC1QwHqwUKQDgpQ%2FKTanJaJYmQ5MftCg4p0ecTFGg%3D%3D&sec=StATqZ39TElAMkgHFQmpHxJtJr%2FQNL2irTm%2Fpklv6cNZ303AJp3TckCEhcRNflRoAt7U6Aq9qQl7wdJwxf2SPrJn49T5GpOkw21z2mHKjioVI5%2FtPf8%2Fklq4Fze54QqnhbpD4bD2LgH0ce3ADNFJDmsdY17IRGO1sshE9LiL00U%3D"
    }
}
