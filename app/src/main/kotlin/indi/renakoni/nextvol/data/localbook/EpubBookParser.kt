package indi.renakoni.nextvol.data.localbook

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.ParseSettings
import org.jsoup.parser.Parser
import org.jsoup.select.NodeFilter
import org.jsoup.select.NodeFilter.FilterResult
import org.jsoup.select.NodeTraversor
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.CRC32

/** Reads the publication's spine, never ZIP entry order. Assets stay in the draft directory. */
object EpubBookParser {
    private const val SVG = "image/svg+xml"
    private val textTypes = setOf("application/xhtml+xml", "text/html")
    private val imageTypes = setOf(SVG, "image/jpeg", "image/png", "image/gif", "image/webp")
    private val blocks = setOf("p", "div", "section", "article", "h1", "h2", "h3", "h4", "h5", "h6",
        "li", "blockquote", "pre", "tr", "td", "br", "hr")
    private val ignored = setOf("head", "script", "style", "noscript")
    private val whitespace = Regex("\\s+")
    private val scheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")

    private data class Item(val id: String, val path: String?, val mediaType: String, val properties: Set<String>, val fallback: String)
    private data class NavigationEntry(val path: String, val fragment: String, val title: String)
    private data class Body(val content: List<LocalBookBlock>, val anchors: Map<String, Int>)

