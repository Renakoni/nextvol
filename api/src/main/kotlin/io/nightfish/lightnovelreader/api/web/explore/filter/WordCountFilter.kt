package io.nightfish.lightnovelreader.api.web.explore.filter

import io.nightfish.lightnovelreader.api.R
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.util.LocalString

/**
 * 字数限制过滤器
 * 用于过滤字数不足特定阈値的书本
 * 属于[LocalFilter]，支持本地过滤
 *
 * @since Api 2
 */
class WordCountFilter : SliderFilter(
    title = LocalString(R.string.filter_word_limit),
    defaultValue = 0f,
    valueRange = 0f..200_0000f,
    steps = 9
), LocalFilter {
    override var enabled: Boolean
        get() = value != 0f
        set(value) { if (!value) this.value = 0f }
    override val displayTitle = LocalString(R.string.filter_word_count)
    override fun filter(bookInformation: BookInformation): Boolean =
        !enabled || bookInformation.wordCount.count >= value
}
