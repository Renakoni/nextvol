package indi.renakoni.nextvol.tts

import hnovel.speech.previewHttpSpeech
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.Locale

class SpeechEngineCatalogTest {
    @Test fun languageFilteringAppliesOnlyToEnabledMicrosoftCatalogUpdates() {
        val tags = listOf("zh-CN", "zh-TW", "en-US", "fr-CA", "ja-JP", "")
        val voices = tags.map { tag -> previewHttpSpeech(buildJsonObject {
            put("name", "Voice $tag"); put("url", "https://example.com/tts")
            putJsonObject("multiTts") {
                putJsonObject("engine") { put("code", "azure") }
                putJsonObject("voice") { put("locale", tag) }
            }
        }.toString()).sources.single() }

        assertEquals(voices.take(3), filterSpeechCatalogLanguages(voices, "azure", true, Locale.CHINA))
        assertEquals(listOf(voices[2], voices[3]), filterSpeechCatalogLanguages(voices, "edgetts", true, Locale.CANADA))
        assertSame(voices, filterSpeechCatalogLanguages(voices, "azure", false, Locale.CHINA))
        assertSame(voices, filterSpeechCatalogLanguages(voices, "vivo", true, Locale.CHINA))
    }
}
