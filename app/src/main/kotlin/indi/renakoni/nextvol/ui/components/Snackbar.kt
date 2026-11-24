package indi.renakoni.nextvol.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.theme.NextVolDesign

@Composable
fun LnrSnackbar(
    snackbarData: SnackbarData,
    modifier: Modifier = Modifier,
    actionOnNewLine: Boolean = false,
    shape: Shape = RoundedCornerShape(18.dp),
    containerColor: Color = NextVolDesign.tokens.snackbarContainer,
    contentColor: Color = NextVolDesign.tokens.snackbarContent,
    actionColor: Color = NextVolDesign.tokens.snackbarAction,
    actionContentColor: Color = NextVolDesign.tokens.snackbarAction,
    dismissActionContentColor: Color = NextVolDesign.tokens.snackbarContent,
) {
    val tokens = NextVolDesign.tokens
    val actionLabel = snackbarData.visuals.actionLabel
    val actionComposable: (@Composable () -> Unit)? =
        if (actionLabel != null) {
            @Composable {
                TextButton(
                    colors = ButtonDefaults.textButtonColors(contentColor = actionColor),
                    onClick = { snackbarData.performAction() },
                    content = {
                        Text(
                            text = actionLabel,
                            style = typography.labelLarge.copy(fontWeight = FontWeight.W600),
                        )
                    }
                )
            }
        } else {
            null
        }
    val dismissActionComposable: (@Composable () -> Unit)? =
        if (snackbarData.visuals.withDismissAction) {
            @Composable {
                IconButton(
                    onClick = { snackbarData.dismiss() },
                    content = {
                        Icon(
                            painterResource(R.drawable.close_24px),
                            contentDescription = stringResource(R.string.close),
                        )
                    }
                )
            }
        } else {
            null
        }
    Surface(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        shape = shape,
        color = Color.Transparent,
        border = BorderStroke(0.75.dp, tokens.glassBorderTop.copy(alpha = 0.25f)),
        shadowElevation = 6.dp,
    ) {
        Snackbar(
            action = actionComposable,
            dismissAction = dismissActionComposable,
            actionOnNewLine = actionOnNewLine,
            shape = shape,
            containerColor = containerColor,
            contentColor = contentColor,
            actionContentColor = actionContentColor,
            dismissActionContentColor = dismissActionContentColor,
            content = {
                Box {
                    Text(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(vertical = 8.dp),
                        text = snackbarData.visuals.message,
                        style = typography.bodyMedium,
                    )
                }
            }
        )
    }
}
