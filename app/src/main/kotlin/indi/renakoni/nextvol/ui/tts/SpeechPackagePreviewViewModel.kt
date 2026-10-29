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

/** Owns a read-only document handle. This flow has no source repository or execution controller. */
@HiltViewModel
class SpeechPackagePreviewViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val mutablePreview = MutableStateFlow<SpeechPackagePreview?>(null)
    val preview = mutablePreview.asStateFlow()
    private var input: Uri? = null
    private var reading: Job? = null

    fun read(uri: Uri) {
        if (uri == input) return
        input = uri
        reading?.cancel()
        mutablePreview.value = null
        reading = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val task = currentCoroutineContext()
                var name = ""
                try {
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
            }
            currentCoroutineContext().ensureActive()
            mutablePreview.value = result
        }
    }
}
