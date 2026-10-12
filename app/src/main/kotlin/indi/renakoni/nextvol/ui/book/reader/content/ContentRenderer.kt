package indi.renakoni.nextvol.ui.book.reader.content

import indi.renakoni.nextvol.data.content.ContentJsonDecoder
import indi.renakoni.nextvol.ui.book.reader.content.componet.ErrorContentComponent
import io.nightfish.lightnovelreader.api.content.ContentData
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/** Reader composition root for JSON decoding, built-in construction and visible error components. */
@Singleton
class ContentRenderer @Inject constructor(
    private val decoder: ContentJsonDecoder,
    private val factory: ContentComponentFactory,
) {
    fun getContentDataFromJson(content: JsonObject): ContentData = ContentData(
        decoder.decodeComponents(content, factory::create, ErrorContentComponent::of),
    )
}
