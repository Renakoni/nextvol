package hnovel.content

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RuleListSessionTest {
    @Test fun repeatedRemoteCursorsStopEvenWhenEveryPageIsFilteredEmpty() = runBlocking {
        val events = mutableListOf<ContentTraceEvent>()
        var calls = 0
        val session = RuleListSession("ruleExplore", true, ContentTrace { events += it }) { page, url, _ ->
            calls++
            assertEquals(if (page == 1) null else "https://example.invalid/b", url)
            RuleListResult(emptyList(), "https://example.invalid/${if (page == 1) 'a' else 'b'}",
                "https://example.invalid/${if (page == 1) 'b' else 'a'}")
        }
        val first = session.page()
        assertNotNull(first.nextCursor)
        assertEquals("FilteredEmpty", events.single().result)
        assertNull(session.page(first.nextCursor).nextCursor)
        assertEquals("RepeatedCursor", events.last().result)
        assertEquals(2, calls)
    }

    @Test fun repeatedBooksStopEvenIfTheRemoteKeepsChangingItsCursor() = runBlocking {
        val events = mutableListOf<ContentTraceEvent>()
        val session = RuleListSession("ruleSearch", true, ContentTrace { events += it }) { page, _, _ ->
            RuleListResult(listOf(RuleBook("same")), "https://example.invalid/$page", "https://example.invalid/${page + 1}")
        }
        assertNotNull(session.page(1).nextPage)
        assertNull(session.page(2).nextPage)
        assertEquals("RepeatedBooks", events.last().result)
    }

    @Test fun explicitAndNumericSessionsKeepReturningUsableContinuationsPastPageSixtyFour() = runBlocking {
        for (explicit in listOf(false, true)) {
            val session = RuleListSession("ruleExplore", explicit, ContentTrace.None) { page, _, _ ->
                RuleListResult(listOf(RuleBook("$page")), "https://example.invalid/$page", "https://example.invalid/${page + 1}")
            }
            var cursor: String? = null
            repeat(70) { index ->
                val page = session.page(cursor)
                assertEquals("${index + 1}", page.books.single().id)
                assertEquals(index + 2, page.nextPage)
                cursor = page.nextCursor
            }
        }
    }

    @Test fun failuresKeepTheCursorAndRollbackMemoryBeforeRetry() = runBlocking {
        var failNext = true
        val session = RuleListSession("ruleSearch", true, ContentTrace.None) { page, url, memory ->
            if (page == 1) memory.put("seen", "first") else {
                assertEquals("https://example.invalid/2", url)
                assertEquals("first", memory.get("seen"))
                memory.put("seen", "failed")
                if (failNext) { failNext = false; throw SourceContentException(ContentError.LoginRequired, "ruleSearch.bookList") }
            }
            RuleListResult(listOf(RuleBook("$page")), "https://example.invalid/$page",
                if (page == 1) "https://example.invalid/2" else null)
        }
        val next = session.page().nextCursor
        try { session.page(next); fail("Authentication must not turn into an empty page") }
        catch (failure: SourceContentException) { assertEquals(ContentError.LoginRequired, failure.code) }
        assertEquals(listOf("2"), session.page(next).books.map { it.id })
    }

    @Test fun concurrentRetriesShareOneSuccessfulPageButNewSessionsHaveTheirOwnMemory() = runBlocking {
        var calls = 0
        fun session() = RuleListSession("ruleSearch", false, ContentTrace.None) { page, _, memory ->
            calls++
            delay(10)
            assertNull(memory.get("visited"))
            memory.put("visited", "yes")
            RuleListResult(listOf(RuleBook("$page")), "https://example.invalid/$page", null)
        }
        val first = session()
        val results = listOf(async { first.page() }, async { first.page() }).awaitAll()
        assertEquals(results.first(), results.last())
        assertEquals(1, calls)
        session().page()
        assertEquals(2, calls)
    }

    @Test fun legacyPagesRemainNumericAndNeverUseAnUnrelatedRequestUrlAsACursor() = runBlocking {
        val requested = mutableListOf<Int>()
        fun session() = RuleListSession("ruleExplore", false, ContentTrace.None) { page, url, _ ->
            assertNull(url)
            requested += page
            RuleListResult(listOf(RuleBook("$page")), "https://example.invalid/post", null)
        }
        val session = session()
        assertEquals("2", session.page().nextCursor)
        assertEquals("3", session.page("2").nextCursor)
        try { session.page("1"); fail("Old cursors cannot restart a live session") }
        catch (failure: SourceContentException) { assertEquals(ContentError.InvalidRule, failure.code) }
        val restored = session()
        assertEquals("65", restored.page("64").nextCursor)
        assertEquals("66", restored.page("65").nextCursor)
        assertEquals(listOf(1, 2, 64, 65), requested)
    }
}
