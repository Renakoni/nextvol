package indi.renakoni.nextvol.ui.book.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import io.nightfish.lightnovelreader.api.userdata.FloatUserData
import kotlin.math.roundToInt

private enum class TypeSetting(
    val title: Int, val range: ClosedFloatingPointRange<Float>, val tickSpacing: Int,
    val value: (ReaderLayoutSettings) -> Float, val preference: (ReaderSettingsEditor) -> FloatUserData,
    val edit: ReaderLayoutSettings.(Float) -> ReaderLayoutSettings,
) {
    Size(R.string.settings_reader_font_size, ReaderLayoutValues.fontSizeRange, 4,
        ReaderLayoutSettings::fontSize, ReaderSettingsEditor::fontSizeUserData, { copy(fontSize = it) }),
    Weight(R.string.settings_theme_text_font_weight, ReaderLayoutValues.fontWeightRange, 100,
        ReaderLayoutSettings::fontWeight, ReaderSettingsEditor::fontWeighUserData, { copy(fontWeight = it) }),
    Line(R.string.settings_reader_line_spacing, ReaderLayoutValues.lineSpacingRange, 2,
        ReaderLayoutSettings::lineSpacing, ReaderSettingsEditor::fontLineHeightUserData, { copy(lineSpacing = it) }),
    Paragraph(R.string.reader_paragraph_spacing, ReaderLayoutValues.paragraphSpacingRange, 4,
        ReaderLayoutSettings::paragraphSpacing, ReaderSettingsEditor::paragraphSpacingUserData, { copy(paragraphSpacing = it) });

    val step get() = if (this == Weight) 100f else 0.5f
}

@Composable
internal fun ReaderTypographyControls(settings: ReaderSettingsEditor, layout: ReaderLayoutSettings, onChange: (ReaderLayoutSettings) -> Unit) {
    var selected by rememberSaveable { mutableStateOf(TypeSetting.Size) }
    val active = selected
    var pending by remember(active) { mutableStateOf<Float?>(null) }
    fun finish() {
        pending?.let { active.preference(settings).asynchronousSet(it) }
        pending = null
    }

    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().background(colors.surfaceContainer).padding(horizontal = 12.dp, vertical = 8.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().selectableGroup()) {
            val columns = if (maxWidth >= 248.dp * LocalDensity.current.fontScale) 4 else 2
            Column {
                TypeSetting.entries.chunked(columns).forEach { row ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                        for (item in row) {
                            TypographyTab(item, item.value(layout), item == active, Modifier.weight(1f)) {
                                if (item != active) {
                                    finish()
                                    selected = item
                                }
                            }
                        }
                    }
                }
            }
        }
        key(active) {
            TypographyRuler(active, active.value(layout), onChange = { value ->
                pending = value
                onChange(active.edit(layout, value))
            }, onFinish = ::finish)
        }
    }
}

private fun Float.readout() = if (this % 1f == 0f) toInt().toString() else toString()

@Composable
private fun TypographyTab(item: TypeSetting, value: Float, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val ink = if (selected) colors.onSurface else colors.onSurfaceVariant
    Column(
        modifier.selectable(selected, role = Role.Tab, onClick = onClick).heightIn(min = 52.dp).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(item.title), style = type.labelMedium, textAlign = TextAlign.Center, color = ink)
        Text(buildAnnotatedString {
            append(value.readout())
            if (item != TypeSetting.Weight) withStyle(SpanStyle(fontSize = type.labelSmall.fontSize)) { append(" sp") }
        }, style = type.titleMedium.copy(fontFeatureSettings = "tnum"), color = ink)
        Spacer(Modifier.padding(top = 3.dp).size(16.dp, 2.dp).background(if (selected) colors.primary else Color.Transparent, CircleShape))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TypographyRuler(setting: TypeSetting, value: Float, onChange: (Float) -> Unit, onFinish: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val label = stringResource(setting.title)
    val interaction = remember { MutableInteractionSource() }
    val range = setting.range
    val span = range.endInclusive - range.start
    val intervals = (span / setting.tickSpacing).roundToInt()
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(range.start.toInt().toString(), Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant)
        Slider(
            value = value, onValueChange = { onChange((it / setting.step).roundToInt() * setting.step) },
            valueRange = range, steps = (span / setting.step).roundToInt() - 1,
            onValueChangeFinished = onFinish, interactionSource = interaction,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics {
                contentDescription = label
                stateDescription = if (setting == TypeSetting.Weight) value.readout() else "${value.readout()} sp"
            },
            thumb = {
                Canvas(Modifier.size(12.dp, 20.dp).indication(interaction, ripple(bounded = false, radius = 16.dp))) {
                    val tip = center.y - 1.dp.toPx()
                    val triangle = Path().apply {
                        moveTo(center.x - 5.dp.toPx(), tip - 6.dp.toPx())
                        lineTo(center.x + 5.dp.toPx(), tip - 6.dp.toPx())
                        lineTo(center.x, tip)
                        close()
                    }
                    drawPath(triangle, colors.primary)
                }
            },
            track = {
                Canvas(Modifier.fillMaxWidth().height(20.dp)) {
                    val y = center.y
                    drawLine(colors.outlineVariant, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                    for (i in 0..intervals) {
                        val x = size.width * i / intervals
                        val major = i % 2 == 0
                        val height = if (major) 6.dp else 3.dp
                        drawLine(if (major) colors.outline else colors.outlineVariant, Offset(x, y),
                            Offset(x, y + height.toPx()), 1.dp.toPx())
                    }
                }
            },
        )
        Text(range.endInclusive.toInt().toString(), Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant)
    }
}
