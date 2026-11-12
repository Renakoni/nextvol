package hnovel.speech

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class SpeechSearchQueryTest {
    @Test fun whitespaceCaseAndFirstColonAreNormalizedOnce() {
        assertEquals(listOf("" to "warm", "code" to "voice:one", "unknown" to "word"),
            SpeechSearchQuery(" \tWARM  CODE: \tVoice:One\nunknown:word ").terms)
    }

    @Test fun blankQueriesHaveNoConditionsButExplicitEmptyValuesRemain() {
        assertTrue(SpeechSearchQuery(" \t\n").terms.isEmpty())
        assertEquals(listOf("target" to "", "" to ""),
            listOf(SpeechSearchQuery("target:").terms.single(), SpeechSearchQuery(":").terms.single()))
    }

    @Test fun parsingAndVoiceMatchingShareTheCapturedDefaultLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val search = SpeechVoiceSearch("code:I")
            Locale.setDefault(Locale.US)
            assertTrue(search.matches(SpeechSearchVoice("", code = "I")))
            assertFalse(search.matches(SpeechSearchVoice("", code = "i")))
        } finally { Locale.setDefault(original) }
    }
}
