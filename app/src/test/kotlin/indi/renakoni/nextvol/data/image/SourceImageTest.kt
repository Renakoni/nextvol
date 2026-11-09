package indi.renakoni.nextvol.data.image

import android.app.Application
import android.graphics.Bitmap
import coil3.asImage
import coil3.ImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.SuccessResult
import indi.renakoni.nextvol.data.book.SourceBookId
import indi.renakoni.nextvol.data.web.*
import io.mockk.every
import io.mockk.coEvery
import io.mockk.mockk
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.web.WebBookDataSource
import io.nightfish.lightnovelreader.api.web.WebDataSourceItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], application = Application::class)
class SourceImageTest {
    private val a = SourceBookId(Identifier("fixture", "a"), "same")
    private val b = SourceBookId(Identifier("fixture", "b"), "same")
    private val url = "https://fixture.invalid/shared.png"

    @Test fun coilMemoryCacheAndHeadersFollowTheBookAcrossSources() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val registry = WebSourceRegistry()
        var aHeaders = mapOf("Authorization" to "account-a")
        for (book in listOf(a, b)) {
            val source = mockk<WebBookDataSource>(relaxed = true) {
                every { id } returns book.sourceId
                every { cache } returns null
                every { permits } returns 2
                every { imageHeader } answers { if (book == a) aHeaders else mapOf("Authorization" to "account-b") }
            }
            registry.register(source, SourceMetadata(WebDataSourceItem(book.sourceId, "Same name", "fixture"), emptySet()))
        }
        val fetched = mutableListOf<String?>()
        val loader = ImageLoader.Builder(context).components {
            add(SourceImageInterceptor(registry, context, mockk { coEvery { image(any()) } returns null }))
            add(SourceImageFetcher.Factory())
            add(object : Fetcher.Factory<Uri> {
                override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher = Fetcher {
                    check(options.networkCachePolicy.readEnabled) { "No disk entry in this fixture" }
                    fetched.add(options.httpHeaders["Authorization"])
                    ImageFetchResult(Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).asImage(), false, DataSource.NETWORK)
                }
            })
        }.build()
        suspend fun load(book: SourceBookId) = loader.execute(ImageRequest.Builder(context)
            .data(SourceImage(book, url)).size(2, 2).build())
        try {
            assertTrue(load(a) is SuccessResult)
            assertTrue(load(b) is SuccessResult)
            assertEquals(DataSource.MEMORY_CACHE, (load(a) as SuccessResult).dataSource)
            assertEquals(listOf("account-a", "account-b"), fetched)
            aHeaders = mapOf("Authorization" to "account-a-new")
            assertTrue(load(a) is SuccessResult)
            assertEquals(listOf("account-a", "account-b"), fetched)
            registry.unregister(a.sourceId)
            assertEquals(DataSource.MEMORY_CACHE, (load(a) as SuccessResult).dataSource)
            assertEquals(DataSource.MEMORY_CACHE, (load(b) as SuccessResult).dataSource)
            assertEquals(2, fetched.size)
            loader.memoryCache?.clear()
            assertFalse(load(a) is SuccessResult)
            assertEquals(2, fetched.size)
        } finally {
            loader.shutdown()
            registry.unregister(a.sourceId)
            registry.unregister(b.sourceId)
        }
    }

    @Test fun imageKeysDistinguishSourcesBooksRevisionsAndRoles() {
        val image = SourceImage(a, url)
        val key = sourceImageCacheKey(image, "1")
        assertNotEquals(key, sourceImageCacheKey(image, "2"))
        assertNotEquals(key, sourceImageCacheKey(image.copy(book = b), "1"))
        assertNotEquals(key, sourceImageCacheKey(image.copy(cover = true), "1"))
        assertNotEquals(key, sourceImageCacheKey(image.copy(book = a.copy(remoteId = "other")), "1"))
        assertFalse(key.contains(url))
        assertEquals(a, SourceBookId.fromStorageKey(a.storageKey))
    }

    @Test fun failedImagesRetryAndDecodedImagesReadDiskAfterSourceRemoval() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val registry = WebSourceRegistry()
        val accounts = SourceSessionManager()
        val calls = mutableListOf<Pair<String, Boolean>>()
        val png = java.io.ByteArrayOutputStream().also {
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        val source = object : WebBookDataSource by EmptyWebDataSource, io.nightfish.lightnovelreader.api.image.SourceImageProvider {
            override val id = a.sourceId
            override suspend fun getImage(bookId: String, url: String, cover: Boolean): com.github.michaelbull.result.Result<ByteArray, io.nightfish.lightnovelreader.api.error.WebRequestError> {
                calls += bookId to cover
                return com.github.michaelbull.result.Ok(if (calls.size == 1) ByteArray(0) else png)
            }
        }
        registry.register(source, SourceMetadata(WebDataSourceItem(a.sourceId, "Rule", "fixture"), emptySet()))
        val cache = coil3.disk.DiskCache.Builder().directory(okio.Path.Companion.run {
            java.nio.file.Files.createTempDirectory("source-image-cache").toString().toPath()
        }).maxSizeBytes(1024 * 1024).build()
        val loader = ImageLoader.Builder(context).diskCache(cache).components {
            add(SourceImageInterceptor(registry, context, mockk { coEvery { image(any()) } returns null })); add(SourceImageFetcher.Factory())
        }.build()
        suspend fun load(cover: Boolean) = loader.execute(ImageRequest.Builder(context)
            .data(SourceImage(a, url, cover)).size(2, 2).build())
        try {
            assertFalse(load(true) is SuccessResult)
            loader.memoryCache?.clear()
            assertTrue(load(true) is SuccessResult)
            assertTrue(load(false) is SuccessResult)
            assertEquals(listOf("same" to true, "same" to true, "same" to false), calls)
            registry.unregister(a.sourceId)
            loader.memoryCache?.clear()
            assertEquals(DataSource.DISK, (load(true) as SuccessResult).dataSource)
            assertEquals(DataSource.DISK, (load(false) as SuccessResult).dataSource)
            accounts.begin(a.sourceId)
            assertTrue(load(false) is SuccessResult)
            loader.memoryCache?.clear(); cache.clear()
            assertFalse(load(true) is SuccessResult)
            assertEquals(3, calls.size)
        } finally { registry.unregister(a.sourceId); loader.shutdown(); cache.shutdown() }
    }
}
