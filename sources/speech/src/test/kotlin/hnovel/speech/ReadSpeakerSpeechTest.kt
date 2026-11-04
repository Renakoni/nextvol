package hnovel.speech

import hnovel.network.BrokerResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder
import java.util.Base64

class ReadSpeakerSpeechTest {
    @Test fun speakerCodeLocaleSpeedAndLiteralTextReachTheHtmlForm() = runBlocking {
        val metadata = buildJsonObject {
            putJsonObject("engine") { put("code", "readspk") }
            putJsonObject("voice") { put("code", "Hong"); put("locale", "zh-CN"); put("param", "unused") }
        }
        val audio = byteArrayOf(1, 2, 3)
        val client = ReadSpeakerSpeechClient(metadata) { request ->
            val fields = request.body!!.split('&').associate {
                it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8")
            }
            assertEquals("Hong", fields["voice"])
            assertEquals("zh_cn", fields["lang"])
            assertEquals("125", fields["speed"])
            assertEquals("<div>你好，&lt;书名&gt; &amp; 文本。</div>",
                String(Base64.getDecoder().decode(fields.getValue("html_base64")), Charsets.UTF_8))
            BrokerResponse(200, request.url, mapOf("Content-Type" to listOf("audio/mpeg")), audio, "UTF-8", 0)
        }
        assertArrayEquals(audio, client.synthesize("你好，<书名> & 文本。", 1.25f, 1f))
    }
}
