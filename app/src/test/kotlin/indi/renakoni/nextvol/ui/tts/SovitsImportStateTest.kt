package indi.renakoni.nextvol.ui.tts

import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.sovits.SovitsImportFile
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SovitsImportStateTest {
    @Test fun weightFieldsRejectMismatchedExtensionsAndKeepThePreviousSelection() {
        val gpt = SovitsImportFile(mockk(), "voice.CKPT")
        val sovits = SovitsImportFile(mockk(), "voice.pth")
        val selected = SovitsImportState().withPickedFile(SovitsImportField.Gpt, gpt)
        assertEquals(gpt, selected.draft.gpt)

        val wrongGpt = selected.withPickedFile(SovitsImportField.Gpt, sovits)
        assertSame(selected.draft, wrongGpt.draft)
        assertEquals(R.string.sovits_choose_extension, wrongGpt.error?.resource)
        assertEquals(".ckpt", wrongGpt.error?.arguments?.single())

        val wrongSovits = wrongGpt.withPickedFile(SovitsImportField.Sovits, sovits.copy(name = "voice.pth.zip"))
        assertNull(wrongSovits.draft.sovits)
        assertEquals(".pth", wrongSovits.error?.arguments?.single())

        val corrected = wrongSovits.withPickedFile(SovitsImportField.Sovits, sovits)
        assertEquals(sovits, corrected.draft.sovits)
        assertNull(corrected.error)
    }
}
