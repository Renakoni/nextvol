package indi.renakoni.nextvol.tts.matcha

import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import indi.renakoni.nextvol.BuildConfig
import indi.renakoni.nextvol.tts.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.io.File
import javax.inject.Inject

/** adb-only acceptance entry; fixed private resource paths, no model download or product UI. */
@AndroidEntryPoint
class MatchaProbeActivity : ComponentActivity() {
    @Inject lateinit var synthesizers: SpeechSynthesizers

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val report = JSONObject().put("mode", intent.getStringExtra("mode") ?: "synthesize")
            try {
                check(BuildConfig.MATCHA_NATIVE) { "Build with -PmatchaNative=true" }
                withTimeout(120_000) { runProbe(report) }
                report.put("success", true)
            } catch (error: Exception) {
                report.put("success", false).put("error", error.toString())
                Log.e("NextVolMatcha", "Probe failed", error)
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    File(filesDir, "matcha-result.json").writeText(report.toString(2))
                }
                Log.i("NextVolMatcha", report.toString())
                finish()
            }
        }
    }

    private suspend fun runProbe(report: JSONObject) = coroutineScope {
        val mode = report.getString("mode")
        val resources = MatchaResources(File(filesDir, if (mode == "invalid") "matcha-invalid" else "matcha"))
        fun provider() = synthesizers.matcha(resources)
        if (mode == "switch") {
            verifySwitch(::provider)
            report.put("stale_clips", 0)
            return@coroutineScope
        }
        val engine = provider()
        val output = File(cacheDir, "matcha-probe.wav").also { it.delete() }
        var started = SystemClock.elapsedRealtime()
        try {
            if (mode == "cancel-init") {
                val opening = launch { engine.open(SpeechSettings()) }
                delay(150)
                started = SystemClock.elapsedRealtime()
                engine.cancel()
                opening.cancelAndJoin()
                report.put("cancel_ms", SystemClock.elapsedRealtime() - started)
            } else if (mode == "invalid") {
                val failure = runCatching { engine.open(SpeechSettings()) }.exceptionOrNull()
                check(failure is SpeechException && failure.error == SpeechError.EngineUnavailable)
                report.put("rejected", failure.error.name)
            } else {
                engine.open(SpeechSettings())
                report.put("init_ms", SystemClock.elapsedRealtime() - started)
                    .put("pss_after_init_kib", Debug.getPss())
                started = SystemClock.elapsedRealtime()
                if (mode == "cancel") {
                    val generating = launch { engine.synthesize("这是一段需要取消的语音。".repeat(10), output) }
                    delay(150)
                    started = SystemClock.elapsedRealtime()
                    engine.cancel()
                    generating.cancelAndJoin()
                    check(!output.exists() && !File(output.path + ".part").exists())
                    report.put("cancel_ms", SystemClock.elapsedRealtime() - started)
                } else {
                    check(mode == "synthesize")
                    engine.synthesize("这是一次离线语音验证。我们正在使用导入的语音包，在应用内部生成声音。", output)
                    validateSpeechWav(output)
                    report.put("synthesis_ms", SystemClock.elapsedRealtime() - started).put("wav_bytes", output.length())
                }
            }
        } finally {
            started = SystemClock.elapsedRealtime()
            withContext(NonCancellable + Dispatchers.IO) { engine.close() }
            report.put("close_ms", SystemClock.elapsedRealtime() - started)
                .put("pss_after_close_kib", Debug.getPss())
        }
        // A failed/cancelled instance must return the sole native slot to the next session.
        if (mode != "synthesize") {
            val next = synthesizers.matcha(MatchaResources(File(filesDir, "matcha")))
            try {
                next.open(SpeechSettings())
                next.synthesize("下一次朗读可以正常开始。", output)
                report.put("recovered_wav_bytes", output.length())
            } finally { withContext(NonCancellable + Dispatchers.IO) { next.close() } }
        }
    }

    private suspend fun verifySwitch(factory: () -> SpeechSynthesizer) = coroutineScope {
        val clips = mutableListOf<SpeechClip>()
        val player = object : SpeechPlayback {
            override suspend fun play(clip: SpeechClip, playWhenReady: Boolean, onRange: (SpeechTiming) -> Unit,
                onEstimatedAnchor: (Int) -> Unit, onState: (SpeechPhase, Boolean) -> Unit) {
                validateSpeechWav(clip.file)
                clips += clip
                onState(SpeechPhase.Playing, true)
            }
            override fun pause() = Unit
            override fun resume() = Unit
            override fun stop() = Unit
            override fun setSpeed(speed: Float) = Unit
        }
        val store = object : SpeechProgressStore {
            override suspend fun load(bookId: String): SpeechBookmark? = null
            override suspend fun save(bookmark: SpeechBookmark) = Unit
        }
        val session = ReadAloudSession(this, SpeechChapterSource { _, _ -> error("Preview only") },
            { SpeechSettings() }, store, factory, player, File(cacheDir, "matcha-session"))
        try {
            session.start(SpeechRequest("", "", "旧的朗读不应该进入新的会话。".repeat(8)))
            val prepared = session.state.first { it.voiceLabel.isNotEmpty() || it.phase == SpeechPhase.Failed }
            check(prepared.phase != SpeechPhase.Failed)
            delay(150)
            session.stop()
            clips.clear()
            session.start(SpeechRequest("", "", "这是替换后的朗读。"))
            val state = session.state.first { it.phase == SpeechPhase.Completed || it.phase == SpeechPhase.Failed }
            check(state.phase == SpeechPhase.Completed)
            check(clips.size == 1 && clips.single().chapter.text == "这是替换后的朗读。")
        } finally { session.stop() }
    }
}
