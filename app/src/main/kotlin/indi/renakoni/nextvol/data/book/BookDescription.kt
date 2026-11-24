package indi.renakoni.nextvol.data.book

import indi.renakoni.nextvol.data.web.zlibrary.ZLibrarySources
import indi.renakoni.nextvol.utils.ofId
import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode

internal fun bookDescriptionForDisplay(bookId: String, raw: String): String {
    if ('<' !in raw) return raw
    // Both adapters have already decoded their HTML into plain text.
    val source = BookIdentity.book(bookId).sourceId
    if (source == ZLibrarySources.ID || source == "Wenku8".ofId()) return raw
    val body = Jsoup.parseBodyFragment(raw).body()
    var hasMarkup = false
    for (element in body.getAllElements()) {
        if (element === body || !element.tag().isKnownTag) continue
        hasMarkup = true
        if (element.normalName() == "script" || element.normalName() == "style") {
            element.remove()
        } else if (element.isBlock) {
            element.before(TextNode("\n"))
            element.after(TextNode("\n"))
        }
    }
    return if (hasMarkup) body.wholeText().replace(Regex("\n[ \t\n]*\n"), "\n\n").trim() else raw
}
