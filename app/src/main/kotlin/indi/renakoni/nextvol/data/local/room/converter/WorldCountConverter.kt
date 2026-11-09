package indi.renakoni.nextvol.data.local.room.converter

import androidx.room.TypeConverter
import indi.renakoni.nextvol.utils.ifEquals
import io.nightfish.lightnovelreader.api.book.WordCount

object WorldCountConverter {
    @TypeConverter
    fun worldCountToString(wordCount: WordCount) = "${wordCount.count}|.:.|${wordCount.unit}|.:.|${wordCount.unitResId}"

    @TypeConverter
    fun stringToWorld(string: String) = string.split("|.:.|").let {
        WordCount(it[0].toLong(), it[1].ifEquals("null") { null }, it[2].ifEquals("null") { null }?.toInt())
    }
}
