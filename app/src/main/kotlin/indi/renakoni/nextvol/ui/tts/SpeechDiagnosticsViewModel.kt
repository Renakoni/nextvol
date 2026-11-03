package indi.renakoni.nextvol.ui.tts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.renakoni.nextvol.data.logging.LogFileSharer
import indi.renakoni.nextvol.data.logging.LoggerRepository
import indi.renakoni.nextvol.data.logging.SpeechLogEvent
import indi.renakoni.nextvol.data.logging.SpeechLogRecord
import indi.renakoni.nextvol.data.logging.SpeechLogStage
import indi.renakoni.nextvol.tts.SpeechError
import indi.renakoni.nextvol.tts.SpeechException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SpeechDiagnosticsViewModel @Inject constructor(
    private val logger: LoggerRepository,
    private val sharer: LogFileSharer,
) : ViewModel() {
    val logs = logger.speechLogEntries
    fun clearLogs() = work {
        if (!withContext(Dispatchers.IO) { logger.deleteSpeechLogs() }) throw SpeechException(SpeechError.Storage)
    }

    fun export() = work {
        val file = withContext(Dispatchers.IO) { logger.prepareSpeechLogFile(logger.speechLogReport()) }
        sharer.share(file)
    }

    private fun work(action: suspend () -> Unit) {
        viewModelScope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                logger.recordSpeech(SpeechLogRecord(SpeechLogStage.Configuration, SpeechLogEvent.Failed, SpeechError.Storage))
            }
        }
    }
}
