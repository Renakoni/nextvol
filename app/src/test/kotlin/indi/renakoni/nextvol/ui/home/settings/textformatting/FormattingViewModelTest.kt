package indi.renakoni.nextvol.ui.home.settings.textformatting

import androidx.lifecycle.viewModelScope
import indi.renakoni.nextvol.data.book.BookRepository
import indi.renakoni.nextvol.data.format.FormatRepository
import indi.renakoni.nextvol.data.local.room.dao.FormattingRuleDao
import indi.renakoni.nextvol.data.local.room.entity.FormattingRuleEntity
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FormattingViewModelTest {
    @Test fun daoUpdatesRefreshGroupsAndRulesWithoutLosingTheCurrentBook() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val changes = Channel<List<FormattingRuleEntity>>(Channel.UNLIMITED)
        val published = Channel<Unit>(Channel.UNLIMITED)
        val dao = mockk<FormattingRuleDao>()
        every { dao.getAllBookRuleEntityFlow() } returns flow {
            for (entities in changes) {
                emit(entities)
                published.send(Unit)
            }
        }
        val repository = FormatRepository(dao)
        val books = mockk<BookRepository>()
        every { books.getBookInformationFlow(any<String>(), any()) } returns emptyFlow()
        val model = FormattingViewModel(repository, books)
        suspend fun publish(vararg entities: FormattingRuleEntity) {
            changes.send(entities.toList())
            published.receive()
        }
        fun groups() = model.formattingGroups.associate { it.id to it.size }
        val global = FormattingRuleEntity(1, "", "Global", false, "a", "b", true)
        val second = global.copy(id = 2, name = "Second", match = "b", replacement = "c")
        val book = global.copy(id = 3, bookId = "book", name = "Book")
        try {
            model.loadBookFormattingRules("")
            runCurrent()
            assertTrue(model.rules.isEmpty())
            assertTrue(groups().isEmpty())
            assertTrue(repository.rules.value.isEmpty())

            publish(global)
            runCurrent()
            assertEquals(mapOf("" to 1), groups())
            assertEquals(listOf(1), model.rules.map { it.id })
            val initial = repository.rules.value

            publish(global, second, book)
            runCurrent()
            assertEquals(mapOf("" to 2, "book" to 1), groups())
            assertEquals(listOf(1, 2), model.rules.map { it.id })
            assertEquals(setOf(""), initial.keys)
            assertEquals(listOf(1), initial.getValue("").map { it.id })

            val edited = global.copy(name = "Edited", isEnabled = false)
            publish(edited, second, book)
            runCurrent()
            assertEquals("Edited", model.rules.first().name)
            assertFalse(model.rules.first().isEnabled)
            assertTrue(initial.getValue("").single().isEnabled)

            // Switch while a rule update is queued, before yielding to the ViewModel collector.
            changes.trySend(listOf(edited, second, book.copy(name = "Updated book"))).getOrThrow()
            model.loadBookFormattingRules("book")
            assertEquals(listOf(3), model.rules.map { it.id })
            published.receive()
            runCurrent()
            assertEquals("book", model.bookId)
            assertEquals(listOf("Updated book"), model.rules.map { it.name })

            publish(edited, second)
            runCurrent()
            assertEquals(mapOf("" to 2), groups())
            assertTrue(model.rules.isEmpty())
            model.loadBookFormattingRules("missing")
            assertTrue(model.rules.isEmpty())
            assertEquals(setOf(""), repository.rules.value.keys)

            model.loadBookFormattingRules("")
            publish()
            runCurrent()
            assertEquals(0, groups()[""] ?: 0)
            assertTrue(groups().isEmpty())
            assertTrue(model.rules.isEmpty())
        } finally {
            model.viewModelScope.cancel()
            changes.close()
            Dispatchers.resetMain()
        }
    }
}
