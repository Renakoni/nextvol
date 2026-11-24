package indi.renakoni.nextvol.ui.components.design

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.hazeGlass
import indi.renakoni.nextvol.theme.LocalNextVolHazeState
import indi.renakoni.nextvol.theme.NextVolDesign
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleClip

enum class GlassVariant {
    /** Optical refraction glass on API 33+, frosted blur on API 31-32, crisp translucent rim fallback on API 24-30. */
    Liquid,
    /** Real-time frosted glassmorphism blur on API 31+, crisp translucent fallback on API 24-30. */
    Frosted,
    /** Light-touch translucent scrim without heavy optical distortion. */
    Subtle,
}

/**
 * Floating translucent surface unifying Liquid Glass (Haze 2.1 hazeGlass),
 * Glassmorphism (hazeBlur), and graceful API 24-30 fallback with a top-lit specular rim.
 */
@Composable
fun LiquidGlassSurface(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = CircleShape,
    variant: GlassVariant = GlassVariant.Liquid,
    hazeState: HazeState? = LocalNextVolHazeState.current,
    blurRadius: Dp = 22.dp,
    elevation: Dp = 10.dp,
    tintColor: Color = NextVolDesign.tokens.glassSurfaceTint,
    fallbackColor: Color = NextVolDesign.tokens.glassFallbackSurface,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    borderWidth: Dp = 0.85.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val tokens = NextVolDesign.tokens
    val surfaceBg = MaterialTheme.colorScheme.surface
    val rimBrush = remember(tokens.glassBorderTop, tokens.glassBorderBottom) {
        Brush.verticalGradient(
            colors = listOf(tokens.glassBorderTop, tokens.glassBorderBottom),
        )
    }

    val useLiquidShader = variant == GlassVariant.Liquid &&
        hazeState != null &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val useFrostedBlur = !useLiquidShader &&
        variant != GlassVariant.Subtle &&
        hazeState != null &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val effectModifier = when {
        useLiquidShader -> {
            val glassStyle = remember(shape, surfaceBg, tintColor, tokens.isDark, tokens.glassShadow) {
                GlassStyle.regular.then {
                    shape(shape)
                    backgroundColor(surfaceBg)
                    tint(tintColor)
                    specularIntensity(if (tokens.isDark) 0.42f else 0.65f)
                    chromaticAberrationStrength(if (tokens.isDark) 0.14f else 0.10f)
                    edgeShadow(tokens.glassShadow)
                }
            }
            Modifier.hazeGlass(
                input = HazeInput.Sources(hazeState),
                style = glassStyle,
            )
        }
        useFrostedBlur -> {
            val blurStyle = remember(blurRadius, surfaceBg, tintColor, fallbackColor) {
                HazeBlurStyle {
                    blurRadius(blurRadius)
                    noiseFactor(0.04f)
                    backgroundColor(surfaceBg)
                    colorEffects(listOf(HazeColorEffect.tint(tintColor)))
                    fallbackColorEffect(HazeColorEffect.tint(fallbackColor))
                }
            }
            Modifier.hazeBlur(
                input = HazeInput.Sources(hazeState),
                style = blurStyle,
            )
        }
        variant == GlassVariant.Subtle -> Modifier.background(tintColor)
        else -> Modifier.background(fallbackColor)
    }

    val surfaceModifier = modifier
        .then(
            if (elevation > 0.dp) {
                Modifier.shadow(
                    elevation = elevation,
                    shape = shape,
                    ambientColor = tokens.glassShadow,
                    spotColor = tokens.glassShadow,
                    clip = false,
                )
            } else {
                Modifier
            },
        )
        .clip(shape)
        .then(effectModifier)
        .border(BorderStroke(borderWidth, rimBrush), shape)

    Box(modifier = surfaceModifier) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            content()
        }
    }
}

/**
 * Editorial Minimalism card powered by Miuix continuous squircle geometry and a hairline border.
 */
@Composable
fun EditorialCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = NextVolDesign.tokens.cardRadius,
    containerColor: Color = NextVolDesign.tokens.cardSurface,
    borderColor: Color = NextVolDesign.tokens.cardBorder,
    borderWidth: Dp = 0.75.dp,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .squircleBackground(color = containerColor, cornerRadius = cornerRadius)
            .squircleBorder(width = borderWidth, color = borderColor, cornerRadius = cornerRadius)
            .squircleClip(cornerRadius = cornerRadius)
            .then(
                if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick)
                else Modifier,
            )
            .padding(contentPadding),
        content = content,
    )
}

@Composable
fun EditorialHairlineDivider(
    modifier: Modifier = Modifier,
    color: Color = NextVolDesign.tokens.divider,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(0.6.dp)
            .background(color),
    )
}

/**
 * Soft squircle icon medallion used in settings rows, section headers, and empty states.
 */
@Composable
fun EditorialIconBadge(
    painter: Painter,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    iconSize: Dp = 19.dp,
    cornerRadius: Dp = 11.dp,
    containerColor: Color = NextVolDesign.tokens.iconBadgeContainer,
    contentColor: Color = NextVolDesign.tokens.iconBadgeContent,
) {
    Box(
        modifier = modifier
            .size(size)
            .squircleBackground(color = containerColor, cornerRadius = cornerRadius)
            .squircleClip(cornerRadius = cornerRadius),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painter,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(iconSize),
        )
    }
}

/**
 * Frosted circular action button with 48dp accessible touch target and 38dp visual rimmed capsule.
 */
@Composable
fun EditorialIconButton(
    onClick: () -> Unit,
    painter: Painter,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.85f),
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val tokens = NextVolDesign.tokens
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics {
                role = Role.Button
                this.contentDescription = contentDescription
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(containerColor)
                .border(0.75.dp, tokens.cardBorder, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painter,
                contentDescription = null,
                tint = if (enabled) contentColor else contentColor.copy(alpha = 0.38f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
