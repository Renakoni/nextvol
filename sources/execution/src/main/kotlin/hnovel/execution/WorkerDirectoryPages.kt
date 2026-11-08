package hnovel.execution

import hnovel.rules.*
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import java.net.URI

/** Hints for bounded prefetch only. The source's next-page rule still decides which pages are consumed. */
internal object WorkerDirectoryPages {
    fun evaluate(task: ExecutionTask.DirectoryPages, limits: ExecutionLimits): ExecutionResult {
        val urls = runCatching {
            if (task.next.isEmpty() || task.next.size > 2048 || task.rules.size > 8 ||
                task.rules.any { !WorkerDiscoveryReadPlan.isReadOnlyRule(it) }) emptyList()
            else if (task.next.size > 1) task.next
            else infer(task.html, task.url, task.next.single())
        }.getOrDefault(emptyList()).takeIf { it.sumOf(String::length) <= 65536 }.orEmpty()
        val output = Json.encodeToString(ExecutedRule.serializer(),
            ExecutedRule(RuleValue.Items(urls.map(RuleValue::Text)), emptyMap()))
        return if (output.toByteArray().size > limits.maxOutputBytes) ExecutionResult.Failure(FailureCode.OutputLimit)
            else ExecutionResult.Success(output)
    }

    private fun infer(html: String, url: String, next: String): List<String> {
        val current = URI(url)
        val second = URI(next)
        if (current.scheme !in setOf("http", "https") || current.rawAuthority != second.rawAuthority ||
            current.scheme != second.scheme || current.rawFragment != null || second.rawFragment != null ||
            current.rawUserInfo != null) return emptyList()
        // A numbered link must agree with the rule's next page. Only one numeric component may vary.
        val candidates = Regex("[0-9]+").findAll(next).filter { it.value == "2" }.map { match ->
            val prefix = next.substring(0, match.range.first)
            val suffix = next.substring(match.range.last + 1)
            Triple(prefix, suffix, Regex("^${Regex.escape(prefix)}([1-9][0-9]*)${Regex.escape(suffix)}$"))
        }.filter { (prefix, suffix, _) ->
            val first = URI(prefix + "1" + suffix)
            if (first == current) true else {
                // The first page often omits its page query or leaves that value empty.
                first.rawAuthority == current.rawAuthority && first.rawPath == current.rawPath && first.rawQuery != null &&
                    queryFirst(first.rawQuery, current.rawQuery, prefix.substringAfterLast('?'))
            }
        }.toList()
        if (candidates.size != 1) return emptyList()
        val (prefix, suffix, pattern) = candidates.single()
        if (Regex("(?i)(?:[?&])(?:cursor|token|nonce|signature|timestamp|offset)=").containsMatchIn(next)) return emptyList()
        val document = Jsoup.parse(html, url)
        val links = document.select("a[href],option[value]").mapNotNull { element ->
            val resolved = runCatching { resolveSourceLink(url, element.attr(if (element.tagName() == "option") "value" else "href")) }.getOrNull()
            val number = if (resolved == url) 1 else resolved?.let(pattern::matchEntire)?.groupValues?.get(1)?.toIntOrNull()
                ?: return@mapNotNull null
            Triple(element, number, element.text().trim())
        }
        // A complete page picker is another explicit enumeration, even without a Last link.
        val byElement = links.associateBy { it.first }
        val pickers = document.select("select").mapNotNull { select ->
            val options = select.select("option")
            if (options.size !in 3..2048 || options.drop(1).any { it.hasAttr("selected") }) return@mapNotNull null
            val numbered = options.map { byElement[it] ?: return@mapNotNull null }
            if (numbered.withIndex().any { (index, link) -> link.second != index + 1 ||
                    !Regex("(?i)(?:第|page\\s*)?${index + 1}(?:页)?").matches(link.third) }) return@mapNotNull null
            numbered.size
        }.distinct()
        if (pickers.isNotEmpty()) return pickers.singleOrNull()?.let { last -> (2..last).map { prefix + it + suffix } }.orEmpty()
        // Adjacent numeric labels and an explicit last-page control avoid guessing from arbitrary IDs.
        if (links.none { it.second == 2 && it.third == "2" } || links.none { it.second == 3 && it.third == "3" }) return emptyList()
        val last = links.filter { (element, _, label) ->
            element.attr("rel").split(' ').any { it.equals("last", true) } ||
                label.lowercase() in setOf("last", "last page", "末页", "尾页", "最后一页")
        }.map { it.second }.distinct().singleOrNull() ?: return emptyList()
        if (last !in 3..2048 || links.any { it.second > last }) return emptyList()
        return (2..last).map { prefix + it + suffix }
    }

    private fun queryFirst(first: String, current: String?, prefix: String): Boolean {
        val key = prefix.substringAfterLast('&').takeIf { it.endsWith('=') } ?: return false
        val parts = first.split('&')
        if (parts.count { it.startsWith(key) } != 1 || key + "1" !in parts) return false
        return current.orEmpty() == parts.filterNot { it.startsWith(key) }.joinToString("&") ||
            current == parts.joinToString("&") { if (it == key + "1") key else it }
    }
}
