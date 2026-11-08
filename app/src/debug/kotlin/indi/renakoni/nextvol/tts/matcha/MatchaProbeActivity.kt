package indi.renakoni.nextvol.tts.matcha

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import indi.renakoni.nextvol.BuildConfig
import indi.renakoni.nextvol.tts.SpeechSettings
import indi.renakoni.nextvol.tts.SpeechSynthesizers
import hnovel.speech.discoverSherpaVoiceConfig
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import javax.inject.Inject

/** adb-only entry for loading the installed sample and generating one complete WAV. */
@AndroidEntryPoint
class MatchaProbeActivity : ComponentActivity() {
    @Inject lateinit var synthesizers: SpeechSynthesizers

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val report = JSONObject()
            try {
                check(BuildConfig.MATCHA_NATIVE) { "Build with -PmatchaNative=true" }
                withTimeout(120_000) {
                    val directory = File(filesDir, "matcha")
                    val paths = directory.walkTopDown().filter { it.isFile }.map { it.relativeTo(directory).invariantSeparatorsPath }.toList()
                    val engine = synthesizers.matcha(MatchaResources(directory, discoverSherpaVoiceConfig(paths)))
                    val output = File(cacheDir, "matcha-probe.wav").also { it.delete() }
                    try {
                        engine.open(SpeechSettings())
                        engine.synthesize("这是一次离线语音验证。我们正在使用导入的语音包，在应用内部生成声音。", output)
                        report.put("wav_bytes", output.length())
                    } finally { withContext(NonCancellable + Dispatchers.IO) { engine.close() } }
                }
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
}
