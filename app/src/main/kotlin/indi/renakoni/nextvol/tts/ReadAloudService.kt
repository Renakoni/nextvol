package indi.renakoni.nextvol.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.MediaStyleNotificationHelper
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import indi.renakoni.nextvol.MainActivity
import indi.renakoni.nextvol.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

@AndroidEntryPoint
@androidx.annotation.OptIn(UnstableApi::class)
class ReadAloudService : MediaSessionService() {
    @Inject lateinit var controller: ReadAloudController
    @Inject lateinit var chapters: SpeechChapterSource
    @Inject lateinit var settings: SpeechSettingsRepository
    @Inject lateinit var progress: SpeechProgressStore
    @Inject lateinit var synthesizers: SpeechSynthesizers
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var player: ExoPlayer
    private lateinit var speech: ReadAloudSession
    private lateinit var sleepTimer: SpeechSleepTimer
    private lateinit var preparationWakeLock: PowerManager.WakeLock
    private var mediaSession: MediaSession? = null
    private var shuttingDown = false
    // System UI can dismiss an old entry asynchronously after a new service has started.
    // Negative IDs also keep these entries separate from the app's update/export notifications.
    private val notificationId = -notificationIds.updateAndGet { if (it == Int.MAX_VALUE) 1 else it + 1 }

