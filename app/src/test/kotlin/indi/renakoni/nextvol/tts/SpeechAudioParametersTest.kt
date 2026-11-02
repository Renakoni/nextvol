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
    @Test fun nativeParametersAreAppliedOnceAndOnlyUnsupportedAdjustmentsReachDsp() {
        val settings = SpeechSettings(rate = 1.5f, pitch = 1.25f, playbackSpeed = 2f)
        val voice = OfflineVoiceConfig("bdetts", "voice", speed = .8f, pitch = .8f, volume = .5f)
        assertEquals(SpeechAudioParameters(pitch = 1.25f), offlineAudioParameters(voice, settings))
        assertEquals(SpeechAudioParameters(pitch = 1f), offlineAudioParameters(voice.copy(engine = "sougou"), settings))
        assertEquals(SpeechAudioParameters(speed = 1.2f, pitch = 1f, volume = .5f),
            offlineAudioParameters(voice.copy(engine = "audio"), settings))
    }

    @Test fun onlineSpeakerParametersRemainSeparateFromNativeRequestRateAndVolume() {
        val source = mockk<HttpSpeechDefinition>()
        every { source.usesMultiTts } returns true
        every { source.raw } returns Json.parseToJsonElement("""{"multiTts":{"voice":{"speed":1.2,"pitch":0.8,"volume":0.6}}}""").jsonObject
        assertEquals(SpeechAudioParameters(1.2f, 1f, .6f), httpAudioParameters(source,
            SpeechSettings(rate = 1.5f, pitch = 1.25f, httpVolume = .5f, playbackSpeed = 2f)))
        every { source.usesMultiTts } returns false
        every { source.raw } returns Json.parseToJsonElement("""{"nextvolVoice":{"speed":1.2,"pitch":0.8,"volume":0.6}}""").jsonObject
        assertEquals(SpeechAudioParameters(1.2f, 1f, .6f), httpAudioParameters(source, SpeechSettings(pitch = 1.25f)))
    }
}
