package indi.renakoni.nextvol.tts.offline.legacy

import android.content.Context
import com.autonavi.jni.tts.AudioManagerAdapter
import com.autonavi.jni.tts.AudioPlayerTask
import com.autonavi.jni.tts.IAudioTaskEventCallback
import com.autonavi.jni.tts.JNIAudioDelegate
import com.autonavi.jni.tts.JNIPlayer
import com.autonavi.jni.tts.JNIRecorder
import hnovel.speech.OfflineVoiceConfig
import indi.renakoni.nextvol.tts.offline.OfflineEngine
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

internal class VcsEngine(
    @Suppress("UNUSED_PARAMETER") context: Context,
    private val directory: File,
    private val voice: OfflineVoiceConfig,
) : OfflineEngine {
    private var adapter: AudioManagerAdapter? = null
    private var closed = false
    @Volatile private var cancelled = false
    @Volatile private var complete: CountDownLatch? = null
    @Volatile private var output: ((ByteArray, Int) -> Unit)? = null
    private val failure = AtomicReference<Throwable?>()
    @Volatile private var playerHandle = 0L
    @Volatile private var taskId = 0L
    override val sampleRate = voice.sampleRate.takeIf { it > 0 } ?: 16000

    private val player = object : JNIPlayer() {
        override fun getCurrentPosition() = 0
        override fun preStartCheck(task: AudioPlayerTask) = 1
        override fun playPCMStart(task: AudioPlayerTask, format: Int) { taskId = task.taskId }
        override fun playPCMData(id: Long, audio: ByteArray, format: Int) {
            if (id == taskId && !cancelled && failure.get() == null) {
                try { output?.invoke(audio, audio.size) }
                catch (problem: Throwable) {
                    failure.compareAndSet(null, problem)
                    complete?.countDown()
                }
            }
        }
        override fun playPCMDataFinished(id: Long, status: Int) { onMediaEvent(id, 2, 1, playerHandle) }
        override fun stopTTSPlay(reason: Int) { onMediaEvent(taskId, 4, reason, playerHandle) }
        override fun pause(id: Long) = Unit
        override fun resume(id: Long) = Unit
        override fun releasePlayer() = Unit
        override fun seekTo(position: Int) = Unit
        override fun setFocusStyle(style: Int) = Unit
        override fun setVolume(volume: Float) = Unit
        override fun stopAudioFilePlay(reason: Int) = Unit
        override fun taskEnd(type: Int, id: Long, reason: Int) = Unit
        override fun playAudioFile(id: Long, type: Short, data: String, position: Int, duration: Int) = Unit
        override fun playAudioFileStart(task: AudioPlayerTask) = Unit
    }

    private val delegate = object : JNIAudioDelegate() {
        override fun bindDelegate(handle: Long) = Unit
        override fun createPlayer(handle: Long): JNIPlayer {
            playerHandle = handle
            return player
        }
        override fun createRecorder(handle: Long): JNIRecorder = object : JNIRecorder() {
            override fun setConfig(rate: Int, channels: Int, format: Int, source: Int, size: Int) = 0
            override fun start() = 0
            override fun stop() = 0
        }
        override fun getLibPath() = ""
        override fun getLogSwitch() = false
        override fun isBackgroundMusicPlaying() = false
        override fun getTranslateParam() = "{}"
        override fun playbackLog(level: Int, message: String) = Unit
        override fun utLog(type: String, message: String) = Unit
        override fun onInited(success: Boolean) {
            if (!success) {
                failure.compareAndSet(null, IllegalStateException("VCS initialization failed"))
                complete?.countDown()
            }
        }
        override fun getExtendFontName(id: Int) = JSONObject()
            .put("ipId", id).put("extend_font_name", font()).put("subName", voice.code).toString()
        override fun getInitParam() = JSONObject()
            .put("workspace", File(directory, "idst/asr").absolutePath)
            .put("debug_path", File(directory, "idst/debug").absolutePath)
            .put("env", "0").put("keep_alive", "60").put("ossupload", "false")
            .put("dip", "10880").put("dic", "C3060").put("div", "ANDH111300")
            .put("tid", "xxxxxxxxxxxxxxxxx").put("diu", "xxxxxxxxxxxxxxxxx").put("adiu", "xxxxxxxxxxxxxxxxx")
            .put("extend_font_name", font()).put("ipId", 0).put("subName", voice.code).toString()
    }

    private fun font(): String = "${voice.code}:${File(directory, "acoustic/${voice.code}").absolutePath}"

    private fun initialize() {
        val model = File(directory, "acoustic/${voice.code}")
        require(model.canonicalFile.toPath().startsWith(directory.canonicalFile.toPath()) && model.exists()) {
            "Missing or escaped VCS voice: ${voice.code}"
        }
        val current = AudioManagerAdapter()
        try {
            check(current.nativeSetDelegate(delegate) { _, _, _, _ -> }) { "VCS could not attach its PCM delegate" }
            current.nativeInit()
            // The SDK's speaker ID is Java String.hashCode, not a model-content hash.
            current.nativeResetSpeaker(voice.code.hashCode()) { }
            adapter = current
        } catch (problem: Throwable) {
            current.nativeDestroy()
            throw problem
        }
    }

    override fun synthesize(text: String, speed: Float, emit: (ByteArray, Int) -> Unit) = synchronized(runtime) {
        check(!closed) { "VCS engine is closed" }
        cancelled = false
        failure.set(null)
        output = emit
        val completion = CountDownLatch(1)
        complete = completion
        try {
            synchronized(lifecycle) {
                if (active !== this) {
                    active?.releaseNative()
                    active = null
                    initialize()
                    active = this
                }
            }
            if (cancelled) throw CancellationException("VCS synthesis cancelled")
            failure.get()?.let { throw it }
            val current = checkNotNull(adapter)
            current.nativeSetTTSSpeed((speed * voice.speed).toDouble())
            current.nativeSetTTSVolume(voice.volume.toDouble())
            val callback = object : IAudioTaskEventCallback {
                override fun onStart(id: Long) = !cancelled
                override fun onMiddle(id: Long, event: Int) = Unit
                override fun onEnd(id: Long, type: Int, reason: Int) { completion.countDown() }
            }
            val id = current.nativePlaySingle(0, text, OWNER, 0, -1.0, -1.0, 1000, -1, 600, -1, 1, callback, null)
            check(id >= 0L) { "VCS synthesis could not start: $id" }
            completion.await()
            failure.get()?.let { throw it }
            if (cancelled) throw CancellationException("VCS synthesis cancelled")
        } finally {
            if (cancelled || failure.get() != null) adapter?.nativeStopWithOwner(OWNER, 0)
            output = null
            complete = null
        }
    }

    override fun cancel() {
        cancelled = true
        synchronized(lifecycle) {
            if (active === this) adapter?.nativeStopWithOwner(OWNER, 0)
        }
        complete?.countDown()
    }

    override fun close() {
        cancel()
        synchronized(runtime) {
            synchronized(lifecycle) {
                if (active === this) {
                    releaseNative()
                    active = null
                }
            }
            closed = true
        }
    }

    private fun releaseNative() {
        adapter?.nativeDestroy()
        adapter = null
    }

    companion object {
        private const val OWNER: Short = 1700
        private val runtime = Any()
        private val lifecycle = Any()
        @Volatile private var active: VcsEngine? = null
    }
}
