package indi.renakoni.nextvol.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.theme.NextVolDesign

@Composable
fun SwitchChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val tokens = NextVolDesign.tokens
    val colors = MaterialTheme.colorScheme
    FilterChip(
        selected = selected,
        onClick = onClick,
        shape = RoundedCornerShape(tokens.chipRadius),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = tokens.cardSurface,
            selectedContainerColor = tokens.activePillFill,
            labelColor = colors.onSurfaceVariant,
            selectedLabelColor = colors.primary,
            selectedLeadingIconColor = colors.primary,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = tokens.cardBorder,
            selectedBorderColor = tokens.activePillBorder,
            borderWidth = 0.75.dp,
            selectedBorderWidth = 1.dp,
        ),
        leadingIcon = {
            AnimatedContent(targetState = selected, label = "switchChipIcon") { isSelected ->
                if (isSelected) {
                    Icon(
                        painter = painterResource(R.drawable.check_24px),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        },
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = if (selected) FontWeight.W600 else FontWeight.W500,
                ),
            )
        }
    )
}
