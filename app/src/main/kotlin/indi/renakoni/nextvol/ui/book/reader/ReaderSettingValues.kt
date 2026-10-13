@file:Suppress("ConstPropertyName")

package indi.renakoni.nextvol.ui.book.reader

// Persisted reader setting values, shared by runtime decisions and menu labels.
object ReaderBackgroundImageMode {
    const val Fixed = "fixed"
    const val Loop = "loop"
}

object ReaderFlipAnimation {
    const val None = "none"
    const val ScrollWithoutShadow = "scroll"
}

object ReaderPageLayoutPreference {
    const val Auto = "auto"
    const val Single = "single"
    const val Double = "double"
}

object ReaderBatteryDisplayMode {
    const val Hidden = "hidden"
    const val Classic = "classic"
}

object ReaderBackBlockMode {
    const val None = "none"
    const val DoublePress = "double_press"
    const val FullyBlocked = "blocked"
}
