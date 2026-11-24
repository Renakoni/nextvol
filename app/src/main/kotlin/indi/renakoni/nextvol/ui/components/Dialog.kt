package indi.renakoni.nextvol.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isUnspecified
import androidx.compose.ui.graphics.luminance
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
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.ui.components.design.EditorialIconBadge
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleClip

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
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onDismissRequest,
                enabled = dismissEnabled,
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(
                    text = dismissText,
                    style = typography.labelLarge,
                )
            }
            Button(
                onClick = onConfirmation,
                enabled = confirmationEnabled,
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text(
                    text = confirmationText,
                    style = typography.labelLarge.copy(fontWeight = FontWeight.W600),
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
    val tokens = NextVolDesign.tokens
    val radius = tokens.sheetRadius
    Dialog(
        onDismissRequest = onDismissRequest,
    ) {
        Column(
            modifier = Modifier
                .sizeIn(minWidth = 280.dp, maxWidth = 560.dp)
                .squircleBackground(color = tokens.cardElevatedSurface, cornerRadius = radius)
                .squircleBorder(width = 0.85.dp, color = tokens.cardBorder, cornerRadius = radius)
                .squircleClip(cornerRadius = radius),
        ) {
            Spacer(Modifier.height(24.dp))
            EditorialIconBadge(
                painter = icon,
                size = 48.dp,
                iconSize = 24.dp,
                cornerRadius = 15.dp,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(14.dp))
            Text(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(horizontal = 24.dp),
                text = title,
                style = typography.displayMedium,
                fontWeight = FontWeight.W600,
                textAlign = TextAlign.Center,
                color = colorScheme.onSurface,
            )
            if (description.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    modifier = Modifier
                        .sizeIn(minWidth = 280.dp, maxWidth = 560.dp)
                        .padding(horizontal = 24.dp),
                    textAlign = TextAlign.Center,
                    text = description,
                    style = typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(18.dp))
            content.invoke(this)
        }
    }
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

    BaseDialog(
        icon = painterResource(R.drawable.palette_24px),
        title = stringResource(R.string.dialog_color_picker),
        description = description,
        onDismissRequest = onDismissRequest,
        onConfirmation = { onConfirmation(currentColor) },
        dismissText = stringResource(R.string.cancel),
        confirmationText = stringResource(R.string.apply),
    ) {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            colors.forEachIndexed { index, color ->
                val isSelected = color == currentColor
                val fill = if (color.isUnspecified) colorScheme.surfaceContainer else color
                val iconTint = if (color.isUnspecified) {
                    colorScheme.onSurfaceVariant
                } else if (fill.luminance() > 0.55f) {
                    Color(0xFF1A1816)
                } else {
                    Color.White
                }
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .border(
                            width = if (isSelected) 2.5.dp else 1.dp,
                            color = if (isSelected) colorScheme.primary else colorScheme.outlineVariant,
                            shape = CircleShape,
                        )
                        .padding(3.dp)
                        .clip(CircleShape)
                        .background(fill)
                        .clickable { currentColor = color },
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        index == 0 -> Icon(
                            painter = painterResource(R.drawable.block_24px),
                            contentDescription = null,
                            tint = iconTint,
                            modifier = Modifier.size(18.dp),
                        )
                        isSelected -> Icon(
                            painter = painterResource(R.drawable.check_24px),
                            contentDescription = null,
                            tint = iconTint,
                            modifier = Modifier.size(18.dp),
                        )
                    }
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
    val tokens = NextVolDesign.tokens
    var text by remember { mutableStateOf(value.toString()) }

    LaunchedEffect(value) {
        text = value.toString()
    }

    val parsed = text.toFloatOrNull()
    val error = text.isNotBlank() && parsed == null

    AlertDialog(
        onDismissRequest = onDismissRequest,
        shape = RoundedCornerShape(tokens.sheetRadius),
        containerColor = tokens.cardElevatedSurface,
        title = {
            Text(
                text = stringResource(R.string.dialog_slider_custom),
                style = typography.displayMedium,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.dialog_value)) },
                    isError = error,
                    singleLine = true,
                    shape = RoundedCornerShape(tokens.controlRadius),
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
            Button(
                onClick = {
                    parsed?.let {
                        onValueChange(it)
                        onConfirmation(it)
                    }
                },
                enabled = parsed != null,
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(stringResource(R.string.apply))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissRequest,
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
