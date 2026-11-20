package indi.renakoni.nextvol.data.content

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import indi.renakoni.nextvol.ui.book.reader.content.componet.SimpleTextComponent
import io.mockk.*
import io.nightfish.lightnovelreader.api.content.component.SimpleTextComponentData
import io.nightfish.lightnovelreader.api.userdata.UserDataRepositoryApi
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
