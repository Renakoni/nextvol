@file:Suppress("AssignedValueIsNeverRead")

package indi.renakoni.nextvol.ui.components

import android.content.Intent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.ui.components.design.EditorialIconBadge
import indi.renakoni.nextvol.ui.components.design.EditorialPopupMenu
import indi.renakoni.nextvol.ui.components.design.EditorialPopupMenuItem
import indi.renakoni.nextvol.ui.home.settings.navigateToSliderValueDialog
import io.nightfish.lightnovelreader.api.ui.LocalNavController
import io.nightfish.lightnovelreader.api.userdata.BooleanUserData
import io.nightfish.lightnovelreader.api.userdata.FloatUserData
import io.nightfish.lightnovelreader.api.userdata.StringUserData
import top.yukonga.miuix.kmp.basic.Slider as MiuixSlider
import top.yukonga.miuix.kmp.basic.Switch as MiuixSwitch
import java.text.DecimalFormat
import kotlin.math.abs
import kotlin.math.roundToInt

internal fun resolveDiscreteStepIndex(sortedSteps: List<Float>, value: Float): Int {
    if (sortedSteps.isEmpty()) return 0
    val exact = sortedSteps.indexOfFirst { it == value }
    if (exact >= 0) return exact
    if (value <= sortedSteps.first()) return 0
    if (value >= sortedSteps.last()) return sortedSteps.lastIndex
    var nearestIndex = 0
    var minDelta = abs(sortedSteps[0] - value)
    for (index in 1..sortedSteps.lastIndex) {
        val delta = abs(sortedSteps[index] - value)
        if (delta < minDelta) {
            minDelta = delta
            nearestIndex = index
        }
    }
    return nearestIndex
}

/**
 * Coordinates deferred persistence during pointer drags while ensuring non-pointer updates
 * (such as accessibility setProgress in Miuix Slider) persist immediately without double writes.
 */
internal class SliderCommitState {
    var isPointerInteracting: Boolean = false
        private set
    var pendingValue: Float? = null
        private set

    fun onPointerDown() {
        isPointerInteracting = true
    }

    fun onValueChanged(value: Float): Float? {
        return if (isPointerInteracting) {
            pendingValue = value
            null
        } else {
            pendingValue = null
            value
        }
    }

    fun onValueChangeFinished(): Float? {
        val committed = pendingValue
        pendingValue = null
        return committed
    }

    fun onPointerReleased(): Float? {
        isPointerInteracting = false
        val committed = pendingValue
        pendingValue = null
        return committed
    }
}

@Composable
fun SettingsSwitchEntry(
    modifier: Modifier = Modifier,
    painter: Painter? = null,
    title: String,
    description: String,
    checked: Boolean,
    booleanUserData: BooleanUserData,
    disabled: Boolean = false
) {
    SettingsSwitchEntry(
        modifier = modifier,
        painter = painter,
        title = title,
        description = description,
        checked = checked,
        onCheckedChange = booleanUserData::asynchronousSet,
        disabled = disabled
    )
}

@Composable
fun SettingsSwitchEntry(
    modifier: Modifier = Modifier,
    painter: Painter? = null,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    disabled: Boolean = false
) {
    val tokens = NextVolDesign.tokens
    Row(
        modifier = Modifier
            .background(tokens.cardSurface)
            .then(modifier)
            .fillMaxWidth()
            .clickable(enabled = !disabled) { onCheckedChange(!checked) }
            .alpha(if (disabled) 0.45f else 1f)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        painter?.let {
            EditorialIconBadge(painter = it)
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                color = colorScheme.onSurface,
                style = typography.headlineSmall,
            )
            if (description.isNotBlank()) {
                Text(
                    text = description,
                    color = colorScheme.onSurfaceVariant,
                    style = typography.bodyMedium,
                )
            }
        }

        MiuixSwitch(
            checked = checked,
            onCheckedChange = { if (!disabled) onCheckedChange(it) },
            enabled = !disabled,
        )
    }
}

