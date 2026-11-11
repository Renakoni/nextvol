package indi.renakoni.nextvol.ui.components

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.transformations
import indi.renakoni.nextvol.data.image.ImageTransPostProcessingViewModel
import indi.renakoni.nextvol.data.book.BookIdentity
import indi.renakoni.nextvol.data.image.SourceImage
import indi.renakoni.nextvol.data.image.SourceImageRetryEvents
import io.nightfish.lightnovelreader.api.image.ImagePostProcessingPipeline
import kotlinx.coroutines.Dispatchers
import indi.renakoni.nextvol.R

@Composable
fun Cover(bookId: String, width: Dp, height: Dp, uri: Uri, title: String, rounded: Dp = 8.dp,
    author: String = "", onFallbackChanged: (Boolean) -> Unit = {}) {
    val missing = uri.toString().isBlank()
    LaunchedEffect(bookId, uri) { if (missing) onFallbackChanged(true) }
    Box(
        modifier = Modifier
            .size(width, height)
            .graphicsLayer {
                shape = RoundedCornerShape(rounded)
                clip = true
            }
    ) {
        if (missing) {
            DefaultBookCover(title, width, height, bookId, author)
        } else {
            RemoteBookCover(bookId, width, height, uri, title, author, onFallbackChanged)
        }
    }
}

@Composable
private fun RemoteBookCover(bookId: String, width: Dp, height: Dp, uri: Uri, title: String, author: String,
    onFallbackChanged: (Boolean) -> Unit) {
    val context = LocalContext.current
    val imageTransPostProcessingViewModel = hiltViewModel<ImageTransPostProcessingViewModel>()
    val transformations = imageTransPostProcessingViewModel
        .imageTransPostProcessingManager
        .getCoil3Transformations(ImagePostProcessingPipeline.bookCover, uri)
    val transformationKeys = transformations.map { it.cacheKey }
    val request = remember(uri, bookId, transformationKeys) {
        ImageRequest.Builder(context)
            .data(SourceImage(BookIdentity.book(bookId), uri.toString(), cover = true))
            .transformations(transformations)
            .crossfade(true)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .interceptorCoroutineContext(Dispatchers.Default)
            .build()

    }
    BookCoverImage(request, bookId, width, height, title, author, onFallbackChanged)
}

@Composable
internal fun BookCoverImage(request: ImageRequest, bookId: String, width: Dp, height: Dp, title: String,
    author: String = "", onFallbackChanged: (Boolean) -> Unit = {},
    imageLoader: ImageLoader = SingletonImageLoader.get(LocalContext.current)) {
    var retry by remember(request) { mutableIntStateOf(0) }
    val identity = imageLoader.coverMemoryIdentity(request, bookId)
    val current = remember(request, retry, imageLoader, identity) {
        request.newBuilder()
            .placeholderMemoryCacheKey(if (request.memoryCachePolicy.readEnabled) imageLoader.coverPlaceholder(identity) else null)
            .apply {
                if (retry != 0) diskCachePolicy(CachePolicy.WRITE_ONLY).networkCachePolicy(CachePolicy.WRITE_ONLY)
            }.build()
    }
    key(retry) {
        SubcomposeAsyncImage(
            model = current,
            imageLoader = imageLoader,
            contentDescription = stringResource(R.string.cover_description, title.ifBlank { stringResource(R.string.cover_untitled) }),
            onError = { onFallbackChanged(true) },
            onSuccess = {
                imageLoader.rememberCover(identity, it.result)
                onFallbackChanged(false)
            },
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(width, height),
            loading = { state ->
                val placeholder = state.painter
                if (placeholder != null) SubcomposeAsyncImageContent(painter = placeholder)
                else DefaultBookCover(title, width, height, bookId, author)
            },
            error = {
                val source = (request.data as? SourceImage)?.book?.sourceId
                LaunchedEffect(source) {
                    if (source != null) SourceImageRetryEvents.requests.collect { changed ->
                        if (changed == source) retry++
                    }
                }
                // A retry keeps the source/cache identity but bypasses a possibly corrupt disk entry.
                DefaultBookCover(title, width, height, bookId, author)
            },
        )
    }
}
