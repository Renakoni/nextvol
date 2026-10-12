package indi.renakoni.nextvol.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isUnspecified
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import indi.renakoni.nextvol.R
import kotlin.math.round

@Composable
fun BaseDialog(
    icon: Painter,
    title: String,
    description: String = "",
    onDismissRequest: () -> Unit,
    onConfirmation: () -> Unit,
    dismissText: String,
    confirmationText: String,
    confirmationEnabled: Boolean = true,
    dismissEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    BaseDialog(
        icon = icon,
        title = title,
        description = description,
        onDismissRequest = onDismissRequest,
    ) {
        content.invoke(this)
        Row(
            modifier = Modifier
                .padding(8.dp, 24.dp, 24.dp, 24.dp)
                .align(Alignment.End),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                onClick = onDismissRequest,
                enabled = dismissEnabled
            ) {
                Text(
                    text = dismissText,
                    style = typography.labelMedium
                )
            }
            TextButton(
                onClick = onConfirmation,
                enabled = confirmationEnabled
            ) {
                Text(
                    text = confirmationText,
                    style = typography.labelMedium
                )
            }
        }
    }
}

@Composable
fun BaseDialog(
    icon: Painter,
    title: String,
    description: String = "",
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(
        onDismissRequest = onDismissRequest,
    ) {
        Card(
            modifier = Modifier
                .sizeIn(minWidth = 280.dp, maxWidth = 560.dp),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceContainerHigh),
        ) {
            Box(Modifier.height(24.dp))
            Icon(
                modifier = Modifier.size(24.dp).align(Alignment.CenterHorizontally),
                painter = icon,
                tint = colorScheme.secondary,
                contentDescription = null
            )
            Box(Modifier.height(16.dp))
            Text(
                modifier = Modifier.align(Alignment.CenterHorizontally),
                text = title,
                style = typography.displayMedium,
                fontWeight = FontWeight.W500,
            )
            Box(Modifier.height(16.dp))
            if (description.isNotBlank()) {
                Text(
                    modifier = Modifier
                        .sizeIn(minWidth = 280.dp, maxWidth = 560.dp)
                        .padding(horizontal = 24.dp),
                    textAlign = TextAlign.Start,
                    text = description,
                    style = typography.labelLarge,
                    color = colorScheme.onSurfaceVariant
                )
                Box(Modifier.height(16.dp))
            }
            content.invoke(this)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SliderDialog(
    onDismissRequest: () -> Unit,
    onConfirmation: () -> Unit,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onSlideChange: (Float) -> Unit,
    onSliderChangeFinished: () -> Unit,
    title: String,
    description: String
) {
    BaseDialog(
        icon = painterResource(R.drawable.filled_settings_24px),
        title = title,
        description = description,
        onDismissRequest = onDismissRequest,
        onConfirmation = onConfirmation,
        dismissText = stringResource(R.string.cancel),
        confirmationText = stringResource(R.string.apply),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.book_word_count_limit),
                    style = typography.labelMedium
                )
                Spacer(Modifier.weight(1f))
                RollingNumber(
                    number = value.toInt(),
                    style = typography.labelMedium,
                    separator = true
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "+",
                    style = typography.labelMedium
                )
            }

            Spacer(Modifier.height(16.dp))

            Slider(
                modifier = Modifier.fillMaxWidth(),
                value = value,
                valueRange = valueRange,
                steps = steps,
                onValueChange = { newValue ->
                    val adjustedValue = if (steps > 0) {
                        val stepSize = (valueRange.endInclusive - valueRange.start) / (steps + 1)
                        valueRange.start + round((newValue - valueRange.start) / stepSize) * stepSize
                    } else {
                        newValue
                    }
                    onSlideChange(adjustedValue)
                },
                onValueChangeFinished = onSliderChangeFinished,
                colors = SliderDefaults.colors(
                    inactiveTrackColor = colorScheme.primaryContainer,
                )
            )
        }
    }
}


@Composable
fun SettingsDisableStatsDialog(
    onClickConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
    onClickShowPrivacyPolicy: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(
            text = stringResource(R.string.settings_statistics_disable_dialog_title),
            style = typography.titleLarge
        ) },
        text = { 
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.settings_statistics_disable_dialog_text))
                Spacer(modifier = Modifier.height(12.dp))
                TextButton(
                    onClick = onClickShowPrivacyPolicy,
                    modifier = Modifier.align(Alignment.Start),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(
                        text = stringResource(R.string.privacy_policy),
                        color = colorScheme.primary,
                        style = typography.labelMedium
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onClickConfirm()
                }
            ) {
                Text(
                    text = stringResource(R.string.settings_statistics_disable_dialog_confirm),
                    color = colorScheme.error
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(R.string.cancel))
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorPickerDialog(
    onDismissRequest: () -> Unit,
    onConfirmation: (Color) -> Unit,
    selectedColor: Color,
    colors: List<Color>,
    description: String = stringResource(R.string.dialog_color_picker_background_desc),
) {
    var currentColor by remember {
        mutableStateOf(selectedColor)
    }

    BaseDialog (
        icon = painterResource(R.drawable.palette_24px),
        title = stringResource(R.string.dialog_color_picker),
        description = description,
        onDismissRequest = onDismissRequest,
        onConfirmation = { onConfirmation(currentColor) },
        dismissText = stringResource(R.string.cancel),
        confirmationText = stringResource(R.string.apply),
    ) {
        FlowRow(
            modifier = Modifier.padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            colors.forEachIndexed { index, color ->
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clickable {
                            currentColor = color
                        }
                ) {
                    val secondary = colorScheme.secondary
                    val surfaceContainer = colorScheme.surfaceContainer
                    val blockIconId = painterResource(R.drawable.block_24px)
                    Canvas(
                        modifier = Modifier.size(44.dp)
                    ) {
                        if (color == currentColor)
                            drawCircle(
                                color = secondary,
                                radius = 22.dp.toPx(),
                            )
                        drawCircle(
                            color = surfaceContainer,
                            radius = 20.dp.toPx(),
                        )
                        drawCircle(
                            color = if (color.isUnspecified) surfaceContainer else color,
                            radius = 20.dp.toPx(),
                        )
                    }
                    if (index == 0)
                        Icon(
                            modifier = Modifier.align(Alignment.Center),
                            painter = blockIconId,
                            contentDescription = null
                        )
                }
            }
        }
    }
}

@Composable
fun SliderValueDialog(
    value: Float,
    onValueChange: (Float) -> Unit,
    onDismissRequest: () -> Unit,
    onConfirmation: (Float) -> Unit
) {
    var text by remember { mutableStateOf(value.toString()) }

    LaunchedEffect(value) {
        text = value.toString()
    }

    val parsed = text.toFloatOrNull()
    val error = text.isNotBlank() && parsed == null

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.dialog_slider_custom)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.dialog_value)) },
                    isError = error,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
                )
                if (error) {
                    Text(
                        text = stringResource(R.string.dialog_slider_custom_illegal_value),
                        color = colorScheme.error,
                        style = typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    parsed?.let {
                        onValueChange(it)
                        onConfirmation(it)
                    }
                },
                enabled = parsed != null
            ) {
                Text(stringResource(R.string.apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
