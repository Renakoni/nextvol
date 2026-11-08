package indi.renakoni.nextvol.ui.components

@Suppress("PropertyName", "unused")
abstract class MenuOptions {
    protected val _optionList: MutableList<Option>
    val optionList: List<Option> get() = _optionList.toList()
    constructor(vararg options: Option) {
        _optionList = options.toMutableList()
    }
    constructor(options: List<Option>) {
        _optionList = options.toMutableList()
    }
    fun option(key: String, nameId: Int): String {
        _optionList.add(Option(key, nameId))
        return key
    }

    fun get(key: String): Option =
        getOrNull(key) ?: throw NoSuchElementException("Option '$key' not found")

    fun getOrNull(key: String): Option? =
        optionList.firstOrNull { it.equals(key) }

    fun getOrDefault(key: String, default: Option): Option =
        getOrNull(key) ?: default

    open class MenuOptionsWithValues<T> : MenuOptions {

        private val optionWithValueList: MutableList<OptionWithValue<T>>

        constructor(vararg options: OptionWithValue<T>) : super(options.toList()) {
            optionWithValueList = options.toMutableList()
        }

        fun getOptionWithValue(key: String): OptionWithValue<T> =
            getOptionWithValueOrNull(key)
                ?: throw NoSuchElementException("OptionWithValue '$key' not found")

        fun getOptionWithValueOrNull(key: String): OptionWithValue<T>? =
            optionWithValueList.firstOrNull { it.equals(key) }
    }


    open class Option(
        open val key: String,
        open val nameId: Int
    ) {
        override fun equals(other: Any?): Boolean = this.key == other
        override fun hashCode(): Int = key.hashCode()
    }

    class OptionWithValue<T>(
        override val key: String,
        override val nameId: Int,
        val value: T
    ): Option(key, nameId)
}
