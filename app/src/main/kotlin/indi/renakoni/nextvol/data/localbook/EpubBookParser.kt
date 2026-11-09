package indi.renakoni.nextvol.data.localbook

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
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
    private const val MAX_ENTRIES = 20_000
    private val blocks = setOf("p", "div", "section", "article", "h1", "h2", "h3", "h4", "h5", "h6",
        "li", "blockquote", "pre", "tr", "td", "br", "hr")
    private val ignored = setOf("head", "script", "style", "noscript")
    private val whitespace = Regex("\\s+")
    private val scheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")

    private data class Item(val id: String, val path: String, val mediaType: String, val properties: Set<String>)
    private data class NavigationEntry(val path: String, val fragment: String, val title: String)
    private data class Body(val content: List<LocalBookBlock>, val anchors: Map<String, Int>)

    fun parse(file: File, assetsDirectory: File, fallbackTitle: String): ParsedLocalBook = ZipFile(file).use { zip ->
        val entries = linkedMapOf<String, ZipEntry>()
        val allEntries = zip.entries()
        var count = 0
        while (allEntries.hasMoreElements()) {
            val entry = allEntries.nextElement()
            requireImport(++count <= MAX_ENTRIES, LocalBookImportReason.EpubLimit) { "The EPUB contains too many files." }
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
            Item(item.attr("id"), resolve(opfPath, item.attr("href")).first, item.attr("media-type"),
                item.attr("properties").split(whitespace).toSet())
        }
        requireImport(items.isNotEmpty() && items.all { it.id.isNotBlank() } && items.map { it.id }.distinct().size == items.size, LocalBookImportReason.CorruptEpub) {
            "The EPUB manifest has invalid IDs."
        }
        val byId = items.associateBy { it.id }
        val spine = requireImportNotNull(opf.named("spine").firstOrNull(), LocalBookImportReason.CorruptEpub) { "The EPUB has no reading order." }
        val readingOrder = spine.named("itemref").map {
            requireImportNotNull(byId[it.attr("idref")], LocalBookImportReason.CorruptEpub) { "The EPUB reading order refers to a missing item." }
        }
        requireImport(readingOrder.isNotEmpty(), LocalBookImportReason.NoContent) { "The EPUB has no readable chapters." }
        val encrypted = if (entries.containsKey("META-INF/encryption.xml")) {
            xml("META-INF/encryption.xml").named("CipherReference").map { resolve("", it.attr("URI")).first }.toSet()
        } else emptySet()
        requireImport(readingOrder.none { it.path in encrypted }, LocalBookImportReason.EncryptedEpub) { "Encrypted EPUB text is not supported." }

        val navigation = mutableListOf<NavigationEntry>()
        val navItem = items.firstOrNull { "nav" in it.properties }
        if (navItem != null) {
            val navDocument = Jsoup.parse(read(navItem.path).inputStream(), null, "")
            val toc = navDocument.named("nav").firstOrNull { node ->
                node.attributes().any { it.key.substringAfter(':') == "type" && "toc" in it.value.split(whitespace) }
            }
            toc?.select("a[href]")?.forEach { link ->
                val (path, fragment) = resolve(navItem.path, link.attr("href"))
                navigation += NavigationEntry(path, fragment, link.text())
            }
        }
        if (navigation.isEmpty()) {
            val ncx = byId[spine.attr("toc")] ?: items.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
            if (ncx != null) xml(ncx.path).named("navPoint").forEach { point ->
                val content = point.named("content").firstOrNull() ?: return@forEach
                val (path, fragment) = resolve(ncx.path, content.attr("src"))
                navigation += NavigationEntry(path, fragment, point.named("navLabel").firstOrNull()?.text().orEmpty())
            }
        }
        val savedAssets = mutableMapOf<String, String>()
        fun image(path: String): String = savedAssets.getOrPut(path) {
            requireImport(path !in encrypted, LocalBookImportReason.EncryptedEpub) { "Encrypted EPUB images are not supported." }
            requireImport(!path.endsWith(".svg", ignoreCase = true) && items.none { it.path == path && it.mediaType == "image/svg+xml" }, LocalBookImportReason.UnsupportedEpub) {
                "Standalone SVG images are not supported. Convert this EPUB to use PNG or JPEG images."
            }
            requireImport(assetsDirectory.isDirectory || assetsDirectory.mkdirs(), LocalBookImportReason.Storage) { "Cannot create the EPUB image directory." }
            val digest = MessageDigest.getInstance("SHA-256").digest(path.toByteArray()).joinToString("") { "%02x".format(it) }
            val extension = path.substringAfterLast('.', "img").takeIf { it.matches(Regex("[a-zA-Z0-9]{1,6}")) } ?: "img"
            val name = "$digest.$extension"
            File(assetsDirectory, name).outputStream().use { copy(path, it) }
            "assets/$name"
        }
        val coverId = metadata.named("meta").firstOrNull { it.attr("name") == "cover" }?.attr("content")
        val cover = items.firstOrNull { "cover-image" in it.properties } ?: byId[coverId]
        val chapters = mutableListOf<LocalBookChapter>()
        for (item in readingOrder) {
            requireImport(item.mediaType in setOf("application/xhtml+xml", "text/html"), LocalBookImportReason.UnsupportedEpub) { "Unsupported EPUB spine content: ${item.mediaType}" }
            val labels = navigation.filter { it.path == item.path && it.title.isNotBlank() }
            val document = Jsoup.parse(read(item.path).inputStream(), null, "")
            val fallback = labels.firstOrNull()?.title
                ?: document.selectFirst("h1,h2,h3")?.text()?.takeIf { it.isNotBlank() }
                ?: document.title().takeIf { it.isNotBlank() } ?: item.path.substringAfterLast('/').substringBeforeLast('.')
            val body = extractBody(document.body()) { href -> image(resolve(item.path, href).first) }
            if (body.content.isEmpty()) continue
            val boundaries = labels.mapNotNull { label ->
                body.anchors[label.fragment]?.let { it to label.title }
            }.distinctBy { it.first }.sortedBy { it.first }.toMutableList()
            if (boundaries.firstOrNull()?.first != 0) boundaries.add(0, 0 to fallback)
            boundaries.forEachIndexed { index, (start, title) ->
                val end = boundaries.getOrNull(index + 1)?.first ?: body.content.size
                if (end > start) chapters += splitChapter(title.limit(200), body.content.subList(start, end))
            }
            requireImport(chapters.size <= TxtBookParser.MAX_CHAPTERS, LocalBookImportReason.TooManyChapters) { "The EPUB contains too many chapters." }
        }
        requireImport(chapters.isNotEmpty(), LocalBookImportReason.NoContent) { "The EPUB has no readable content." }
        ParsedLocalBook(
            title = (metadata.named("title").firstOrNull()?.text()?.takeIf { it.isNotBlank() } ?: fallbackTitle).limit(200),
            chapters = chapters,
            author = metadata.named("creator").joinToString(", ") { it.text() }.limit(2_000),
            description = metadata.named("description").firstOrNull()?.text().orEmpty().limit(16_000),
            publishingHouse = metadata.named("publisher").firstOrNull()?.text().orEmpty().limit(1_000),
            coverPath = cover?.let { image(it.path) },
        )
    }

    private fun extractBody(root: Element, image: (String) -> String): Body {
        val content = mutableListOf<LocalBookBlock>()
        val anchors = linkedMapOf<String, Int>()
        val text = StringBuilder()
        fun flush() {
            val value = text.toString().replace(whitespace, " ").trim()
            if (value.isNotEmpty()) content += LocalBookBlock.Text(value)
            text.clear()
        }
        fun visit(node: Node, depth: Int) {
            requireImport(depth <= 256, LocalBookImportReason.EpubLimit) { "The EPUB markup is nested too deeply." }
            if (node is TextNode) { text.append(node.wholeText); return }
            if (node !is Element) return
            val tag = node.tagName().substringAfter(':').lowercase()
            if (tag in ignored) return
            requireImport(tag != "svg" || node.named("image").isNotEmpty(), LocalBookImportReason.UnsupportedEpub) { "Inline vector SVG artwork is not supported." }
            if (tag in blocks) flush()
            val id = node.id().ifEmpty { if (tag == "a") node.attr("name") else "" }
            if (id.isNotEmpty()) { flush(); anchors.putIfAbsent(id, content.size) }
            if (tag == "img" || tag == "image") {
                flush()
                val href = node.attr("src").ifEmpty { node.attr("href") }.ifEmpty { node.attr("xlink:href") }
                requireImport(href.isNotBlank(), LocalBookImportReason.CorruptEpub) { "An EPUB image has no resource path." }
                content += LocalBookBlock.Image(image(href))
            } else {
                node.childNodes().forEach { visit(it, depth + 1) }
            }
            if (tag in blocks) flush()
        }
        visit(root, 0)
        flush()
        return Body(content, anchors)
    }

    private fun splitChapter(title: String, blocks: List<LocalBookBlock>): List<LocalBookChapter> {
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

    private fun Element.named(name: String): List<Element> = allElements.filter { it.tagName().substringAfter(':') == name }

    private fun String.limit(length: Int) = take(length).let {
        if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it
    }

    private fun resolve(base: String, href: String): Pair<String, String> {
        requireImport(!scheme.containsMatchIn(href) && !href.startsWith('/'), LocalBookImportReason.CorruptEpub) { "EPUB resources must be local to the archive." }
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
        requireImport(path.isNotEmpty() && path.length <= 4096 && !path.startsWith('/') && '\\' !in path && '\u0000' !in path && !scheme.containsMatchIn(path), LocalBookImportReason.CorruptEpub) {
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
