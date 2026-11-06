package indi.renakoni.nextvol.tts

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class SpeechVoicePoolTest {
    private val first = SpeechVoiceRef(httpSource = "first", label = "First")
    private val second = SpeechVoiceRef(httpSource = "second", label = "Second")
    private val third = SpeechVoiceRef(httpSource = "third", label = "Third")
    private val group = "青年"
    private val items = listOf(first, second).map { SpeechVoiceItem(group, it) }
    private val catalog = listOf(first, second, third).associate { it.key to
        SpeechResolvedVoice(it, SpeechSettings(httpSource = it.httpSource), 100) }

    @Test fun randomRoundUsesEachEnabledCandidateOnceAndRefillsOnlyAfterExhaustion() {
        val pool = SpeechVoicePool(Random(19))
        val imported = first.copy(externalId = "http_test_first")
        val enabled = items + SpeechVoiceItem(group, imported) + SpeechVoiceItem(group, third, activate = false)
        val resolved = catalog + (imported.key to catalog.getValue(first.key).copy(reference = imported))
        repeat(2) {
            val round = List(2) { pool.draw("book", group, enabled, resolved)!! }
            assertEquals(setOf(first.key, second.key), round.map { it.canonicalKey }.toSet())
        }
        assertNull(pool.draw("book", "空池", enabled, catalog))
    }

    @Test fun cacheEvictionAndRestartKeepDurablePairsWhileBooksRemainIndependent() {
        val pool = SpeechVoicePool(Random(9))
        var saved = emptyList<SpeechVoicePair>()
        repeat(3) { index -> saved += pool.assign("book", "人物$index", group, saved, items, catalog, 1)!! }
        saved.forEach { assertEquals(it.value, pool.existing("book", it.person, listOf(group), saved, catalog, 1)!!.value) }
        val other = pool.assign("book", "人物0", group, saved, listOf(SpeechVoiceItem(group, third)), catalog, 1, schemeId = "other")!!
        saved += other
        assertEquals(third, pool.existing("book", "人物0", listOf(group), saved, catalog, 1, "other")!!.value)
        assertEquals(saved.first().value, pool.existing("book", "人物0", listOf(group), saved, catalog, 1)!!.value)
        assertEquals(1, pool.cacheSize)
        pool.setCacheLimit(0)
        assertEquals(0, pool.cacheSize)
        val restarted = SpeechVoicePool(Random(36))
        assertEquals(saved.first().value,
            restarted.assign("book", "人物0", group, saved, items, catalog, 1)!!.value)
        assertNull(restarted.existing("another-book", "人物0", listOf(group), saved, catalog, 1))
    }

    @Test fun membershipChangesOnlyAffectFutureDrawsAndCatalogChangesRefreshOrReplaceAutomaticPairs() {
        val pool = SpeechVoicePool(Random(3))
        val assigned = pool.assign("book", "林飞", group, emptyList(), items, catalog, 5)!!
        val disabled = items.map { it.copy(activate = false) } + SpeechVoiceItem(group, third)
        assertEquals(assigned.value, pool.assign("book", "林飞", group, listOf(assigned), disabled, catalog, 5)!!.value)
        assertEquals(third, pool.assign("book", "陈雨", group, listOf(assigned), disabled, catalog, 5)!!.value)
        val revised = catalog + (assigned.value.key to catalog.getValue(assigned.value.key).copy(
            reference = assigned.value.copy(label = "Renamed"), revision = "2"))
        assertEquals("Renamed", pool.existing("book", "林飞", listOf(group), listOf(assigned), revised, 5)!!.value.label)
        val removed = catalog - assigned.value.key
        assertEquals(third, pool.assign("book", "林飞", group, listOf(assigned), disabled, removed, 5)!!.value)
    }
}
