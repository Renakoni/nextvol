package indi.renakoni.nextvol.ui.book.reader.content

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import indi.renakoni.nextvol.ui.book.reader.content.componet.ImageComponent
import indi.renakoni.nextvol.ui.book.reader.content.componet.SimpleTextComponent
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponent
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponentData
import io.nightfish.lightnovelreader.api.content.component.ImageComponentData
import io.nightfish.lightnovelreader.api.content.component.SimpleTextComponentData
import javax.inject.Inject
import javax.inject.Singleton

/** Creates built-in reader components without dynamic loading or reflection. */
@Singleton
class ContentComponentFactory @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val userDataRepository: UserDataRepository,
) {
    internal fun create(data: AbstractContentComponentData): AbstractContentComponent<out AbstractContentComponentData>? =
        when (data) {
            is SimpleTextComponentData -> SimpleTextComponent(data, userDataRepository, context)
            is ImageComponentData -> ImageComponent(data)
            else -> null
        }
}
