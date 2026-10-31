package indi.renakoni.nextvol.tts

import indi.renakoni.nextvol.data.book.BookIdentity
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Only a completed/current playback boundary is durable; regenerated audio has no stable clock. */
@Serializable
data class SpeechBookmark(
    val bookId: String,
    val chapterId: String,
    val fingerprint: String,
    val offset: Int,
    val bookTitle: String = "",
    val chapterTitle: String = "",
    val completed: Boolean = false,
    val replacementKey: String? = null,
    val segmentIndex: Int? = null,
)

interface SpeechProgressStore {
    suspend fun load(bookId: String): SpeechBookmark?
    suspend fun save(bookmark: SpeechBookmark)
}

@Singleton
class RepositorySpeechProgressStore @Inject constructor(private val userData: UserDataRepository) : SpeechProgressStore {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun load(bookId: String): SpeechBookmark? = withContext(Dispatchers.IO) {
        decode(userData.stringUserData(path(bookId)).get())?.takeIf { it.bookId == bookId }
    }

    override suspend fun save(bookmark: SpeechBookmark) = withContext(Dispatchers.IO) {
        userData.stringUserData(path(bookmark.bookId)).set(json.encodeToString(bookmark))
    }

    private fun path(bookId: String) = "tts.progress.${BookIdentity.book(bookId).fileKey}"
    private fun decode(value: String?): SpeechBookmark? = try {
        value?.let { json.decodeFromString<SpeechBookmark>(it) }?.takeIf { it.offset >= 0 }
    } catch (_: SerializationException) { null }
    catch (_: IllegalArgumentException) { null }
}
