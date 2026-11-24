package indi.renakoni.nextvol.ui.book.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.ui.LocalAppTheme
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleClip

@Composable
internal fun ReaderPaperSelector(settings: ReaderSettingsEditor) = CompositionLocalProvider(
    LocalAppTheme provides (LocalReaderAppTheme.current ?: LocalAppTheme.current),
) {
    val tokens = NextVolDesign.tokens
    val scheme = MaterialTheme.colorScheme
    val selected = ReaderPaper.fromId(settings.paperId)
    val defaultSettings = remember(settings) {
        object : ReaderSettings by settings {
            override val paperId = ReaderPaper.Default.id
        }
    }
    val defaultBackground = readerBackgroundColor(defaultSettings)
    val defaultText = readerTextColor(defaultSettings)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        val fontScale = LocalDensity.current.fontScale
        val columns = when {
            maxWidth < 280.dp || fontScale > 1.5f -> 1
            maxWidth >= 600.dp && fontScale <= 1.3f -> 4
            else -> 2
        }
        Column(
            modifier = Modifier.selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ReaderPaper.entries.chunked(columns).forEach { row ->
                Row(
                    modifier = Modifier.height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    row.forEach { paper ->
                        val colors = paper.colors
                        val pageBackground = colors?.background ?: defaultBackground
                        val foreground = colors?.text ?: defaultText
                        val accent = colors?.accent ?: LocalAppTheme.current.colorScheme.primary
                        val isSelected = paper == selected
                        val cardRadius = 18.dp
                        val previewShape = RoundedCornerShape(11.dp)

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .squircleBackground(
                                    color = if (isSelected) tokens.activePillFill else tokens.cardSurface,
                                    cornerRadius = cardRadius,
                                )
                                .squircleBorder(
                                    width = if (isSelected) 1.5.dp else 0.75.dp,
                                    color = if (isSelected) scheme.primary else tokens.cardBorder,
                                    cornerRadius = cardRadius,
                                )
                                .squircleClip(cardRadius)
                                .testTag("reader-paper-${paper.id}")
                                .selectable(
                                    selected = isSelected,
                                    role = Role.RadioButton,
                                    onClick = { settings.paperIdUserData.asynchronousSet(paper.id) },
                                )
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .testTag("reader-paper-preview-${paper.id}")
                                    .fillMaxWidth()
                                    .height(124.dp * fontScale.coerceAtMost(1.5f))
                                    .clip(previewShape)
                                    .background(pageBackground)
                                    .border(0.75.dp, foreground.copy(alpha = 0.14f), previewShape),
                            ) {
                                if (paper == ReaderPaper.Default && defaultSettings.usesBackgroundImage) {
                                    Image(
                                        painter = rememberReaderBackgroundPainter(defaultSettings),
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop,
                                    )
                                }
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text(
                                            text = stringResource(R.string.reader_paper_preview_title),
                                            modifier = Modifier.weight(1f),
                                            color = foreground,
                                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.W600),
                                            fontFamily = FontFamily.Serif,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (isSelected) {
                                            Box(
                                                modifier = Modifier
                                                    .size(20.dp)
                                                    .clip(CircleShape)
                                                    .background(accent),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Icon(
                                                    painter = painterResource(R.drawable.check_24px),
                                                    contentDescription = null,
                                                    tint = pageBackground,
                                                    modifier = Modifier.size(13.dp),
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        text = stringResource(R.string.reader_paper_sample),
                                        color = foreground.copy(alpha = 0.88f),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Serif,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(0.36f)
                                            .height(3.dp)
                                            .clip(CircleShape)
                                            .background(accent),
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp, vertical = 1.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = stringResource(paper.label),
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontWeight = if (isSelected) FontWeight.W700 else FontWeight.W500,
                                    ),
                                    color = if (isSelected) scheme.primary else scheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(accent),
                                )
                            }
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
