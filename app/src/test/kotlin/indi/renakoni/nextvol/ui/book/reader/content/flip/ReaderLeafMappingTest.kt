package indi.renakoni.nextvol.ui.book.reader.content.flip

import org.junit.Assert.*
import org.junit.Test

class ReaderLeafMappingTest {
    @Test fun everyRealLeafAppearsExactlyOnceInOrder() {
        for (count in 0..999) for (columns in 1..2) {
            val mapping = ReaderLeafMapping(count, columns)
            val flattened = (0 until mapping.screenCount).flatMap { mapping.leavesOnScreen(it).toList() }
            assertEquals((0 until count).toList(), flattened)
            flattened.forEach { leaf -> assertTrue(leaf in mapping.leavesOnScreen(mapping.screenForLeaf(leaf))) }
            assertTrue(mapping.leavesOnScreen(-1).isEmpty())
            assertTrue(mapping.leavesOnScreen(mapping.screenCount).isEmpty())
            assertEquals(-1, mapping.screenForLeaf(count))
        }
    }

    @Test fun historicalPercentageResolvesALeafBeforeAScreen() {
        assertEquals(1, ReaderLeafMapping(5, 2).screenForProgress(0.85f))
        assertEquals(2, ReaderLeafMapping(5, 2).screenForProgress(0.9f))
        for (count in listOf(1, 2, 3, 5)) for (columns in 1..2) {
            val mapping = ReaderLeafMapping(count, columns)
            assertEquals(0, mapping.screenForProgress(0f))
            assertEquals(mapping.screenCount - 1, mapping.screenForProgress(1f))
            assertEquals(mapping.screenCount - 1, mapping.screenForProgress(2f))
        }
    }
}
