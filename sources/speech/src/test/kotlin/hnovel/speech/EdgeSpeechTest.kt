package hnovel.speech

import hnovel.network.BrokerWebSocketMessage
import org.junit.Assert.*
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory

class EdgeSpeechTest {
    @Test fun currentHandshakeTokenMatchesPinnedUpstreamVector() {
        assertEquals("BAF4076F572BFE4E-2D6FBE5C0E24F56622EB94F37395F2D153C6C0C451DAAE2249A459BB3AFAD62C",
            EdgeGec.token(1760000123456))
    }

    @Test fun nativeSsmlKeepsOriginalUnitsAndEscapesEditableAttributesAndText() {
        val voice = "ja-JP-Voice\" & <test>"
        val text = "こんにちは & <voice> {{literal}}"
        val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(EdgeSpeechClient.ssml(voice, text, 1f, 1f).byteInputStream())
        assertEquals(text, xml.documentElement.textContent)
        assertEquals(voice, xml.getElementsByTagName("voice").item(0).attributes.getNamedItem("name").nodeValue)
        val prosody = xml.getElementsByTagName("prosody").item(0).attributes
        assertEquals("40%", prosody.getNamedItem("rate").nodeValue)
        assertEquals("50%", prosody.getNamedItem("volume").nodeValue)
        assertEquals("+0Hz", prosody.getNamedItem("pitch").nodeValue)
    }

    @Test fun lengthPrefixedAudioWaitsForTurnEndAndTextErrorsCannotBecomeAudio() {
        val audio = "ID3xxxxxxxxxpayload".toByteArray()
        val reader = EdgeSpeechAudio()
        assertFalse(reader.receive(BrokerWebSocketMessage.Text("Path:turn.start\r\n\r\n{}")))
        assertFalse(reader.receive(binary("Path:audio\r\nContent-Type:audio/mpeg\r\n", audio)))
        assertFalse(reader.receive(binary("Path:audio\r\n", byteArrayOf())))
        assertTrue(reader.receive(BrokerWebSocketMessage.Text("Path:turn.end\r\n\r\n{}")))
        assertArrayEquals(audio, reader.bytes())
        val failure = assertThrows(HttpSpeechException::class.java) {
            EdgeSpeechAudio().receive(BrokerWebSocketMessage.Text("Path:response\r\n\r\n{\"error\":{\"message\":\"private\"}}"))
        }
        assertEquals(HttpSpeechError.Network, failure.error)
        assertFalse(failure.toString().contains("private"))
        assertThrows(HttpSpeechException::class.java) {
            EdgeSpeechAudio().receive(binary("Path:audio\r\nContent-Type:application/json\r\n", "{}".toByteArray()))
        }
        assertThrows(HttpSpeechException::class.java) {
            EdgeSpeechAudio().receive(BrokerWebSocketMessage.Binary(byteArrayOf(0, 50, 0)))
        }
    }

    private fun binary(header: String, bytes: ByteArray): BrokerWebSocketMessage.Binary {
        val prefix = header.toByteArray()
        return BrokerWebSocketMessage.Binary(byteArrayOf((prefix.size ushr 8).toByte(), prefix.size.toByte()) + prefix + bytes)
    }

    @Test fun streamingDeliversPayloadBeforeTurnEndWithoutRetainingIt() {
        val received = java.io.ByteArrayOutputStream()
        val reader = EdgeSpeechAudio { bytes, offset, count -> received.write(bytes, offset, count) }
        val audio = "ID3xxxxxxxxxpayload".toByteArray()
        assertFalse(reader.receive(binary("Path:audio\r\nContent-Type:audio/mpeg\r\n", audio)))
        assertArrayEquals(audio, received.toByteArray())
        assertTrue(reader.receive(BrokerWebSocketMessage.Text("Path:turn.end\r\n\r\n{}")))
        assertTrue(reader.bytes().isEmpty())
    }
}
