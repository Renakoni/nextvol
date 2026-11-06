package indi.renakoni.nextvol.tts

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class BackgroundMusicTrack(val id: String, val name: String, val selected: Boolean = false)

@Serializable
data class BackgroundMusicSettings(val tracks: List<BackgroundMusicTrack> = emptyList(), val volume: Float = 0.5f,
    val enabled: Boolean = false)

@Singleton
class BackgroundMusicRepository @Inject constructor(@ApplicationContext private val context: Context,
    userData: UserDataRepository, private val schemes: SpeechSettingsRepository) {
    private val stored = userData.stringUserData("tts.backgroundMusic")
    private val directory = File(context.filesDir, "background-music")
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val mutableUnavailable = MutableStateFlow<Set<String>>(emptySet())
    val unavailable = mutableUnavailable.asStateFlow()
    val changes = stored.getFlow().distinctUntilChanged().map(::decode)

    suspend fun importTracks(uris: List<Uri>, replacingId: String? = null) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = decode(stored.get())
            val previous = current.tracks.find { it.id == replacingId }
            val copied = mutableListOf<File>()
            var published = false
            try {
                directory.mkdirs()
                val tracks = uris.mapIndexed { index, uri ->
                    val id = UUID.randomUUID().toString()
                    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    } ?: uri.lastPathSegment ?: id
                    val destination = File(directory, id).also(copied::add)
                    (context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open background music")).use { input ->
                        destination.outputStream().use { input.copyTo(it) }
                    }
                    BackgroundMusicTrack(id, name, index == 0 && previous?.selected == true)
                }
                val updated = if (previous == null) current.tracks + tracks
                    else current.tracks.map { if (it.id == previous.id) tracks.first() else it } + tracks.drop(1)
                stored.set(json.encodeToString(current.copy(tracks = updated)))
                published = true
                if (previous != null) schemes.updateAll { settings ->
                    val music = settings.backgroundMusic
                    settings.copy(backgroundMusic = music.copy(selected = if (previous.id in music.selected)
                        music.selected - previous.id + tracks.first().id else music.selected))
                }
            } catch (failure: Exception) {
                if (!published) copied.forEach(File::delete)
                throw failure
            }
            if (previous != null) {
                file(previous).delete()
                mutableUnavailable.update { it - previous.id }
            }
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = decode(stored.get())
            stored.set(json.encodeToString(current.copy(tracks = current.tracks.filterNot { it.id == id })))
            schemes.updateAll { it.copy(backgroundMusic = it.backgroundMusic.copy(selected = it.backgroundMusic.selected - id)) }
            File(directory, id).delete()
            mutableUnavailable.update { it - id }
        }
    }

    fun file(track: BackgroundMusicTrack): File = File(directory, track.id)

    fun markUnavailable(id: String) { mutableUnavailable.update { it + id } }

    suspend fun refreshAvailability() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val tracks = decode(stored.get()).tracks
            val ids = tracks.map(BackgroundMusicTrack::id).toSet()
            val missing = tracks.filterNot { file(it).isFile }.map(BackgroundMusicTrack::id)
            mutableUnavailable.update { it.intersect(ids) + missing }
        }
    }

    private fun decode(value: String?): BackgroundMusicSettings = try {
        value?.let { json.decodeFromString<BackgroundMusicSettings>(it) } ?: BackgroundMusicSettings()
    } catch (_: IllegalArgumentException) {
        BackgroundMusicSettings()
    }
}

internal fun BackgroundMusicSettings.forScheme(selection: SpeechSchemeMusic) = copy(
    enabled = selection.enabled, volume = selection.volume,
    tracks = tracks.map { it.copy(selected = it.id in selection.selected) })
