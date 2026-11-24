package indi.renakoni.nextvol.ui.storagemanager

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.storage.StorageUsageSnapshot
import indi.renakoni.nextvol.theme.NextVolDesign
import indi.renakoni.nextvol.ui.components.design.EditorialCard
import indi.renakoni.nextvol.ui.components.design.EditorialHairlineDivider
import indi.renakoni.nextvol.ui.home.reading.stats.predefinedColors
import indi.renakoni.nextvol.utils.formatSize
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleClip
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

@Composable
internal fun StorageUsageChart(snapshot: StorageUsageSnapshot, loading: Boolean) {
    val sections = snapshot.sections()
    var selectedIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    val selectedSection = selectedIndex?.let { sections.getOrNull(it) }
    val colors = MaterialTheme.colorScheme
    val tokens = NextVolDesign.tokens
    // Reuse the reading-statistics palette; reserve the error color for destructive actions.
    val categoryColors = listOf(
        predefinedColors[0],
        predefinedColors[4],
        predefinedColors[1],
        predefinedColors[2],
        predefinedColors[5],
        colors.outline,
    )
    val updated = remember(snapshot.calculatedAt) {
        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(snapshot.calculatedAt))
    }

    EditorialCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = tokens.cardRadius,
        contentPadding = PaddingValues(20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.storage_distribution),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.W600,
                )
                Text(
                    text = if (loading) stringResource(R.string.storage_measuring)
                    else stringResource(R.string.storage_updated_at, updated),
                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                    color = colors.onSurfaceVariant,
                )
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val sideBySide = maxWidth >= 340.dp && LocalDensity.current.fontScale <= 1.15f
                val chart: @Composable () -> Unit = {
                    StorageDonut(sections, snapshot.totalBytes, categoryColors, selectedIndex)
                }
                val legend: @Composable (Modifier) -> Unit = { modifier ->
                    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        sections.forEachIndexed { index, section ->
                            val isSelected = index == selectedIndex
                            val highlight = if (isSelected) {
                                categoryColors[index].copy(alpha = 0.14f)
                            } else {
                                Color.Transparent
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .squircleBackground(color = highlight, cornerRadius = 12.dp)
                                    .squircleClip(cornerRadius = 12.dp)
                                    .semantics { this.selected = isSelected }
                                    .testTag("storage_category_$index")
                                    .clickable(role = Role.Button) {
                                        selectedIndex = if (isSelected) null else index
                                    }
                                    .heightIn(min = 44.dp)
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(
                                    Modifier
                                        .size(8.dp)
                                        .background(categoryColors[index], CircleShape),
                                )
                                if (sideBySide) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(section.title),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.onSurfaceVariant,
                                        )
                                        Text(
                                            text = formatSize(section.size),
                                            style = MaterialTheme.typography.labelLarge.copy(
                                                fontFeatureSettings = "tnum",
                                            ),
                                            fontWeight = FontWeight.W600,
                                        )
                                    }
                                } else {
                                    Text(
                                        text = stringResource(section.title),
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = colors.onSurfaceVariant,
                                    )
                                    Text(
                                        text = formatSize(section.size),
                                        style = MaterialTheme.typography.labelLarge.copy(
                                            fontFeatureSettings = "tnum",
                                        ),
                                        fontWeight = FontWeight.W600,
                                    )
                                }
                            }
                        }
                    }
                }
                if (sideBySide) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        chart()
                        legend(Modifier.weight(1f))
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        chart()
                        legend(Modifier.fillMaxWidth())
                    }
                }
            }
            if (selectedSection != null) {
                EditorialHairlineDivider()
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(selectedSection.title),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.W600,
                        )
                        val share = if (snapshot.totalBytes > 0) {
                            selectedSection.size.toDouble() / snapshot.totalBytes
                        } else {
                            0.0
                        }
                        Text(
                            text = NumberFormat.getPercentInstance().apply {
                                maximumFractionDigits = 1
                            }.format(share),
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontFeatureSettings = "tnum",
                            ),
                            fontWeight = FontWeight.W600,
                            color = colors.primary,
                        )
                    }
                    Text(
                        text = stringResource(selectedSection.description),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun StorageDonut(
    sections: List<StorageManagerSection>,
    total: Long,
    colors: List<Color>,
    selected: Int?,
) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val arcColors = colors.mapIndexed { index, color ->
        animateColorAsState(
            targetValue = if (selected == null || selected == index) color else color.copy(alpha = 0.2f),
            label = "storageSegment",
        ).value
    }
    val diameter = (164 * LocalDensity.current.fontScale.coerceIn(1f, 1.5f)).dp
    Box(Modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 15.dp.toPx()
            val inset = stroke / 2
            val bounds = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), bounds, style = Stroke(stroke))
            var start = -90f
            sections.forEachIndexed { index, section ->
                val sweep = if (total > 0) (section.size.toDouble() / total * 360).toFloat() else 0f
                val gap = minOf(2.5f, sweep / 5)
                if (sweep > 0f) {
                    drawArc(
                        color = arcColors[index],
                        startAngle = start + gap / 2,
                        sweepAngle = sweep - gap,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = bounds,
                        style = Stroke(stroke, cap = StrokeCap.Butt),
                    )
                }
                start += sweep
            }
        }
        Column(
            modifier = Modifier.width(diameter - 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = formatSize(total),
                style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.W700,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.storage_used_short),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
