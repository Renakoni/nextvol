package indi.renakoni.nextvol.ui.book.reader

import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.ui.components.MenuOptions
import indi.renakoni.nextvol.ui.components.MenuOptions.Option

data object ReaderBgImageDisplayModeOptions: MenuOptions(
    Option(ReaderBackgroundImageMode.Fixed, R.string.key_bg_image_display_mode_fixed),
    Option(ReaderBackgroundImageMode.Loop, R.string.key_bg_image_display_mode_loop)
)

data object FlipAnimationOptions: MenuOptions(
    Option(ReaderFlipAnimation.None, R.string.key_flip_animation_none),
    Option(ReaderFlipAnimation.ScrollWithoutShadow, R.string.key_flip_animation_scroll)
)

data object ReaderPageLayoutOptions: MenuOptions(
    Option(ReaderPageLayoutPreference.Auto, R.string.reader_page_layout_auto),
    Option(ReaderPageLayoutPreference.Single, R.string.reader_page_layout_single),
    Option(ReaderPageLayoutPreference.Double, R.string.reader_page_layout_double)
)

data object ReaderIndicatorBatteryDisplayModeOptions: MenuOptions(
    Option(ReaderBatteryDisplayMode.Hidden, R.string.key_reader_indicator_battery_display_mode_hidden),
    Option(ReaderBatteryDisplayMode.Classic, R.string.key_reader_indicator_battery_display_mode_classic)
)

data object ReaderBackBlockModeOptions: MenuOptions(
    Option(ReaderBackBlockMode.None, R.string.key_reader_back_block_mode_none),
    Option(ReaderBackBlockMode.DoublePress, R.string.key_reader_back_block_mode_double_press),
    Option(ReaderBackBlockMode.FullyBlocked, R.string.key_reader_back_block_mode_blocked)
)
