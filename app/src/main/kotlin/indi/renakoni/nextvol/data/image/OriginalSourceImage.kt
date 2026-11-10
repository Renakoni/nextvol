package indi.renakoni.nextvol.data.image

import android.content.Context
import android.graphics.BitmapFactory
import coil3.SingletonImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.decode.BlackholeDecoder
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import indi.renakoni.nextvol.data.web.SourceRequestVersion
import hnovel.network.RequestRetryContext
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

internal object ValidatedSourceImage : ImageSource.Metadata()

/** Ask the platform codec for dimensions without allocating the display pixels. */
internal fun validateOriginalImage(bytes: ByteArray) {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    check(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unrecognized image data" }
}

/** Keep Coil's source binding, transport and cache policies, but skip display decoding. */
@OptIn(ExperimentalCoilApi::class)
internal suspend fun originalSourceImage(context: Context, image: SourceImage, force: Boolean): ByteArray {
    var bytes: ByteArray? = null
    var invalidCachedImage = false
    val request = ImageRequest.Builder(context)
        .data(image.copy(preferDownloaded = false))
        .coroutineContext((currentCoroutineContext()[RequestRetryContext] ?: EmptyCoroutineContext) +
            (currentCoroutineContext()[SourceRequestVersion] ?: EmptyCoroutineContext))
        .memoryCachePolicy(CachePolicy.DISABLED)
        .diskCachePolicy(if (force) CachePolicy.WRITE_ONLY else CachePolicy.ENABLED)
        .decoderFactory { result, _, _ -> Decoder {
            bytes = result.source.source().readByteArray().also {
                if (result.dataSource == coil3.decode.DataSource.DISK || result.source.metadata !== ValidatedSourceImage) {
                    try { validateOriginalImage(it) }
                    catch (failure: IllegalStateException) {
                        invalidCachedImage = result.dataSource == coil3.decode.DataSource.DISK
                        throw failure
                    }
                }
            }
            DecodeResult(BlackholeDecoder.Factory.EMPTY_IMAGE, isSampled = false)
        } }
        .build()
    val result = SingletonImageLoader.get(context).execute(request)
    if (result is ErrorResult) {
        if (invalidCachedImage && !force) return originalSourceImage(context, image, force = true)
        throw result.throwable
    }
    return checkNotNull(bytes) { "Original image bytes are unavailable" }
}
