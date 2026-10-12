package indi.renakoni.nextvol.ui.home.settings.data

import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.update.UpdateChannel
import indi.renakoni.nextvol.data.update.UpdatePlatform
import indi.renakoni.nextvol.ui.components.MenuOptions
import indi.renakoni.nextvol.ui.components.MenuOptions.Option
import indi.renakoni.nextvol.utils.DateFormat
import indi.renakoni.nextvol.utils.DateOrder

object SettingsMenuOptions {
    data object UpdateChannelOptions: MenuOptions(
        UpdateChannel.entries.map { channel ->
            Option(channel.key, when (channel) {
                UpdateChannel.RELEASE -> R.string.key_update_channel_release
                UpdateChannel.DEVELOPMENT -> R.string.key_update_channel_development
                UpdateChannel.CI -> R.string.key_update_channel_ci
            })
        }
    )

    data object UpdatePlatformOptions: MenuOptions(
        UpdatePlatform.entries.map { platform ->
            Option(platform.key, when (platform) {
                UpdatePlatform.GITHUB -> R.string.key_platform_github
                UpdatePlatform.LNR_API -> R.string.key_platform_lnr_api
            })
        }
    )

    data object DarkModeOptions: MenuOptions(
        Option("FollowSystem", R.string.key_dark_mode_follow_system),
        Option("Enabled", R.string.key_dark_mode_enabled),
        Option("Disabled", R.string.key_dark_mode_disabled)
    )

    data object AppLocaleOptions: MenuOptions(
        Option("none", R.string.key_locale_none),
        Option("zh-CN", R.string.key_locale_zh_cn),
        Option("zh-HK", R.string.key_locale_zh_hk),
        Option("zh-TW", R.string.key_locale_zh_tw),
        Option("ja-JP", R.string.key_locale_ja_jp),
        Option("ko-kr", R.string.key_locale_ko_kr),
        Option("ko-kp", R.string.key_locale_ko_kp)
    )

    data object LogLevelOptions: MenuOptions(
        Option("none", R.string.key_log_level_none),
        Option("error", R.string.key_log_level_error),
        Option("warning", R.string.key_log_level_warning),
        Option("info", R.string.key_log_level_info),
        Option("debug", R.string.key_log_level_debug),
        Option("verbose", R.string.key_log_level_verbose),
    )

    data object LightThemeNameOptions: MenuOptions(
        Option("light_default", R.string.key_light_theme_default),
        Option("light_designer", R.string.key_light_theme_designer)
    )

    data object DarkThemeNameOptions: MenuOptions(
        Option("dark_default", R.string.key_dark_theme_default),
        Option("dark_obsidian", R.string.key_dark_theme_obsidian),
        Option("dark_designer", R.string.key_dark_theme_designer)
    )

    data object SelectImage: MenuOptions() {
        val Default = option("default", R.string.key_default_image)
        val Customize = option("customize", R.string.key_customize_image)
    }

    data object SelectText: MenuOptions() {
        val Default = option("default", R.string.key_default_text)
        val Customize = option("customize", R.string.key_customize_text)
    }

    data object DateFormatOptions: MenuOptions(
        Option(DateFormat.NUMERIC.key, R.string.key_date_format_numeric),
        Option(DateFormat.WRITTEN.key, R.string.key_date_format_written)
    )

    data object DateOrderOptions: MenuOptions(
        Option(DateOrder.AUTO.key, R.string.key_date_order_auto),
        Option(DateOrder.YMD.key, R.string.key_date_order_ymd),
        Option(DateOrder.DMY.key, R.string.key_date_order_dmy),
        Option(DateOrder.MDY.key, R.string.key_date_order_mdy)
    )

    data object DurationStyleOptions: MenuOptions() {
        val Simple = option("simple", R.string.key_duration_style_simple)
        val Detailed = option("detailed", R.string.key_duration_style_detailed)
    }
}
