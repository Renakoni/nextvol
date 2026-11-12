package indi.renakoni.nextvol.data.book

import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode

internal fun bookDescriptionForDisplay(raw: String): String {
    if ('<' !in raw) return raw
    val body = Jsoup.parseBodyFragment(raw).body()
    // Plain descriptions may contain angle brackets. Rule extraction already
    // handles entities, so do not decode a plain description a second time.
    if (body.getAllElements().none { it !== body && it.tag().isKnownTag }) return raw
    body.select("script,style").remove()
    body.select("br").forEach { it.before(TextNode("\n")); it.remove() }
    body.getAllElements().filter { it !== body && it.isBlock }.forEach {
        it.before(TextNode("\n")); it.after(TextNode("\n"))
    }
    return body.wholeText().replace(Regex("\n{3,}"), "\n\n").trim()
}
