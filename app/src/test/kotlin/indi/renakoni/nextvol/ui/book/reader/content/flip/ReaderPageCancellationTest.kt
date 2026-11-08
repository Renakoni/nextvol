package indi.renakoni.nextvol.ui.book.reader.content.flip

import android.app.Application
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import indi.renakoni.nextvol.ui.book.reader.content.componet.SimpleTextComponent
import indi.renakoni.nextvol.ui.book.reader.ReaderTextLayoutInput
import io.mockk.every
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.content.component.SimpleTextComponentData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class ReaderPageCancellationTest {
    @Test fun cancellationStopsBeforeMeasuringAnotherParagraph() {
        for (texts in listOf(listOf("first\nsecond\nthird"), listOf("first", "second", "third"))) {
            val job = Job()
            var measurements = 0
            val measured = mockk<TextLayoutResult> {
                every { lineCount } returns 1
                every { getLineStart(0) } returns 0
                every { getLineTop(0) } returns 0f
                every { getLineBottom(0) } returns 10f
            }
            val textMeasurer = mockk<TextMeasurer> {
                every { measure(any<String>(), any(), constraints = any()) } answers {
                    measurements++
                    job.cancel()
                    measured
                }
            }
            val layout = mockk<ReaderTextLayoutInput> {
                every { paragraphSpacingPx } returns 0
                every { style } returns TextStyle.Default
                every { measurer } returns textMeasurer
            }
            val components = texts.map { text ->
                mockk<SimpleTextComponent> { every { data } returns SimpleTextComponentData(text) }
            }

            assertThrows(CancellationException::class.java) {
                runBlocking(job) { paginateReaderComponents(components, height = 100, width = 100, layout = layout) }
            }

            assertEquals("Cancellation must stop the remaining paragraph measurements", 1, measurements)
        }
    }
}