    override fun onCreate() {
        super.onCreate()
        // The speech session owns buffering; completed or paused audio needs no player grace period.
        setForegroundServiceTimeoutMs(0)
        preparationWakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NextVol:SpeechPreparation").apply { setReferenceCounted(false) }
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.tts_title), NotificationManager.IMPORTANCE_LOW))
        player = ExoPlayer.Builder(this).setWakeMode(C.WAKE_MODE_LOCAL).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            setHandleAudioBecomingNoisy(true)
        }
        speech = ReadAloudSession(scope, chapters, settings::get, progress, synthesizers::synthesizer,
            ExoSpeechPlayback(player, this) { speech.interrupt() }, File(cacheDir, "read-aloud"))
        sleepTimer = SpeechSleepTimer(scope, SystemClock::elapsedRealtime,
            onChanged = { deadline ->
                if (!shuttingDown && speech.state.value.request != null)
                    controller.publish(speech.state.value.copy(sleepTimerDeadline = deadline))
            },
            onExpired = {
                speech.stop()
                controller.publish(speech.state.value)
                stopSelf()
            })
        val controls = object : ForwardingSimpleBasePlayer(player) {
            override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
                if (playWhenReady) { if (!sleepTimer.expireIfDue() && foreground()) speech.resume() } else speech.pause()
                return Futures.immediateVoidFuture()
            }
            override fun handleStop(): ListenableFuture<*> {
                speech.stop()
                return Futures.immediateVoidFuture()
            }
        }
        mediaSession = MediaSession.Builder(this, controls).setSessionActivity(openPlayer())
            .setCallback(object : MediaSession.Callback {
                override fun onConnectAsync(session: MediaSession, info: MediaSession.ControllerInfo): ListenableFuture<MediaSession.ConnectionResult> {
                    val result = if (!info.isTrusted && info.packageName != packageName) MediaSession.ConnectionResult.reject()
                    else MediaSession.ConnectionResult.AcceptedResultBuilder(session, info)
                        .setAvailablePlayerCommands(Player.Commands.Builder().addAll(
                            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_STOP, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                            Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE,
                            Player.COMMAND_GET_METADATA, Player.COMMAND_GET_AUDIO_ATTRIBUTES,
                        ).build()).build()
                    return Futures.immediateFuture(result)
                }
            }).build().also(::addSession)
        scope.launch {
            var notificationState: ReadAloudState? = null
            speech.state.collect {
                // Keep the pending UI request until the service receives its first command.
                if (!shuttingDown && it.request != null) {
                    // Audio focus can resume the player without a UI/media-session Resume command.
                    if (it.isActive && sleepTimer.expireIfDue()) return@collect
                    if (it.request.isPreview && it.phase in setOf(SpeechPhase.Completed, SpeechPhase.Failed)) {
                        if (sleepTimer.expireIfDue() || speech.restoreAfterPreview()) return@collect
                    }
                    // ExoPlayer owns playback wakefulness; chapter loading and synthesis have no player yet.
                    if (it.phase == SpeechPhase.Preparing || it.phase == SpeechPhase.Buffering) {
                        if (!preparationWakeLock.isHeld) preparationWakeLock.acquire(90_000)
                    } else if (preparationWakeLock.isHeld) preparationWakeLock.release()
                    if (it.phase in setOf(SpeechPhase.Stopped, SpeechPhase.Completed, SpeechPhase.Failed)) sleepTimer.cancel()
                    controller.publish(it.copy(sleepTimerDeadline = sleepTimer.deadline))
                    // Word boundaries update the reader, not the system notification/foreground service.
                    val summary = it.copy(position = null)
                    if (summary != notificationState) {
                        notificationState = summary
                        updateNotification()
                    }
                    if (it.phase == SpeechPhase.Stopped) stopSelf()
                }
            }
        }
        scope.launch {
            settings.changes.collect(speech::updateSettings)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession.takeIf { controllerInfo.isTrusted || controllerInfo.packageName == packageName }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.getStringExtra(ReadAloudController.ACTION)?.let { name -> SpeechAction.entries.find { it.name == name } }
            ?: return START_NOT_STICKY
        val request = try { intent.getStringExtra(ReadAloudController.REQUEST)?.let { Json.decodeFromString<SpeechRequest>(it) } }
            catch (_: IllegalArgumentException) { null }
        if (speech.state.value.request == null && action != SpeechAction.Start && action != SpeechAction.Resume) {
            if (action == SpeechAction.Stop) controller.publish(ReadAloudState())
            stopSelf()
            return START_NOT_STICKY
        }
        if (action == SpeechAction.Start) {
            if (request?.isPreview == true) {
                if (sleepTimer.expireIfDue()) return START_NOT_STICKY
            } else sleepTimer.cancel()
        }
        if (action == SpeechAction.Resume && sleepTimer.expireIfDue()) return START_NOT_STICKY
        if ((action == SpeechAction.Start || action == SpeechAction.Resume) && !foreground()) return START_NOT_STICKY
        when (action) {
            SpeechAction.Start -> if (request != null) speech.start(request) else stopSelf()
            SpeechAction.Resume -> if (speech.state.value.request != null) speech.resume() else if (request != null) speech.start(request) else stopSelf()
            SpeechAction.Pause -> speech.pause()
            SpeechAction.Stop -> { speech.stop(); stopSelf() }
            SpeechAction.Previous -> speech.skipSegment(-1)
            SpeechAction.Next -> speech.skipSegment(1)
            SpeechAction.PreviousChapter -> speech.changeChapter(false)
            SpeechAction.NextChapter -> speech.changeChapter(true)
            SpeechAction.SleepTimer -> {
                val minutes = intent.getIntExtra(ReadAloudController.TIMER_MINUTES, 0)
                if (minutes in 0..60 && speech.state.value.phase in setOf(
                        SpeechPhase.Preparing, SpeechPhase.Buffering, SpeechPhase.Playing, SpeechPhase.Paused)) {
                    sleepTimer.set(minutes.takeIf { it > 0 })
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val state = speech.state.value
        if (!state.isActive && !(state.phase == SpeechPhase.Paused && player.playWhenReady)) stopSelf()
    }

    override fun onUpdateNotificationAsync(session: MediaSession, startInForegroundRequired: Boolean): ListenableFuture<Void?> {
        if (!shuttingDown && ::speech.isInitialized) updateNotification(startInForegroundRequired)
        return Futures.immediateFuture(null)
    }

    private fun updateNotification(required: Boolean = false) {
        if (shuttingDown) return
        val state = speech.state.value
        when {
            state.phase == SpeechPhase.Stopped -> ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            required || state.isActive || state.phase == SpeechPhase.Paused && player.playWhenReady -> foreground()
            else -> {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
                getSystemService(NotificationManager::class.java).notify(notificationId, notification())
            }
        }
    }

    private fun foreground(): Boolean {
        if (shuttingDown) return false
        return try {
            ServiceCompat.startForeground(this, notificationId, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            true
        } catch (_: IllegalStateException) { serviceUnavailable(); false }
        catch (_: SecurityException) { serviceUnavailable(); false }
    }

    private fun serviceUnavailable() {
        shuttingDown = true
        val last = speech.state.value.takeIf { it.request != null } ?: controller.state.value
        speech.stop()
        controller.publish(last.copy(phase = SpeechPhase.Failed, error = SpeechError.ServiceUnavailable))
        stopSelf()
    }

    private fun notification(): Notification {
        val state = if (::speech.isInitialized) speech.state.value else controller.state.value
        val request = state.request ?: controller.state.value.request
        val builder = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.headphones_24px)
            .setContentTitle(state.bookTitle.ifEmpty { getString(R.string.tts_title) })
            .setContentText(state.error?.let { getString(it.messageResource) }
                ?: state.chapterTitle.ifEmpty { getString(state.phase.labelResource) })
            .setContentIntent(openPlayer()).setOnlyAlertOnce(true).setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOngoing(state.isActive)
        if (request != null) {
            val pause = state.isActive
            fun action(value: SpeechAction): PendingIntent {
                val intent = ReadAloudController.intent(this, value, request)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                return if (value == SpeechAction.Resume && Build.VERSION.SDK_INT >= 26) {
                    PendingIntent.getForegroundService(this, value.ordinal, intent, flags)
                } else PendingIntent.getService(this, value.ordinal, intent, flags)
            }
            builder.addAction(if (pause) R.drawable.pause_24px else R.drawable.play_arrow_24px,
                getString(if (pause) R.string.tts_pause else R.string.tts_resume), action(if (pause) SpeechAction.Pause else SpeechAction.Resume))
            builder.addAction(R.drawable.stop_24px, getString(R.string.tts_stop), action(SpeechAction.Stop))
            mediaSession?.let { builder.setStyle(MediaStyleNotificationHelper.MediaStyle(it).setShowActionsInCompactView(0, 1)) }
        }
        return builder.build()
    }

    private fun openPlayer() = PendingIntent.getActivity(this, 56,
        Intent(this, MainActivity::class.java).setAction(ReadAloudController.OPEN_PLAYER),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    override fun onDestroy() {
        shuttingDown = true
        val last = speech.state.value
        sleepTimer.cancel()
        scope.cancel()
        speech.stop()
        mediaSession?.release()
        mediaSession = null
        player.release()
        if (preparationWakeLock.isHeld) preparationWakeLock.release()
        getSystemService(NotificationManager::class.java).cancel(notificationId)
        if (last.isActive && controller.state.value.error != SpeechError.ServiceUnavailable) {
            controller.publish(last.copy(phase = SpeechPhase.Paused))
        } else controller.publish(controller.state.value.copy(sleepTimerDeadline = null))
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "read-aloud"
        private val notificationIds = AtomicInteger((SystemClock.elapsedRealtime() % Int.MAX_VALUE).toInt())
    }
}
