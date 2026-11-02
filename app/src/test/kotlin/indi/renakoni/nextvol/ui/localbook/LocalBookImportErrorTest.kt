package indi.renakoni.nextvol.ui.localbook

import android.app.Application
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.localbook.LocalBookImportFailure
import indi.renakoni.nextvol.data.localbook.LocalBookImportReason
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class, qualifiers = "en-rUS")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class LocalBookImportErrorTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    @Before fun create() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        activity.setup()
    }
    @After fun destroy() { activity.pause().stop().destroy() }

    @Test fun everyReasonUsesTranslatedResourcesInAllSupportedLanguages() {
        val context = RuntimeEnvironment.getApplication()
        val locales = listOf("en", "zh-CN", "zh-TW")
        val messages = locales.associateWith { locale ->
            val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(locale))
            })
            LocalBookImportReason.entries.associateWith { localized.getString(it.messageResource()) }
        }
        for (reason in LocalBookImportReason.entries) {
            assertTrue(messages.getValue("en").getValue(reason).isNotBlank())
            for (locale in listOf("zh-CN", "zh-TW")) {
                assertNotEquals("$locale: $reason must not fall back to English",
                    messages.getValue("en").getValue(reason), messages.getValue(locale).getValue(reason))
            }
        }
    }

    @Test fun diagnosticsAreInitiallyHiddenAndCanBeExpandedAndCollapsed() {
        val failure = LocalBookImportFailure(LocalBookImportReason.Unknown, "raw diagnostic details")
        activity.get().setContent { MaterialTheme {
            LocalBookImportError(failure, Modifier.width(360.dp))
        } }
        compose.onNodeWithText(activity.get().getString(R.string.local_import_error_unknown)).assertIsDisplayed()
        compose.onNodeWithText(failure.details).assertDoesNotExist()
        compose.onNodeWithText("Show diagnostic details").performClick()
        compose.onNodeWithText(failure.details).assertIsDisplayed()
        compose.onNodeWithText("Hide diagnostic details").performClick()
        compose.onNodeWithText(failure.details).assertDoesNotExist()
    }

    @Test @Config(qualifiers = "zh-rCN-w360dp-h800dp")
    fun localizedMainMessageAndDetailsActionRemainAccessibleWithLargeTextInDarkTheme() {
        activity.get().setContent { MaterialTheme(colorScheme = darkColorScheme()) {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                LocalBookImportError(LocalBookImportFailure(LocalBookImportReason.EmptyFile, "The selected file is empty."), Modifier.width(360.dp))
            }
        } }
        compose.onNodeWithText("所选文件是空的。请选择包含正文的文件。").assertIsDisplayed()
        compose.onNodeWithText("The selected file is empty.").assertDoesNotExist()
        compose.onNodeWithText("查看诊断详情").assertIsDisplayed().performClick()
        compose.onNodeWithText("The selected file is empty.").assertIsDisplayed()
    }
}
