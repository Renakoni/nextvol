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
            val turkish = Locale.forLanguageTag("tr-TR")
            Locale.setDefault(turkish)
            val query = SpeechSearchQuery("CODE: I")
            val search = SpeechVoiceSearch("code:I")
            Locale.setDefault(Locale.US)
            assertEquals(turkish, query.locale)
            assertEquals(listOf("code" to "ı"), query.terms)
            assertTrue(search.matches(SpeechSearchVoice("", code = "I")))
        } finally { Locale.setDefault(original) }
    }
}
