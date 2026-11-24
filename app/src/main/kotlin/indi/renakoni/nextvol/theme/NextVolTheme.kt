package indi.renakoni.nextvol.theme

import android.app.Activity
import android.graphics.Color
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import indi.renakoni.nextvol.ui.LocalAppTheme
import indi.renakoni.nextvol.ui.LocalDarkColorScheme
import indi.renakoni.nextvol.ui.LocalLightColorScheme
import io.nightfish.lightnovelreader.api.ui.theme.AppTypography
import top.yukonga.miuix.kmp.theme.MiuixTheme

data class AppTheme(
    val isDark: Boolean,
    val colorScheme: ColorScheme
)

@Composable
fun NextVolTheme(
    darkMode: String,
    isDynamicColor: Boolean = true,
    lightThemeName: String,
    darkThemeName: String,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val systemDark = isSystemInDarkTheme()

    val isDark = remember(darkMode, systemDark) {
        when (darkMode) {
            "Enabled" -> true
            "Disabled" -> false
            else -> systemDark
        }
    }

    val lightColorScheme = remember(lightThemeName, isDynamicColor) {
        if (isDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            dynamicLightColorScheme(context)
        else when (lightThemeName) {
            "light_default" -> DefaultLightColorScheme
            "light_designer" -> DesignerLightColorScheme
            else -> DefaultLightColorScheme
        }
    }

    val darkColorScheme = remember(darkThemeName, isDynamicColor) {
        if (isDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            dynamicDarkColorScheme(context)
        else when (darkThemeName) {
            "dark_obsidian" -> DarkObsidianColorScheme
            "dark_designer" -> DesignerDarkColorScheme
            else -> DefaultDarkColorScheme
        }
    }

    val colorScheme = if (isDark) darkColorScheme else lightColorScheme

    val appTheme = remember(isDark, colorScheme) {
        AppTheme(isDark = isDark, colorScheme = colorScheme)
    }

    val designTokens = remember(isDark, colorScheme) {
        buildNextVolDesignTokens(colorScheme = colorScheme, isDark = isDark)
    }

    val miuixColors = rememberBridgedMiuixColors(
        colorScheme = colorScheme,
        isDark = isDark,
    )

    @Suppress("deprecation")
    DisposableEffect(view, isDark) {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !isDark
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                controller.isAppearanceLightNavigationBars = !isDark
            }
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }
        onDispose { }
    }

    CompositionLocalProvider(
        LocalAppTheme provides appTheme,
        LocalLightColorScheme provides lightColorScheme,
        LocalDarkColorScheme provides darkColorScheme,
        LocalNextVolTokens provides designTokens,
    ) {
        MiuixTheme(colors = miuixColors) {
            MaterialTheme(
                colorScheme = colorScheme,
                typography = AppTypography,
                content = content,
            )
        }
    }
}
