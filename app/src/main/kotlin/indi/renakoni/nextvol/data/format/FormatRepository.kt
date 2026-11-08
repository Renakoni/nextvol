package indi.renakoni.nextvol.data.format

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import indi.renakoni.nextvol.data.local.room.dao.FormattingRuleDao
import indi.renakoni.nextvol.data.local.room.entity.FormattingRuleEntity
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.content.component.SimpleTextComponentData
import io.nightfish.lightnovelreader.api.explore.ExploreDisplayBook
import io.nightfish.lightnovelreader.api.text.ComponentProcessor
import io.nightfish.lightnovelreader.api.text.TextProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FormatRepository @Inject constructor(
    private val formattingRuleDao: FormattingRuleDao,
) : TextProcessor {
    override val enabled = true
    private val processorMap = mutableMapOf<String, SnapshotStateList<FormattingRule>>()

    init {
        CoroutineScope(Dispatchers.IO).launch {
            formattingRuleDao.getAllBookRuleEntityFlow().collect { formattingRuleEntities ->
                processorMap.values.forEach { it.clear() }
                for (formattingRule in formattingRuleEntities) {
                    if (!processorMap.contains(formattingRule.bookId)) processorMap[formattingRule.bookId] =
                        mutableStateListOf()
                    processorMap[formattingRule.bookId]!!.add(
                        FormattingRule(
                            id = formattingRule.id,
                            name = formattingRule.name,
                            isRegex = formattingRule.isRegex,
                            match = formattingRule.match,
                            replacement = formattingRule.replacement,
                            isEnabled = formattingRule.isEnabled
                        )
                    )
                }
            }
        }
    }

    private fun processText(bookId: String, text: String): String {
        val global = applyFormattingRules(text, processorMap.getOrDefault("", emptyList()))
        return applyFormattingRules(global, processorMap.getOrDefault(bookId, emptyList()))
    }

    suspend fun getFormattingRules(id: Int): FormattingRule? {
        val formattingRule = formattingRuleDao.getBookRuleEntity(id) ?: return null
        return FormattingRule(
            id = formattingRule.id,
            name = formattingRule.name,
            isRegex = formattingRule.isRegex,
            match = formattingRule.match,
            replacement = formattingRule.replacement,
            isEnabled = formattingRule.isEnabled
        )
    }

    suspend fun insertRule(bookId: String, formattingRule: FormattingRule) {
        formattingRuleDao.insertRuleEntity(
            FormattingRuleEntity(
                bookId = bookId,
                name = formattingRule.name,
                isRegex = formattingRule.isRegex,
                match = formattingRule.match,
                replacement = formattingRule.replacement,
                isEnabled = formattingRule.isEnabled
            )
        )
    }

    suspend fun updateRule(bookId: String, formattingRule: FormattingRule) {
        formattingRule.id ?: return
        formattingRuleDao.insertRuleEntity(
            FormattingRuleEntity(
                id = formattingRule.id,
                bookId = bookId,
                name = formattingRule.name,
                isRegex = formattingRule.isRegex,
                match = formattingRule.match,
                replacement = formattingRule.replacement,
                isEnabled = formattingRule.isEnabled,
            )
        )
    }

    suspend fun deleteRule(ruleId: Int) {
        formattingRuleDao.deleteRule(ruleId)
    }

    fun getFormattingMap(): Map<String, List<FormattingRule>> = processorMap

    fun getStateBookFormattingRules(bookId: String): List<FormattingRule> {
        if (!processorMap.contains(bookId)) processorMap[bookId] = mutableStateListOf()
        return processorMap[bookId]!!
    }

    override fun processText(text: String): String = text
    override fun List<String>.process() = this
    override fun <T> Map<T, String>.process() = this
    override fun processExploreBooksRow(exploreDisplayBook: ExploreDisplayBook): ExploreDisplayBook =
        exploreDisplayBook

    override fun processBookInformation(bookInformation: BookInformation): BookInformation =
        bookInformation.copy(
            title = processText(bookInformation.id, bookInformation.title),
            subtitle = processText(bookInformation.id, bookInformation.subtitle),
            author = processText(bookInformation.id, bookInformation.author),
            description = processText(bookInformation.id, bookInformation.description),
            publishingHouse = processText(bookInformation.id, bookInformation.publishingHouse),
    )

    override fun processBookVolumes(bookVolumes: BookVolumes): BookVolumes = bookVolumes.copy(
        volumes = bookVolumes.volumes.map { volume ->
            volume.copy(
                volumeTitle = processText(bookVolumes.bookId, volume.volumeTitle),
                chapters = volume.chapters.map {
                    it.copy(
                        title = processText(bookVolumes.bookId, it.title)
                    )
                })
        })

    override fun processChapterContent(bookId: String, chapterContent: ChapterContent, componentProcessor: ComponentProcessor): ChapterContent = chapterContent.copy(
        content = componentProcessor.apply {
            process<SimpleTextComponentData> {
                SimpleTextComponentData(processText(bookId, it.text))
            }
        }.get()
    )
}