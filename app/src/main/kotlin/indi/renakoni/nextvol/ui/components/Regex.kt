package indi.renakoni.nextvol.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.TextUnit
import indi.renakoni.nextvol.ui.home.reading.stats.predefinedColors

private object RegexTheme {
    val parentheses = predefinedColors[2]
    val brackets = predefinedColors[4]
    val math = predefinedColors[0]
    val symbol = predefinedColors[1]
    val control = predefinedColors[2]
    val escape = predefinedColors[3]
}

fun regexAnnotatedString(regex: String): AnnotatedString =
    buildAnnotatedString {
        append(regex)
        var escaped = false
        var isInBrackets = false
        regex.forEachIndexed { index, char ->
            if (escaped) {
                val color = if (char.isLetter()) RegexTheme.control else RegexTheme.escape
                addStyle(SpanStyle(color = color), index - 1, index + 1)
                escaped = false
                return@forEachIndexed
            }
            val color = when (char) {
                '(', ')' -> RegexTheme.parentheses
                '[', ']', '{', '}', '.' -> RegexTheme.brackets
                '^', '-', '$', '|' -> RegexTheme.symbol
                '\\' -> {
                    escaped = true
                    null
                }
                '*', '+' -> RegexTheme.math
                in '0'..'9' -> if (isInBrackets) RegexTheme.math else null
                ',' -> if (isInBrackets) RegexTheme.parentheses else null
                else -> null
            }
            color?.let { addStyle(SpanStyle(color = it), index, index + 1) }
            if (char == '{') isInBrackets = true
            if (char == '}') isInBrackets = false
        }
    }

@Composable
fun RegexText(
    regex: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit) = {},
    style: TextStyle = LocalTextStyle.current
) {
    Text(
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        fontStyle = fontStyle,
        fontWeight = fontWeight,
        letterSpacing = letterSpacing,
        textDecoration = TextDecoration.None,
        textAlign = textAlign,
        lineHeight = lineHeight,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines,
        onTextLayout = onTextLayout,
        style = style,
        text = regexAnnotatedString(regex),
        fontFamily = FontFamily.Monospace
    )
}

@Preview(showBackground = true, backgroundColor = 0xFFFFFFFF, showSystemUi = false)
@Composable
private fun Preview() {
    Column {
        RegexText($$"\\\\[[abc][^abc][a-z][^a-z][a-zA-z]\\.a|b\\s\\S(?:.*)(...)a?a*a+a{3}a{3,6}^$aaaaadsa\\")
        Text($$"\\\\[[abc][^abc][a-z][^a-z][a-zA-z]\\.a|b\\s\\S(?:.*)(...)a?a*a+a{3}a{3,6}^$aaaaadsa\\")
    }
}
