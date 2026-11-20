package indi.renakoni.nextvol.ui.tts

import indi.renakoni.nextvol.tts.sovits.SovitsImportDraft
import indi.renakoni.nextvol.tts.sovits.SovitsImportField
import indi.renakoni.nextvol.tts.sovits.SovitsImportFile
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SovitsImportStateTest {
    @Test fun eachPickReplacesItsWeightAndImportRequiresBothExtensionsToMatch() {
        val gpt = SovitsImportFile(mockk(), "voice.CKPT")
        val sovits = SovitsImportFile(mockk(), "voice.pth")
        val selected = SovitsImportState(draft = SovitsImportDraft(name = "Voice", text = "Reference",
            audio = SovitsImportFile(mockk(), "sample.wav"), gpt = gpt, sovits = sovits))
        assertTrue(selected.draft.ready)

        val wrongGpt = selected.withPickedFile(SovitsImportField.Gpt, sovits)
        assertEquals(sovits, wrongGpt.draft.gpt)
        assertFalse(SovitsImportField.Gpt.accepts(wrongGpt.draft.gpt))
        assertFalse(wrongGpt.draft.ready)
        assertNull(wrongGpt.error)

        val wrongSovits = wrongGpt.withPickedFile(SovitsImportField.Sovits, sovits.copy(name = "voice.pth.zip"))
        val fixedGpt = wrongSovits.withPickedFile(SovitsImportField.Gpt, gpt)
        assertEquals("voice.pth.zip", fixedGpt.draft.sovits?.name)
        assertFalse(SovitsImportField.Sovits.accepts(fixedGpt.draft.sovits))
        assertFalse(fixedGpt.draft.ready)
        assertTrue(fixedGpt.withPickedFile(SovitsImportField.Sovits, sovits).draft.ready)
    }
}
