package hnovel.content

data class LoginActionResult(val messages: List<String> = emptyList(),
    val refreshDiscovery: Boolean = false)
