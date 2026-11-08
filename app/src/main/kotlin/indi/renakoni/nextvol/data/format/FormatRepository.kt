package indi.renakoni.nextvol.data.format

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FormatRepository @Inject constructor(
    private val formattingRuleDao: FormattingRuleDao,
) : TextProcessor {
    override val enabled = true
    private val mutableRules = MutableStateFlow<Map<String, List<FormattingRule>>>(emptyMap())
    val rules = mutableRules.asStateFlow()

    init {
        CoroutineScope(Dispatchers.IO).launch {
            formattingRuleDao.getAllBookRuleEntityFlow().collect { formattingRuleEntities ->
                mutableRules.value = Collections.unmodifiableMap(
                    formattingRuleEntities.groupBy({ it.bookId }) { formattingRule ->
                        FormattingRule(
                            id = formattingRule.id,
                            name = formattingRule.name,
                            isRegex = formattingRule.isRegex,
                            match = formattingRule.match,
                            replacement = formattingRule.replacement,
                            isEnabled = formattingRule.isEnabled
                        )
                    }.mapValues { (_, rules) -> Collections.unmodifiableList(rules) }
                )
            }
        }
    }

    private fun processText(bookId: String, text: String): String {
        val snapshot = rules.value
        val global = applyFormattingRules(text, snapshot.getOrDefault("", emptyList()))
        return applyFormattingRules(global, snapshot.getOrDefault(bookId, emptyList()))
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