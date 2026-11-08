package indi.renakoni.nextvol.ui.dialog

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.renakoni.nextvol.data.update.UpdateCheckRepository
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class UpdatesAvailableDialogViewModel @Inject constructor(
    private val updateCheckRepository: UpdateCheckRepository
) : ViewModel() {
    val releaseFlow = updateCheckRepository.releaseFlow
    val availableFlow = updateCheckRepository.availableFlow
    val updatePhaseFlow = updateCheckRepository.updatePhase
    val isDownloading: StateFlow<Boolean> = updateCheckRepository.isDownloading
    val downloadProgress: StateFlow<Float> = updateCheckRepository.downloadProgress

    fun downloadUpdate() =
        updateCheckRepository.downloadUpdate()

    fun checkUpdate() =
        updateCheckRepository.check()

    fun resetAvailable() = updateCheckRepository.resetAvailable()
}