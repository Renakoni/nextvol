package indi.renakoni.nextvol.data.update

enum class UpdatePlatform(val key: String, val parser: UpdateParser) {
    NEXTVOL_API("NextvolAPI", NextvolApiParser),
    GITHUB("GitHub", GithubParser);

    companion object {
        val default = NEXTVOL_API
        fun fromKey(key: String?) = entries.find { it.key == key } ?: default
    }
}
