package io.nightfish.lightnovelreader.api.web.explore.filter

import androidx.annotation.IntRange
import io.nightfish.lightnovelreader.api.util.LocalString

/**
 * 滑块过滤器的抽象基类
 * 封装以浮点数滑块输入为过滤条件的过滤器
 *
 * @property valueRange 滑块的取値范围
 * @property steps 滑块的隶山步数，0表示连续滑动
 * @property enabled 该过滤器是否已启用
 * @property displayTitle 显示到界面的标题，默认与getTitle相同
 *
 * @since Api 2
 */
abstract class SliderFilter(
    private val title: LocalString,
    defaultValue: Float,
    val valueRange: ClosedFloatingPointRange<Float>,
    @field:IntRange(from = 0) val steps: Int = 0,
) : Filter<Float>(defaultValue) {
    abstract var enabled: Boolean
    open val displayTitle = title
    /**
     * 获取过滤器的显示标题
     *
     * @return 标题的本地化字符串
     *
     * @since Api 2
     */
    override fun getTitle(): LocalString = title
}
