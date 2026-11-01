package indi.renakoni.nextvol.tts.offline

import android.app.Application
import android.content.Context
import hnovel.network.StorageCipher
import hnovel.speech.SpeechEngineConfig
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

    @Test fun onlyRegisteredEnginesPersistWithTheirSettingsAndDisabledState() = runBlocking {
        val root = files.newFolder()
        val context = mockk<Context> { every { filesDir } returns root }
        fun repository() = OfflineEngineRepository(context, StorageCipher.Plain)
        val first = repository()
        assertTrue(first.all().isEmpty())
        first.register(SpeechEngineConfig("sherpa", "Sherpa"))
        val edited = first.get("sherpa")!!.copy(name = "My Sherpa", note = "My notes", enabled = false, order = 2)
        first.save(edited)
        first.register(SpeechEngineConfig("sherpa", "Package name", parameters = "package defaults"))
        assertEquals(edited, repository().get("sherpa"))

        val imported = SpeechEngineConfig("xiaomi", "Package Xiaomi", parameters = "shared package parameters")
        first.register(imported)
        first.register(imported.copy(parameters = "another voice package"))
        assertEquals(imported, repository().get("xiaomi"))
        assertEquals(2, repository().all().size)
    }
}
