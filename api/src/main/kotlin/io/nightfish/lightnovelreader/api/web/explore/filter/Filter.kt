package io.nightfish.lightnovelreader.api.web.explore.filter

import io.nightfish.lightnovelreader.api.util.LocalString

/**
 * 探索页过滤器的抽象基类
 * 子类封装具体的过滤器类型，并定义标题
 * 当过滤器的[value]发生变化时会自动触发所有已注册的监听器
 *
 * @param T 过滤器持有值的类型
 * @param default 过滤器的默认值
 *
 * @since Api 2
 */
abstract class Filter<T>(default: T) {
    private data class Listener<T> (
        val listener: (T) -> Unit
    )

    /**
     * 过滤器的当前值
     * 修改此属性时将自动触发所有注册的监听器
     *
     * @since Api 2
     */
    var value: T = default
        set(value) {
            field = value
            onChange(value)
        }
    private var listeners = mutableListOf<Listener<T>>()

    /**
     * 获取过滤器的显示标题
     *
     * @return 标题的本地化字符串
     *
     * @since Api 2
     */
    abstract fun getTitle(): LocalString

    private fun onChange(value: T) {
        listeners.forEach {
            it.listener.invoke(value)
        }
    }

    /**
     * 注册一个监听器，当[value]发生变化时调用
     *
     * @param onChange 当值发生变化时被调用的回调
     *
     * @since Api 2
     */
    fun addOnChangeListener(onChange: (T) -> Unit) {
        listeners.add(Listener(onChange))
        listeners = listeners.distinct().reversed().toMutableList()
    }
}
