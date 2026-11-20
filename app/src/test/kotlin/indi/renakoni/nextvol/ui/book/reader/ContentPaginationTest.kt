package indi.renakoni.nextvol.ui.book.reader

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.nightfish.lightnovelreader.api.content.component.SimpleTextComponentData
import indi.renakoni.nextvol.ui.book.reader.content.flip.paginateComponents
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponent
import io.nightfish.lightnovelreader.api.content.component.AbstractDivisibleContentComponent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ContentPaginationTest {
    @Test
    fun mixedComponentsRetainInstancesAndSplitSequentiallyWithHeightThenWidth() = runTest {
        val events = mutableListOf<String>()
        val first = Page("first")
        val middle = Page("middle")
        val pages = listOf(Page("part 1"), Page("part 2"))
        val divisible = Divisible { height, width ->
            events += "first/start/$height/$width"
            yield()
            events += "first/end"
            pages
        }
        val empty = Divisible { height, width ->
            events += "empty/$height/$width"
            emptyList()
        }
        val result = paginateComponents(listOf(first, divisible, middle, empty), height = 240, width = 120)
        assertEquals(listOf(first, pages[0], pages[1], middle), result)
        listOf(first, pages[0], pages[1], middle).zip(result).forEach { (expected, actual) -> assertSame(expected, actual) }
        assertEquals(listOf("first/start/240/120", "first/end", "empty/240/120"), events)
    }

    @Test
    fun splitFailurePropagatesWithoutStartingLaterComponents() = runTest {
        val failure = IllegalStateException("component split failed")
        var laterCalls = 0
        val components = listOf(
            Divisible { _, _ -> throw failure },
            Divisible { _, _ -> laterCalls++; emptyList() },
        )
        assertSame(failure, runCatching { paginateComponents(components, 240, 120) }.exceptionOrNull())
        assertEquals(0, laterCalls)
    }

    private class Page(text: String) : AbstractContentComponent<SimpleTextComponentData>(SimpleTextComponentData(text)) {
        override val id = data.id
        @Composable override fun Content(modifier: Modifier) = Unit
    }

    private class Divisible(private val split: suspend (Int, Int) -> List<Page>) :
        AbstractDivisibleContentComponent<Page, SimpleTextComponentData>(SimpleTextComponentData("divisible")) {
        override val id = data.id
        @Composable override fun Content(modifier: Modifier) = Unit
        override suspend fun split(height: Int, width: Int) = split.invoke(height, width)
    }
}
