package indi.renakoni.nextvol.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class RegexTest {
    @Test fun highlightingPreservesBackreferencesEscapesAndIncompleteInput() {
        val pattern = """(a)\1{2,3}\,\\\d""" + "\\"

        assertEquals(pattern, regexAnnotatedString(pattern).text)
    }
}
