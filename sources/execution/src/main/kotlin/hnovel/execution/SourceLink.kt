package hnovel.execution

import java.net.URL

/** Resolve the logical URL without executing request options or encoding chapter titles. */
fun resolveSourceLink(base: String, value: String): String {
    val option = Regex(",\\s*(?=\\{)").find(value)?.range?.first ?: value.length
    val address = value.substring(0, option).trim()
    if (address.startsWith("data:")) return address + value.substring(option)
    val baseEnd = Regex(",\\s*(?=\\{)").find(base)?.range?.first ?: base.length
    val resolved = try { if (address.startsWith("http://", true) || address.startsWith("https://", true)) URL(address)
        else URL(URL(base.substring(0, baseEnd).trim()), address) }
        catch (failure: Exception) { throw IllegalArgumentException("Invalid source URL", failure) }
    val scheme = resolved.protocol?.lowercase()
    require(scheme in setOf("http", "https") && !resolved.host.isNullOrBlank() && resolved.userInfo == null)
    return resolved.toString().replaceFirst(Regex("^[A-Za-z][A-Za-z0-9+.-]*:"), "$scheme:") + value.substring(option)
}
