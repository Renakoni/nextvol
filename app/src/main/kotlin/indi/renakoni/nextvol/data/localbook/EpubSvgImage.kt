package indi.renakoni.nextvol.data.localbook

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.Base64
import android.util.Xml
import com.caverock.androidsvg.SVG
import com.caverock.androidsvg.SVGParseException
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.File
import kotlin.math.roundToInt

/** Rasterizes self-contained SVG artwork during import for the existing offline image pipeline. */
internal object EpubSvgImage {
    const val MAX_BYTES = 4L * 1024 * 1024
    private const val MAX_DIMENSION = 1600
    private const val MAX_EMBEDDED_PIXELS = 4_194_304L
    private const val MAX_ELEMENTS = 8_192
    private const val MAX_DEPTH = 64
    private const val XLINK = "http://www.w3.org/1999/xlink"

    fun writePng(bytes: ByteArray, destination: File) {
        val svg = try {
            validate(bytes)
            SVG.getFromInputStream(bytes.inputStream())
        } catch (failure: XmlPullParserException) {
            throw LocalBookImportException(LocalBookImportReason.CorruptEpub, "Invalid EPUB SVG image.", failure)
        } catch (failure: SVGParseException) {
            throw LocalBookImportException(LocalBookImportReason.CorruptEpub, "Invalid EPUB SVG image.", failure)
        }

        val viewBox = svg.documentViewBox
        val width = positiveSize(svg.documentWidth) ?: positiveSize(viewBox?.width()) ?: 300f
        val height = positiveSize(svg.documentHeight) ?: positiveSize(viewBox?.height()) ?: 150f
        val scale = (MAX_DIMENSION / maxOf(width, height)).coerceAtMost(1f)
        val bitmap = Bitmap.createBitmap(
            (width * scale).roundToInt().coerceAtLeast(1),
            (height * scale).roundToInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        try {
            if (viewBox == null) svg.setDocumentViewBox(0f, 0f, width, height)
            svg.setDocumentWidth("100%")
            svg.setDocumentHeight("100%")
            try {
                // A bitmap canvas keeps untrusted artwork out of the hardware display list.
                svg.renderToCanvas(Canvas(bitmap))
            } catch (failure: StackOverflowError) {
                throw LocalBookImportException(LocalBookImportReason.EpubLimit, "EPUB SVG references are nested too deeply.", failure)
            }
            destination.outputStream().use { output ->
                requireImport(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output), LocalBookImportReason.Storage) {
                    "Cannot save the rasterized EPUB image."
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun positiveSize(value: Float?): Float? = value?.takeIf { it.isFinite() && it > 0f }

    private fun validate(bytes: ByteArray) {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        parser.setInput(bytes.inputStream(), null)
        var elements = 0
        var embeddedPixels = 0L
        var event = parser.nextToken()
        while (event != XmlPullParser.END_DOCUMENT) {
            requireImport(event != XmlPullParser.DOCDECL, LocalBookImportReason.UnsupportedEpub) {
                "EPUB SVG document type declarations are not supported."
            }
            if (event == XmlPullParser.START_TAG) {
                requireImport(++elements <= MAX_ELEMENTS && parser.depth <= MAX_DEPTH, LocalBookImportReason.EpubLimit) {
                    "An EPUB SVG image has too many elements or is nested too deeply."
                }
                if (parser.depth == 1) {
                    requireImport(parser.name == "svg", LocalBookImportReason.CorruptEpub) { "Invalid EPUB SVG root element." }
                }
                if (parser.name == "image") {
                    val href = parser.getAttributeValue(XLINK, "href") ?: parser.getAttributeValue(null, "href").orEmpty()
                    embeddedPixels += embeddedImagePixels(href)
                    requireImport(embeddedPixels <= MAX_EMBEDDED_PIXELS, LocalBookImportReason.EpubLimit) {
                        "Embedded raster images in an EPUB SVG exceed the pixel limit."
                    }
                }
            }
            event = parser.nextToken()
        }
    }

    private fun embeddedImagePixels(href: String): Long {
        val mediaType = href.substringBefore(',').removePrefix("data:").removeSuffix(";base64")
        requireImport(mediaType in setOf("image/png", "image/jpeg") && href.startsWith("data:$mediaType;base64,"), LocalBookImportReason.UnsupportedEpub) {
            "EPUB SVG raster images must be embedded PNG or JPEG data."
        }
        val encoded = href.substringAfter(',')
        val data = try {
            Base64.decode(encoded, Base64.DEFAULT)
        } catch (failure: IllegalArgumentException) {
            throw LocalBookImportException(LocalBookImportReason.CorruptEpub, "Invalid embedded EPUB SVG image.", failure)
        }
        val size = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, size)
        requireImport(size.outWidth > 0 && size.outHeight > 0 && size.outMimeType == mediaType, LocalBookImportReason.CorruptEpub) {
            "An embedded EPUB SVG image does not match its declared format."
        }
        return size.outWidth.toLong() * size.outHeight
    }
}
