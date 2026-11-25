package indi.renakoni.nextvol.ui.book.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.ui.components.SettingsSwitchEntry
import indi.renakoni.nextvol.ui.components.SliderCommitState
import indi.renakoni.nextvol.ui.home.settings.SettingsCategory
import io.nightfish.lightnovelreader.api.userdata.FloatUserData
import top.yukonga.miuix.kmp.basic.Slider as MiuixSlider
import kotlin.math.roundToInt

@Composable
internal fun ReaderLayoutSettingsPage(settings: ReaderSettingsEditor) {
    val tokens = NextVolDesign.tokens
    val draft = ReaderLayoutDraft.rememberDraft(settings)
    val layout = draft.layout
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val previewHeight = minOf(144.dp, maxHeight * 0.28f)
        Column(Modifier.fillMaxSize()) {
            // Keep the live sample visible while the independent layout controls scroll beneath it.
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(R.string.reader_layout_preview),
                    modifier = Modifier.padding(horizontal = 4.dp),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.W600),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ReaderLayoutPreview(
                    settings = settings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(previewHeight)
                        .border(0.75.dp, tokens.cardBorder, RoundedCornerShape(16.dp)),
                    preview = layout,
                    showMarginGuide = true,
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                SettingsCategory { ReaderFontEntry(settings) }
                SettingsCategory(title = stringResource(R.string.reader_layout_typography)) {
                    ReaderTypographyControls(settings, draft)
                }
                SettingsCategory(title = stringResource(R.string.reader_page_layout_title)) {
                    ReaderPageLayoutEntry(settings)
                }
                SettingsCategory(title = stringResource(R.string.margin_settings)) {
                    SettingsSwitchEntry(
                        title = stringResource(R.string.settings_reader_auto_margin),
                        description = stringResource(R.string.settings_reader_auto_margin_desc),
                        checked = layout.autoPadding,
                        booleanUserData = settings.autoPaddingUserData,
                    )
                    if (!layout.autoPadding) {
                        ReaderMarginSlider(R.string.settings_reader_top_margin, layout.top, settings.topPaddingUserData, draft) { copy(top = it) }
                        ReaderMarginSlider(R.string.settings_reader_bottom_margin, layout.bottom, settings.bottomPaddingUserData, draft) { copy(bottom = it) }
                        ReaderMarginSlider(R.string.settings_reader_left_margin, layout.start, settings.leftPaddingUserData, draft) { copy(start = it) }
                        ReaderMarginSlider(R.string.settings_reader_right_margin, layout.end, settings.rightPaddingUserData, draft) { copy(end = it) }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun ReaderMarginSlider(
    title: Int,
    value: Float,
    field: FloatUserData,
    draft: ReaderLayoutDraft,
    edit: ReaderLayoutSettings.(Float) -> ReaderLayoutSettings,
) {
    val tokens = NextVolDesign.tokens
    val colors = MaterialTheme.colorScheme
    val label = stringResource(title)
    val commitState = remember { SliderCommitState() }
    val currentFinish by rememberUpdatedState { draft.finish(field) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(tokens.cardSurface)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurface,
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(tokens.activePillFill)
                    .padding(horizontal = 9.dp, vertical = 3.dp),
            ) {
                Text(
                    text = "${value.readerValueText()} dp",
                    color = colors.primary,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.W600,
                        fontFeatureSettings = "tnum",
                    ),
                )
            }
        }
        MiuixSlider(
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = label }
                .pointerInput(commitState) {
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitPointerEvent(PointerEventPass.Initial)
                            if (down.changes.any { it.pressed }) {
                                commitState.onPointerDown()
                                while (true) {
                                    val next = awaitPointerEvent(PointerEventPass.Final)
                                    if (next.changes.none { it.pressed }) break
                                }
                                commitState.onPointerReleased()?.let { currentFinish() }
                            }
                        }
                    }
                },
            value = value,
            onValueChange = { raw ->
                val snapped = (raw * 2).roundToInt() / 2f
                draft.move(field, snapped, edit)
                if (commitState.onValueChanged(snapped) != null) {
                    currentFinish()
                }
            },
            onValueChangeFinished = {
                if (commitState.onValueChangeFinished() != null) {
                    currentFinish()
                }
            },
            valueRange = ReaderLayoutValues.marginRange,
        )
    }
}

@Composable
internal fun ReaderLayoutPreview(
    settings: ReaderSettings,
    modifier: Modifier = Modifier,
    preview: ReaderLayoutSettings = ReaderLayoutSettings.from(settings),
    showMarginGuide: Boolean = false,
) = ReaderPaperTheme(settings) {
    val layout = rememberReaderTextLayout(preview)
    val textColor = readerTextColor(settings)
    val density = LocalDensity.current
    val paragraphSpacing = with(density) { layout.settings.paragraphSpacing.sp.toDp() }
    val windowSize = LocalWindowInfo.current.containerSize
    val direction = LocalLayoutDirection.current
    val indicatorHeight = if (settings.enableTimeIndicator || settings.enableReadingChapterProgressIndicator ||
        settings.enableChapterTitleIndicator
    ) 36.dp else 0.dp
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
        if (settings.usesBackgroundImage) {
            Image(
                painter = rememberReaderBackgroundPainter(settings),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(previewPadding)
                .then(
                    if (showMarginGuide) Modifier.border(1.dp, textColor.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                    else Modifier
                )
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(paragraphSpacing, Alignment.CenterVertically),
        ) {
            stringResource(R.string.reader_welcome_preview).split('\n').forEach { line ->
                Text(line, style = layout.style.copy(textAlign = TextAlign.Center), color = textColor)
            }
        }
    }
}
