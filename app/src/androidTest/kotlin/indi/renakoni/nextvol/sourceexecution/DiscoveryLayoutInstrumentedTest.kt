package indi.renakoni.nextvol.sourceexecution

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import indi.renakoni.nextvol.R
import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.data.web.*
import indi.renakoni.nextvol.reader.ReaderLayoutTestActivity
import indi.renakoni.nextvol.theme.NextVolTheme
import indi.renakoni.nextvol.ui.home.discovery.*
import indi.renakoni.nextvol.ui.home.explore.home.ExploreHomeScreen
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.WebDataSourceItem
import io.nightfish.lightnovelreader.api.web.discovery.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DiscoveryLayoutInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ReaderLayoutTestActivity>()

    @Test fun mixedSectionStatesKeepBooksAndRetryAccessibleInBothThemesAndLargeText() {
        val id = Identifier("fixture", "discovery-layout")
        val source = SourceListing(SourceMetadata(WebDataSourceItem(id, "书目精选", "Fixture"),
            setOf(SourceCapability.Explore)), SourceStatus.Ready)
        fun section(key: String, title: String) = SourceDiscoverySection(key, title, listOf(
            SourceDiscoveryBook(SourceBookId(id, "$key-one"), "沿着星光去旅行", "", ""),
            SourceDiscoveryBook(SourceBookId(id, "$key-two"), "午后图书馆", "", "")
        ), SourceDiscoveryTarget(id, "/$key"))
        val daily = section("daily", "日榜")
        val weekly = section("weekly", "周榜").copy(books = emptyList(),
            previewFailure = DiscoveryPreviewFailure(DiscoveryError.Network), previewRetryAvailable = true)
        var sections by mutableStateOf(listOf(daily, weekly, section("monthly", "月榜"),
            section("articles", "文章").copy(books = emptyList(), previewLoading = true)))
        var dark by mutableStateOf(false)
        var fontScale by mutableFloatStateOf(1f)
        var retried: SourceDiscoverySection? = null
        var opened: SourceDiscoverySection? = null
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                NextVolTheme(if (dark) "Enabled" else "Disabled", false, "light_default", "dark_default") {
                    ExploreHomeScreen(DiscoveryPageState(listOf(source), id,
                        mapOf(id to DiscoveryPageContent(loading = true, sections = sections))), {}, { _, _ -> }, {},
                        { opened = it }, {}, {}, {}, { _, _ -> }, { _, _ -> }, {}, onRetryPreview = { retried = it })
                }
            }
        }
        compose.mainClock.autoAdvance = false
        for (night in listOf(false, true)) {
            compose.runOnIdle { dark = night; fontScale = if (night) 1.5f else 1f }
            compose.mainClock.advanceTimeBy(500)
            compose.onNodeWithText("周榜").performScrollTo().assertIsDisplayed()
            val retry = compose.onNodeWithText(compose.activity.getString(R.string.discovery_retry))
            retry.performScrollTo().assertIsDisplayed().performClick()
            assertEquals(weekly, retried)
            screenshot(if (night) "discovery-dark-large.png" else "discovery-light.png")
        }
        compose.runOnIdle { sections = sections.map { if (it.id == "weekly") it.copy(previewFailure = null, previewLoading = true) else it } }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText(compose.activity.getString(R.string.discovery_retry)).assertDoesNotExist()
        compose.onAllNodesWithText(compose.activity.getString(R.string.discovery_preview_loading)).onFirst().performScrollTo().assertIsDisplayed()
        screenshot("discovery-retrying-large.png")
        compose.onNodeWithText("月榜").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithContentDescription(compose.activity.getString(R.string.explore_more)).onLast().performClick()
        assertEquals(id, opened!!.more!!.sourceId)
        compose.runOnIdle { sections = sections.map { if (it.id == "weekly") it.copy(previewLoading = false) else it } }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText(compose.activity.getString(R.string.discovery_preview_empty)).performScrollTo().assertIsDisplayed()
        screenshot("discovery-empty-large.png")
        compose.runOnIdle { sections = listOf(daily.copy(previewLoading = true)) }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("沿着星光去旅行").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.discovery_preview_loading)).assertDoesNotExist()
        screenshot("discovery-resuming-large.png")
    }

    private fun screenshot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("discoveryScreenshots") != "true") return
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(compose.activity.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
