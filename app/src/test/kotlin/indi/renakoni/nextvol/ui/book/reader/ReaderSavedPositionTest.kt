package indi.renakoni.nextvol.ui.book.reader

import android.app.Application
import android.os.Bundle
import android.os.Parcel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import indi.renakoni.nextvol.data.book.BookReadingDataAccess
import indi.renakoni.nextvol.data.book.ChapterSource
import indi.renakoni.nextvol.data.local.room.dao.UserDataDao
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import indi.renakoni.nextvol.ui.book.reader.content.ContentUiState
import indi.renakoni.nextvol.ui.book.reader.content.ReaderModeController
import indi.renakoni.nextvol.ui.book.reader.content.ReaderModeFactory
import indi.renakoni.nextvol.ui.book.reader.content.ReaderPosition
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderSavedPositionTest {
    private val scheduler = TestCoroutineScheduler()
    private val store = ViewModelStore()
    private var nextId = 0
    private val position = ReaderPosition("book", "current", 2, 1397, "content-fingerprint")
    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher(scheduler))
    @After fun tearDown() {
        store.clear()
        scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    @Test fun newViewModelRestoresSerializedCheckpointBeforeOrAfterModeSelection() {
        val handle = SavedStateHandle()
        val first = reader(handle)
        scheduler.runCurrent()
        first.openBook("book", "current")
        first.positions.positioned(first.uiState.contentUiState!!, position)
        val saved = roundTrip(handle.get<Bundle>("reader.content.checkpoint")!!)
        store.clear()
        scheduler.runCurrent()

        for (selectFirst in listOf(false, true)) {
            val restored = reader(SavedStateHandle(mapOf("reader.content.checkpoint" to roundTrip(saved))))
            assertNotSame(first, restored)
            if (selectFirst) scheduler.runCurrent()
            restored.openBook("book", "stale-route-chapter")
            scheduler.runCurrent()
            assertEquals("current", restored.uiState.contentUiState!!.readingChapterId)
            assertEquals(position, restored.positions.pending!!.position)
            assertEquals(position, restored.positions.checkpoint!!.position)
        }
    }

    @Test fun savedPositionFromAnotherBookDoesNotOverrideRoute() {
        val original = SavedStateHandle()
        val first = reader(original)
        scheduler.runCurrent()
        first.openBook("book", "current")
        first.positions.positioned(first.uiState.contentUiState!!, position)
        val saved = original.get<Bundle>("reader.content.checkpoint")!!
        val next = reader(SavedStateHandle(mapOf("reader.content.checkpoint" to roundTrip(saved))))
        scheduler.runCurrent()
        next.openBook("other", "requested")
        assertEquals("requested", next.uiState.contentUiState!!.readingChapterId)
        assertNull(next.positions.pending)
        assertNull(next.positions.checkpoint!!.position)
    }

    private fun roundTrip(value: Bundle): Bundle {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeBundle(value)
            parcel.setDataPosition(0)
            parcel.readBundle(javaClass.classLoader)!!
        } finally { parcel.recycle() }
    }

    private fun reader(handle: SavedStateHandle): ReaderViewModel {
        val factory = mockk<ReaderModeFactory> {
            every { create(any(), any(), any(), any()) } answers {
                object : ReaderModeController {
                    private var book = ""
                    private var chapter: String? = null
                    private var navigation: (String, String, Boolean) -> Unit = { _, _, _ -> }
                    override val requestedChapterId get() = chapter
                    override val uiState = mockk<ContentUiState>(relaxed = true) {
                        every { bookId } answers { book }
                        every { readingChapterId } answers { chapter }
                    }
                    override fun observeNavigation(listener: (String, String, Boolean) -> Unit) { navigation = listener }
                    override fun changeBookId(id: String) { book = id }
                    override fun changeChapter(id: String) {
                        chapter = id
                        if (id.isNotBlank()) navigation(book, id, false)
                    }
                    override fun loadNextChapter() = Unit
                    override fun loadPrevChapter() = Unit
                    override fun close() = Unit
                }
            }
        }
        val dao = mockk<UserDataDao>(relaxed = true) { every { getFlow(any()) } returns flowOf(null) }
        val chapters = mockk<ChapterSource> { every { getBookVolumesFlow(any(), any()) } returns emptyFlow() }
        return ReaderViewModel(mockk(relaxed = true), chapters, mockk<BookReadingDataAccess>(relaxed = true),
            UserDataRepository(dao), factory, mockk(relaxed = true), handle).also { store.put("reader-${nextId++}", it) }
    }
}
