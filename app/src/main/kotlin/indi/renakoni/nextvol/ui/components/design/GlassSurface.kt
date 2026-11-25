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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.shadow.DropShadowPainter
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
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
 * Glassmorphism (hazeBlur), and graceful API 24-30 fallback with dual-bevel optical rims
 * and a true sculpted floating shadow that remains crisp over both pure-white and colorful surfaces.
 */
@Composable
fun LiquidGlassSurface(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = CircleShape,
    variant: GlassVariant = GlassVariant.Liquid,
    hazeState: HazeState? = LocalNextVolHazeState.current,
    blurRadius: Dp = 14.dp,
    elevation: Dp = 12.dp,
    tintColor: Color = NextVolDesign.tokens.glassSurfaceTint,
    fallbackColor: Color = NextVolDesign.tokens.glassFallbackSurface,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    borderWidth: Dp = 1.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val tokens = NextVolDesign.tokens
    val surfaceBg = MaterialTheme.colorScheme.surface

    val outerRimBrush = remember(tokens.isDark, tokens.glassBorderTop, tokens.glassBorderBottom) {
        val bottom = tokens.glassBorderBottom
        val top = if (tokens.isDark) tokens.glassBorderTop else bottom.copy(alpha = bottom.alpha * 0.6f)
        Brush.linearGradient(listOf(top, bottom))
    }

    val useLiquidShader = variant == GlassVariant.Liquid &&
        hazeState != null &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val useFrostedBlur = !useLiquidShader &&
        variant != GlassVariant.Subtle &&
        hazeState != null &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val effectiveBlurRadius = if (variant == GlassVariant.Liquid) {
        minOf(blurRadius, 12.dp)
    } else {
        blurRadius
    }

    val effectModifier = when {
        useLiquidShader -> {
            val glassStyle = remember(shape, surfaceBg, tintColor, tokens.isDark, tokens.glassShadow) {
                GlassStyle.clear.then {
                    shape(shape)
                    backgroundColor(surfaceBg)
                    tint(tintColor)
                    specularIntensity(if (tokens.isDark) 0.42f else 0.68f)
                    chromaticAberrationStrength(0.16f)
                    ambientResponse(0.88f)
                    edgeSoftness(3.dp)
                    fresnelExponent(2.0f)
                    edgeShadow(tokens.glassShadow)
                }
            }
            Modifier.hazeGlass(
                input = HazeInput.Sources(hazeState),
                style = glassStyle,
            )
        }
        useFrostedBlur -> {
            val blurStyle = remember(effectiveBlurRadius, surfaceBg, tintColor, fallbackColor) {
                HazeBlurStyle {
                    blurRadius(effectiveBlurRadius)
                    noiseFactor(0.02f)
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

    val shadowModifier = if (elevation > 0.dp) {
        Modifier.drawWithCache {
            val outline = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
            val ambient = DropShadowPainter(shape, Shadow(
                radius = elevation * 1.35f,
                color = if (tokens.isDark) Color(0x72000000) else Color(0x260F172A),
                offset = DpOffset(0.dp, elevation * 0.42f),
            ))
            val contact = DropShadowPainter(shape, Shadow(
                radius = elevation * 0.42f,
                color = if (tokens.isDark) Color(0x4A000000) else Color(0x160F172A),
                offset = DpOffset(0.dp, elevation * 0.14f),
            ))
            onDrawBehind {
                // Only the exterior casts a shadow. The content behind the glass stays visible.
                clipPath(outline, clipOp = ClipOp.Difference) {
                    with(ambient) { draw(size) }
                    with(contact) { draw(size) }
                }
            }
        }
    } else {
        Modifier
    }

    val glassSheenModifier = if (variant == GlassVariant.Liquid) {
        val topGlow = if (tokens.isDark) Color.White.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.46f)
        val bottomBounce = if (tokens.isDark) Color.White.copy(alpha = 0.025f) else Color.White.copy(alpha = 0.22f)
        val innerBevelBrush = remember(tokens.isDark, tokens.glassBorderTop) {
            if (tokens.isDark) {
                Brush.linearGradient(
                    colorStops = arrayOf(
                        0.0f to Color.White.copy(alpha = 0.09f),
                        0.45f to Color.White.copy(alpha = 0.02f),
                        1.0f to Color.White.copy(alpha = 0.05f),
                    ),
                )
            } else {
                Brush.linearGradient(
                    colorStops = arrayOf(
                        0.0f to tokens.glassBorderTop.copy(alpha = tokens.glassBorderTop.alpha * 0.8f),
                        0.45f to Color.White.copy(alpha = 0.20f),
                        1.0f to Color.White.copy(alpha = 0.58f),
                    ),
                )
            }
        }
        Modifier.drawWithCache {
            val sheen = Brush.verticalGradient(
                colorStops = arrayOf(
                    0.0f to topGlow,
                    0.40f to Color.Transparent,
                    0.75f to Color.Transparent,
                    1.0f to bottomBounce,
                ),
                startY = 0f,
                endY = size.height,
            )
            val specularDot = Brush.radialGradient(
                colors = listOf(
                    if (tokens.isDark) Color.White.copy(alpha = 0.035f) else Color.White.copy(alpha = 0.26f),
                    Color.Transparent,
                ),
                center = Offset(size.width * 0.20f, 0f),
                radius = size.height * 1.45f,
            )
            val insetPx = borderWidth.toPx() + 0.6.dp.toPx()
            val innerSize = size.copy(width = size.width - 2 * insetPx, height = size.height - 2 * insetPx)
            val bevel = if (innerSize.isEmpty()) null else shape.createOutline(innerSize, layoutDirection, this)
            val stroke = Stroke(width = 1.dp.toPx())
            onDrawWithContent {
                drawRect(brush = sheen)
                drawRect(brush = specularDot)
                drawContent()
                if (bevel != null) inset(insetPx) { drawOutline(bevel, innerBevelBrush, style = stroke) }
            }
        }
    } else {
        Modifier
    }

    val surfaceModifier = modifier
        .then(shadowModifier)
        .clip(shape)
        .then(effectModifier)
        .then(glassSheenModifier)
        .border(BorderStroke(borderWidth, outerRimBrush), shape)

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
            .height(0.75.dp)
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
 * Clean circular action button with 48dp accessible touch target.
 * Borderless and transparent by default so top bars stay calm; renders a subtle capsule
 * fill only when an explicit containerColor is supplied (e.g., active toggle states).
 */
@Composable
fun EditorialIconButton(
    onClick: () -> Unit,
    painter: Painter,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = Color.Transparent,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val hasContainer = containerColor != Color.Transparent
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
                .then(
                    if (hasContainer) Modifier.background(containerColor) else Modifier,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painter,
                contentDescription = null,
                tint = if (enabled) contentColor else contentColor.copy(alpha = 0.38f),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
