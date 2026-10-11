package indi.renakoni.nextvol.benchmark

import android.os.Bundle
import android.os.Trace
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.github.michaelbull.result.get
import dagger.hilt.android.AndroidEntryPoint
import indi.renakoni.nextvol.data.book.ChapterRepository
import indi.renakoni.nextvol.data.content.component.SimpleTextComponent
import indi.renakoni.nextvol.data.local.room.NextVolDatabase
import indi.renakoni.nextvol.data.web.BackgroundSourceRequest
import indi.renakoni.nextvol.data.web.SourceRegistration
import indi.renakoni.nextvol.data.web.WebSourceRegistry
import indi.renakoni.nextvol.theme.NextVolTheme
import indi.renakoni.nextvol.ui.LocalReaderBookId
import indi.renakoni.nextvol.ui.book.reader.LocalReaderTextLayout
import indi.renakoni.nextvol.ui.book.reader.ReaderViewModel
import indi.renakoni.nextvol.ui.book.reader.rememberReaderTextLayout
import indi.renakoni.nextvol.ui.book.reader.content.ContentComponent
import indi.renakoni.nextvol.ui.book.reader.content.flip.FlipPageContentUiState
import indi.renakoni.nextvol.ui.book.reader.content.componet.LocalReaderTextDrawObserver
import indi.renakoni.nextvol.ui.book.reader.content.componet.ReaderTextFragment
import io.nightfish.lightnovelreader.api.ui.LocalReaderStyle
import io.nightfish.lightnovelreader.api.ui.LocalNavController
import io.nightfish.lightnovelreader.api.ui.ReaderStyle
import io.nightfish.lightnovelreader.api.web.WebDataSourcePriority
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Test-only controls around the production VM, repository, controllers and renderers. */
@AndroidEntryPoint
@android.annotation.TargetApi(29)
class ReaderPerformanceActivity : ComponentActivity() {
    @Inject lateinit var registry: WebSourceRegistry
    @Inject lateinit var chapters: ChapterRepository
    private val model: ReaderViewModel by viewModels()
    private lateinit var registration: SourceRegistration
    private var background: Job? = null
    private var status by mutableStateOf("starting")
    private var ping by mutableStateOf(0)
    private var opened by mutableStateOf(false)
    private var traceCookie: Int? = null
    private var interactionCookie: Int? = null
    private var expectedFont: Float? = null
    private var expectedFlip: Boolean? = null
    private var sequence = 0
    private var requiredOffset = 0
    private var lastVisibleOffset = 0
    private var firstReadable = true
    private var firstVisibleY = Float.MAX_VALUE
    private var pendingDraw: DrawEvidence? = null
    private var completionPosted = false
    private lateinit var originalText: Map<String, String>
    private data class DrawEvidence(val chapter: String, val offset: Int, val preserved: Boolean)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(android.os.Build.VERSION.SDK_INT >= 29) { "Reader benchmark requires API 29+" }
        ReaderPerformanceStats.reset()
        begin("startup")
        interactionCookie = cookies.incrementAndGet().also { Trace.beginAsyncSection("reader.to_interactive", it) }
        originalText = listOf("1", "2").associateWith {
            ReaderPerformanceFixture.text(it, ReaderPerformanceFixture.profile(this))
        }
        window.decorView.viewTreeObserver.addOnPreDrawListener { firstVisibleY = Float.MAX_VALUE; true }
        registry.unregister(ReaderPerformanceFixture.sourceId)
        registration = registry.register(ReaderPerformanceSource(this), ReaderPerformanceFixture.metadata)
        lifecycleScope.launch {
            val last = withContext(Dispatchers.IO) {
                NextVolDatabase.getInstance(this@ReaderPerformanceActivity).userReadingDataDao()
                    .getEntity(ReaderPerformanceFixture.book.storageKey)
            }
            model.openBook(ReaderPerformanceFixture.book.storageKey,
                last?.lastReadChapterId ?: ReaderPerformanceFixture.chapter("1"))
            opened = true
        }
        setContent {
            NextVolTheme("Disabled", false, "Default", "Default", "en-US") {
                val settings = model.readerSettings
                val layout = rememberReaderTextLayout(settings)
                CompositionLocalProvider(
                    LocalNavController provides rememberNavController(),
                    LocalReaderBookId provides ReaderPerformanceFixture.book.storageKey,
                    LocalReaderTextLayout provides layout,
                    LocalReaderStyle provides ReaderStyle(settings.fontSize, settings.fontLineHeight,
                        settings.fontWeigh, settings.textColor, settings.textDarkColor),
                    LocalReaderTextDrawObserver provides ::drawn,
                ) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                        Row {
                            TextButton(onClick = { navigate("1") }) { Text("Previous") }
                            TextButton(onClick = { navigate("2") }) { Text("Next") }
                            TextButton(onClick = {
                                ping++
                                interactionCookie?.let { Trace.endAsyncSection("reader.to_interactive", it) }
                                interactionCookie = null
                            }) { Text("Ping") }
                            TextButton(onClick = ::refresh) { Text("Refresh") }
                        }
                        Row {
                            TextButton(onClick = ::startBackground) { Text("Busy") }
                            TextButton(onClick = {
                                ReaderPerformanceStats.cancelRequestedNs = System.nanoTime()
                                background?.cancel()
                            }) { Text("Cancel") }
                            TextButton(onClick = {
                                begin("font", lastVisibleOffset)
                                lifecycleScope.launch { settings.fontSizeUserData.set(settings.fontSize + 2f) }
                            }) { Text("Bigger") }
                            TextButton(onClick = {
                                begin("mode", lastVisibleOffset)
                                lifecycleScope.launch { settings.isUsingFlipPageUserData.set(!settings.isUsingFlipPage) }
                            }) { Text("Mode") }
                            TextButton(onClick = ::recreate) { Text("Recreate") }
                        }
                        Text("$status ping=$ping", Modifier.semantics { contentDescription = status })
                        if (opened) ContentComponent(
                            modifier = Modifier.weight(1f), uiState = model.uiState.contentUiState,
                            settingState = settings, fontFamilySettings = model.fontFamilySettings,
                            paddingValues = PaddingValues(12.dp), changeIsImmersive = {},
                            onClickPrevChapter = model::prevChapter, onClickNextChapter = model::nextChapter,
                        )
                    }
                }
            }
        }
    }

    override fun onStart() { super.onStart(); model.setActive(true) }
    override fun onStop() { model.setActive(false); super.onStop() }
    override fun onDestroy() {
        traceCookie?.let { Trace.endAsyncSection("reader.to_readable", it) }
        interactionCookie?.let { Trace.endAsyncSection("reader.to_interactive", it) }
        traceCookie = null
        interactionCookie = null
        opened = false
        sequence++
        pendingDraw = null
        background?.cancel()
        registration.unregister()
        super.onDestroy()
    }

    private fun begin(action: String, offset: Int = 0) {
        traceCookie?.let { Trace.endAsyncSection("reader.to_readable", it) }
        sequence++
        requiredOffset = offset
        expectedFont = if (action == "font") model.readerSettings.fontSize + 2f else null
        expectedFlip = if (action == "mode") !model.readerSettings.isUsingFlipPage else null
        pendingDraw = null
        ReaderPerformanceStats.action = action
        ReaderPerformanceStats.readable = ""
        ReaderPerformanceStats.anchorPreserved = null
        ReaderPerformanceStats.contentVerified = false
        status = "waiting:$action"
        traceCookie = cookies.incrementAndGet().also { Trace.beginAsyncSection("reader.to_readable", it) }
    }

    private fun navigate(id: String) {
        begin("chapter$id")
        model.changeChapter(ReaderPerformanceFixture.chapter(id))
    }

    private fun startBackground() {
        check(background?.isActive != true)
        ReaderPerformanceStats.backgroundSubmittedNs = System.nanoTime()
        // Match download ownership; plain preloads may share detached coalesced work.
        background = lifecycleScope.launch(Dispatchers.IO + BackgroundSourceRequest { null }) {
            val cookie = cookies.incrementAndGet()
            Trace.beginAsyncSection("reader.background.request", cookie)
            try {
                chapters.preloadChapterContent(ReaderPerformanceFixture.chapter("background"),
                    ReaderPerformanceFixture.book.storageKey, WebDataSourcePriority.Default)
            } finally { Trace.endAsyncSection("reader.background.request", cookie) }
        }
    }

    private fun refresh() {
        val id = model.uiState.contentUiState?.readingChapterId ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val cookie = cookies.incrementAndGet()
            Trace.beginAsyncSection("reader.refresh.request", cookie)
            try {
                val result = chapters.refreshChapter(
                    indi.renakoni.nextvol.data.book.SourceChapterId.fromStorageKey(id),
                    WebDataSourcePriority.Default, fresh = true)
                check(result.isOk)
            } finally { Trace.endAsyncSection("reader.refresh.request", cookie) }
        }
    }

    private fun drawn(fragment: ReaderTextFragment, text: TextLayoutResult, coordinates: LayoutCoordinates) {
        if (!opened || fragment.componentIndex != 0 || fragment.text.isEmpty()) return
        if (expectedFont?.let { text.layoutInput.style.fontSize.value != it } == true) return
        if (expectedFlip?.let { (model.uiState.contentUiState is FlipPageContentUiState) != it } == true) return
        val key = model.uiState.contentUiState?.readingChapterId ?: return
        val id = indi.renakoni.nextvol.data.book.SourceChapterId.fromStorageKey(key).remoteId
        val original = originalText[id] ?: return
        if (fragment.start < 0 || fragment.start + fragment.text.length > original.length ||
            !original.regionMatches(fragment.start, fragment.text, 0, fragment.text.length)) return
        val clip = coordinates.boundsInWindow()
        if (clip.isEmpty) return
        val origin = coordinates.positionInWindow()
        val target = requiredOffset - fragment.start
        val candidates = if (target in fragment.text.indices) listOf(target)
            else (0 until text.lineCount).map(text::getLineStart)
        val visible = candidates.firstOrNull { offset ->
            if (offset !in fragment.text.indices || fragment.text[offset].isWhitespace()) false else {
                val glyph = text.getBoundingBox(offset).translate(origin)
                glyph.width > 0 && glyph.height > 0 && clip.contains(glyph.topLeft) &&
                    clip.contains(glyph.bottomRight - Offset(0.1f, 0.1f))
            }
        } ?: return
        val y = text.getBoundingBox(visible).top + origin.y
        if (y < firstVisibleY) {
            firstVisibleY = y
            lastVisibleOffset = fragment.start + visible
        }
        if (traceCookie == null) return
        val expectedSequence = sequence
        val evidence = DrawEvidence(id, fragment.start + visible, fragment.start + visible == requiredOffset)
        if (pendingDraw == null || evidence.preserved) pendingDraw = evidence
        if (completionPosted) return
        completionPosted = true
        // Do not mutate Compose state during drawing or wait on readiness to display normal UI.
        window.decorView.post {
            completionPosted = false
            if (sequence != expectedSequence || traceCookie == null) return@post
            val drawn = pendingDraw ?: return@post
            val components = model.uiState.contentUiState?.readingChapterContent?.get()?.content
            val profile = ReaderPerformanceFixture.profile(this)
            val expected = ReaderPerformanceFixture.textComponents(drawn.chapter, profile)
            ReaderPerformanceStats.contentVerified = components?.take(expected.size)?.map {
                (it as? SimpleTextComponent)?.data?.text
            } == expected && components?.size == expected.size + if (profile == "mixed") 2 else 0
            if (!ReaderPerformanceStats.contentVerified) {
                ReaderPerformanceStats.error = "Prepared chapter differs from complete fixture content"
                return@post
            }
            ReaderPerformanceStats.anchor = "${drawn.chapter}:0:${drawn.offset}"
            ReaderPerformanceStats.anchorPreserved = drawn.preserved
            ReaderPerformanceStats.readable = drawn.chapter
            traceCookie?.let { Trace.endAsyncSection("reader.to_readable", it) }
            traceCookie = null
            status = "readable:${drawn.chapter}:${ReaderPerformanceStats.action}"
            if (firstReadable) { firstReadable = false; reportFullyDrawn() }
        }
    }

    companion object { private val cookies = AtomicInteger() }
}