@Composable
fun SettingsSliderEntry(
    modifier: Modifier = Modifier,
    painter: Painter? = null,
    title: String,
    unit: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    valueFormat: (Float) -> Float = { (it * 2).roundToInt().toFloat() / 2 },
    floatUserData: FloatUserData,
    steps: List<Float>? = null
) {
    val navController = LocalNavController.current
    var tempValue by remember { mutableFloatStateOf(value) }
    LaunchedEffect(value) {
        tempValue = value
    }

    SettingsSliderEntry(
        painter = painter,
        modifier = modifier,
        title = title,
        unit = unit,
        value = tempValue,
        valueRange = valueRange,
        valueFormat = valueFormat,
        steps = steps,
        onSlideChange = { tempValue = it },
        onSliderChangeFinished = { committed ->
            tempValue = committed
            floatUserData.asynchronousSet(committed)
        },
        onLongClick = {
            navController.navigateToSliderValueDialog(floatUserData.path, tempValue)
        }
    )
}

@Composable
private fun SettingsSliderEntry(
    modifier: Modifier = Modifier,
    painter: Painter? = null,
    title: String,
    unit: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    valueFormat: (Float) -> Float = { (it * 2).roundToInt().toFloat() / 2 },
    steps: List<Float>? = null,
    onSlideChange: (Float) -> Unit,
    onSliderChangeFinished: (Float) -> Unit,
    onLongClick: () -> Unit
) {
    val tokens = NextVolDesign.tokens
    val commitState = remember { SliderCommitState() }
    val currentOnCommit by rememberUpdatedState(onSliderChangeFinished)
    val actualSteps = steps?.distinct()?.sorted()?.takeIf { it.size >= 2 }
    val sliderValue = if (actualSteps != null) {
        resolveDiscreteStepIndex(actualSteps, value).toFloat()
    } else value

    val sliderRange = if (actualSteps != null) 0f..(actualSteps.lastIndex.toFloat())
    else valueRange

    val stepsCount = if (actualSteps != null) (actualSteps.size - 2).coerceAtLeast(0) else 0
    val displayValue = if (actualSteps != null) actualSteps[sliderValue.roundToInt().coerceIn(0, actualSteps.lastIndex)] else value

    Row(
        modifier = Modifier
            .background(tokens.cardSurface)
            .then(modifier)
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = onLongClick,
            )
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        painter?.let {
            EditorialIconBadge(painter = it)
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = title,
                    color = colorScheme.onSurface,
                    style = typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(tokens.activePillFill)
                        .border(0.6.dp, tokens.activePillBorder, RoundedCornerShape(10.dp))
                        .padding(horizontal = 9.dp, vertical = 3.dp)
                        .animateContentSize(
                            animationSpec = tween(
                                durationMillis = 200,
                                easing = FastOutSlowInEasing,
                            ),
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AnimatedText(
                        text = DecimalFormat("#.#").format(displayValue),
                        color = colorScheme.primary,
                        style = typography.labelMedium.copy(
                            fontWeight = FontWeight.W600,
                            fontFeatureSettings = "tnum",
                        ),
                        maxLines = 1,
                    )
                    if (unit.isNotEmpty()) {
                        Spacer(Modifier.width(2.dp))
                        Text(
                            text = unit,
                            color = colorScheme.primary,
                            style = typography.labelMedium.copy(fontWeight = FontWeight.W600),
                            maxLines = 1,
                        )
                    }
                }
            }

            MiuixSlider(
                value = sliderValue.coerceIn(sliderRange.start, sliderRange.endInclusive),
                onValueChange = { raw ->
                    val newValue = if (actualSteps != null) {
                        val index = raw.roundToInt().coerceIn(0, actualSteps.lastIndex)
                        actualSteps[index]
                    } else {
                        valueFormat(raw)
                    }
                    onSlideChange(newValue)
                    commitState.onValueChanged(newValue)?.let(currentOnCommit)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(commitState) {
                        awaitPointerEventScope {
                            while (true) {
                                val initial = awaitPointerEvent(PointerEventPass.Initial)
                                if (initial.changes.any { it.pressed }) {
                                    commitState.onPointerDown()
                                }
                                val finalEvent = awaitPointerEvent(PointerEventPass.Final)
                                if (commitState.isPointerInteracting && finalEvent.changes.none { it.pressed }) {
                                    commitState.onPointerReleased()?.let(currentOnCommit)
                                }
                            }
                        }
                    },
                valueRange = sliderRange,
                steps = stepsCount,
                onValueChangeFinished = {
                    commitState.onValueChangeFinished()?.let(currentOnCommit)
                },
            )
        }
    }
}

