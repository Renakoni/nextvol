package indi.renakoni.nextvol.data.explore

import indi.renakoni.nextvol.data.text.SimplifiedTraditionalProcessor
import io.nightfish.lightnovelreader.api.book.BookInformation
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/** Evidence supplied by a host adapter, independent of its private recall scores. */
enum class SearchEvidence { ExplicitId, VerifiedAlias, Related, Tag, Loose }

internal data class SearchRank(val tier: Int, val field: Int = 0, val distance: Int = 0) : Comparable<SearchRank> {
    override fun compareTo(other: SearchRank): Int = when {
        tier != other.tier -> tier.compareTo(other.tier)
        field != other.field -> field.compareTo(other.field)
        else -> distance.compareTo(other.distance)
    }
}

/** One query, bounded Unicode edit distance, and no network or filtering of source results. */
internal class SearchRanking(keyword: String) {
    private val query = normalize(keyword)
    private val points = query.codePoints().toArray()
    private val looseQuery = loose(query)

    fun rank(info: BookInformation?, evidence: SearchEvidence?): SearchRank {
        val title = info?.title?.let(::normalize).orEmpty()
        val author = info?.author?.let(::normalize).orEmpty()
        var best = SearchRank(if (title.isBlank() && author.isBlank()) 7 else 6)
        evidence?.let {
            best = when (it) {
                SearchEvidence.ExplicitId -> SearchRank(0)
                SearchEvidence.VerifiedAlias -> SearchRank(1)
                SearchEvidence.Related, SearchEvidence.Tag, SearchEvidence.Loose -> SearchRank(5)
            }
        }
        if (query.isEmpty()) return best
        for ((field, text) in listOf(title, author).withIndex()) {
            if (text.isEmpty()) continue
            val match = when {
                text == query -> SearchRank(0, field)
                text.startsWith(query) -> SearchRank(2, field, text.codePointCount(0, text.length) - points.size)
                query in text -> SearchRank(3, field, text.codePointCount(0, text.length) - points.size)
                else -> {
                    val looseText = loose(text)
                    // Punctuation-only differences are weak evidence, not spelling corrections.
                    if (looseQuery.isNotEmpty() && looseQuery == looseText) SearchRank(5, field)
                    else fuzzy(text)?.let { SearchRank(4, field, it) }
                        ?: SearchRank(if (looseQuery.isNotEmpty() && looseQuery in looseText) 5 else 6, field)
                }
            }
            if (match < best) best = match
        }
        return best
    }

    private fun fuzzy(text: String): Int? {
        if (points.size !in 4..128 || text.codePointCount(0, text.length) > 128) return null
        val other = text.codePoints().toArray()
        val limit = if (points.size < 8) 1 else 2
        if (abs(points.size - other.size) > limit) return null
        var previous = IntArray(other.size + 1) { it }
        var current = IntArray(other.size + 1)
        for (i in 1..points.size) {
            current.fill(limit + 1)
            current[0] = i
            var minimum = limit + 1
            for (j in maxOf(1, i - limit)..minOf(other.size, i + limit)) {
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1,
                    previous[j - 1] + if (points[i - 1] == other[j - 1]) 0 else 1)
                minimum = minOf(minimum, current[j])
            }
            if (minimum > limit) return null
            val swap = previous; previous = current; current = swap
        }
        val edits = previous[other.size]
        val length = maxOf(points.size, other.size)
        return if (edits <= limit && edits * 4 <= length) edits * 10_000 / length else null
    }

    companion object {
        fun normalize(text: String): String = buildString {
            val normalized = SimplifiedTraditionalProcessor.toSimplified(
                Normalizer.normalize(text, Normalizer.Form.NFKC)).lowercase(Locale.ROOT)
            var space = false
            for (char in normalized) {
                if (char.isWhitespace()) space = isNotEmpty()
                else {
                    if (space) append(' ')
                    append(char)
                    space = false
                }
            }
        }

        private fun loose(text: String): String = buildString {
            text.codePoints().forEach { if (Character.isLetterOrDigit(it)) appendCodePoint(it) }
        }
    }
}
