package indi.renakoni.nextvol.ui.book.reader

import android.app.Application
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import indi.renakoni.nextvol.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class DirectorySearchResourcesTest {
    @Test fun searchPromptsAndMatchCountsResolveInEverySupportedLanguage() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        for ((language, hint) in listOf(
            "en" to "Chapter title or number",
            "zh-CN" to "搜索章节名或编号",
            "zh-TW" to "搜尋章節名或編號",
        )) {
            val config = Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
            val resources = app.createConfigurationContext(config).resources
            assertEquals(language, hint, resources.getString(R.string.reader_directory_search_hint))
            assertFalse(resources.getString(R.string.reader_directory_clear_search).isBlank())
            assertFalse(resources.getString(R.string.reader_directory_no_results).isBlank())
            for (count in listOf(0, 1, 2, 5, 21)) {
                val expected = when {
                    language.startsWith("zh") -> "找到 $count 章"
                    count == 1 -> "1 chapter found"
                    else -> "$count chapters found"
                }
                assertEquals("$language/$count", expected, resources.getQuantityString(R.plurals.reader_directory_matches, count, count))
            }
        }
    }
}
