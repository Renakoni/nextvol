package indi.renakoni.nextvol.tts.offline

import android.app.Application
import android.content.Context
import hnovel.network.StorageCipher
import hnovel.speech.OfflineEngineConfig
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class OfflineEngineRepositoryTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun engineSettingsWorkBeforeImportAndSurviveReimportAndRestart() = runBlocking {
        val root = files.newFolder()
        val context = mockk<Context> { every { filesDir } returns root }
        fun repository() = OfflineEngineRepository(context, StorageCipher.Plain)
        val first = repository()
        assertEquals(16, first.all().size)
        val edited = first.get("sherpa")!!.copy(name = "My Sherpa", parameters = "{\"threads\":4}", note = "My notes")
        first.save(edited)
        first.register(OfflineEngineConfig("sherpa", "Package name", parameters = "package defaults"))
        assertEquals(edited, repository().get("sherpa"))

        val imported = OfflineEngineConfig("xiaomi", "Package Xiaomi", parameters = "shared package parameters")
        first.register(imported)
        first.register(imported.copy(parameters = "another voice package"))
        assertEquals(imported, repository().get("xiaomi"))
        assertEquals(16, repository().all().size)
    }
}
