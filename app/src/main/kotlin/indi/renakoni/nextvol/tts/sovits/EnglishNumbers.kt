package indi.renakoni.nextvol.tts.sovits

internal object EnglishNumbers {
    private val small = "zero one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen".split(' ')
    private val tens = "zero ten twenty thirty forty fifty sixty seventy eighty ninety".split(' ')
    fun cardinal(value: Long): String = when {
        value < 20 -> small[value.toInt()]
        value < 100 -> tens[(value / 10).toInt()] + if (value % 10 == 0L) "" else " " + cardinal(value % 10)
        value < 1000 -> cardinal(value / 100) + " hundred" + if (value % 100 == 0L) "" else " " + cardinal(value % 100)
        else -> {
            val (scale, label) = listOf(1_000_000_000_000L to "trillion", 1_000_000_000L to "billion",
                1_000_000L to "million", 1000L to "thousand").first { value >= it.first }
            cardinal(value / scale) + " " + label + if (value % scale == 0L) "" else " " + cardinal(value % scale)
        }
    }
    fun ordinal(value: Long): String {
        val words = cardinal(value)
        val last = words.substringAfterLast(' ')
        val ending = when (last) {
            "one" -> "first"; "two" -> "second"; "three" -> "third"; "five" -> "fifth"
            "eight" -> "eighth"; "nine" -> "ninth"; "twelve" -> "twelfth"
            else -> if (last.endsWith('y')) last.dropLast(1) + "ieth" else last + "th"
        }
        return words.dropLast(last.length) + ending
    }
}
