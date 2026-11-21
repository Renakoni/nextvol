package indi.renakoni.nextvol.tts

import android.content.Context
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.source.SilenceMediaSource
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import indi.renakoni.nextvol.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Playback completion and pauses come from ExoPlayer, not synthesis callbacks. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class ExoSpeechPlayback(private val player: ExoPlayer, private val context: Context,
    onInterrupted: () -> Unit = {}) : SpeechPlayback {
    private var active: CompletableDeferred<Unit>? = null
    private var listener: Player.Listener? = null
    override val isPlaying get() = player.isPlaying

    init {
        // A system interruption can arrive before synthesis finishes or between audio files.
        // Keep this listener until the service releases its player, independently of each clip.
        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY ||
                        reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)) onInterrupted()
            }
        })
    }

    override suspend fun play(clip: SpeechClip, playWhenReady: Boolean, onRange: (SpeechTiming) -> Unit,
        onEstimatedAnchor: (Int) -> Unit, nextClip: Flow<SpeechClip?>,
        onState: (SpeechPhase, Boolean) -> Unit) = coroutineScope {
        val continuing = player.currentMediaItem?.mediaId == clip.file.name
        if (!continuing) stop()
        val completed = CompletableDeferred<Unit>()
        active = completed
        var seekPending = false
        var queuedId: String? = null
        val playbackChanges = Channel<Unit>(Channel.CONFLATED)
        fun reportState() {
            if (active !== completed || completed.isCompleted) return
            playbackChanges.trySend(Unit)
            if (player.playbackState == Player.STATE_ENDED) completed.complete(Unit)
            else {
                val phase = when {
                    !player.playWhenReady || player.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE -> SpeechPhase.Paused
                    player.isPlaying -> SpeechPhase.Playing
                    else -> SpeechPhase.Buffering
                }
                onState(phase, player.playWhenReady)
            }
        }
        val events = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && mediaItem != null && mediaItem.mediaId == queuedId)
                    completed.complete(Unit)
            }

            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (active === completed && reason == Player.DISCONTINUITY_REASON_SEEK) {
                    seekPending = true
                    playbackChanges.trySend(Unit)
                }
            }

            override fun onEvents(player: Player, events: Player.Events) = reportState()
            override fun onPlayerError(error: PlaybackException) {
                if (active === completed) completed.completeExceptionally(clip.stream?.failure ?: SpeechException(SpeechError.Playback))
            }
        }
        listener = events
        player.addListener(events)
        try {
            if (continuing) {
                val index = player.currentMediaItemIndex
                if (index > 0) player.removeMediaItems(0, index)
                player.playerError?.let { throw clip.stream?.failure ?: SpeechException(SpeechError.Playback) }
                reportState()
            } else {
                load(clip, append = false)
                player.playWhenReady = playWhenReady
                player.prepare()
            }
            val preload = launch {
                nextClip.collect { next ->
                    if (completed.isCompleted || active !== completed || player.currentMediaItem?.mediaId != clip.file.name) return@collect
                    if (next != null && queuedId == null) {
                        queuedId = next.file.name
                        load(next, append = true)
                    } else if (next == null && queuedId != null) {
                        queuedId = null
                        player.removeMediaItem(player.currentMediaItemIndex + 1)
                    }
                }
            }
            val estimate = if (clip.timings.isEmpty() && clip.segment.pauseMs == null) SpeechFollowEstimate(clip.segment.text) else null
            val tracking = if (estimate != null || clip.timings.isNotEmpty()) launch {
                var reported: SpeechTiming? = null
                var reportedAnchor: Int? = null
                while (isActive && active === completed && !completed.isCompleted) {
                    if (player.isPlaying || seekPending && player.playbackState == Player.STATE_READY) {
                        val wasSeek = seekPending
                        seekPending = false
                        val position = player.currentPosition
                        if (estimate != null) {
                            val anchor = estimate.anchorAt(position, clip.stream?.durationMs ?: player.duration)
                            if (anchor == null) seekPending = wasSeek
                            else {
                                if (anchor != reportedAnchor) { reportedAnchor = anchor; onEstimatedAnchor(anchor) }
                            }
                        } else (clip.timings.rangeAt(position) ?: clip.timings.firstOrNull()?.takeIf { wasSeek })?.let { range ->
                            if (range != reported) { reported = range; onRange(range) }
                        }
                    }
                    // No polling while paused/buffering. Resume, readiness and explicit seek wake the tracker.
                    if (player.isPlaying) delay(50) else playbackChanges.receive()
                }
            } else null
            try { completed.await() } finally { preload.cancel(); tracking?.cancel() }
        } finally {
            player.removeListener(events)
            if (active === completed) { active = null; listener = null }
        }
    }

    private fun load(clip: SpeechClip, append: Boolean) {
        val title = clip.chapter.title.ifEmpty { context.getString(R.string.tts_preview) }
        val item = MediaItem.Builder().setUri(clip.file.toUri()).setMediaId(clip.file.name)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title)
                .setArtist(clip.chapter.bookTitle.ifEmpty { context.getString(R.string.app_name) })
                .setSubtitle(context.getString(R.string.tts_passage, clip.index + 1, clip.count)).build()).build()
        val pauseMs = clip.segment.pauseMs
        val source = when {
            pauseMs != null -> SilenceMediaSource.Factory().setDurationUs(pauseMs.toLong() * 1000)
                .createMediaSource().apply { updateMediaItem(item) }
            clip.stream != null -> streamingSpeechMediaSource(item, clip.stream)
            else -> null
        }
        if (source == null) {
            if (append) player.addMediaItem(item) else player.setMediaItem(item)
        } else if (append) player.addMediaSource(source) else player.setMediaSource(source)
    }

    override fun positionMs(clip: SpeechClip): Long =
        if (player.currentMediaItem?.mediaId == clip.file.name) player.currentPosition else 0L

    override fun pause() = player.pause()
    override fun resume() = player.play()
    override fun stop() {
        listener?.let(player::removeListener)
        listener = null
        active?.cancel()
        active = null
        player.stop()
        player.clearMediaItems()
    }
}
