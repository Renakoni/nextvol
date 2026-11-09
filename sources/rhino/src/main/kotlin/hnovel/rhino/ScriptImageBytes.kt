package hnovel.rhino

import org.mozilla.javascript.NativeArray
import java.util.Base64

/** Preserve the script's signed-byte array contract without a decimal JSON round trip. */
internal fun imageBytesResult(value: Any?, maxEncodedChars: Int): String {
    val array = value as? NativeArray ?: throw UnsupportedResult()
    // The existing output envelope owns capacity; bound allocation before converting the array.
    if (array.length > maxEncodedChars.toLong() / 4 * 3) throw ResultTooLarge()
    val bytes = ByteArray(array.length.toInt()) { index ->
        val number = (array.get(index, array) as? Number)?.toDouble() ?: throw UnsupportedResult()
        val integer = number.toInt()
        if (number != integer.toDouble() || integer !in -128..255) throw UnsupportedResult()
        integer.toByte()
    }
    return Base64.getEncoder().encodeToString(bytes)
}
