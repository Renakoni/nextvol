package indi.renakoni.nextvol.tts.names

/*
 * Adapted from fdlibm e_log2.c and k_log.h (Sun/FreeBSD, as used by Android libm).
 * Copyright (C) 1993 by Sun Microsystems, Inc. All rights reserved.
 * Developed at SunSoft, a Sun Microsystems, Inc. business.
 * Permission to use, copy, modify, and distribute this software is freely
 * granted, provided that this notice is preserved.
 */

/** Base-two reduction avoids the extra rounding of JVM ln(x) / ln(2). */
internal fun nativeDiscoveryLog2(value: Double): Double {
    var x = value
    var bits = x.toRawBits()
    var high = (bits ushr 32).toInt()
    var exponent = 0
    if (high < 0x00100000) {
        if (x == 0.0) return Double.NEGATIVE_INFINITY
        if (x < 0.0) return Double.NaN
        exponent -= 54
        x *= 1.8014398509481984e16
        bits = x.toRawBits()
        high = (bits ushr 32).toInt()
    }
    if (high >= 0x7ff00000) return x + x
    if (x == 1.0) return 0.0
    exponent += (high shr 20) - 1023
    high = high and 0x000fffff
    val normalize = (high + 0x95f64) and 0x100000
    x = Double.fromBits(((high or (normalize xor 0x3ff00000)).toLong() shl 32) or (bits and 0xffffffffL))
    exponent += normalize shr 20
    val fraction = x - 1.0
    val halfSquare = 0.5 * fraction * fraction
    val s = fraction / (2.0 + fraction)
    val z = s * s
    val w = z * z
    val even = w * (3.999999999940941908e-1 + w * (2.222219843214978396e-1 + w * 1.531383769920937332e-1))
    val odd = z * (6.666666666666735130e-1 + w * (2.857142874366239149e-1 +
        w * (1.818357216161805012e-1 + w * 1.479819860511658591e-1)))
    val remainder = s * (halfSquare + (odd + even))
    val hi = Double.fromBits((fraction - halfSquare).toRawBits() and -0x100000000L)
    val lo = (fraction - hi) - halfSquare + remainder
    val valueHi = hi * 1.44269504072144627571
    var valueLo = (lo + hi) * 1.67517131648865118353e-10 + lo * 1.44269504072144627571
    val sum = exponent.toDouble() + valueHi
    valueLo += (exponent.toDouble() - sum) + valueHi
    return valueLo + sum
}
