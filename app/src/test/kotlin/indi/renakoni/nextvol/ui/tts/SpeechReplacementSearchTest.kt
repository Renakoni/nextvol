package indi.renakoni.nextvol.ui.tts

import indi.renakoni.nextvol.tts.SpeechReplacementRule
import org.junit.Assert.*
import org.junit.Test

class SpeechReplacementSearchTest {
    @Test fun searchMatchesOriginalFieldsAndCombinesConditions() {
        val first = SpeechReplacementRule(name = "Reading", source = "Chongqing", target = "Chungking", group = "places")
        val second = first.copy(id = "second", name = "Disabled", source = "(Chongqing)", target = "", regex = true, activate = false)
        val rules = listOf(first, second)
        assertEquals(rules, filterSpeechReplacements(rules, "chongqing"))
        assertEquals(listOf(first), filterSpeechReplacements(rules, "reading Chungking"))
        assertEquals(listOf(second), filterSpeechReplacements(rules, "source:chongqing isregex:true ischecked:false"))
        assertEquals(listOf(first), filterSpeechReplacements(rules, "target:"))
        assertTrue(filterSpeechReplacements(rules, "places").isEmpty())
    }

    @Test fun unknownPrefixesAndNonTrueBooleanValuesFollowOriginalSearch() {
        val first = SpeechReplacementRule(name = "nameabc", regex = false, activate = false)
        val second = SpeechReplacementRule(name = "abc", regex = true, activate = true)
        val rules = listOf(first, second)
        assertEquals(listOf(first), filterSpeechReplacements(rules, "name:abc isregex:other ischecked:"))
        assertEquals(rules, filterSpeechReplacements(rules, ":"))
    }
}
