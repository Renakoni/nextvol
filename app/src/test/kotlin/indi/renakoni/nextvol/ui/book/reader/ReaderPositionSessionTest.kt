package indi.renakoni.nextvol.ui.book.reader

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import indi.renakoni.nextvol.ui.book.reader.content.ContentUiState
import indi.renakoni.nextvol.ui.book.reader.content.ReaderCheckpoint
import indi.renakoni.nextvol.ui.book.reader.content.ReaderMode
import indi.renakoni.nextvol.ui.book.reader.content.ReaderModeController
import indi.renakoni.nextvol.ui.book.reader.content.ReaderModeHost
import indi.renakoni.nextvol.ui.book.reader.content.ReaderPosition
import indi.renakoni.nextvol.ui.book.reader.content.ReaderPositionSession
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class ReaderPositionSessionTest {
    private val position = ReaderPosition("book", "chapter", 3, 879, "fingerprint")
    private fun state(chapter: String = "chapter") = mockk<ContentUiState> {
        every { readingChapterId } returns chapter
    }

    @Test fun oldRendererCannotPublishDisposeOrFinishReplacementRequest() {
        val session = ReaderPositionSession()
        val state = state()
        session.activate(state, "book", "chapter", ReaderCheckpoint("book", "chapter", position))
        val oldToken = Any()
        session.register(state, oldToken) { position }
        val oldRequest = session.pending!!
        val newToken = Any()
        var visible = position
        session.register(state, newToken) { visible }
        val request = session.pending!!
        assertNotSame(oldRequest, request)
        assertFalse(session.ownsRenderer(state, oldToken))
        assertTrue(session.ownsRenderer(state, newToken))
        session.unregister(state, oldToken)
        session.finish(state, oldRequest, null)
        session.publish(state, oldToken, position.copy(offset = 0))
        assertSame(request, session.pending)
        session.finish(state, request, position)
        visible = position.copy(offset = 900)
        session.publish(state, newToken, visible)
        assertEquals(900, session.captureNow()!!.position!!.offset)
    }

    @Test fun repeatedReflowPreservesExactLogicalOffsetAndInvalidRestoreTerminates() {
        val session = ReaderPositionSession()
        val state = state()
        val token = Any()
        session.activate(state, "book", "chapter", ReaderCheckpoint("book", "chapter", position))
        session.register(state, token) { position }
        repeat(12) {
            val request = session.reflow(state)!!
            session.publish(state, token, position.copy(offset = 800))
            assertEquals(879, request.position.offset)
            session.finish(state, request, request.position)
        }
        session.finish(state, session.reflow(state)!!, null)
        assertNull(session.pending)
        assertNull(session.checkpoint!!.position)
    }

    @Test fun captureHappensBeforeControllerCloseAndModeRebinding() {
        val session = ReaderPositionSession()
        val states = mutableListOf<ContentUiState>()
        var oldClosed = false
        val host = ReaderModeHost(session) {
            object : ReaderModeController {
                override val uiState = state().also(states::add)
                override fun changeBookId(id: String) = Unit
                override fun changeChapter(id: String) = Unit
                override fun loadNextChapter() = Unit
                override fun loadPrevChapter() = Unit
                override fun close() { oldClosed = true }
            }
        }
        host.select(ReaderMode.Flip, { "book" }, { "chapter" })
        session.register(states[0], Any()) {
            assertFalse(oldClosed)
            position
        }
        host.select(ReaderMode.Scroll, { "book" }, { "chapter" })
        assertTrue(oldClosed)
        assertEquals(position, session.pending!!.position)
        assertTrue(session.owns(states[1]))
        host.close()
    }

    @Test fun checkpointStoresOnlySourceCoordinatesAndRequestedChapterSurvivesNewSession() {
        val handle = SavedStateHandle()
        val session = ReaderPositionSession(handle)
        val state = state()
        session.activate(state, "book", "chapter", ReaderCheckpoint("book", "chapter", position))
        val data = handle.get<android.os.Bundle>("reader.content.checkpoint")!!
        assertEquals(setOf("book", "chapter", "component", "offset", "fingerprint"), data.keySet())
        assertEquals(position, ReaderPositionSession(SavedStateHandle(mapOf("reader.content.checkpoint" to data))).checkpoint!!.position)
        session.navigate(state, "book", "next", false)
        val pending = handle.get<android.os.Bundle>("reader.content.checkpoint")!!
        assertEquals(ReaderCheckpoint("book", "next"),
            ReaderPositionSession(SavedStateHandle(mapOf("reader.content.checkpoint" to pending))).checkpoint)
    }
}
