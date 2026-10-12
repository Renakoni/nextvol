package indi.renakoni.nextvol.ui.book.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.LocalAppTheme

@Composable
internal fun ReaderPaperSelector(settings: ReaderSettingsEditor) = CompositionLocalProvider(
    LocalAppTheme provides (LocalReaderAppTheme.current ?: LocalAppTheme.current),
) {
    val selected = ReaderPaper.fromId(settings.paperId)
    val defaultSettings = remember(settings) { object : ReaderSettings by settings { override val paperId = ReaderPaper.Default.id } }
    val defaultBackground = readerBackgroundColor(defaultSettings)
    val defaultText = readerTextColor(defaultSettings)
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        val fontScale = LocalDensity.current.fontScale
        val columns = when {
            maxWidth < 280.dp || fontScale > 1.5f -> 1
            maxWidth >= 600.dp && fontScale <= 1.3f -> 4
            else -> 2
        }
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ReaderPaper.entries.chunked(columns).forEach { row ->
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { paper ->
                        val colors = paper.colors
                        val foreground = colors?.text ?: defaultText
                        val accent = colors?.accent ?: LocalAppTheme.current.colorScheme.primary
                        val isSelected = paper == selected
                        val shape = RoundedCornerShape(12.dp)
                        Surface(
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            shape = shape,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(if (isSelected) 2.dp else 1.dp,
                                if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                        ) {
                            Column(
                                Modifier.testTag("reader-paper-${paper.id}").selectable(selected = isSelected, role = Role.RadioButton,
                                    onClick = { settings.paperIdUserData.asynchronousSet(paper.id) })
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Box(Modifier.testTag("reader-paper-preview-${paper.id}").fillMaxWidth().height(128.dp * fontScale.coerceAtMost(1.5f))
                                    .clip(RoundedCornerShape(6.dp)).background(colors?.background ?: defaultBackground)) {
                                    if (paper == ReaderPaper.Default && defaultSettings.usesBackgroundImage) Image(
                                        painter = rememberReaderBackgroundPainter(defaultSettings), contentDescription = null,
                                        modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text(stringResource(R.string.reader_paper_preview_title), Modifier.weight(1f), color = foreground,
                                                style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Serif)
                                            Box(Modifier.size(18.dp)) {
                                                if (isSelected) Icon(painterResource(R.drawable.check_24px),
                                                    contentDescription = null, tint = accent)
                                            }
                                        }
                                        Text(stringResource(R.string.reader_paper_sample), color = foreground,
                                            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Serif)
                                        Box(Modifier.fillMaxWidth(0.35f).height(3.dp).background(accent, RoundedCornerShape(2.dp)))
                                    }
                                }
                                Text(stringResource(paper.label), style = MaterialTheme.typography.titleSmall)
                            }
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
