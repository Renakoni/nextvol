package indi.renakoni.nextvol.ui.book.reader

import androidx.lifecycle.ViewModelStore
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import hnovel.content.*
import hnovel.execution.ExecutionAuthority
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.book.*
import indi.renakoni.nextvol.data.web.*
import indi.renakoni.nextvol.data.web.rules.*
import io.mockk.*
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.WebDataSourceItem
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderSourcePanelViewModelTest {
    private class Fixture {
        val id = Identifier("rules", "panel")
        val book = SourceBookId(id, "A")
        val chapter = SourceChapterId(book, "one")
        val rules = mockk<RuleSource> { every { close() } just Runs }
        val accounts = SourceSessionManager(ExecutionAuthority())
        val panel = LoginAttempt(id, 0, "r1", rules, LoginReadingContext("A", "one", .25f))
        val metadata = SourceMetadata(WebDataSourceItem(id, "Fixture", ""), setOf(SourceCapability.Login), revision = "r1")
        val listings = MutableStateFlow(listOf(SourceListing(metadata, SourceStatus.Ready)))
        val runtime = mockk<SourceRuntime> { every { isAvailable } returns true; every { metadata } returns this@Fixture.metadata }
        val registry = mockk<WebSourceRegistry> {
            every { sources } returns listings
            coEvery { resolve(id) } returns SourceResolution.Ready(runtime)
        }
        val form = LoginForm(emptyList(), null)
        val login = mockk<SourceLoginService> {
            coEvery { begin(id, any(), any()) } returns panel
            coEvery { form(panel) } returns this@Fixture.form
            coEvery { withAttempt<Any?>(panel, any()) } coAnswers { secondArg<suspend () -> Any?>().invoke() }
        }
        val refresh = mockk<ReadingPanelRefresh>()
        val model = ReaderSourcePanelViewModel(login, registry, accounts, refresh)
        val store = ViewModelStore().apply { put("panel", model) }
        fun open() { model.bind(book.storageKey, chapter.storageKey); model.open(.25f) }
    }

    @Test fun changingBookCancelsOldWriteAndDoesNotRefreshTheNewBook() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = Fixture()
        try {
            f.open(); runCurrent()
            assertTrue(f.model.visible); assertFalse(f.model.busy)
            coVerify { f.login.begin(f.id, any(), LoginReadingContext("A", "one", .25f)) }
            coEvery { f.login.submit(any(), any(), any(), any(), any()) } coAnswers { awaitCancellation() }
            f.model.submit(emptyMap(), "opaque-button", f.form.id, longClick = true) { _, _, _ -> fail("Stale result") }
            f.model.submit(emptyMap(), "opaque-button", f.form.id) { _, _, _ -> fail("Duplicate result") }
            runCurrent()
            f.model.bind(SourceBookId(f.id, "B").storageKey, null)
            runCurrent()
            assertFalse(f.model.visible); assertFalse(f.model.busy)
            assertEquals(R.string.reader_source_panel_expired, f.model.notice)
            assertFalse(f.panel.lifetime.isActive)
            coVerify(exactly = 1) { f.login.submit(f.panel, emptyMap(), "opaque-button", f.form.id, true) }
            coVerify(exactly = 1) { f.login.submit(any(), any(), any(), any(), any()) }
            coVerify(exactly = 0) { f.refresh.refresh(any(), any(), any(), any()) }
        } finally { f.store.clear(); Dispatchers.resetMain() }
    }

    @Test fun completionRefreshFailureAndAccountReplacementHaveSeparateStates() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = Fixture()
        try {
            f.open(); runCurrent()
            coEvery { f.login.submit(any(), any(), any(), any()) } returns LoginActionResult(setOf(LoginRefreshTarget.Directory))
            coEvery { f.refresh.refresh(f.book, f.chapter, f.runtime, setOf(LoginRefreshTarget.Directory)) } returns Ok(ReadingPanelUpdate())
            var applied = 0
            f.model.submit(emptyMap(), "id", f.form.id) { book, chapter, _ ->
                assertEquals(f.book.storageKey, book); assertEquals(f.chapter.storageKey, chapter); applied++
            }
            runCurrent()
            assertEquals(1, applied)
            assertEquals(R.string.reader_source_panel_completed, f.model.notice)
            coEvery { f.refresh.refresh(any(), any(), any(), any()) } returns Err(WebRequestError("offline", "offline"))
            f.model.submit(emptyMap(), "id", f.form.id) { _, _, _ -> fail("Failed refresh") }
            runCurrent()
            assertEquals(R.string.reader_source_panel_refresh_failed, f.model.notice)
            coVerify(exactly = 2) { f.login.submit(any(), any(), any(), any()) }
            f.accounts.begin(f.id); runCurrent()
            assertFalse(f.model.visible)
            assertEquals(R.string.reader_source_panel_expired, f.model.notice)
            verify(exactly = 1) { f.rules.close() }
        } finally { f.store.clear(); Dispatchers.resetMain() }
    }

    @Test fun sourceRevisionAndPageDestructionInvalidateThePanel() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = Fixture()
        try {
            f.open(); runCurrent()
            f.listings.value = listOf(SourceListing(f.metadata.copy(revision = "r2"), SourceStatus.Ready))
            runCurrent()
            assertFalse(f.model.visible)
            assertFalse(f.panel.lifetime.isActive)
            assertEquals(R.string.reader_source_panel_expired, f.model.notice)
        } finally { f.store.clear(); Dispatchers.resetMain() }
    }
}
