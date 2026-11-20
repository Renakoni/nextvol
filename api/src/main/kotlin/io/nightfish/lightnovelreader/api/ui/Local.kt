package io.nightfish.lightnovelreader.api.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.navigation.NavController

/**
 * 当前页面的导航控制器
 * 需在Compose导航宿主内使用
 *
 * @since Api 2
 */
val LocalNavController = compositionLocalOf<NavController> {
    error("CompositionLocal LocalNavController not present")
}

/**
 * 当前阅读器的样式配置
 *
 * @since Api 2
 */
val LocalReaderStyle = compositionLocalOf {
    ReaderStyle(
        fontSize = 22f,
        fontLineHeight = 7f,
        fontWeight = 500f,
        textColor = Color.Unspecified,
        textDarkColor = Color.Unspecified,
    )
}
