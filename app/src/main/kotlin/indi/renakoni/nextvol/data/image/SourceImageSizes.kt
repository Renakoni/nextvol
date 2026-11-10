package indi.renakoni.nextvol.data.image

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

internal data class SourceImageSize(val width: Int, val height: Int)

/** The last observed original geometry is a layout hint, never an image cache hit. */
@Singleton
class SourceImageSizes @Inject constructor(@ApplicationContext context: Context) {
    private val preferences = context.getSharedPreferences("source_image_sizes", Context.MODE_PRIVATE)

    internal fun get(image: SourceImage): SourceImageSize? {
        val value = preferences.getLong(sourceImageCacheKey(image, ""), 0L)
        if (value == 0L) return null
        return SourceImageSize((value ushr 32).toInt(), value.toInt())
    }

    internal fun put(image: SourceImage, size: SourceImageSize) {
        preferences.edit().putLong(sourceImageCacheKey(image, ""), (size.width.toLong() shl 32) or size.height.toLong()).apply()
    }
}

