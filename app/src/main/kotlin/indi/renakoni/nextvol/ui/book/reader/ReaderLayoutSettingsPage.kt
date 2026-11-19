package indi.renakoni.nextvol.ui.book.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.home.settings.SettingsCategory
import indi.renakoni.nextvol.ui.components.SettingsSwitchEntry
import io.nightfish.lightnovelreader.api.userdata.FloatUserData
import kotlin.math.roundToInt

@Composable
internal fun ReaderLayoutSettingsPage(settings: ReaderSettingsEditor) {
    val draft = ReaderLayoutDraft.rememberDraft(settings)
    val layout = draft.layout
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val previewHeight = minOf(148.dp, maxHeight * 0.3f)
            Column(Modifier.fillMaxSize()) {
                // Keep the sample visible while the independent controls scroll beneath it.
                Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                    Text(stringResource(R.string.reader_layout_preview),
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ReaderLayoutPreview(settings, Modifier.fillMaxWidth().height(previewHeight), layout)
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    SettingsCategory { ReaderFontEntry(settings) }
                    SettingsCategory(title = stringResource(R.string.reader_layout_typography)) {
                        ReaderTypographyControls(settings, draft)
                    }
                    SettingsCategory(title = stringResource(R.string.margin_settings)) {
                        SettingsSwitchEntry(
                            title = stringResource(R.string.settings_reader_auto_margin),
                            description = stringResource(R.string.settings_reader_auto_margin_desc),
                            checked = layout.autoPadding, booleanUserData = settings.autoPaddingUserData,
                        )
                        if (!layout.autoPadding) {
                            ReaderMarginSlider(R.string.settings_reader_top_margin, layout.top, settings.topPaddingUserData, draft) { copy(top = it) }
                            ReaderMarginSlider(R.string.settings_reader_bottom_margin, layout.bottom, settings.bottomPaddingUserData, draft) { copy(bottom = it) }
                            ReaderMarginSlider(R.string.settings_reader_left_margin, layout.start, settings.leftPaddingUserData, draft) { copy(start = it) }
                            ReaderMarginSlider(R.string.settings_reader_right_margin, layout.end, settings.rightPaddingUserData, draft) { copy(end = it) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderMarginSlider(
    title: Int, value: Float, field: FloatUserData, draft: ReaderLayoutDraft,
    edit: ReaderLayoutSettings.(Float) -> ReaderLayoutSettings,
) {
    val label = stringResource(title)
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh)
        .padding(horizontal = 22.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text("${value.readerValueText()} dp",
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
        Slider(
            modifier = Modifier.semantics { contentDescription = label },
            value = value,
            onValueChange = { draft.move(field, (it * 2).roundToInt() / 2f, edit) },
            onValueChangeFinished = { draft.finish(field) },
            valueRange = ReaderLayoutValues.marginRange,
        )
    }
}

@Composable
internal fun ReaderLayoutPreview(
    settings: ReaderSettings, modifier: Modifier = Modifier, preview: ReaderLayoutSettings = ReaderLayoutSettings.from(settings),
) = ReaderPaperTheme(settings) {
    val layout = rememberReaderTextLayout(preview)
    val density = LocalDensity.current
    val paragraphSpacing = with(density) { layout.settings.paragraphSpacing.sp.toDp() }
    val windowSize = LocalWindowInfo.current.containerSize
    val direction = LocalLayoutDirection.current
    val indicatorHeight = if (settings.enableTimeIndicator || settings.enableReadingChapterProgressIndicator ||
        settings.enableChapterTitleIndicator) 40.dp else 0.dp
    val pagePadding = readerPadding(layout.settings, indicatorHeight)
    BoxWithConstraints(modifier.clip(RoundedCornerShape(16.dp)).background(readerBackgroundColor(settings))) {
        // Leave breathing room above the frame, then scale the reading margins into the remaining height.
        // Text stays at the chosen size and scrolls inside that viewport, even at maximum margins.
        val topInset = 6.dp
        val widthScale = (constraints.maxWidth.toFloat() / windowSize.width.coerceAtLeast(1)).coerceAtMost(1f)
        val heightScale = ((constraints.maxHeight - with(density) { topInset.toPx() }).coerceAtLeast(0f) /
            windowSize.height.coerceAtLeast(1)).coerceAtMost(1f)
        val previewPadding = PaddingValues(
            start = pagePadding.calculateStartPadding(direction) * widthScale,
            end = pagePadding.calculateEndPadding(direction) * widthScale,
            top = topInset + pagePadding.calculateTopPadding() * heightScale,
            bottom = pagePadding.calculateBottomPadding() * heightScale,
        )
        if (settings.usesBackgroundImage) Image(
            painter = rememberReaderBackgroundPainter(settings), contentDescription = null,
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
        )
        Column(
            Modifier.fillMaxSize().padding(previewPadding)
                .border(1.dp, readerTextColor(settings).copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(paragraphSpacing, Alignment.CenterVertically),
        ) {
            stringResource(R.string.reader_welcome_preview).split('\n').forEach { line ->
                Text(line, style = layout.style.copy(textAlign = TextAlign.Center), color = readerTextColor(settings))
            }
        }
    }
}
