package indi.renakoni.nextvol.ui.book.reader

import android.app.Application
import android.net.Uri
import androidx.compose.ui.graphics.Color
import indi.renakoni.nextvol.data.local.room.dao.UserDataDao
import indi.renakoni.nextvol.data.local.room.entity.UserDataEntity
import indi.renakoni.nextvol.data.userdata.UserDataRepository
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentHashMap

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class ReaderSettingsBoundaryTest {
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun settingAdapterPreservesDefaultsAndStoragePaths() {
        val state = SettingState(UserDataRepository(InMemoryUserDataDao()), scope)

        assertEquals(15f, state.fontSize)
        assertEquals(7f, state.fontLineHeight)
        assertEquals(true, state.enableHideStatusBar)
        assertEquals("classic", state.batteryIndicatorDisplayMode)
        assertEquals("FollowSystem", state.darkModeKey)
        assertEquals(UserDataPath.Reader.FontSize.path, state.fontSizeUserData.path)
        assertEquals(UserDataPath.Reader.EnableHideStatusBar.path, state.enableHideStatusBarUserData.path)
        assertEquals(UserDataPath.Settings.Display.DarkMode.path, state.darkModeKeyUserData.path)
        assertEquals(false, state.isUsingVolumeKeyFlip)
        assertEquals(-1f, state.volumeKeyContinuousFlipInterval)
        assertEquals(0.6f, state.volumeKeyScrollFraction)
        assertEquals("reader.isUsingVolumeKeyFlip", state.isUsingVolumeKeyFlipUserData.path)
        assertEquals("reader.volumeKeyScrollFraction", state.volumeKeyScrollFractionUserData.path)
    }

    @Test
    fun reducedMotionRestoresWithoutRewritingPaperLayoutOrOriginalAnimation() = runBlocking {
        val dao = InMemoryUserDataDao()
        val first = SettingState(UserDataRepository(dao), scope)
        assertEquals(false, first.reduceMotion)
        first.flipAnimeUserData.set("scroll")
        first.paperIdUserData.set("sage")
        first.fontSizeUserData.set(21f)
        first.reduceMotionUserData.set(true)
        val restored = SettingState(UserDataRepository(dao), scope)
        withTimeout(5_000) {
            while (!restored.reduceMotion || restored.paperId != "sage" || restored.fontSize != 21f) delay(1)
        }
        assertEquals(false, restored.animatePageTurns)
        assertEquals("scroll", restored.flipAnime)
        first.reduceMotionUserData.set(false)
        withTimeout(5_000) { while (restored.reduceMotion) delay(1) }
        assertEquals(true, restored.animatePageTurns)
        assertEquals("sage", restored.paperId)
        assertEquals(21f, restored.fontSize)
    }

    @Test
    fun pageLayoutMissingAndUnknownValuesReadAsAutoWithoutRewritingStorage() = runBlocking {
        val dao = InMemoryUserDataDao()
        val state = SettingState(UserDataRepository(dao), scope)
        assertEquals("reader.pageLayout", state.pageLayoutUserData.path)
        assertEquals("auto", state.pageLayout)
        assertNull(dao.get(UserDataPath.Reader.PageLayout.path))
        for (unknown in listOf("", "future-layout", "DOUBLE")) {
            state.pageLayoutUserData.set("single")
            withTimeout(5_000) { while (state.pageLayout != "single") delay(1) }
            state.pageLayoutUserData.set(unknown)
            withTimeout(5_000) { while (state.pageLayout != "auto") delay(1) }
            val restored = SettingState(UserDataRepository(dao), scope)
            assertEquals("auto", restored.pageLayout)
            assertEquals(unknown, dao.get(UserDataPath.Reader.PageLayout.path))
        }
    }

    @Test
    fun pageLayoutSynchronizesAndRestoresWithoutChangingOtherReaderSettings() = runBlocking {
        val dao = InMemoryUserDataDao()
        val first = SettingState(UserDataRepository(dao), scope)
        val second = SettingState(UserDataRepository(dao), scope)
        first.isUsingFlipPageUserData.set(false)
        first.isUsingContinuousScrollingUserData.set(true)
        first.flipAnimeUserData.set("none")
        first.autoPaddingUserData.set(false)
        first.leftPaddingUserData.set(27f)
        first.paperIdUserData.set("sage")
        val otherSettings = dao.getAllEntities().associate { it.path to it.value }
        for (value in listOf("auto", "single", "double")) {
            first.pageLayoutUserData.set(value)
            val restored = SettingState(UserDataRepository(dao), scope)
            withTimeout(5_000) {
                while (first.pageLayout != value || second.pageLayout != value || restored.pageLayout != value) delay(1)
            }
            assertEquals(value, dao.get(UserDataPath.Reader.PageLayout.path))
            assertEquals(otherSettings, dao.getAllEntities().filter { it.path != UserDataPath.Reader.PageLayout.path }
                .associate { it.path to it.value })
        }
        second.pageLayoutUserData.set("single")
        withTimeout(5_000) { while (first.pageLayout != "single") delay(1) }
        assertEquals("single", dao.get(UserDataPath.Reader.PageLayout.path))
    }

    @Test
    fun paperChoicePersistsWithoutReplacingCustomAppearanceOrLayout() = runBlocking {
        val dao = InMemoryUserDataDao()
        val first = SettingState(UserDataRepository(dao), scope)
        assertEquals("default", first.paperId)
        first.fontSizeUserData.set(21f)
        first.fontLineHeightUserData.set(9f)
        first.textColorUserData.set(Color.Blue)
        first.backgroundColorUserData.set(Color.Yellow)
        first.enableBackgroundImageUserData.set(true)
        val image = Uri.parse("file:///saved-reader-background.png")
        first.backgroundImageUriUserData.set(image)
        first.paperIdUserData.set("sage")
        val restored = SettingState(UserDataRepository(dao), scope)
        withTimeout(5_000) {
            while (restored.paperId != "sage" || restored.backgroundImageUri != image ||
                restored.fontSize != 21f || restored.fontLineHeight != 9f ||
                restored.textColor != Color.Blue || restored.backgroundColor != Color.Yellow ||
                !restored.enableBackgroundImage) delay(1)
        }
        assertEquals("sage", dao.get("reader.paperId"))
        assertEquals(21f, restored.fontSize)
        assertEquals(9f, restored.fontLineHeight)
        assertEquals(Color.Blue, restored.textColor)
        assertEquals(Color.Yellow, restored.backgroundColor)
        assertEquals(false, restored.usesBackgroundImage)
        assertEquals(true, restored.enableBackgroundImage)
        assertEquals("FollowSystem", restored.darkModeKey)
        first.paperIdUserData.set("default")
        withTimeout(5_000) { while (restored.paperId != "default") delay(1) }
        assertEquals(true, restored.usesBackgroundImage)
        assertEquals(image, restored.backgroundImageUri)
        assertEquals(Color.Blue, restored.textColor)
        assertEquals(Color.Yellow, restored.backgroundColor)
    }

    @Test
    fun volumeScrollSettingPersistsAndInvalidStoredValuesFallBack() = runBlocking {
        val dao = InMemoryUserDataDao()
        val first = SettingState(UserDataRepository(dao), scope)
        first.isUsingVolumeKeyFlipUserData.set(true)
        first.volumeKeyScrollFractionUserData.set(0.25f)
        val restored = SettingState(UserDataRepository(dao), scope)
        withTimeout(5_000) {
            while (!restored.isUsingVolumeKeyFlip || restored.volumeKeyScrollFraction != 0.25f) delay(1)
        }
        assertEquals("0.25", dao.get("reader.volumeKeyScrollFraction"))
        for (raw in listOf("NaN", "Infinity", "-Infinity", "0", "-1", "1.1", "invalid")) {
            dao.insert("reader.volumeKeyScrollFraction", "reader", "Float", raw)
            val invalid = SettingState(UserDataRepository(dao), scope)
            withTimeout(5_000) {
                while (first.volumeKeyScrollFraction != 0.6f) delay(1)
            }
            assertEquals(0.6f, invalid.volumeKeyScrollFraction)
            first.volumeKeyScrollFractionUserData.set(0.25f)
            withTimeout(5_000) {
                while (first.volumeKeyScrollFraction != 0.25f) delay(1)
            }
        }
    }

    @Test
    fun lineAndParagraphSpacingPersistIndependentlyAndInvalidLayoutValuesAreSafe() = runBlocking {
        val dao = InMemoryUserDataDao()
        val first = SettingState(UserDataRepository(dao), scope)
        assertEquals(0f, first.paragraphSpacing)
        first.fontLineHeightUserData.set(12.5f)
        first.paragraphSpacingUserData.set(3.5f)
        first.topPaddingUserData.set(24f)
        val restored = SettingState(UserDataRepository(dao), scope)
        withTimeout(5_000) {
            while (restored.fontLineHeight != 12.5f || restored.paragraphSpacing != 3.5f || restored.topPadding != 24f) delay(1)
        }
        assertEquals("3.5", dao.get("reader.paragraphSpacing"))
        first.paragraphSpacingUserData.set(0f)
        withTimeout(5_000) { while (restored.paragraphSpacing != 0f) delay(1) }
        assertEquals(12.5f, restored.fontLineHeight)
        first.fontLineHeightUserData.set(Float.NaN)
        first.fontWeighUserData.set(2000f)
        first.fontSizeUserData.set(Float.POSITIVE_INFINITY)
        first.leftPaddingUserData.set(-20f)
        withTimeout(5_000) {
            while (restored.fontLineHeight != 7f || restored.fontWeigh != 900f || restored.leftPadding != 0f) delay(1)
        }
        assertEquals(15f, restored.fontSize)
    }

    @Test
    fun allMarginsAndAutomaticModePersistWithoutReplacingManualValues() = runBlocking {
        val dao = InMemoryUserDataDao()
        val first = SettingState(UserDataRepository(dao), scope)
        first.autoPaddingUserData.set(false)
        first.topPaddingUserData.set(24f)
        first.bottomPaddingUserData.set(48f)
        first.leftPaddingUserData.set(32f)
        first.rightPaddingUserData.set(40f)
        val restored = SettingState(UserDataRepository(dao), scope)
        withTimeout(5_000) {
            while (restored.autoPadding || restored.topPadding != 24f || restored.bottomPadding != 48f ||
                restored.leftPadding != 32f || restored.rightPadding != 40f) delay(1)
        }
        first.autoPaddingUserData.set(true)
        withTimeout(5_000) { while (!restored.autoPadding) delay(1) }
        first.autoPaddingUserData.set(false)
        withTimeout(5_000) { while (restored.autoPadding) delay(1) }
        assertEquals(listOf(24f, 48f, 32f, 40f),
            listOf(restored.topPadding, restored.bottomPadding, restored.leftPadding, restored.rightPadding))
    }

    @Test
    fun adapterCanBeNarrowedToReaderAndThemeCapabilities() {
        val state = SettingState(UserDataRepository(InMemoryUserDataDao()), scope)
        val readerSettings: ReaderSettings = state
        val readerEditor: ReaderSettingsEditor = state
        val themeSettings: ThemeSettings = state
        val themeEditor: ThemeSettingsEditor = state

        assertSame(state, readerSettings)
        assertSame(state, readerEditor)
        assertSame(state, themeSettings)
        assertSame(state, themeEditor)
        assertEquals(state.fontSize, readerSettings.fontSize)
        assertEquals(state.darkModeKey, themeSettings.darkModeKey)
    }

    @Test
    fun editingOneSharedSettingPropagatesToBothObservedStates() = runBlocking {
        val dao = InMemoryUserDataDao()
        val first = SettingState(UserDataRepository(dao), scope)
        val second = SettingState(UserDataRepository(dao), scope)

        first.fontSizeUserData.set(22f)

        withTimeout(5_000) {
            while (first.fontSize != 22f || second.fontSize != 22f) delay(1)
        }
        assertEquals("22.0", dao.get(UserDataPath.Reader.FontSize.path))
    }

    @Test
    fun concurrentWritesKeepPersistedValueAndObservedFlowAtomic() = runBlocking {
        val dao = InMemoryUserDataDao()
        val path = UserDataPath.Reader.FontSize.path
        val emissions = Channel<String?>(Channel.UNLIMITED)
        val collector = launch(Dispatchers.Unconfined) {
            dao.getFlow(path).collect { emissions.send(it) }
        }

        try {
            withTimeout(5_000) {
                assertNull(emissions.receive())
                dao.insert(path, "reader", "float", "19.0")
                assertEquals("19.0", emissions.receive())

                val ready = Channel<Unit>(2)
                val start = CompletableDeferred<Unit>()
                coroutineScope {
                    val writers = listOf("20.0", "21.0").map { value ->
                        async(Dispatchers.Default) {
                            ready.send(Unit)
                            start.await()
                            dao.insert(path, "reader", "float", value)
                        }
                    }
                    repeat(2) { ready.receive() }
                    start.complete(Unit)
                    writers.awaitAll()
                }

                val stored = dao.get(path)
                val observed = mutableListOf<String?>()
                do {
                    observed += emissions.receive()
                } while (observed.last() != stored)
                // StateFlow may conflate overlapping writes; the live observer must
                // converge to the persisted value without requiring every intermediate value.
                assertTrue(observed.all { it == "20.0" || it == "21.0" })
                assertEquals(stored, observed.last())
                assertEquals(stored, dao.getFlow(path).first())
            }
        } finally {
            collector.cancel()
        }
    }

    private class InMemoryUserDataDao : UserDataDao {
        private val values = ConcurrentHashMap<String, UserDataEntity>()
        private val flows = ConcurrentHashMap<String, MutableStateFlow<String?>>()
        private val writeLock = Any()

        override suspend fun insert(path: String, group: String, type: String, value: String) {
            synchronized(writeLock) {
                values[path] = UserDataEntity(path, group, type, value)
                flows.computeIfAbsent(path) { MutableStateFlow(values[path]?.value) }.value = value
            }
        }

        override suspend fun insert(userDataEntity: UserDataEntity) {
            insert(
                userDataEntity.path,
                userDataEntity.group,
                userDataEntity.type,
                userDataEntity.value
            )
        }

        override suspend fun get(path: String): String? = synchronized(writeLock) { values[path]?.value }

        override fun getFlow(path: String): Flow<String?> = synchronized(writeLock) {
            flows.computeIfAbsent(path) { MutableStateFlow(values[path]?.value) }
        }

        override fun getEntity(path: String): UserDataEntity? = synchronized(writeLock) { values[path] }

        override fun getGroupValues(group: String): List<UserDataEntity> =
            values.values.filter { it.group == group }

        override suspend fun remove(path: String) {
            synchronized(writeLock) {
                values.remove(path)
                flows.computeIfAbsent(path) { MutableStateFlow(null) }.value = null
            }
        }

        override fun getAllEntities(): List<UserDataEntity> = values.values.toList()
    }
}
