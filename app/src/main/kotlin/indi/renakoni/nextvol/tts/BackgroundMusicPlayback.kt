package indi.renakoni.nextvol.tts

import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import java.io.File
import kotlin.random.Random

/** One accompaniment channel for the listening session, independent of speech clips and bookmarks. */
internal class BackgroundMusicPlayback(private val player: Player,
    private val file: (BackgroundMusicTrack) -> File,
    private val onUnavailable: (String) -> Unit) : AutoCloseable {
    private var tracks = emptyList<BackgroundMusicTrack>()
    private val remaining = mutableListOf<BackgroundMusicTrack>()
    private val failed = mutableSetOf<String>()
    private var current: BackgroundMusicTrack? = null
    private var bookId: String? = null
    private var session: Triple<String, Boolean, SpeechPhase>? = null
    private var active = false
    private var suppressed = false

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) next()
            }

            override fun onPlayerError(error: PlaybackException) {
                current?.let { failed += it.id; onUnavailable(it.id) }
                next()
            }
        })
    }

    fun updateSettings(settings: BackgroundMusicSettings) {
        player.volume = settings.volume
        val selected = if (settings.enabled) settings.tracks.filter { it.selected } else emptyList()
        if (tracks != selected) {
            tracks = selected
            remaining.clear()
            val ids = tracks.map { it.id }.toSet()
            failed.retainAll(ids)
            if (current?.id !in ids) clearTrack()
            // The current track has already been drawn in this round.
            remaining += tracks.filter { it.id != current?.id && it.id !in failed }
        }
        sync()
    }

    fun updateSession(request: SpeechRequest, phase: SpeechPhase) {
        val nextSession = Triple(request.bookId, request.isPreview, phase)
        if (session == nextSession) return
        session = nextSession
        if (!request.isPreview && bookId != request.bookId) {
            reset()
            bookId = request.bookId
        }
        when (phase) {
            SpeechPhase.Playing -> active = true
            // Keep music across speech clips, but acquire speech audio focus before starting it.
            SpeechPhase.Preparing, SpeechPhase.Buffering -> Unit
            SpeechPhase.Paused -> active = false
            SpeechPhase.Stopped, SpeechPhase.Completed, SpeechPhase.Failed -> reset()
        }
        sync()
    }

    fun setSuppressed(value: Boolean) { suppressed = value; sync() }

    private fun sync() {
        if (active && !suppressed && current == null) next()
        player.playWhenReady = active && !suppressed && current != null
    }

    private fun next() {
        if (remaining.isEmpty()) remaining += tracks.filter { it.id !in failed }
        if (remaining.isEmpty()) {
            clearTrack()
            return
        }
        val track = remaining.removeAt(Random.nextInt(remaining.size))
        current = track
        player.setMediaItem(MediaItem.Builder().setMediaId(track.id).setUri(file(track).toUri()).build())
        player.prepare()
        player.playWhenReady = active && !suppressed
    }

    private fun clearTrack() {
        current = null
        player.stop()
        player.clearMediaItems()
    }

    private fun reset() {
        active = false
        bookId = null
        remaining.clear()
        failed.clear()
        clearTrack()
    }

    override fun close() = player.release()
}
