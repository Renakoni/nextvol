package hnovel.content

import hnovel.network.BrokerRequest
import hnovel.network.SourceNetworkRoute
import org.junit.Assert.*
import org.junit.Test

class DiscoveryPreviewBudgetTest {
    private val route = SourceNetworkRoute.systemDefault()
    private fun key(index: Int = 0) = DiscoveryPreviewDocuments.Key(
        BrokerRequest("content", "https://fixture.test/$index"), route.reference, emptyList(), emptyMap(), null)
    private fun document(body: String) = PageDocument(body, "https://fixture.test/list", successfulResponse = true)

    @Test fun aThousandSourcesShareBothTheDocumentAndBodyBudget() {
        for (length in listOf(1, DiscoveryPreviewDocuments.MAX_DOCUMENT_CHARS)) {
            val pool = DiscoveryPreviewDocuments.Pool()
            val sources = List(1000) { DiscoveryPreviewDocuments(pool) }
            sources.forEachIndexed { index, cache ->
                cache.put(key(), document(index.toString().padEnd(length, 'x')), null, cache.generation(), now = 0)
            }
            val retained = sources.mapNotNull { it.get(key(), now = 1) }
            val expected = minOf(DiscoveryPreviewDocuments.MAX_DOCUMENTS, DiscoveryPreviewDocuments.MAX_TOTAL_CHARS / length)
            assertEquals(expected, retained.size)
            assertTrue(retained.sumOf { it.document.body.length } <= DiscoveryPreviewDocuments.MAX_TOTAL_CHARS)
            assertNull(sources.first().get(key(), now = 1))
            assertTrue(sources.last().get(key(), now = 1)!!.document.body.startsWith("999"))
        }
    }

    @Test fun recentHitsOutliveOlderEntriesAndOneSourceCannotReplaceEveryOtherSource() {
        val pool = DiscoveryPreviewDocuments.Pool()
        val sources = List(DiscoveryPreviewDocuments.MAX_DOCUMENTS) { DiscoveryPreviewDocuments(pool) }
        sources.forEach { it.put(key(), document("body"), null, it.generation(), now = 0) }
        assertNotNull(sources.first().get(key(), now = 1))
        val newcomer = DiscoveryPreviewDocuments(pool)
        repeat(10) { newcomer.put(key(it), document("new"), null, newcomer.generation(), now = 2) }
        assertNotNull(sources.first().get(key(), now = 3))
        assertNull(sources[1].get(key(), now = 3))
        assertEquals(4, (0 until 10).count { newcomer.get(key(it), now = 3) != null })
        assertEquals(12, sources.count { it.get(key(), now = 3) != null })
    }

    @Test fun identicalRequestsStayOwnerIsolatedAndClearRejectsLateWrites() {
        val pool = DiscoveryPreviewDocuments.Pool()
        val first = DiscoveryPreviewDocuments(pool)
        val second = DiscoveryPreviewDocuments(pool)
        val generation = first.generation()
        first.put(key(), document("first account"), null, generation, now = 0)
        assertNull(second.get(key(), now = 1))
        second.put(key(), document("second account"), null, second.generation(), now = 0)
        first.clear()
        first.put(key(), document("late result"), null, generation, now = 1)
        assertNull(first.get(key(), now = 2))
        assertEquals("second account", second.get(key(), now = 2)!!.document.body)
    }

    @Test fun anotherSourcesAccessPrunesExpiredAndRetiredDocumentsWithoutRevisitingTheirOwners() {
        val pool = DiscoveryPreviewDocuments.Pool()
        val old = DiscoveryPreviewDocuments(pool)
        old.put(key(), document("old"), null, old.generation(), now = 0)
        val current = DiscoveryPreviewDocuments(pool)
        current.put(key(), document("current"), null, current.generation(), now = 60_000_000_000L)
        assertEquals(1, pool.entries.size)
        assertEquals("current", current.get(key(), now = 60_000_000_001L)!!.document.body)
        route.invalidate()
        assertNull(current.get(key(), now = 60_000_000_002L))
        assertTrue(pool.entries.isEmpty())
    }
}
