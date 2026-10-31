package indi.renakoni.nextvol.ui.tts

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import hnovel.speech.SpeechPackageError
import hnovel.speech.SpeechPackagePreview
import hnovel.speech.HttpSpeechPackageImport
import hnovel.speech.previewHttpSpeechPackage
import hnovel.speech.previewSpeechPackage
import hnovel.speech.OfflinePackagePreview
import hnovel.speech.OfflinePackagePlan
import hnovel.speech.previewOfflineVoice
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.tts.SpeechSettingsRepository
import indi.renakoni.nextvol.tts.matcha.OfflineSpeechRepository
import indi.renakoni.nextvol.tts.matcha.OfflineInstallException
import indi.renakoni.nextvol.tts.matcha.OfflineInstallError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class SpeechPackagePreviewState(
    val preview: SpeechPackagePreview? = null,
    val httpImports: Map<Int, HttpSpeechPackageImport> = emptyMap(),
    val offlineImports: Map<Pair<Int, Int>, OfflinePackagePreview> = emptyMap(),
    val offlineSupported: Boolean = true,
    val installing: Boolean = false,
    val installingId: String? = null,
    val installed: String? = null,
    val installError: Int? = null,
)

/** Preview is static. Installation only begins after the user chooses a supported voice. */
@HiltViewModel
class SpeechPackagePreviewViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val offline: OfflineSpeechRepository,
    private val settings: SpeechSettingsRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SpeechPackagePreviewState())
    val state = mutableState.asStateFlow()
    private var input: Uri? = null
    private var reading: Job? = null
    private var installing: Job? = null

    fun read(uri: Uri) {
        if (uri == input) return
        input = uri
        reading?.cancel()
        mutableState.value = SpeechPackagePreviewState()
        reading = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val task = currentCoroutineContext()
                var name = ""
                val preview = try {
                    name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0)?.take(256).orEmpty() else ""
                    }.orEmpty()
                    task.ensureActive()
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                        // A seekable descriptor lets ZipFile read the index without copying multi-GB models.
                        previewSpeechPackage(File("/proc/self/fd/${descriptor.fd}"), name) { task.ensureActive() }
                    } ?: SpeechPackagePreview(name, error = SpeechPackageError.ReadFailed)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { SpeechPackagePreview(name, error = SpeechPackageError.ReadFailed) }
                val imports = preview.engines.mapIndexedNotNull { index, engine ->
                    task.ensureActive()
                    if (engine.type == "http") index to previewHttpSpeechPackage(engine) else null
                }.toMap()
                val local = preview.engines.flatMapIndexed { index, engine ->
                    if (engine.type != "http") engine.voices.mapIndexed { voiceIndex, voice ->
                        (index to voiceIndex) to previewOfflineVoice(preview, engine, voice)
                    } else emptyList()
                }.toMap()
                SpeechPackagePreviewState(preview, imports, local)
            }
            currentCoroutineContext().ensureActive()
            mutableState.value = result
        }
    }

    fun install(plan: OfflinePackagePlan) {
        val uri = input ?: return
        if (state.value.installing || !state.value.offlineSupported || state.value.offlineImports.values.none { it.plan == plan }) return
        mutableState.update { it.copy(installing = true, installingId = plan.id, installError = null, installed = null) }
        installing = viewModelScope.launch {
            try {
                val voice = withContext(Dispatchers.IO) {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                        offline.install(File("/proc/self/fd/${descriptor.fd}"), plan)
                    } ?: error("Unreadable package")
                }
                settings.update { if (it.offlineVoice?.id == voice.reference.id) it.copy(offlineVoice = voice.reference) else it }
                mutableState.update { it.copy(installed = voice.name) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                val message = when ((failure as? OfflineInstallException)?.error) {
                    OfflineInstallError.ChangedPackage -> R.string.tts_offline_changed
                    OfflineInstallError.InvalidArchive -> R.string.tts_package_invalid_archive
                    null -> R.string.tts_import_failed
                }
                mutableState.update { it.copy(installError = message) }
            } finally { mutableState.update { it.copy(installing = false) } }
        }
    }

    fun cancelInstall() { installing?.cancel() }
}
