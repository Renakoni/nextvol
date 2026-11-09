package indi.renakoni.nextvol.defaultplugin.wenku8.explore.expanedpage.filter

import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.util.local
import io.nightfish.lightnovelreader.api.web.explore.filter.LocalFilter
import io.nightfish.lightnovelreader.api.web.explore.filter.SingleChoiceFilter

class PublishingHouseSingleChoiceFilter : SingleChoiceFilter(
    title = "文库".local(),
    dialogTitle = "文库筛选".local(),
    description = "根据小说的文库筛选".local(),
    defaultChoice = "全部轻小说"
), LocalFilter {
    override fun filter(bookInformation: BookInformation): Boolean =
        value == this.getDefaultChoice() || bookInformation.publishingHouse == this.value
}
