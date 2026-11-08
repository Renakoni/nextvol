package indi.renakoni.nextvol.tts.offline

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import hnovel.speech.previewOfflineVoice
import hnovel.speech.previewSpeechPackage
import indi.renakoni.nextvol.tts.SpeechSettings
import indi.renakoni.nextvol.tts.SpeechSynthesizers
import indi.renakoni.nextvol.tts.matcha.OfflineSpeechRepository
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject

/** Shell-only real-package probe; uses the same installer and synthesis route as the UI. */
@AndroidEntryPoint
class OfflinePackageProbeActivity : ComponentActivity() {
    @Inject lateinit var repository: OfflineSpeechRepository
    @Inject lateinit var synthesizers: SpeechSynthesizers

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val report = JSONObject()
            try {
                withContext(Dispatchers.IO) {
                    report.put("stage", "preview")
                    val archive = File(requireNotNull(intent.getStringExtra("package")))
                    val preview = previewSpeechPackage(archive)
                    check(preview.error == null) { "Package preview failed: ${preview.error}" }
                    val plans = preview.engines.flatMap { engine -> engine.voices.map { voice ->
                        previewOfflineVoice(preview, engine, voice)
                    } }
                    report.put("voices", JSONArray(plans.map { plan -> JSONObject()
                        .put("name", plan.plan?.name).put("engine", plan.plan?.configuration?.engine)
                        .put("code", plan.plan?.configuration?.code).put("error", plan.error?.name)
                    }))
                    if (intent.getBooleanExtra("previewOnly", false)) return@withContext
                    val selected = plans[intent.getIntExtra("voice", 0)]
                    val plan = checkNotNull(selected.plan) { "Voice preview failed: ${selected.error}" }
                    report.put("stage", "install")
                    val installed = repository.install(archive, plan)
                    report.put("installed", installed.reference.id)
                    val engine = synthesizers.synthesizer()
                    val output = File(cacheDir, "offline-probe.wav").also {
                        it.delete()
                        File(it.path + ".part").delete()
                    }
                    try {
                        report.put("stage", "open")
                        withTimeout(120_000) {
                            engine.open(SpeechSettings(offlineVoice = installed.reference))
                            report.put("stage", "synthesize")
                            engine.synthesize(intent.getStringExtra("text") ?: "你好，这是离线语音验证。Hello, this is an offline speech test.", output)
                        }
                        report.put("wav_bytes", output.length())
                    } finally { withContext(NonCancellable) {
                        try { engine.close() } finally {
                            if (!intent.getBooleanExtra("keep", false)) repository.delete(installed.reference)
                        }
                    } }
                }
                report.put("success", true)
            } catch (failure: Exception) {
                report.put("success", false).put("error", failure.toString())
                    .put("cause", failure.cause?.toString())
                Log.e("NextVolOffline", "Package probe failed", failure)
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    File(filesDir, "offline-probe-result.json").writeText(report.toString(2))
                }
                Log.i("NextVolOffline", "Probe success=${report.optBoolean("success")}, stage=${report.optString("stage")}")
                finish()
            }
        }
    }
}
