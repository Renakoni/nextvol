package indi.renakoni.nextvol.tts

import indi.renakoni.nextvol.data.userdata.UserDataRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Automatic allocation updates the table without restarting the playback that requested it. */
@Singleton
class SpeechVoicePoolRepository @Inject constructor(userData: UserDataRepository) {
    private val stored = userData.stringUserData("tts.voicePairs")
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val pool = SpeechVoicePool()
    private val mutableRevisions = MutableStateFlow(0L)
    val revisions = mutableRevisions.asStateFlow()
    val changes = stored.getFlow().map(::decode).distinctUntilChanged()

    suspend fun pairs(bookId: String? = null): List<SpeechVoicePair> = withContext(Dispatchers.IO) {
        decode(stored.get()).filter { bookId == null || it.bookId == bookId }
    }

    suspend fun existing(bookId: String, person: String, groups: List<String>, pairs: List<SpeechVoicePair>,
        revision: Long, catalog: Map<String, SpeechResolvedVoice>, maxCache: Int): SpeechVoicePair? =
        mutex.withLock {
            // O6.j.l changes the draw bag, not existing pairs. Membership does not revoke a stable assignment.
            // An older plan must not repopulate the cache after a manual edit/removal cleared it.
            val current = if (revision == revisions.value) pairs else withContext(Dispatchers.IO) { decode(stored.get()) }
            pool.existing(bookId, person, groups, current, catalog, maxCache)
        }

    suspend fun assign(bookId: String, person: String, group: String, items: List<SpeechVoiceItem>,
        catalog: Map<String, SpeechResolvedVoice>, maxCache: Int, bookTitle: String = ""): SpeechVoicePair? =
        withContext(Dispatchers.IO) { mutex.withLock {
            val pairs = decode(stored.get())
            val pair = pool.assign(bookId, person, group, pairs, items, catalog, maxCache, bookTitle)
                ?: return@withLock null
            val next = replace(pairs, pair)
            if (next != pairs) stored.set(json.encodeToString(next))
            pair
        } }

    suspend fun setManual(pair: SpeechVoicePair) = change { pairs ->
        replace(pairs, pair.copy(manual = true))
    }

    suspend fun remove(ids: Set<String>) = change { pairs -> pairs.filterNot { it.id in ids } }

    suspend fun clear(bookId: String? = null) = withContext(Dispatchers.IO) { mutex.withLock {
        val pairs = decode(stored.get())
        val next = pairs.filterNot { bookId == null || it.bookId == bookId }
        if (next != pairs) {
            stored.set(json.encodeToString(next))
            mutableRevisions.value++
        }
        pool.clear(bookId)
    } }

    /** Returns the unchanged rows whose group has no available enabled candidate. */
    suspend fun reassign(ids: Set<String>, items: List<SpeechVoiceItem>,
        catalog: Map<String, SpeechResolvedVoice>): Set<String> = withContext(Dispatchers.IO) { mutex.withLock {
        val failed = mutableSetOf<String>()
        val pairs = decode(stored.get())
        val next = pairs.map { pair ->
            if (pair.id !in ids) pair else {
                val voice = pool.draw(pair.bookId, pair.group, items, catalog)
                if (voice == null) { failed += pair.id; pair } else pair.copy(value = voice, manual = false)
            }
        }
        pool.clearCache()
        if (next != pairs) {
            stored.set(json.encodeToString(next))
            mutableRevisions.value++
        }
        failed
    } }

    suspend fun setCacheLimit(limit: Int) = withContext(Dispatchers.IO) { mutex.withLock { pool.setCacheLimit(limit) } }

    private suspend fun change(update: (List<SpeechVoicePair>) -> List<SpeechVoicePair>) =
        withContext(Dispatchers.IO) { mutex.withLock {
            val pairs = decode(stored.get())
            val next = update(pairs)
            if (next != pairs) {
                stored.set(json.encodeToString(next))
                pool.clearCache()
                mutableRevisions.value++
            }
        } }

    private fun replace(pairs: List<SpeechVoicePair>, pair: SpeechVoicePair): List<SpeechVoicePair> {
        var inserted = false
        return buildList {
            for (old in pairs) {
                val samePerson = old.bookId == pair.bookId && old.person == pair.person
                if (old.id == pair.id || samePerson && (old.group == pair.group ||
                    pair.person != SPEECH_LANGUAGE_PERSON && pair.manual && old.manual)) {
                    if (!inserted) { add(pair); inserted = true }
                } else add(old)
            }
            if (!inserted) add(pair)
        }
    }

    private fun decode(value: String?): List<SpeechVoicePair> = try {
        value?.let { json.decodeFromString<List<SpeechVoicePair>>(it) } ?: emptyList()
    } catch (_: SerializationException) { emptyList() }
    catch (_: IllegalArgumentException) { emptyList() }
}
