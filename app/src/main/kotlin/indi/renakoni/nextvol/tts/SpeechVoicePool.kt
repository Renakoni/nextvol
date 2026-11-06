package indi.renakoni.nextvol.tts

import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.random.Random

// Original O6.f.d speaker markers; unlike localized group labels, these survive a UI language change.
const val SPEECH_POOL_MALE = "@mismatchMale"
const val SPEECH_POOL_FEMALE = "@mismatchFemale"

@Serializable
data class SpeechVoiceItem(
    val group: String,
    val value: SpeechVoiceRef,
    val name: String = value.label,
    val activate: Boolean = true,
    val id: String = UUID.randomUUID().toString(),
)

@Serializable
data class SpeechVoicePair(
    val bookId: String,
    val person: String,
    val group: String,
    val value: SpeechVoiceRef,
    val manual: Boolean = false,
    val bookTitle: String = "",
    val id: String = UUID.randomUUID().toString(),
    val schemeId: String = DEFAULT_SPEECH_SCHEME,
)

/** O6.j.i's random draw without replacement. Persisted pairs are independent of the bounded cache. */
internal class SpeechVoicePool(private val random: Random = Random.Default) {
    private data class Lookup(val bookId: String, val person: String, val groups: List<String>)
    private data class Pool(val bookId: String, val group: String)
    private data class Bag(var members: Set<String>, val remaining: MutableList<String>)
    private val cache = LinkedHashMap<Lookup, SpeechVoicePair>(16, 0.75f, true)
    private val bags = mutableMapOf<Pool, Bag>()
    internal val cacheSize get() = cache.size

    fun existing(bookId: String, person: String, groups: List<String>, pairs: List<SpeechVoicePair>,
        catalog: Map<String, SpeechResolvedVoice>, maxCache: Int): SpeechVoicePair? {
        val lookup = Lookup(bookId, person, groups.toList())
        val manual = pairs.firstOrNull { it.bookId == bookId && it.person == person && it.manual &&
            (person != SPEECH_LANGUAGE_PERSON || it.group in groups) }
        val cached = cache[lookup]
        val pair = manual ?: cached?.takeIf { available(it, catalog) }
            ?: groups.firstNotNullOfOrNull { group -> pairs.firstOrNull {
                it.bookId == bookId && it.person == person && it.group == group && available(it, catalog)
            } }
        if (pair == null) cache.remove(lookup)
        else cache[lookup] = pair
        trim(maxCache)
        // Cache identities, never resolved model/settings snapshots: every plan sees the current catalog revision.
        return pair?.let { it.copy(value = catalog[it.value.key]?.reference ?: it.value) }
    }

    fun assign(bookId: String, person: String, group: String, pairs: List<SpeechVoicePair>,
        items: List<SpeechVoiceItem>, catalog: Map<String, SpeechResolvedVoice>, maxCache: Int,
        bookTitle: String = ""): SpeechVoicePair? {
        existing(bookId, person, listOf(group), pairs, catalog, maxCache)?.let { return it }
        val reference = draw(bookId, group, items, catalog) ?: return null
        val old = pairs.firstOrNull { it.bookId == bookId && it.person == person && it.group == group }
        return SpeechVoicePair(bookId, person, group, reference, bookTitle = bookTitle,
            id = old?.id ?: UUID.randomUUID().toString())
    }

    fun draw(bookId: String, group: String, items: List<SpeechVoiceItem>,
        catalog: Map<String, SpeechResolvedVoice>): SpeechVoiceRef? {
        val candidates = items.asSequence().filter { it.group == group && it.activate }
            .mapNotNull { catalog[it.value.key]?.takeIf { voice -> voice.error == null }?.reference }
            .associateBy { it.canonicalKey }
        val key = Pool(bookId, group)
        val bag = bags.getOrPut(key) { Bag(candidates.keys, candidates.keys.toMutableList()) }
        // Adding a candidate must not reshuffle previous draws or existing people.
        bag.remaining.retainAll(candidates.keys)
        bag.remaining.addAll(candidates.keys - bag.members)
        bag.members = candidates.keys
        if (bag.remaining.isEmpty()) bag.remaining.addAll(candidates.keys)
        if (bag.remaining.isEmpty()) return null
        return candidates.getValue(bag.remaining.removeAt(random.nextInt(bag.remaining.size)))
    }

    fun clearCache() = cache.clear()

    fun setCacheLimit(limit: Int) = trim(limit)

    fun clear(bookId: String? = null) {
        cache.keys.removeAll { bookId == null || it.bookId == bookId }
        bags.keys.removeAll { bookId == null || it.bookId == bookId }
    }

    private fun available(pair: SpeechVoicePair, catalog: Map<String, SpeechResolvedVoice>) =
        pair.manual || catalog[pair.value.key]?.let { it.error == null } == true

    private fun trim(limit: Int) {
        while (cache.size > limit.coerceAtLeast(0)) cache.remove(cache.keys.first())
    }
}
