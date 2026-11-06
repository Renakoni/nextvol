package indi.renakoni.nextvol.tts

import indi.renakoni.nextvol.data.userdata.UserDataRepository
import indi.renakoni.nextvol.tts.matcha.OfflineVoiceRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
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
    val offlineVoice: OfflineVoiceRef? = null,
    val httpRevision: String = "",
    val replacements: List<SpeechReplacementRule> = emptyList(),
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault
    val text: SpeechTextSettings = SpeechTextSettings(),
    val httpVolume: Float = 1f,
    val narrationVoices: List<SpeechVoiceRef> = emptyList(),
    val dialogueVoices: List<SpeechVoiceRef> = emptyList(),
    val speakerVoices: Map<String, SpeechVoiceRef> = emptyMap(),
    val speakerBookId: String = "",
    val voiceRevision: String = "",
    val voiceRoutingConfigured: Boolean = false,
    val allowMultipleVoices: Boolean = false,
    val roleRules: List<SpeechRoleRule> = emptyList(),
    val roleContextPattern: String? = null,
    val engineCacheCapacity: Int = 10,
    val audio: SpeechAudioSettings = SpeechAudioSettings(),
    val voiceItems: List<SpeechVoiceItem> = emptyList(),
    val autoAssignRole: Boolean = false,
    val maxCacheVoiceNum: Int = 50,
    val diagnosticLogging: Boolean = false,
    val backgroundMusic: SpeechSchemeMusic = SpeechSchemeMusic(),
    val schemeId: String = DEFAULT_SPEECH_SCHEME,
    @Transient val schemeName: String = "",
    @Transient val bookId: String? = null,
) {
    fun validated() = copy(
        rate = rate?.takeIf { it.isFinite() }?.coerceIn(0.5f, 2f),
        pitch = pitch?.takeIf { it.isFinite() }?.coerceIn(0.5f, 2f),
        playbackSpeed = playbackSpeed.takeIf { it.isFinite() }?.coerceIn(0.5f, 2f) ?: 1f,
        httpVolume = httpVolume.takeIf { it.isFinite() }?.coerceIn(0f, 2f) ?: 1f,
        httpSource = httpSource.takeIf { offlineVoice == null },
        httpRevision = httpRevision.takeIf { offlineVoice == null && httpSource != null }.orEmpty(),
        text = text.validated(),
        engineCacheCapacity = engineCacheCapacity.coerceIn(3, 32),
        maxCacheVoiceNum = maxCacheVoiceNum.coerceAtLeast(0),
    )
}

internal val SpeechSettings.hasVoiceRouting get() =
    voiceRoutingConfigured || narrationVoices.isNotEmpty() || dialogueVoices.isNotEmpty() || speakerVoices.isNotEmpty() || roleRules.any { it.activate } || autoAssignRole

@Singleton
class SpeechSettingsRepository @Inject constructor(userData: UserDataRepository) {
    private val stored = userData.stringUserData("tts.schemes")
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    val schemes = stored.getFlow().distinctUntilChanged().map(::decode)
    val changes = changesForBook(null)

    fun changesForBook(bookId: String?) = schemes.map { it.forBook(bookId) }.distinctUntilChanged()

    suspend fun snapshot(): SpeechSchemes = withContext(Dispatchers.IO) { decode(stored.get()) }
    suspend fun get(): SpeechSettings = snapshot().forBook(null)
    suspend fun get(schemeId: String): SpeechSettings = snapshot().settings(schemeId)
    suspend fun forBook(bookId: String?): SpeechSettings = snapshot().forBook(bookId)

    suspend fun update(change: (SpeechSettings) -> SpeechSettings) = edit { current ->
        current.update(current.defaultId, change)
    }

    suspend fun update(schemeId: String, change: (SpeechSettings) -> SpeechSettings) = edit { it.update(schemeId, change) }

    /** Shared voice edits must repair references in every scheme, without selecting an editor target. */
    suspend fun updateAll(change: (SpeechSettings) -> SpeechSettings) = edit { current ->
        current.copy(items = current.items.map { it.copy(settings = change(it.settings).schemeContent()) })
    }

    suspend fun create(name: String, copyFrom: String? = null, bookId: String? = null): String {
        val id = java.util.UUID.randomUUID().toString()
        edit { current ->
            val content = copyFrom?.let { current.settings(it).schemeContent() } ?: SpeechSettings()
            current.copy(items = current.items + SpeechScheme(id, name.trim(), content),
                bindings = if (bookId == null) current.bindings else current.bindings + (bookId to id))
        }
        return id
    }

    suspend fun rename(id: String, name: String) = edit { current ->
        current.copy(items = current.items.map { if (it.id == id) it.copy(name = name.trim()) else it })
    }

    suspend fun setDefault(id: String) = edit { it.copy(defaultId = id) }
    suspend fun bind(bookId: String, id: String?) = edit {
        it.copy(bindings = if (id == null) it.bindings - bookId else it.bindings + (bookId to id))
    }

    suspend fun delete(id: String, replacement: String) = edit { it.delete(id, replacement) }

    private suspend fun edit(change: (SpeechSchemes) -> SpeechSchemes) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = stored.get()
            val updated = json.encodeToString(change(decode(current)))
            if (updated != current) stored.set(updated)
        }
    }

    private fun decode(value: String?): SpeechSchemes = try {
        value?.let { json.decodeFromString<SpeechSchemes>(it) } ?: SpeechSchemes()
    } catch (_: SerializationException) {
        SpeechSchemes()
    } catch (_: IllegalArgumentException) {
        SpeechSchemes()
    }
}
