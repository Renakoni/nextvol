package indi.renakoni.nextvol.ui.home.explore

import android.app.Application
import indi.renakoni.nextvol.data.explore.*
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.WordCount
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class SearchRankingTest {
    private fun info(title: String, author: String = "") = BookInformation("id", title, author = author,
        description = "", publishingHouse = "", wordCount = WordCount(0), lastUpdated = LocalDateTime.MIN, isComplete = false)

    @Test fun commonTiersOrderTextEvidenceAndMissingMetadataWithoutFiltering() {
        val ranking = SearchRanking("义妹生活")
        val ranks = listOf(
            ranking.rank(info("義妹生活"), null),
            ranking.rank(info("別名"), SearchEvidence.VerifiedAlias),
            ranking.rank(info("义妹生活 后日谈"), null),
            ranking.rank(info("我的义妹生活"), null),
            ranking.rank(info("义妺生活"), null),
            ranking.rank(info("系列续作"), SearchEvidence.Related),
            ranking.rank(info("其他作品"), null), ranking.rank(null, null))
        assertEquals((0..7).toList(), ranks.map { it.tier })
        assertEquals(ranks, ranks.sorted())
        assertEquals(0, ranking.rank(null, SearchEvidence.ExplicitId).tier)
        assertTrue(ranking.rank(info("义妹生活"), null) < ranking.rank(info("其他", "义妹生活"), null))
    }

    @Test fun normalizationPreservesKanaNonBmpPunctuationAndNumbers() {
        for ((query, title) in listOf("Ｒｅ：從零開始" to "Re:从零开始", " Title\t  Here " to "title here",
            "とある" to "とある", "𠮷野家" to "𠮷野家", "86" to "86"))
            assertEquals(query, 0, SearchRanking(query).rank(info(title), null).tier)
        assertEquals(6, SearchRanking("とある").rank(info("さくら"), null).tier)
        assertEquals(5, SearchRanking("Re:Zero").rank(info("Re Zero"), null).tier)
        assertEquals(6, SearchRanking("!!!").rank(info("???"), null).tier)
        assertEquals(6, SearchRanking("86").rank(info("87"), null).tier)
        assertEquals(2, SearchRanking("凡人").rank(info("凡人修仙传之仙界篇"), null).tier)
    }

    @Test fun fuzzyDistanceUsesCodePointsAndHasLengthAndSimilarityBounds() {
        for ((query, title, tier) in listOf(Triple("abcd", "abxd", 4), Triple("abcdefgh", "axcdefxh", 4),
            Triple("abcdefg", "axcdexg", 6), Triple("𠮷𠮷𠮷𠮷", "𠮷𠮷𠮷野", 4), Triple("abc", "axc", 6),
            Triple("ab", "ba", 6), Triple("a".repeat(129), "a".repeat(128) + "b", 6)))
            assertEquals(query, tier, SearchRanking(query).rank(info(title), null).tier)
        assertEquals(2, SearchRanking("long").rank(info("long" + "x".repeat(1000)), null).tier)
    }
}
