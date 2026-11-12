package indi.renakoni.nextvol.ui.book.detail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.michaelbull.result.Result
import com.google.android.material.bottomsheet.BottomSheetBehavior.State
import indi.renakoni.nextvol.data.download.DownloadItem
import indi.renakoni.nextvol.data.download.BookDownloadStatus
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.UserReadingData
import io.nightfish.lightnovelreader.api.error.WebRequestError

@State
interface DetailUiState {
    val bookInformation: Result<BookInformation, WebRequestError>?
    val bookVolumes: Result<BookVolumes, WebRequestError>?
    val userReadingData: UserReadingData?
    val downloadState: BookDownloadStatus
    val downloadItem: DownloadItem?
    val isInBookshelf: Boolean
    val readingAvailable: Boolean
    val onlineDirectory: Boolean
    val directoryAvailable get() = readingAvailable || onlineDirectory
    val canCache: Boolean
    val metadataOnly: Boolean
}

class MutableDetailUiState: DetailUiState {
    override var bookInformation: Result<BookInformation, WebRequestError>? by mutableStateOf(null)
    override var bookVolumes: Result<BookVolumes, WebRequestError>? by mutableStateOf(null)
    override var userReadingData: UserReadingData? by mutableStateOf(null)
    override var downloadState: BookDownloadStatus by mutableStateOf(BookDownloadStatus())
    override var downloadItem: DownloadItem? by mutableStateOf(null)
    override var isInBookshelf: Boolean by mutableStateOf(false)
    override var readingAvailable: Boolean by mutableStateOf(false)
    override var onlineDirectory: Boolean by mutableStateOf(false)
    override var canCache: Boolean by mutableStateOf(false)
    override var metadataOnly: Boolean by mutableStateOf(false)
}
