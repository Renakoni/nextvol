package indi.renakoni.nextvol.data.localbook

import android.graphics.Bitmap
import android.graphics.Canvas
import com.caverock.androidsvg.SVG
import com.caverock.androidsvg.SVGExternalFileResolver
import com.caverock.androidsvg.SVGParseException
import kotlinx.coroutines.CancellationException
import kotlin.math.roundToInt

/** Renders EPUB SVG artwork into the existing offline image format. */
internal object EpubSvgImage {
    private val linkedImage = ThreadLocal<(String) -> Bitmap>()

    init {
        SVG.setInternalEntitiesEnabled(false)
        // AndroidSVG's resolver is global. Image reads belong to the current import.
        SVG.registerExternalFileResolver(object : SVGExternalFileResolver() {
            override fun resolveImage(filename: String): Bitmap? = linkedImage.get()?.invoke(filename)
        })
    }

    fun render(bytes: ByteArray, readImage: (String) -> Bitmap): Bitmap {
        val linked = mutableMapOf<String, Bitmap>()
        val previousResolver = linkedImage.get()
        var rendered: Bitmap? = null
        try {
            val svg = SVG.getFromInputStream(bytes.inputStream())
            val viewBox = svg.documentViewBox
            val width = positiveSize(svg.documentWidth) ?: positiveSize(viewBox?.width()) ?: 300f
            val height = positiveSize(svg.documentHeight) ?: positiveSize(viewBox?.height()) ?: 150f
            val bitmap = Bitmap.createBitmap(width.roundToInt().coerceAtLeast(1), height.roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            rendered = bitmap
            if (viewBox == null) svg.setDocumentViewBox(0f, 0f, width, height)
            svg.setDocumentWidth("100%")
            svg.setDocumentHeight("100%")
            linkedImage.set { href -> linked.getOrPut(href) { readImage(href) } }
            // Render to a software bitmap for use by the existing offline reader.
            svg.renderToCanvas(Canvas(bitmap))
            rendered = null
            return bitmap
        } catch (failure: SVGParseException) {
            throw LocalBookImportException(LocalBookImportReason.CorruptEpub, "Invalid EPUB SVG image.", failure)
        } catch (failure: StackOverflowError) {
            throw LocalBookImportException(LocalBookImportReason.CorruptEpub, "Cannot render the EPUB SVG structure.", failure)
        } catch (failure: RuntimeException) {
            if (failure is LocalBookImportException || failure is CancellationException || failure is SecurityException) throw failure
            throw LocalBookImportException(LocalBookImportReason.CorruptEpub, "Cannot render an EPUB SVG image.", failure)
        } finally {
            if (previousResolver == null) linkedImage.remove() else linkedImage.set(previousResolver)
            linked.values.forEach(Bitmap::recycle)
            rendered?.recycle()
        }
    }

    private fun positiveSize(value: Float?): Float? = value?.takeIf { it.isFinite() && it > 0f }
}