    fun parse(file: File, assetsDirectory: File, fallbackTitle: String, checkActive: () -> Unit = {}): ParsedLocalBook = ZipFile(file).use { zip ->
        val entries = linkedMapOf<String, ZipEntry>()
        val allEntries = zip.entries()
        while (allEntries.hasMoreElements()) {
            val entry = allEntries.nextElement()
            if (entry.isDirectory) continue
            val path = normalizePath(entry.name)
            requireImport(entries.put(path, entry) == null, LocalBookImportReason.CorruptEpub) { "The EPUB has duplicate resource paths." }
        }
        fun copy(path: String, output: OutputStream) {
            val entry = requireImportNotNull(entries[path], LocalBookImportReason.CorruptEpub) { "An EPUB resource is missing: $path" }
            zip.getInputStream(entry).use { input ->
                val crc = CRC32()
                val buffer = ByteArray(8192)
                while (true) {
                    checkActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    crc.update(buffer, 0, read)
                }
                requireImport(entry.crc == crc.value, LocalBookImportReason.CorruptEpub) { "An EPUB resource is damaged." }
            }
        }
        fun read(path: String) = ByteArrayOutputStream().also { copy(path, it) }.toByteArray()
        fun xml(path: String) = Jsoup.parse(read(path).inputStream(), null, "", Parser.xmlParser())
        val container = xml("META-INF/container.xml")
        val packagePath = container.named("rootfile").firstOrNull {
            it.attr("media-type") == "application/oebps-package+xml"
        }?.attr("full-path") ?: container.named("rootfile").firstOrNull()?.attr("full-path")
        val opfPath = resolve("", requireImportNotNull(packagePath, LocalBookImportReason.CorruptEpub) { "The EPUB has no package document." }).first
        val opf = xml(opfPath)
        val metadata = requireImportNotNull(opf.named("metadata").firstOrNull(), LocalBookImportReason.CorruptEpub) { "The EPUB has no metadata." }
        val manifest = requireImportNotNull(opf.named("manifest").firstOrNull(), LocalBookImportReason.CorruptEpub) { "The EPUB has no manifest." }
        val items = manifest.named("item").map { item ->
            val path = item.attr("href").takeUnless(::isRemote)?.let { resolve(opfPath, it).first }
            Item(item.attr("id"), path, item.attr("media-type"),
                item.attr("properties").split(whitespace).toSet(), item.attr("fallback"))
        }
        val byId = items.associateBy { it.id }
        requireImport(byId.isNotEmpty() && byId.size == items.size && byId.keys.none(String::isBlank), LocalBookImportReason.CorruptEpub) {
            "The EPUB manifest has invalid IDs."
        }
        val spine = requireImportNotNull(opf.named("spine").firstOrNull(), LocalBookImportReason.CorruptEpub) { "The EPUB has no reading order." }
        val readingOrder = spine.named("itemref").mapNotNull { ref ->
            val primary = requireImportNotNull(byId[ref.attr("idref")], LocalBookImportReason.CorruptEpub) {
                "The EPUB reading order refers to a missing item."
            }
            var candidate = primary
            val visited = mutableSetOf<String>()
            while (candidate.path == null || candidate.mediaType !in textTypes && candidate.mediaType !in imageTypes) {
                if (candidate.fallback.isEmpty()) {
                    if (ref.attr("linear") == "no") return@mapNotNull null
                    throw LocalBookImportException(LocalBookImportReason.UnsupportedEpub, "Unsupported EPUB spine content: ${primary.mediaType}")
                }
                requireImport(visited.add(candidate.id), LocalBookImportReason.CorruptEpub) { "The EPUB contains a circular fallback chain." }
                candidate = requireImportNotNull(byId[candidate.fallback], LocalBookImportReason.CorruptEpub) {
                    "An EPUB fallback refers to a missing item."
                }
            }
            primary.path to candidate
        }
        requireImport(readingOrder.isNotEmpty(), LocalBookImportReason.NoContent) { "The EPUB has no readable chapters." }
        val encrypted = if (entries.containsKey("META-INF/encryption.xml")) {
            xml("META-INF/encryption.xml").named("CipherReference").map { resolve("", it.attr("URI")).first }.toSet()
        } else emptySet()
        val navigation = mutableListOf<NavigationEntry>()
        val navPath = items.firstOrNull { "nav" in it.properties }?.path
        if (navPath != null) {
            val navDocument = Jsoup.parse(read(navPath).inputStream(), null, "")
            val toc = navDocument.named("nav").firstOrNull { node ->
                node.attributes().any { it.key.substringAfter(':') == "type" && "toc" in it.value.split(whitespace) }
            }
            toc?.select("a[href]")?.forEach { link ->
                val href = link.attr("href")
                if (isRemote(href)) return@forEach
                val (path, fragment) = resolve(navPath, href)
                navigation += NavigationEntry(path, fragment, link.text())
            }
        }
        if (navigation.isEmpty()) {
            val ncxPath = (byId[spine.attr("toc")] ?: items.firstOrNull { it.mediaType == "application/x-dtbncx+xml" })?.path
            if (ncxPath != null) xml(ncxPath).named("navPoint").forEach { point ->
                val content = point.named("content").firstOrNull() ?: return@forEach
                val href = content.attr("src")
                if (isRemote(href)) return@forEach
                val (path, fragment) = resolve(ncxPath, href)
                navigation += NavigationEntry(path, fragment, point.named("navLabel").firstOrNull()?.text().orEmpty())
            }
        }
        val savedAssets = mutableMapOf<String, String>()
        val svgPaths = items.filter { it.mediaType == SVG }.mapNotNull { it.path }.toSet()
        fun isSvg(path: String) = path in svgPaths || path.endsWith(".svg", ignoreCase = true)
        fun saveImage(key: String, extension: String, write: (File) -> Unit): String = savedAssets.getOrPut(key) {
            requireImport(assetsDirectory.isDirectory || assetsDirectory.mkdirs(), LocalBookImportReason.Storage) { "Cannot create the EPUB image directory." }
            val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
            val name = "$digest.$extension"
            write(File(assetsDirectory, name))
            "assets/$name"
        }
        fun svg(key: String, base: String, bytes: () -> ByteArray): String = saveImage(key, "png") { destination ->
            val rendering = mutableSetOf(base)
            fun render(path: String, source: ByteArray): Bitmap = EpubSvgImage.render(source) { href ->
                val linked = resolve(path, href).first
                requireImport(linked !in encrypted, LocalBookImportReason.EncryptedEpub) { "Encrypted EPUB images are not supported." }
                if (isSvg(linked)) {
                    requireImport(rendering.add(linked), LocalBookImportReason.CorruptEpub) { "Circular SVG image reference: $linked" }
                    try { render(linked, read(linked)) } finally { rendering.remove(linked) }
                } else {
                    val data = read(linked)
                    requireImportNotNull(BitmapFactory.decodeByteArray(data, 0, data.size), LocalBookImportReason.CorruptEpub) {
                        "Cannot decode an EPUB SVG raster image: $linked"
                    }
                }
            }
            val bitmap = render(base, bytes())
            try {
                destination.outputStream().use { output ->
                    requireImport(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output), LocalBookImportReason.Storage) {
                        "Cannot save the rasterized EPUB image."
                    }
                }
            } finally { bitmap.recycle() }
        }
        fun image(path: String): String {
            requireImport(path !in encrypted, LocalBookImportReason.EncryptedEpub) { "Encrypted EPUB images are not supported." }
            return if (isSvg(path)) {
                svg(path, path) { read(path) }
            } else {
                val extension = path.substringAfterLast('.', "img").takeIf { it.matches(Regex("[a-zA-Z0-9]{1,6}")) } ?: "img"
                saveImage(path, extension) { destination -> destination.outputStream().use { copy(path, it) } }
            }
        }
        val labelsByPath = navigation.filter { it.title.isNotBlank() }.groupBy { it.path }
        val chapters = mutableListOf<LocalBookChapter>()
        for ((primaryPath, item) in readingOrder) {
            val path = requireNotNull(item.path)
            val labels = labelsByPath[path].orEmpty().let {
                if (primaryPath == path) it else labelsByPath[primaryPath].orEmpty() + it
            }
            val fileTitle = path.substringAfterLast('/').substringBeforeLast('.')
            if (item.mediaType in imageTypes) {
                chapters += LocalBookChapter(labels.firstOrNull()?.title ?: fileTitle,
                    blocks = listOf(LocalBookBlock.Image(image(path))))
            } else {
                requireImport(path !in encrypted, LocalBookImportReason.EncryptedEpub) { "Encrypted EPUB text is not supported." }
                val document = Jsoup.parse(read(path).inputStream(), null, "", Parser.htmlParser().settings(ParseSettings.preserveCase))
                val fallback = labels.firstOrNull()?.title
                    ?: document.selectFirst("h1,h2,h3")?.text()?.takeIf { it.isNotBlank() }
                    ?: document.title().takeIf { it.isNotBlank() } ?: fileTitle
                var nextSvg = 0
                val body = extractBody(document.body(), { href -> image(resolve(path, href).first) }, { node ->
                    svg("$path\u0000${nextSvg++}", path) {
                        checkActive()
                        inlineSvg(node)
                    }
                })
                if (body.content.isEmpty()) continue
                val boundaries = labels.mapNotNull { label ->
                    body.anchors[label.fragment]?.let { it to label.title }
                }.distinctBy { it.first }.sortedBy { it.first }.toMutableList()
                if (boundaries.firstOrNull()?.first != 0) boundaries.add(0, 0 to fallback)
                boundaries.forEachIndexed { index, (start, title) ->
                    val end = boundaries.getOrNull(index + 1)?.first ?: body.content.size
                    if (end > start) chapters += splitChapter(title, body.content.subList(start, end))
                }
            }
        }
        requireImport(chapters.isNotEmpty(), LocalBookImportReason.NoContent) { "The EPUB has no readable content." }
        // Exclude titles and other embedded XHTML from subsequent book metadata lookups.
        val descriptions = metadata.named("description")
        val description = descriptions.firstOrNull()?.descriptionMarkup().orEmpty()
        descriptions.forEach { it.remove() }
        val coverId = metadata.named("meta").firstOrNull { it.attr("name") == "cover" }?.attr("content")
        val coverPath = (items.firstOrNull { "cover-image" in it.properties } ?: byId[coverId])?.path
        ParsedLocalBook(
            title = metadata.named("title").firstOrNull()?.text()?.takeIf { it.isNotBlank() } ?: fallbackTitle,
            chapters = chapters,
            author = metadata.named("creator").joinToString(", ") { it.text() },
            description = description,
            publishingHouse = metadata.named("publisher").firstOrNull()?.text().orEmpty(),
            coverPath = coverPath?.let(::image),
        )
    }

    private fun inlineSvg(node: Element): ByteArray {
        val svg = node.clone()
        for (ancestor in node.parents()) {
            for (attribute in ancestor.attributes()) {
                if (attribute.key.startsWith("xmlns:") && !svg.hasAttr(attribute.key)) svg.attr(attribute.key, attribute.value)
            }
        }
        if (!svg.hasAttr("xmlns")) svg.attr("xmlns", "http://www.w3.org/2000/svg")
        val document = Document("")
        document.outputSettings().syntax(Document.OutputSettings.Syntax.xml).escapeMode(Entities.EscapeMode.xhtml).prettyPrint(false)
        document.appendChild(svg)
        return svg.outerHtml().toByteArray(Charsets.UTF_8)
    }

    private fun extractBody(root: Element, image: (String) -> String, svg: (Element) -> String): Body {
        val content = mutableListOf<LocalBookBlock>()
        val anchors = linkedMapOf<String, Int>()
        val text = StringBuilder()
        fun flush() {
            val value = text.toString().replace(whitespace, " ").trim()
            if (value.isNotEmpty()) content += LocalBookBlock.Text(value)
            text.clear()
        }
        NodeTraversor.filter(object : NodeFilter {
            override fun head(node: Node, depth: Int): FilterResult {
                if (node is TextNode) text.append(node.wholeText)
                if (node !is Element) return FilterResult.CONTINUE
                val tag = node.normalName().substringAfter(':')
                if (tag in ignored) return FilterResult.SKIP_ENTIRELY
                if (tag in blocks) flush()
                val id = node.id().ifEmpty { if (tag == "a") node.attr("name") else "" }
                if (id.isNotEmpty()) { flush(); anchors.putIfAbsent(id, content.size) }
                if (tag == "svg") {
                    flush()
                    val wrapper = node.children().singleOrNull()?.takeIf { it.normalName().substringAfter(':') == "image" }
                    // AndroidSVG needs explicit image dimensions. Bare wrappers use the source's intrinsic size.
                    val href = wrapper?.takeUnless { it.hasAttr("width") && it.hasAttr("height") }
                        ?.let { it.attr("href").ifEmpty { it.attr("xlink:href") } }.orEmpty()
                    content += LocalBookBlock.Image(if (href.isNotBlank() && !isRemote(href) && !href.startsWith('#')) image(href) else svg(node))
                    return FilterResult.SKIP_ENTIRELY
                }
                if (tag == "img" || tag == "image") {
                    flush()
                    val href = node.attr("src").ifEmpty { node.attr("href") }.ifEmpty { node.attr("xlink:href") }
                    requireImport(href.isNotBlank(), LocalBookImportReason.CorruptEpub) { "An EPUB image has no resource path." }
                    content += LocalBookBlock.Image(image(href))
                    return FilterResult.SKIP_ENTIRELY
                }
                return FilterResult.CONTINUE
            }

            override fun tail(node: Node, depth: Int): FilterResult {
                if (node is Element && node.normalName().substringAfter(':') in blocks) flush()
                return FilterResult.CONTINUE
            }
        }, root)
        flush()
        return Body(content, anchors)
    }

    private fun splitChapter(title: String, blocks: List<LocalBookBlock>): List<LocalBookChapter> {
        // Cache rows hold one chapter segment. Split long chapters without dropping content.
        val result = mutableListOf<LocalBookChapter>()
        val current = mutableListOf<LocalBookBlock>()
        var length = 0
        fun flush() {
            if (current.isEmpty()) return
            result += LocalBookChapter(if (result.isEmpty()) title else "$title (${result.size + 1})", blocks = current.toList())
            current.clear()
            length = 0
        }
        for (block in blocks) {
            if (current.size == TxtBookParser.MAX_CHAPTER_BLOCKS) flush()
            if (block is LocalBookBlock.Image) { current += block; continue }
            val text = (block as LocalBookBlock.Text).value
            var offset = 0
            while (offset < text.length) {
                if (length == TxtBookParser.MAX_CHAPTER_CHARACTERS || current.size == TxtBookParser.MAX_CHAPTER_BLOCKS) flush()
                var end = minOf(text.length, offset + TxtBookParser.MAX_CHAPTER_CHARACTERS - length)
                if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
                if (end == offset) { flush(); continue }
                current += LocalBookBlock.Text(text.substring(offset, end))
                length += end - offset
                offset = end
            }
        }
        flush()
        return result
    }

    private fun Element.descriptionMarkup(): String {
        if (childrenSize() == 0) return wholeText().trim()
        // Use HTML tag semantics so a prefixed XHTML break serializes as a single <br />.
        for (element in getAllElements()) {
            element.tagName(element.tagName().substringAfter(':'), Parser.NamespaceHtml)
        }
        return html()
    }

    private fun Element.named(name: String): List<Element> = allElements.filter { it.tagName().substringAfter(':') == name }

    private fun isRemote(href: String): Boolean = scheme.containsMatchIn(href) || href.startsWith("//")

    private fun resolve(base: String, href: String): Pair<String, String> {
        requireImport(!isRemote(href) && !href.startsWith('/'), LocalBookImportReason.CorruptEpub) { "EPUB resources must be local to the archive." }
        val rawPath = href.substringBefore('#').substringBefore('?')
        val fragment = decodePath(href.substringAfter('#', ""))
        if (rawPath.isEmpty()) return base to fragment
        val path = decodePath(rawPath)
        requireImport(!path.startsWith('/') && !scheme.containsMatchIn(path), LocalBookImportReason.CorruptEpub) { "EPUB resources must be local to the archive." }
        val parent = base.substringBeforeLast('/', "")
        return normalizePath(if (parent.isEmpty()) path else "$parent/$path") to fragment
    }

    private fun decodePath(path: String): String = try {
        URLDecoder.decode(path.replace("+", "%2B"), "UTF-8")
    } catch (failure: IllegalArgumentException) {
        throw LocalBookImportException(LocalBookImportReason.CorruptEpub, "Invalid EPUB resource encoding.", failure)
    }

    private fun normalizePath(path: String): String {
        requireImport(path.isNotEmpty() && !path.startsWith('/') && '\\' !in path && '\u0000' !in path && !scheme.containsMatchIn(path), LocalBookImportReason.CorruptEpub) {
            "Invalid EPUB resource path."
        }
        val parts = mutableListOf<String>()
        for (part in path.split('/')) when (part) {
            "", "." -> Unit
            ".." -> { requireImport(parts.isNotEmpty(), LocalBookImportReason.CorruptEpub) { "An EPUB resource escapes the archive." }; parts.removeAt(parts.lastIndex) }
            else -> parts += part
        }
        requireImport(parts.isNotEmpty(), LocalBookImportReason.CorruptEpub) { "Invalid EPUB resource path." }
        return parts.joinToString("/")
    }
}
