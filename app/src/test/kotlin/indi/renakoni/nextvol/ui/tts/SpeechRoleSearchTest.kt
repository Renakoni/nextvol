package indi.renakoni.nextvol.ui.tts

import indi.renakoni.nextvol.tts.SpeechRoleRule
import indi.renakoni.nextvol.tts.SpeechVoiceRef
import org.junit.Assert.*
import org.junit.Test

class SpeechRoleSearchTest {
    @Test fun submittedSearchCombinesLiteralFieldsAndOriginalPrefixes() {
        val role = SpeechRoleRule(id = "one", name = "Alice", regex = "Alice|Al", group = "Friends",
            speaker = SpeechVoiceRef(httpSource = "engine:voice", label = "Warm"), matchAfter = false)
        val abstract = role.copy(id = "two", name = "Family", regex = "Bob|Ben", isAbstract = true, activate = false)
        val rules = listOf(role, abstract)
        assertEquals(listOf(role), filterSpeechRoles(rules, "alice voice: WARM isChecked:true matchMode:before"))
        assertEquals(listOf(abstract), filterSpeechRoles(rules, "isAbstract:true isChecked:false"))
        assertEquals(listOf(role), filterSpeechRoles(rules, "regex:Alice|"))
        assertTrue(filterSpeechRoles(rules, "Friends").isEmpty())
        assertEquals(rules, filterSpeechRoles(rules, "matchMode:unknown"))
    }

    @Test fun dragSwapsSameGroupRolesOrWholeGroupsIncludingFilteredOutMembers() {
        val first = SpeechRoleRule(id = "one", group = "A")
        val hidden = first.copy(id = "hidden")
        val last = first.copy(id = "last", group = "B")
        val rules = listOf(first, hidden, last)
        assertEquals(listOf(hidden, first, last), moveSpeechRoles(rules, "role:one", "role:hidden"))
        assertEquals(rules, moveSpeechRoles(rules, "role:one", "role:last"))
        assertEquals(listOf(last, first, hidden), moveSpeechRoles(rules, "group:A", "group:B"))
    }
}
