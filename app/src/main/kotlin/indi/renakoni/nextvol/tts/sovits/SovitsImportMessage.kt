package indi.renakoni.nextvol.tts.sovits

import androidx.annotation.StringRes

internal class SovitsImportMessage(@param:StringRes val resource: Int, vararg val arguments: Any)

internal class SovitsImportException(val text: SovitsImportMessage) : IllegalArgumentException()

internal inline fun requireSovits(condition: Boolean, message: () -> SovitsImportMessage) {
    if (!condition) throw SovitsImportException(message())
}

internal fun errorSovits(message: SovitsImportMessage): Nothing = throw SovitsImportException(message)
