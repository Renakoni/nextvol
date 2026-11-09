package io.nightfish.lightnovelreader.api.web.explore.filter

import io.nightfish.lightnovelreader.api.util.LocalString

/**
 * 单选过滤器
 * 保存单选过滤条件的当前值与默认选项
 *
 * @property dialogTitle 选择对话框的标题
 * @property description 过滤器的描述文字
 *
 * @since Api 2
 */
open class SingleChoiceFilter(
    private val title: LocalString,
    val dialogTitle: LocalString,
    val description: LocalString,
    private val defaultChoice: String
): Filter<String>(defaultChoice) {
    /**
     * 获取过滤器的显示标题
     *
     * @return 标题的本地化字符串
     *
     * @since Api 2
     */
    override fun getTitle(): LocalString = title

    /**
     * 获取过滤器的默认选项
     *
     * @return 默认选项的字符串
     *
     * @since Api 2
     */
    fun getDefaultChoice(): String = defaultChoice
}
