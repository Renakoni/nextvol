package indi.renakoni.nextvol.ui.home.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

interface ExportContext {
    val localBookCache: Boolean
    val bookshelf: Boolean
    val readingData: Boolean
    val settings: Boolean
    val bookmark: Boolean
}

class MutableExportContext: ExportContext {
    override var localBookCache by mutableStateOf(true)
    override var bookshelf by mutableStateOf(true)
    override var readingData by mutableStateOf(true)
    override var settings by mutableStateOf(true)
    override var bookmark by mutableStateOf(true)
}
