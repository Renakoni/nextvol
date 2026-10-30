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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class SpeechPackagePreviewState(
    val preview: SpeechPackagePreview? = null,
    val httpImports: Map<Int, HttpSpeechPackageImport> = emptyMap(),
)

/** Owns a read-only document handle. This flow has no source repository or execution controller. */
@HiltViewModel
class SpeechPackagePreviewViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SpeechPackagePreviewState())
    val state = mutableState.asStateFlow()
    private var input: Uri? = null
    private var reading: Job? = null

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
                SpeechPackagePreviewState(preview, imports)
            }
            currentCoroutineContext().ensureActive()
            mutableState.value = result
        }
    }
}
