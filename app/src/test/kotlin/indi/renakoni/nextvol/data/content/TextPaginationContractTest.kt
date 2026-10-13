package indi.renakoni.nextvol.data.content

import android.app.Application
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import indi.renakoni.nextvol.ui.book.reader.content.componet.SimpleTextComponent
import io.mockk.*
import io.nightfish.lightnovelreader.api.content.component.SimpleTextComponentData
import io.nightfish.lightnovelreader.api.userdata.UserDataRepositoryApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class TextPaginationContractTest {
    private val settings = mockk<UserDataRepositoryApi>(relaxed = true)
    private val context = RuntimeEnvironment.getApplication()
    private var mockedMeasurer: TextMeasurer? = null

    @After
    fun tearDown() { mockedMeasurer?.let { unmockkObject(it) } }

    @Test
    fun pageSlicesRetainNewlinesOrderAndPartialFinalPage() {
        val lines = listOf("A\n", "BB\n", "\n", "CCC\n", "D")
        val component = component(lines.joinToString(""))
        assertEquals(listOf("A\nBB\n", "\nCCC\n", "D"), with(component) {
            layout(lines).getSlipString(data.text, 100, 20)
        })
    }

    @Test
    fun onlyBlankFirstAndLastPagesAreDiscarded() {
        val lines = listOf("\n", "A\n", "\n", "B\n", " ")
        val component = component(lines.joinToString(""))
        assertEquals(listOf("A\n", "\n", "B\n"), with(component) {
            layout(lines).getSlipString(data.text, 100, 10)
        })
    }

    @Test
    fun aViewportShorterThanOneLineStillConsumesOneLine() {
        val component = component("A")
        val layout = layout(listOf("A"))
        assertEquals(listOf("A"), with(component) {
            layout.getSlipString(data.text, 100, 5)
        })
        verify(exactly = 0) { layout.getLineBottom(-1) }
    }

    @Test
    fun zeroViewportStillMakesForwardProgress() {
        val component = component("A")
        val layout = layout(listOf("A"))
        assertEquals(listOf("A"), with(component) {
            layout.getSlipString(data.text, 0, 0)
        })
    }

    @Test
    fun negativeViewportStillMakesForwardProgress() {
        val component = component("A")
        val layout = layout(listOf("A"))
        assertEquals(listOf("A"), with(component) {
            layout.getSlipString(data.text, -1, -1)
        })
    }

    @Test
    fun splitPreservesSettingReadOrderDefaultsMeasurementInputsAndComponentConstruction() = runTest {
        val reads = mutableListOf<String>()
        val component = component("A\nBB\nC")
        coEvery { component.fontSizeUserData.getOrDefault(15f) } answers { reads += "size"; 18f }
        coEvery { component.fontLineHeightUserData.getOrDefault(7f) } answers { reads += "line"; 4f }
        coEvery { component.fontWeightUserData.getOrDefault(500f) } answers { reads += "weight"; 600f }
        coEvery { component.fontFamilyUriUserData.getOrDefault(Uri.EMPTY) } answers { reads += "font"; Uri.EMPTY }
        val style = slot<TextStyle>()
        val constraints = slot<Constraints>()
        // Keep CI independent of native font engines: use fixed, explicit line metrics.
        val measurer = component.textMeasurer
        mockedMeasurer = measurer
        mockkObject(measurer)
        every {
            measurer.measure(
                text = "A\nBB\nC", style = capture(style), overflow = any(), softWrap = any(),
                maxLines = any(), constraints = capture(constraints), layoutDirection = any(),
                density = any(), fontFamilyResolver = any(), skipCache = any(),
            )
        } returns layout(listOf("A\n", "BB\n", "C"))

        val pages = component.split(height = 20, width = 100)
        assertEquals(listOf("size", "line", "weight", "font"), reads)
        assertEquals(18.sp, style.captured.fontSize)
        assertEquals(22.sp, style.captured.lineHeight)
        assertEquals(FontWeight(600), style.captured.fontWeight)
        assertNull(style.captured.fontFamily)
        assertEquals(Constraints(maxHeight = 20, maxWidth = 100), constraints.captured)
        assertEquals(listOf("A\nBB\n", "C"), pages.map { it.data.text })
        pages.forEach {
            assertSame(settings, it.userDataRepositoryApi)
            assertSame(context, it.context)
            assertNotSame(component.textMeasurer, it.textMeasurer)
        }
    }

    private fun component(text: String) = SimpleTextComponent(SimpleTextComponentData(text), settings, context)

    private fun layout(lines: List<String>): TextLayoutResult {
        val starts = lines.runningFold(0) { offset, line -> offset + line.length }
        return mockk {
            every { lineCount } returns lines.size
            every { getLineTop(any()) } answers { firstArg<Int>() * 10f }
            every { getLineBottom(any()) } answers {
                val line = firstArg<Int>()
                require(line in lines.indices)
                (line + 1) * 10f
            }
            every { getLineStart(any()) } answers { starts[firstArg()] }
            every { getLineEnd(any(), any()) } answers { starts[firstArg<Int>() + 1] }
            every { getOffsetForPosition(any()) } answers {
                starts[(Offset(firstArg<Long>()).y.toInt() / 10).coerceIn(lines.indices)]
            }
            every { getLineForOffset(any()) } answers { starts.indexOf(firstArg<Int>()) }
        }
    }
}