@Composable
fun SettingsMenuEntry(
    modifier: Modifier = Modifier,
    painter: Painter? = null,
    title: String,
    description: String? = null,
    options: MenuOptions,
    disabled: Boolean = false,
    selectedOptionKey: String,
    stringUserData: StringUserData
) {
    SettingsMenuEntry(
        modifier = modifier,
        painter = painter,
        title = title,
        description = description,
        options = options,
        disabled = disabled,
        selectedOptionKey = selectedOptionKey,
        onOptionChange = { stringUserData.asynchronousSet(it) }
    )
}

@Composable
fun SettingsMenuEntry(
    modifier: Modifier = Modifier,
    painter: Painter? = null,
    title: String,
    description: String? = null,
    options: MenuOptions,
    disabled: Boolean = false,
    selectedOptionKey: String,
    onOptionChange: (String) -> Unit
) {
    val tokens = NextVolDesign.tokens
    var expanded by remember(disabled) { mutableStateOf(false) }
    val selectedOption = remember(options, selectedOptionKey) { options.getOrNull(selectedOptionKey) }
    val optionLabel = if (disabled) {
        stringResource(R.string.unavailable)
    } else {
        selectedOption?.let { stringResource(it.nameId) } ?: "(null)"
    }

    Row(
        modifier = Modifier
            .background(tokens.cardSurface)
            .then(modifier)
            .fillMaxWidth()
            .clickable(enabled = !disabled) { expanded = true }
            .alpha(if (disabled) 0.45f else 1f)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        painter?.let {
            EditorialIconBadge(painter = it)
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                color = colorScheme.onSurface,
                style = typography.headlineSmall,
            )
            description?.let {
                Text(
                    text = it,
                    color = colorScheme.onSurfaceVariant,
                    style = typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(2.dp))
            AnimatedTextLine(
                text = optionLabel,
                style = typography.labelLarge.copy(fontWeight = FontWeight.W600),
                color = if (disabled) colorScheme.onSurfaceVariant else colorScheme.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Box(contentAlignment = Alignment.CenterEnd) {
            Icon(
                painter = painterResource(R.drawable.arrow_forward_ios_24px),
                contentDescription = null,
                tint = colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                modifier = Modifier
                    .size(14.dp)
                    .rotate(if (expanded) 270f else 90f),
            )
            if (!disabled) {
                EditorialPopupMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    options.optionList.forEach { option ->
                        EditorialPopupMenuItem(
                            text = stringResource(option.nameId),
                            selected = option.key == selectedOptionKey,
                            onClick = {
                                expanded = false
                                onOptionChange(option.key)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsClickableEntry(
    modifier: Modifier = Modifier,
    painter: Painter? = null,
    title: String,
    description: String,
    openUrl: String
) {
    val context = LocalContext.current
    SettingsClickableEntry(
        modifier = modifier,
        painter = painter,
        title = title,
        description = description,
        onClick = {
            openUrl.let { url ->
                val intent = Intent(Intent.ACTION_VIEW, url.toUri())
                context.startActivity(intent, null)
            }
        }
    )
}

@Composable
fun SettingsClickableEntry(
    modifier: Modifier = Modifier,
    painter: Painter? = null,
    title: String,
    option: String? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    description: String? = null,
    onClick: () -> Unit
) {
    val tokens = NextVolDesign.tokens
    Row(
        modifier = Modifier
            .background(tokens.cardSurface)
            .then(modifier)
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        painter?.let {
            EditorialIconBadge(painter = it)
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                color = colorScheme.onSurface,
                style = typography.headlineSmall,
            )
            description?.let {
                Text(
                    text = it,
                    color = colorScheme.onSurfaceVariant,
                    style = typography.bodyMedium,
                )
            }
            option?.let {
                AnimatedTextLine(
                    text = it,
                    style = typography.labelLarge.copy(fontWeight = FontWeight.W500),
                    color = colorScheme.primary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (trailingContent != null) {
            Box(contentAlignment = Alignment.CenterEnd) {
                trailingContent()
            }
        } else {
            Icon(
                painter = painterResource(R.drawable.arrow_forward_ios_24px),
                contentDescription = null,
                tint = colorScheme.onSurfaceVariant.copy(alpha = 0.62f),
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
