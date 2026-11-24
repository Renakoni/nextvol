package indi.renakoni.nextvol.data.book

import indi.renakoni.nextvol.data.localbook.LocalBookStore
import org.junit.Assert.assertEquals
import org.junit.Test

class BookDescriptionTest {
    @Test fun htmlDescriptionsKeepParagraphsAndDecodeEntitiesWithoutScriptOrStyleText() {
        val raw = "<br>标签：战斗 异世界<p class=\"introduce\">摩根王国六王子被人推进井里。" +
            "<br>王子 &amp; 魔女</p>\n\t<p>边陲小镇</p><script>hidden()</script><style>p { color: red }</style>"
        assertEquals("标签：战斗 异世界\n摩根王国六王子被人推进井里。\n王子 & 魔女\n\n边陲小镇",
            bookDescriptionForDisplay(SourceBookId(LocalBookStore.SOURCE, "book").storageKey, raw))
    }
}
