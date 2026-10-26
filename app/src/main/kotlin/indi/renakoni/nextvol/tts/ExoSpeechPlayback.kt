package indi.renakoni.nextvol.tts

import android.content.Context
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import indi.renakoni.nextvol.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Playback completion and pauses come from ExoPlayer, not synthesis callbacks. */
internal class ExoSpeechPlayback(private val player: ExoPlayer, private val context: Context,
    onInterrupted: () -> Unit = {}) : SpeechPlayback {
    private var active: CompletableDeferred<Unit>? = null
    private var listener: Player.Listener? = null

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
        onEstimatedAnchor: (Int) -> Unit,
        onState: (SpeechPhase, Boolean) -> Unit) = coroutineScope {
        stop()
        val completed = CompletableDeferred<Unit>()
        active = completed
        var seekPending = false
        val playbackChanges = Channel<Unit>(Channel.CONFLATED)
        val events = object : Player.Listener {
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (active === completed && reason == Player.DISCONTINUITY_REASON_SEEK) {
                    seekPending = true
                    playbackChanges.trySend(Unit)
                }
            }

            override fun onEvents(player: Player, events: Player.Events) {
                if (active !== completed) return
                playbackChanges.trySend(Unit)
                if (player.playbackState == Player.STATE_ENDED) {
                    completed.complete(Unit)
                    return
                }
                val phase = when {
                    !player.playWhenReady || player.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE -> SpeechPhase.Paused
                    player.isPlaying -> SpeechPhase.Playing
                    else -> SpeechPhase.Buffering
                }
                onState(phase, player.playWhenReady)
            }
            override fun onPlayerError(error: PlaybackException) {
                if (active === completed) completed.completeExceptionally(SpeechException(SpeechError.Playback))
            }
        }
        listener = events
        player.addListener(events)
        try {
            val title = clip.chapter.title.ifEmpty { context.getString(R.string.tts_preview) }
            player.setMediaItem(MediaItem.Builder().setUri(clip.file.toUri()).setMediaId(clip.file.name)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(title)
                    .setArtist(clip.chapter.bookTitle.ifEmpty { context.getString(R.string.app_name) })
                    .setSubtitle(context.getString(R.string.tts_passage, clip.index + 1, clip.count)).build()).build())
            player.playWhenReady = playWhenReady
            player.prepare()
            val estimate = if (clip.timings.isEmpty()) SpeechFollowEstimate(clip.segment.text) else null
            val tracking = launch {
                var reported: SpeechTiming? = null
                var reportedAnchor: Int? = null
                while (isActive && active === completed) {
                    if (player.isPlaying || seekPending && player.playbackState == Player.STATE_READY) {
                        val wasSeek = seekPending
                        seekPending = false
                        val position = player.currentPosition
                        if (estimate != null) {
                            val anchor = estimate.anchorAt(position, player.duration)
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
            }
            try { completed.await() } finally { tracking.cancel() }
        } finally {
            player.removeListener(events)
            if (active === completed) { active = null; listener = null }
        }
    }

    override fun setSpeed(speed: Float) = player.setPlaybackSpeed(speed)
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
