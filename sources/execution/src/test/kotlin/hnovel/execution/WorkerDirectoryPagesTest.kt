package hnovel.execution

import hnovel.rules.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class WorkerDirectoryPagesTest {
    private fun plan(url: String, page: (Int) -> String, last: Int = 21, lastLabel: String = "Last",
        rules: List<String> = listOf("a@href"), numbers: List<Int> = listOf(2, 3, 4, 5, 6)): List<String> {
        val html = numbers.joinToString("") { "<a href='${page(it)}'>$it</a>" } +
            "<a href='${page(last)}'>$lastLabel</a>"
        return plan(html, url, page(2), rules)
    }

    private fun plan(html: String, url: String, next: String, rules: List<String> = listOf("a@href")): List<String> {
        val result = WorkerDirectoryPages.evaluate(ExecutionTask.DirectoryPages(html, url, listOf(next), rules), ExecutionLimits())
        assertTrue(result.toString(), result is ExecutionResult.Success)
        return (Json.decodeFromString(ExecutedRule.serializer(), (result as ExecutionResult.Success).output).value as RuleValue.Items)
            .values.map { (it as RuleValue.Text).value }
    }

    @Test fun queryAndPathPaginationFillTheGapBeforeLastWithoutChangingBookOrSort() {
        for (first in listOf("https://example.org/book/", "https://example.org/book/?p=", "https://example.org/book/?p=1")) {
            val page = { n: Int -> "https://example.org/book/?p=$n" }
            assertEquals((2..21).map(page), plan(first, page))
        }
        val query = { n: Int -> "https://example.org/toc?id=123&page=$n&sort=asc" }
        assertEquals((2..21).map(query), plan(query(1), query, lastLabel = "末页"))
        val path = { n: Int -> "https://example.org/book123/toc_$n.html" }
        assertEquals((2..21).map(path), plan(path(1), path, lastLabel = "尾页"))
    }

    @Test fun ambiguousStatefulOrUnboundedPatternsRemainSequential() {
        val page = { n: Int -> "https://example.org/book/?p=$n" }
        assertTrue(plan(page(1), page, lastLabel = "21").isEmpty())
        assertTrue(plan(page(1), page, numbers = listOf(2, 4, 6)).isEmpty())
        assertTrue(plan(page(1), page, last = 2049).isEmpty())
        assertTrue(plan("https://other.org/book/?p=1", page).isEmpty())
        assertTrue(plan("https://example.org/other/?p=1", page).isEmpty())
        for (key in listOf("cursor", "offset", "token")) {
            val stateful = { n: Int -> "https://example.org/book/?$key=$n" }
            assertTrue(plan(stateful(1), stateful).isEmpty())
        }
        for (rule in listOf("@js:java.ajax('/token');result", "@js:cookie.setCookie(baseUrl,'a=b');result",
            "@js:org.jsoup.Jsoup.parse(result).select('a').attr('href','/other')", "@js:var org=result;org.jsoup.Jsoup.parse(result)"))
            assertTrue(rule, plan(page(1), page, rules = listOf(rule)).isEmpty())
    }

    @Test fun completeDropdownMustStartAtCurrentPageAndContainEveryNumberInOrder() {
        val url = "https://example.org/book/"
        val next = url + "?page=2"
        fun picker(numbers: List<Int>, selected: Int = 1) = "<select>" + numbers.joinToString("") {
            "<option value='${if (it == 1) url else url + "?page=$it"}' ${if (it == selected) "selected" else ""}>Page $it</option>"
        } + "</select>"
        assertEquals((2..6).map { url + "?page=$it" }, plan(picker((1..6).toList()), url, next))
        for (invalid in listOf(picker(listOf(1, 2, 3, 6)), picker((1..6).toList(), selected = 2),
            picker((1..6).reversed().toList()), picker((1..6).toList()) + picker((1..5).toList())))
            assertTrue(plan(invalid, url, next).isEmpty())
    }
}
