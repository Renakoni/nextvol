package indi.renakoni.nextvol.utils

import android.icu.text.DateTimePatternGenerator
import android.icu.util.ULocale
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * 应用内日期的显示格式
 *
 *  - NUMERIC → 2025/12/31
 *  - WRITTEN → 2025年12月31日, December 31, 2025
 */
enum class DateFormat(val key: String) {
    NUMERIC("numeric"), WRITTEN("written");

    companion object {
        fun fromString(value: String, default: DateFormat = NUMERIC): DateFormat =
            when (value.lowercase()) {
                NUMERIC.key -> NUMERIC
                WRITTEN.key -> WRITTEN
                else -> default
            }
    }
}

val appDisplayLocale: Locale
    get() = Locale.getDefault()

enum class DateOrder(val key: String) {
    AUTO("auto"), YMD("ymd"), DMY("dmy"), MDY("mdy");

    companion object {
        fun fromString(value: String): DateOrder = when (value.lowercase()) {
            YMD.key -> YMD
            DMY.key -> DMY
            MDY.key -> MDY
            else -> AUTO
        }
    }
}

fun dateFormatter(
    format: DateFormat,
    showYear: Boolean = true,
    order: DateOrder = DateOrder.AUTO
): DateTimeFormatter {
    val locale = appDisplayLocale

    return when (format) {
        DateFormat.NUMERIC -> {
            if (order == DateOrder.AUTO) {
                val generator = DateTimePatternGenerator.getInstance(
                    ULocale.forLocale(locale)
                )
                val pattern = if (showYear) "yMd" else "Md"
                DateTimeFormatter.ofPattern(generator.getBestPattern(pattern), locale)
            } else {
                DateTimeFormatter.ofPattern(
                    when {
                        showYear && order == DateOrder.DMY -> "d/M/yyyy"
                        showYear && order == DateOrder.MDY -> "M/d/yyyy"
                        showYear && order == DateOrder.YMD -> "yyyy/M/d"
                        !showYear && order == DateOrder.DMY -> "d/M"
                        else -> "M/d"
                    },
                    locale
                )
            }
        }

        DateFormat.WRITTEN -> {
            if (showYear) {
                DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale)
            } else {
                val generator = DateTimePatternGenerator.getInstance(
                    ULocale.forLocale(locale)
                )
                DateTimeFormatter.ofPattern(generator.getBestPattern("MMMMd"), locale)
            }
        }
    }
}

fun dateFormatter(): DateTimeFormatter = dateFormatter(
    DateFormat.fromString(FormattingSettings.dateFormat),
    FormattingSettings.dateShowYear,
    DateOrder.fromString(FormattingSettings.dateOrder)
)
