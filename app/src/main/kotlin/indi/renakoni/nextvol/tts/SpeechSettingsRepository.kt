package indi.renakoni.nextvol.tts

import indi.renakoni.nextvol.data.userdata.UserDataRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class SpeechSettings(
    val engine: String = "",
    val voice: String = "",
    val rate: Float? = null,
    val pitch: Float? = null,
    val httpSource: String? = null,
    val playbackSpeed: Float = 1f,
) {
    fun validated() = copy(
        rate = rate?.takeIf { it.isFinite() }?.coerceIn(0.5f, 2f),
        pitch = pitch?.takeIf { it.isFinite() }?.coerceIn(0.5f, 2f),
        playbackSpeed = playbackSpeed.takeIf { it.isFinite() }?.coerceIn(0.5f, 2f) ?: 1f,
    )
}

@Singleton
class SpeechSettingsRepository @Inject constructor(userData: UserDataRepository) {
    private val stored = userData.stringUserData("tts.settings")
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    val changes = stored.getFlow().map(::decode).distinctUntilChanged()

    suspend fun get(): SpeechSettings = withContext(Dispatchers.IO) { decode(stored.get()) }

    suspend fun update(change: (SpeechSettings) -> SpeechSettings) = withContext(Dispatchers.IO) {
        mutex.withLock { stored.set(json.encodeToString(change(decode(stored.get())).validated())) }
    }

    private fun decode(value: String?): SpeechSettings = try {
        value?.let { json.decodeFromString<SpeechSettings>(it).validated() } ?: SpeechSettings()
    } catch (_: SerializationException) {
        SpeechSettings()
    } catch (_: IllegalArgumentException) {
        SpeechSettings()
    }
}
