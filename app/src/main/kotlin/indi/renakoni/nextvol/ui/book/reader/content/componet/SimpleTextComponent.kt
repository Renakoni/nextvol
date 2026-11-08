package indi.renakoni.nextvol.ui.book.reader.content.componet

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import io.nightfish.lightnovelreader.api.content.component.AbstractDivisibleContentComponent
import io.nightfish.lightnovelreader.api.content.component.SimpleTextComponentData
import io.nightfish.lightnovelreader.api.userdata.UriUserData
import io.nightfish.lightnovelreader.api.userdata.UserDataRepositoryApi

// Host rendering and measurement live in the UI layer.
class SimpleTextComponent(
    data: SimpleTextComponentData,
    val userDataRepositoryApi: UserDataRepositoryApi,
    val context: Context
): AbstractDivisibleContentComponent<SimpleTextComponent, SimpleTextComponentData>(data) {
    private val pagination = TextPagination(userDataRepositoryApi, context)
    val fontSizeUserData get() = pagination.fontSizeUserData
    val fontLineHeightUserData get() = pagination.fontLineHeightUserData
    val fontWeightUserData get() = pagination.fontWeightUserData
    val fontFamilyUriUserData get() = pagination.fontFamilyUriUserData
    val textMeasurer get() = pagination.textMeasurer

    override val id = SimpleTextComponentData.id

    @Composable
    override fun Content(modifier: Modifier) {
        ReaderTextContent(data.text, fontFamilyUriUserData, modifier)
    }

    override suspend fun split(height: Int, width: Int): List<SimpleTextComponent> =
        pagination.split(data.text, height, width)
            .map { SimpleTextComponent(SimpleTextComponentData(it), userDataRepositoryApi, context) }

    suspend fun readerFontFamily(fontFamilyUriUserData: UriUserData): FontFamily? =
        pagination.readerFontFamily(fontFamilyUriUserData)

    fun TextLayoutResult.getSlipString(text: String, width: Int, height: Int): List<String> =
        pageText(text, width, height)
}
