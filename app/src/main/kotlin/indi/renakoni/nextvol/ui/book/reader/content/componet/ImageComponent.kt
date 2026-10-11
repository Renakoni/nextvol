package indi.renakoni.nextvol.ui.book.reader.content.componet

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponent
import io.nightfish.lightnovelreader.api.content.component.ImageComponentData

class ImageComponent(
    data: ImageComponentData
): AbstractContentComponent<ImageComponentData>(data) {
    override val id = ImageComponentData.id

    @Composable
    override fun Content(modifier: Modifier) {
        ReaderImageContent(data.uri, modifier)
    }
}
