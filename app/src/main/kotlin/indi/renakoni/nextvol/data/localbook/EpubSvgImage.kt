package indi.renakoni.nextvol.data.localbook

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import com.caverock.androidsvg.SVG
import com.caverock.androidsvg.SVGExternalFileResolver
import com.caverock.androidsvg.SVGParseException
import kotlinx.coroutines.CancellationException
import java.io.File
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

    fun writePng(bytes: ByteArray, destination: File, readImage: (String) -> ByteArray) {
        val linked = mutableMapOf<String, Bitmap>()
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
            linkedImage.set { href ->
                linked.getOrPut(href) {
                    val data = readImage(href)
                    requireImportNotNull(BitmapFactory.decodeByteArray(data, 0, data.size), LocalBookImportReason.CorruptEpub) {
                        "Cannot decode an EPUB SVG raster image."
                    }
                }
            }
            // Render to a software bitmap for use by the existing offline reader.
            svg.renderToCanvas(Canvas(bitmap))
            destination.outputStream().use { output ->
                requireImport(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output), LocalBookImportReason.Storage) {
                    "Cannot save the rasterized EPUB image."
                }
            }
        } catch (failure: SVGParseException) {
            throw LocalBookImportException(LocalBookImportReason.CorruptEpub, "Invalid EPUB SVG image.", failure)
        } catch (failure: StackOverflowError) {
            throw LocalBookImportException(LocalBookImportReason.CorruptEpub, "Cannot render the EPUB SVG structure.", failure)
        } catch (failure: RuntimeException) {
            if (failure is LocalBookImportException || failure is CancellationException || failure is SecurityException) throw failure
            throw LocalBookImportException(LocalBookImportReason.CorruptEpub, "Cannot render an EPUB SVG image.", failure)
        } finally {
            linkedImage.remove()
            linked.values.forEach(Bitmap::recycle)
            rendered?.recycle()
        }
    }

    private fun positiveSize(value: Float?): Float? = value?.takeIf { it.isFinite() && it > 0f }
}
