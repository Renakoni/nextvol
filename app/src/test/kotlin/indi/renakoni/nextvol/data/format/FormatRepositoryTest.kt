package indi.renakoni.nextvol.data.format

import android.app.Application
import indi.renakoni.nextvol.data.local.room.dao.FormattingRuleDao
import indi.renakoni.nextvol.data.local.room.entity.FormattingRuleEntity
import io.mockk.every
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterInformation
import io.nightfish.lightnovelreader.api.book.Volume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class FormatRepositoryTest {
    @Test fun globalRulesRunBeforeBookRulesAndRunTwiceForAnEmptyBookId() = runTest {
        val loaded = CompletableDeferred<Unit>()
        val dao = mockk<FormattingRuleDao>()
        every { dao.getAllBookRuleEntityFlow() } returns flow {
            emit(listOf(
                FormattingRuleEntity(bookId = "", name = "global", isRegex = false,
                    match = "a", replacement = "aa", isEnabled = true),
                FormattingRuleEntity(bookId = "book", name = "book", isRegex = false,
                    match = "aa", replacement = "b", isEnabled = true),
            ))
            loaded.complete(Unit)
        }
        val repository = FormatRepository(dao)
        loaded.await()
        val volumes = BookVolumes("book", listOf(Volume("volume", "a", listOf(ChapterInformation("chapter", "a")))))

        assertEquals(BookVolumes("book", listOf(Volume("volume", "b", listOf(ChapterInformation("chapter", "b"))))),
            repository.processBookVolumes(volumes))
        assertEquals(BookVolumes("", listOf(Volume("volume", "aaaa", listOf(ChapterInformation("chapter", "aaaa"))))),
            repository.processBookVolumes(volumes.copy(bookId = "")))
    }
}
