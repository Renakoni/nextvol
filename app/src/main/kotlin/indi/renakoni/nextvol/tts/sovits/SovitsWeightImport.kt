package indi.renakoni.nextvol.tts.sovits

import android.content.Context
import android.net.Uri
import android.os.Build
import android.webkit.MimeTypeMap
import dagger.hilt.android.qualifiers.ApplicationContext
import hnovel.speech.OfflineVoiceConfig
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.SpeechException
import indi.renakoni.nextvol.tts.matcha.InstalledOfflineVoice
import indi.renakoni.nextvol.tts.matcha.OfflineSpeechRepository
import indi.renakoni.nextvol.tts.runtime.SpeechRuntimePack
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import javax.inject.Inject

internal data class SovitsImportFile(val uri: Uri, val name: String)

internal enum class SovitsImportField(val extension: String?) {
    Gpt("ckpt"), Sovits("pth"), Audio(null);

    // OpenDocument filters by the provider's MIME type. Unmapped weight extensions share the binary type.
    val mimeType: String get() = if (extension == null) "audio/*"
        else MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"

    fun accepts(file: SovitsImportFile?) = file != null &&
        (extension == null || file.name.endsWith(".$extension", ignoreCase = true))
}

internal data class SovitsImportDraft(
    val name: String = "",
    val gpt: SovitsImportFile? = null,
    val sovits: SovitsImportFile? = null,
    val audio: SovitsImportFile? = null,
    val text: String = "",
    val avatar: String = "",
    val referenceLanguage: SovitsReferenceLanguage? = null,
) {
    val transcriptFromFilename get() = audio?.name?.substringBeforeLast('.')?.takeIf(String::isNotBlank)
    val ready get() = name.isNotBlank() && name.length <= 80 && text.isNotBlank() && audio != null &&
        SovitsImportField.Gpt.accepts(gpt) && SovitsImportField.Sovits.accepts(sovits)
}

internal class SovitsWeightImport @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val voices: OfflineSpeechRepository,
    private val runtime: SovitsRuntimeModels,
) {
    suspend fun install(draft: SovitsImportDraft, progress: (SovitsImportMessage) -> Unit): InstalledOfflineVoice = withContext(Dispatchers.IO) {
        require(draft.ready)
        requireSovits(SpeechRuntimePack.Sovits.installed) { SovitsImportMessage(R.string.sovits_runtime_required) }
        if (Build.VERSION.SDK_INT < 29) errorSovits(SovitsImportMessage(R.string.tts_offline_android, "29"))
        val task = currentCoroutineContext()
        fun open(file: SovitsImportFile): InputStream = context.contentResolver.openInputStream(file.uri)
            ?: errorSovits(SovitsImportMessage(R.string.sovits_file_unreadable))
        voices.installSovits(draft.name, draft.avatar) { stage ->
            progress(SovitsImportMessage(R.string.sovits_checking_reference))
            val audio = requireNotNull(draft.audio)
            val extension = audio.name.substringAfterLast('.', "").lowercase()
            requireSovits(extension.matches(Regex("[a-z0-9]{1,8}"))) { SovitsImportMessage(R.string.sovits_reference_extension_missing) }
            val reference = File(stage, "reference.$extension")
            open(audio).use { input -> reference.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var size = 0L
                while (true) {
                    task.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    size += count
                    requireSovits(size <= 32L * 1024 * 1024) { SovitsImportMessage(R.string.sovits_reference_file_invalid) }
                    output.write(buffer, 0, count)
                }
            } }
            try { decodeSovitsReference(reference) }
            catch (failure: Exception) {
                task.ensureActive()
                errorSovits(SovitsImportMessage(R.string.sovits_reference_audio_invalid))
            }
            val gpt = requireNotNull(draft.gpt)
            val sovits = requireNotNull(draft.sovits)
            val result = CheckpointImporter(stage, context.assets::open).convert(
                { open(gpt) }, { open(sovits) }, progress, task::ensureActive)
            val audioDirectory = File(result.character, "prompt_wav")
            check(audioDirectory.mkdir() && reference.renameTo(File(audioDirectory, reference.name)))
            val transcript = draft.text.trim()
            val language = draft.referenceLanguage ?: SovitsReferenceLanguage.detect(transcript)
            File(result.character, "prompt_wav.json").writeText(JSONObject().put("Normal",
                JSONObject().put("wav", reference.name).put("text", transcript).put("language", language.code)).toString())
            runtime.prepare(progress)
            progress(SovitsImportMessage(R.string.sovits_preparing_models))
            try { SovitsFrontend(runtime.directory).use { it.encode(transcript, language) } }
            catch (_: LinkageError) { errorSovits(SovitsImportMessage(R.string.tts_offline_runtime_missing, "GPT-SoVITS")) }
            catch (_: SpeechException) { errorSovits(SovitsImportMessage(R.string.sovits_reference_text_invalid)) }
            catch (_: IllegalArgumentException) { errorSovits(SovitsImportMessage(R.string.sovits_reference_text_invalid)) }
            prepareSovitsSharedEncoder(runtime.directory)
            OfflineVoiceConfig("gpt-sovits", "${result.architecture}.${result.character.name}",
                locale = "zh-CN", sampleRate = 32000)
        }
    }
}
