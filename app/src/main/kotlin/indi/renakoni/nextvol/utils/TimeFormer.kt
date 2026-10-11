package indi.renakoni.nextvol.utils

import android.icu.text.RelativeDateTimeFormatter
import android.icu.text.RelativeDateTimeFormatter.Direction
import android.icu.text.RelativeDateTimeFormatter.RelativeUnit
import android.icu.text.MeasureFormat
import android.icu.text.NumberFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.time.Duration.Companion.seconds

fun formTime(
    time: LocalDateTime,
    dateFormat: DateFormat = DateFormat.NUMERIC,
    useRelativeTime: Boolean = true,
): String {
    if (time == LocalDateTime.MIN) return "-"

    val now = LocalDateTime.now()
    val locale = appDisplayLocale

    val absFormatter = dateFormatter(
        dateFormat,
        FormattingSettings.dateShowYear,
        DateOrder.fromString(FormattingSettings.dateOrder)
    ).withLocale(locale)

    if (!useRelativeTime) {
        return time.format(absFormatter)
    }

    val minutesAgo = ChronoUnit.MINUTES.between(time, now)
    val hoursAgo = ChronoUnit.HOURS.between(time, now)

    val rdf = RelativeDateTimeFormatter.getInstance(locale)

    return when {
        hoursAgo >= 72 -> time.format(absFormatter)
        hoursAgo >= 24 -> rdf.format((hoursAgo / 24).toDouble(), Direction.LAST, RelativeUnit.DAYS)
        hoursAgo >= 1 -> rdf.format(hoursAgo.toDouble(), Direction.LAST, RelativeUnit.HOURS)
        minutesAgo >= 1 -> rdf.format(minutesAgo.toDouble(), Direction.LAST, RelativeUnit.MINUTES)
        minutesAgo in 0..1  -> rdf.format(minutesAgo.toDouble(), Direction.LAST, RelativeUnit.MINUTES)
        else -> time.format(absFormatter)
    }
}

fun formTime(time: LocalDateTime): String =
    formTime(time, DateFormat.fromString(FormattingSettings.dateFormat), FormattingSettings.useRelativeTime)

fun formMinutes(totalMinutes: Int): String =
    formReadingDuration(totalMinutes.toLong() * 60)

fun formReadingDuration(totalSeconds: Int): String =
    formReadingDuration(totalSeconds.toLong())

fun formReadingDuration(totalSeconds: Long): String {
    val df = DurationFormat(appDisplayLocale)

    return when {
        totalSeconds < 60 -> df.format(
            totalSeconds.coerceAtLeast(0).seconds,
            DurationFormat.Unit.SECOND,
            DurationFormat.Unit.SECOND
        )

        totalSeconds < 60 * 60 -> df.format(
            totalSeconds.seconds,
            DurationFormat.Unit.MINUTE,
            DurationFormat.Unit.MINUTE
        )

        else -> {
            val numberFormat = NumberFormat.getNumberInstance(appDisplayLocale).apply {
                minimumFractionDigits = 1
                maximumFractionDigits = 1
            }
            MeasureFormat.getInstance(
                appDisplayLocale,
                MeasureFormat.FormatWidth.NARROW,
                numberFormat
            ).format(Measure(totalSeconds / (60 * 60.0), MeasureUnit.HOUR))
        }
    }
}
