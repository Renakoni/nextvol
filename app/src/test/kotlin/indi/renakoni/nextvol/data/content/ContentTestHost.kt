package indi.renakoni.nextvol.data.content

import indi.renakoni.nextvol.data.userdata.UserDataRepository
import indi.renakoni.nextvol.ui.book.reader.content.ContentComponentFactory
import indi.renakoni.nextvol.ui.book.reader.content.ContentRenderer
import io.mockk.mockk
import org.robolectric.RuntimeEnvironment

internal class ContentTestHost {
    val registry = ContentComponentRegistry()
    val decoder = ContentJsonDecoder(registry)
    val settings = mockk<UserDataRepository>(relaxed = true)
    val renderer = ContentRenderer(decoder, ContentComponentFactory(RuntimeEnvironment.getApplication(), settings))
}
