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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
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
import io.nightfish.lightnovelreader.api.ui.components.SettingsSwitchEntry
import io.nightfish.lightnovelreader.api.userdata.FloatUserData
import kotlin.math.roundToInt

@Composable
internal fun ReaderLayoutSettingsPage(settings: ReaderSettingsEditor) {
    val stored = ReaderLayoutSettings.from(settings)
    var draft by remember(stored) { mutableStateOf(stored) }
    val previewSettings = object : ReaderSettingsEditor by settings {
        override val fontSize = draft.fontSize
        override val fontWeigh = draft.fontWeight
        override val fontLineHeight = draft.lineSpacing
        override val paragraphSpacing = draft.paragraphSpacing
        override val autoPadding = draft.autoPadding
        override val topPadding = draft.top
        override val bottomPadding = draft.bottom
        override val leftPadding = draft.start
        override val rightPadding = draft.end
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val previewHeight = minOf(148.dp, maxHeight * 0.3f)
            Column(Modifier.fillMaxSize()) {
                // Keep the sample visible while the independent controls scroll beneath it.
                Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                    Text(stringResource(R.string.reader_layout_preview),
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ReaderLayoutPreview(previewSettings, Modifier.fillMaxWidth().height(previewHeight))
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    SettingsCategory { ReaderFontEntry(settings) }
                    SettingsCategory(title = stringResource(R.string.reader_layout_typography)) {
                        ReaderTypographyControls(settings, draft) { draft = it }
                    }
                    SettingsCategory(title = stringResource(R.string.margin_settings)) {
                        SettingsSwitchEntry(
                            title = stringResource(R.string.settings_reader_auto_margin),
                            description = stringResource(R.string.settings_reader_auto_margin_desc),
                            checked = draft.autoPadding, booleanUserData = settings.autoPaddingUserData,
                        )
                        if (!draft.autoPadding) {
                            ReaderLayoutSlider(R.string.settings_reader_top_margin, draft.top, ReaderLayoutValues.marginRange, "dp", settings.topPaddingUserData) { draft = draft.copy(top = it) }
                            ReaderLayoutSlider(R.string.settings_reader_bottom_margin, draft.bottom, ReaderLayoutValues.marginRange, "dp", settings.bottomPaddingUserData) { draft = draft.copy(bottom = it) }
                            ReaderLayoutSlider(R.string.settings_reader_left_margin, draft.start, ReaderLayoutValues.marginRange, "dp", settings.leftPaddingUserData) { draft = draft.copy(start = it) }
                            ReaderLayoutSlider(R.string.settings_reader_right_margin, draft.end, ReaderLayoutValues.marginRange, "dp", settings.rightPaddingUserData) { draft = draft.copy(end = it) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ReaderTypographyControls(settings: ReaderSettingsEditor, layout: ReaderLayoutSettings, onChange: (ReaderLayoutSettings) -> Unit) {
    ReaderLayoutSlider(R.string.settings_reader_font_size, layout.fontSize, ReaderLayoutValues.fontSizeRange, "sp", settings.fontSizeUserData, icon = R.drawable.format_size_24px) { onChange(layout.copy(fontSize = it)) }
    ReaderLayoutSlider(R.string.settings_theme_text_font_weight, layout.fontWeight, ReaderLayoutValues.fontWeightRange, "", settings.fontWeighUserData, step = 100f, icon = R.drawable.format_bold_24px) { onChange(layout.copy(fontWeight = it)) }
    ReaderLayoutSlider(R.string.settings_reader_line_spacing, layout.lineSpacing, ReaderLayoutValues.lineSpacingRange, "sp", settings.fontLineHeightUserData, icon = R.drawable.format_line_spacing_24px) { onChange(layout.copy(lineSpacing = it)) }
    ReaderLayoutSlider(R.string.reader_paragraph_spacing, layout.paragraphSpacing, ReaderLayoutValues.paragraphSpacingRange, "sp", settings.paragraphSpacingUserData, icon = R.drawable.format_paragraph_spacing_24px) { onChange(layout.copy(paragraphSpacing = it)) }
}

@Composable
private fun ReaderLayoutSlider(
    title: Int, value: Float, range: ClosedFloatingPointRange<Float>, unit: String,
    userData: FloatUserData, step: Float = 0.5f, icon: Int? = null, onChange: (Float) -> Unit,
) {
    val label = stringResource(title)
    // Accessibility can change and finish the value before Compose gets another frame.
    var pendingValue by remember(value) { mutableFloatStateOf(value) }
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh)
        .padding(horizontal = 22.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
        if (icon != null) Icon(painterResource(icon), contentDescription = null,
            modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Text("${if (value % 1f == 0f) value.toInt().toString() else value.toString()} $unit",
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            }
            Slider(
                modifier = Modifier.semantics { contentDescription = label },
                value = value,
                onValueChange = {
                    pendingValue = ((it / step).roundToInt() * step).coerceIn(range)
                    onChange(pendingValue)
                },
                onValueChangeFinished = { userData.asynchronousSet(pendingValue) },
                valueRange = range,
            )
        }
    }
}

@Composable
internal fun ReaderLayoutPreview(settings: ReaderSettingsEditor, modifier: Modifier = Modifier) = ReaderPaperTheme(settings) {
    val layout = rememberReaderTextLayout(settings)
    val density = LocalDensity.current
    val paragraphSpacing = with(density) { layout.settings.paragraphSpacing.sp.toDp() }
    val windowSize = LocalWindowInfo.current.containerSize
    val direction = LocalLayoutDirection.current
    val indicatorHeight = if (settings.enableTimeIndicator || settings.enableReadingChapterProgressIndicator ||
        settings.enableChapterTitleIndicator) 40.dp else 0.dp
    val pagePadding = readerPadding(layout.settings, indicatorHeight)
    BoxWithConstraints(modifier.clip(RoundedCornerShape(16.dp)).background(readerBackgroundColor(settings))) {
        // Preserve the fraction of each page edge occupied by margins, system bars and the footer.
        // Text stays at the chosen size and scrolls inside that viewport, even at maximum margins.
        val widthScale = (constraints.maxWidth.toFloat() / windowSize.width.coerceAtLeast(1)).coerceAtMost(1f)
        val heightScale = (constraints.maxHeight.toFloat() / windowSize.height.coerceAtLeast(1)).coerceAtMost(1f)
        val previewPadding = PaddingValues(
            start = pagePadding.calculateStartPadding(direction) * widthScale,
            end = pagePadding.calculateEndPadding(direction) * widthScale,
            top = pagePadding.calculateTopPadding() * heightScale,
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
