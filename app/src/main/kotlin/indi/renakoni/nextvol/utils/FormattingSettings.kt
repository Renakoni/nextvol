package indi.renakoni.nextvol.utils

object FormattingSettings {
    @Volatile var dateFormat: String = DateFormat.NUMERIC.key
        internal set

    @Volatile var dateShowYear: Boolean = true
        internal set

    @Volatile var dateOrder: String = DateOrder.AUTO.key
        internal set

    @Volatile var useRelativeTime: Boolean = true
        internal set
}
