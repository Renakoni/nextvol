package indi.renakoni.nextvol.ui.book.detail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import indi.renakoni.nextvol.ui.book.reader.DirectorySearchMatch
import indi.renakoni.nextvol.ui.book.reader.searchDirectory
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.Volume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun rememberDetailDirectoryState(bookId: String): DetailDirectoryState {
    val volumeExpansion = rememberSaveable(bookId) { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    val requestedPage = rememberSaveable(bookId) { mutableStateOf<Int?>(null) }
    val descending = rememberSaveable(bookId) { mutableStateOf(false) }
    val hideReadChapters = rememberSaveable(bookId) { mutableStateOf(false) }
    val searchExpanded = rememberSaveable(bookId) { mutableStateOf(false) }
    val query = rememberSaveable(bookId) { mutableStateOf("") }
    val scrollToDirectory = remember { mutableStateOf(false) }
    val chapterToLocate = remember { mutableStateOf<String?>(null) }
    return remember(volumeExpansion, requestedPage, descending, hideReadChapters, searchExpanded, query,
        scrollToDirectory, chapterToLocate) {
        DetailDirectoryState(volumeExpansion, requestedPage, descending, hideReadChapters, searchExpanded, query,
            scrollToDirectory, chapterToLocate)
    }
}

internal class DetailDirectoryState(
    volumeExpansion: MutableState<Map<String, Boolean>>,
    requestedPage: MutableState<Int?>,
    descending: MutableState<Boolean>,
    hideReadChapters: MutableState<Boolean>,
    searchExpanded: MutableState<Boolean>,
    query: MutableState<String>,
    scrollToDirectory: MutableState<Boolean>,
    chapterToLocate: MutableState<String?>,
) {
    private var volumeExpansion by volumeExpansion
    private var requestedPage by requestedPage
    var descending by descending
        private set
    var hideReadChapters by hideReadChapters
        private set
    var searchExpanded by searchExpanded
        private set
    var query by query
        private set
    var scrollToDirectory by scrollToDirectory
        private set
    var chapterToLocate by chapterToLocate
        private set
    val searching get() = query.isNotBlank()

    @Composable
    fun data(bookVolumes: BookVolumes?, progress: Map<String, Float>, currentChapterId: String?,
        selectingChapters: Boolean): DetailDirectoryData {
        val volumes = bookVolumes?.volumes.orEmpty()
        val readIds = remember(progress) { progress.filterValues { it >= 1f }.keys }
        val allChapters = remember(volumes) { directoryChapters(volumes) }
        val readingIndex = remember(allChapters, currentChapterId) { allChapters.indexOfFirst { it.chapter.id == currentChapterId } }
        val volumesById = remember(volumes) { volumes.associateBy { it.volumeId } }
        val chapterOrdinals = remember(allChapters) { allChapters.mapIndexed { index, entry -> entry.key to index + 1 }.toMap() }
        val readCounts = remember(volumes, readIds) { volumes.associate { volume ->
            volume.volumeId to volume.chapters.count { it.id in readIds }
        } }
        val pageCount = directoryPageCount(allChapters.size)
        val page = (requestedPage ?: directoryPageForChapter(allChapters.size, readingIndex, descending)).coerceIn(0, pageCount - 1)
        val hideRead = hideReadChapters && !selectingChapters
        val pageChapters = remember(allChapters, page, descending) { directoryPageChapters(allChapters, page, descending) }
        val pageVolumes = remember(pageChapters) { pageChapters.groupBy { it.volumeId } }
        val chapters = remember(pageChapters, hideRead, readIds) {
            pageChapters.filterNot { hideRead && it.chapter.id in readIds }
        }
        val visibleChapterKeys = chapters.filter {
            isVolumeExpanded(volumesById.getValue(it.volumeId), readCounts.getValue(it.volumeId), volumes.size)
        }.mapTo(mutableSetOf()) { it.key }
        val directoryItemKeys = buildList {
            pageVolumes.forEach { (volumeId, entries) ->
                if (volumesById.getValue(volumeId).volumeTitle.isNotBlank()) add("volume:$volumeId")
                entries.forEach { if (it.key in visibleChapterKeys) add(it.key) }
            }
        }
        val query = this.query
        val searching = searching
        val matches by key(bookVolumes, query) {
            produceState<List<DirectorySearchMatch>?>(null) {
                if (searching && bookVolumes != null) value = withContext(Dispatchers.Default) { searchDirectory(bookVolumes, query) }
            }
        }
        val results = remember(matches, hideRead, readIds, descending) {
            matches?.filterNot { hideRead && it.chapter.id in readIds }?.let { if (descending) it.asReversed() else it }
        }
        return DetailDirectoryData(volumes, readIds, allChapters, readingIndex, currentChapterId, volumesById,
            chapterOrdinals, readCounts, pageCount, page, hideRead, pageVolumes, chapters, visibleChapterKeys,
            directoryItemKeys, matches, results)
    }

    private fun isVolumeExpanded(volume: Volume, readCount: Int, volumeCount: Int): Boolean =
        volume.volumeTitle.isBlank() || (volumeExpansion[volume.volumeId]
            ?: (readCount < volume.chapters.size || volumeCount > 8))

    fun isVolumeExpanded(volumeId: String, data: DetailDirectoryData): Boolean =
        isVolumeExpanded(data.volumesById.getValue(volumeId), data.readCounts.getValue(volumeId), data.volumes.size)

    fun toggleVolume(volumeId: String, data: DetailDirectoryData) {
        volumeExpansion = volumeExpansion + (volumeId to !isVolumeExpanded(volumeId, data))
    }

    fun changePage(page: Int) {
        requestedPage = page
        scrollToDirectory = true
    }

    fun toggleOrder() {
        descending = !descending
        if (!searching) requestedPage = 0
        scrollToDirectory = true
    }

    fun toggleHideRead() { hideReadChapters = !hideReadChapters }
    fun showReadChapters() { hideReadChapters = false }
    fun openSearch() { searchExpanded = true }

    fun closeSearch() {
        query = ""
        searchExpanded = false
    }

    fun changeQuery(value: String) {
        query = value
        if (value.isNotBlank()) scrollToDirectory = true
    }

    fun locateCurrentChapter(data: DetailDirectoryData, onSearchClosed: () -> Unit) {
        closeSearch()
        onSearchClosed()
        requestedPage = directoryPageForChapter(data.allChapters.size, data.readingIndex, descending)
        hideReadChapters = false
        data.allChapters.getOrNull(data.readingIndex)?.let { volumeExpansion = volumeExpansion + (it.volumeId to true) }
        chapterToLocate = data.currentChapterId
        scrollToDirectory = true
    }

    fun consumeScrollRequest() {
        scrollToDirectory = false
        chapterToLocate = null
    }
}

/** Derived for the current inputs; only browsing intentions are saved. */
internal data class DetailDirectoryData(
    val volumes: List<Volume>,
    val readIds: Set<String>,
    val allChapters: List<DirectorySearchMatch>,
    val readingIndex: Int,
    val currentChapterId: String?,
    val volumesById: Map<String, Volume>,
    val chapterOrdinals: Map<String, Int>,
    val readCounts: Map<String, Int>,
    val pageCount: Int,
    val page: Int,
    val hideRead: Boolean,
    val pageVolumes: Map<String, List<DirectorySearchMatch>>,
    val chapters: List<DirectorySearchMatch>,
    val visibleChapterKeys: Set<String>,
    val directoryItemKeys: List<String>,
    val matches: List<DirectorySearchMatch>?,
    val results: List<DirectorySearchMatch>?,
)
