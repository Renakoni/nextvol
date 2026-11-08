package indi.renakoni.nextvol.tts

import hnovel.speech.HttpSpeechDefinition
import hnovel.speech.OfflineVoiceConfig
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechAudioParametersTest {
    @Test fun offlineSpeakerAdjustmentsReachDspOnceAndGlobalPitchCombinesWithSpeakerPitch() {
        val settings = SpeechSettings(rate = 1.5f, pitch = 2f, volume = .4f)
        val voice = OfflineVoiceConfig("bdetts", "voice", speed = .8f, pitch = .5f, volume = .5f)
        assertEquals(SpeechAudioParameters(.8f, 1f, .5f), offlineAudioParameters(voice, settings))
    }

    @Test fun httpSpeakerParametersCombineWithGlobalPitchAndUnboundGlobalVolume() {
        val source = mockk<HttpSpeechDefinition>()
        every { source.usesMultiTts } returns true
        every { source.raw } returns Json.parseToJsonElement("""{"multiTts":{"voice":{"speed":1.2,"pitch":0.5,"volume":0.6}}}""").jsonObject
        assertEquals(SpeechAudioParameters(1.2f, 1f, .6f), httpAudioParameters(source,
            SpeechSettings(rate = 1.5f, pitch = 2f, volume = .5f)))
        every { source.usesMultiTts } returns false
        every { source.raw } returns Json.parseToJsonElement("""{"nextvolVoice":{"speed":1.2,"pitch":0.5,"volume":0.6}}""").jsonObject
        assertEquals(SpeechAudioParameters(1.2f, 1f, .3f), httpAudioParameters(source,
            SpeechSettings(rate = 1.5f, pitch = 2f, volume = .5f)))
    }
}
