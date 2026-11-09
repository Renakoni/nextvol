package indi.renakoni.nextvol.data.book

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import indi.renakoni.nextvol.R
import io.nightfish.lightnovelreader.api.book.WordCount
import java.text.NumberFormat
import java.util.Locale

private fun Long.numberTransform(): String =
    when {
        0 <= this && this == 1_000L -> NumberFormat.getNumberInstance(Locale.getDefault()).format(this)
        this in 1_000L..<1_000_000L -> NumberFormat.getNumberInstance(Locale.getDefault()).format(this / 1000) + "K"
        1_000_000 <= this -> NumberFormat.getNumberInstance(Locale.getDefault()).format(this / 10000) + "W"
        else -> this.toString()
    }

@Composable
fun WordCount.get(): String =
    if (unit != null)
        if (unit!!.contains("{count}")) unit!!.replace("{count}", count.numberTransform())
        else "$count $unit"
    else if (unitResId != null)
        stringResource(unitResId!!).let {
            if (it.contains("{count}")) it.replace("{count}", count.numberTransform())
            else "$count $it"
        }
    else stringResource(R.string.book_info_word_count_kilo, count.numberTransform())
