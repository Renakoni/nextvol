package indi.renakoni.nextvol.ui.book.reader.content.componet

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import indi.renakoni.nextvol.data.content.component.ErrorContentComponentData
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponent

class ErrorContentComponent(data: ErrorContentComponentData) :
    AbstractContentComponent<ErrorContentComponentData>(data) {
    override val id = ErrorContentComponentData.id

    @Composable
    override fun Content(modifier: Modifier) {
        ReaderErrorContent(data.message)
    }

    companion object {
        fun of(message: String) = ErrorContentComponent(
            ErrorContentComponentData(message)
        )
    }
}
