package indi.renakoni.nextvol.ui.book.reader.content.componet

import android.content.Context
import android.net.Uri
import android.util.DisplayMetrics
import android.view.View
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import indi.renakoni.nextvol.ui.book.reader.loadReaderFontFamilySafe
import io.nightfish.lightnovelreader.api.ui.theme.AppTypography
import io.nightfish.lightnovelreader.api.userdata.UriUserData
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import io.nightfish.lightnovelreader.api.userdata.UserDataRepositoryApi

/** Host font measurement and page slicing. Each component keeps its own measurer as before. */
internal class TextPagination(userDataRepositoryApi: UserDataRepositoryApi, context: Context) {
    val fontSizeUserData = userDataRepositoryApi.floatUserData(UserDataPath.Reader.FontSize.path)
    val fontLineHeightUserData = userDataRepositoryApi.floatUserData(UserDataPath.Reader.FontLineHeight.path)
    val fontWeightUserData = userDataRepositoryApi.floatUserData(UserDataPath.Reader.FontWeigh.path)
    val fontFamilyUriUserData = userDataRepositoryApi.uriUserData(UserDataPath.Reader.FontFamilyUri.path)
    val textMeasurer = TextMeasurer(
        createFontFamilyResolver(context),
        Density(
            context.resources.configuration.densityDpi.toFloat() / DisplayMetrics.DENSITY_DEFAULT,
            context.resources.configuration.fontScale,
        ),
        if (context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_LTR) {
            LayoutDirection.Ltr
        } else {
            LayoutDirection.Rtl
        }
    )

    suspend fun split(text: String, height: Int, width: Int): List<String> {
        val safeHeight = height.coerceAtLeast(1)
        val safeWidth = width.coerceAtLeast(1)
        val fontSize = fontSizeUserData.getOrDefault(15f)
        val fontLineHeight = fontLineHeightUserData.getOrDefault(7f)
        val fontWeigh = fontWeightUserData.getOrDefault(500f)
        return textMeasurer.measure(
            text = text,
            style = AppTypography.bodyMedium.copy(
                fontSize = fontSize.sp,
                lineHeight = (fontLineHeight + fontSize).sp,
                fontWeight = FontWeight(fontWeigh.toInt()),
                fontFamily = readerFontFamily(fontFamilyUriUserData),
            ),
            constraints = Constraints(maxHeight = safeHeight, maxWidth = safeWidth),
        ).pageText(text, safeWidth, safeHeight)
    }

    suspend fun readerFontFamily(fontFamilyUriUserData: UriUserData): FontFamily? {
        val uri = fontFamilyUriUserData.getOrDefault(Uri.EMPTY)
        val fontFamily = loadReaderFontFamilySafe(uri)
        return fontFamily
    }
}

internal fun TextLayoutResult.pageText(text: String, width: Int, height: Int): List<String> {
    val safeWidth = width.coerceAtLeast(1)
    val safeHeight = height.coerceAtLeast(1)
    val result: MutableList<String> = mutableListOf()
    var lastLine = 0
    fun getNotOverflowText(startLine: Int): String {
        fun getNotOverflowLine(): Int {
            val startHeight = getLineTop(startLine)
            fun isLineOverflow(line: Int): Boolean =
                getLineBottom(line) > safeHeight + startHeight

            var checkLine = getLineForOffset(
                getOffsetForPosition(
                    Offset(
                        safeWidth.toFloat(),
                        startHeight + safeHeight
                    )
                )
            ).coerceIn(startLine, lineCount - 1)
            while (checkLine > startLine && isLineOverflow(checkLine)) checkLine--
            return checkLine
        }

        val startTextOffset = getLineStart(startLine)
        lastLine = getNotOverflowLine()
        val endTextOffset = getLineEnd(lastLine)
        lastLine++
        return text.slice(startTextOffset..<endTextOffset)
    }
    while (lastLine < this.lineCount) {
        getNotOverflowText(lastLine).let(result::add)
    }
    return result.mapIndexedNotNull { index, string ->
        when (index) {
            0 if string.isBlank() -> null
            result.size - 1 if string.isBlank() -> null
            else -> string
        }
    }
}
