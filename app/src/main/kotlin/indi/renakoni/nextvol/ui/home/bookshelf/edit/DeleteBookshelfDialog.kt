package indi.renakoni.nextvol.ui.home.bookshelf.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
fun DeleteBookshelfDialog(
    onDismissRequest: () -> Unit,
    onConfirmation: () -> Unit,
) {
    val tokens = NextVolDesign.tokens
    val radius = tokens.sheetRadius

    Dialog(onDismissRequest = onDismissRequest) {
        Column(
            modifier = Modifier
                .sizeIn(minWidth = 280.dp, maxWidth = 420.dp)
                .squircleBackground(color = tokens.cardElevatedSurface, cornerRadius = radius)
                .squircleBorder(width = 0.85.dp, color = tokens.cardBorder, cornerRadius = radius)
                .squircleClip(radius)
                .padding(horizontal = 24.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            EditorialIconBadge(
                painter = painterResource(R.drawable.delete_forever_24px),
                size = 48.dp,
                iconSize = 24.dp,
                cornerRadius = 15.dp,
                containerColor = colorScheme.errorContainer.copy(alpha = 0.72f),
                contentColor = colorScheme.error,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.dialog_delete_bookshelf),
                style = typography.displayMedium.copy(fontWeight = FontWeight.W600),
                color = colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.dialog_delete_bookshelf_text),
                style = typography.bodyMedium,
                color = colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(22.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onDismissRequest,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(
                        text = stringResource(R.string.cancel),
                        style = typography.labelLarge,
                    )
                }
                Button(
                    onClick = onConfirmation,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colorScheme.error,
                        contentColor = colorScheme.onError,
                    ),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = stringResource(android.R.string.ok),
                        style = typography.labelLarge.copy(fontWeight = FontWeight.W600),
                    )
                }
            }
        }
    }
}
