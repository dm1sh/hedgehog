package dm1sh.hedgehog.book

import dm1sh.hedgehog.model.DefaultVoices
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.StringReader
import java.net.URLDecoder
import java.util.Locale
import java.util.zip.ZipFile

object TextsConverter {

    private val XHTML_EXTS = listOf(".xhtml", ".html", ".htm", ".xml")
    private val NOTE_TYPE_ATTRS = listOf("noteref", "footnote", "endnote", "rearnote", "note")
    private val BLOCK_TAGS = setOf("aside", "li", "div", "section", "p", "td", "dd")
    private val HTML_BLOCK_BREAK_TAGS = setOf(
        "p", "div", "h1", "h2", "h3", "h4", "h5", "h6",
        "li", "ol", "ul", "tr", "td", "th", "blockquote",
        "pre", "hr", "section", "article", "header", "footer",
        "figcaption", "dt", "dd", "table", "caption"
    )
    private val FB2_LINE_BREAK_END_TAGS = setOf(
        "p", "subtitle", "text-author", "stanza", "date", "v", "tr"
    )
    private val BACKLINK_TEXTS = setOf("↑", "⤴", "↩", "←", "back", "return", "")
    private val NOTEREF_MARKER_REGEX = Regex("^[(\\[{]?[\\d*†‡§¶]+[)\\]}]?$")
    private val LEADING_NOTE_NUM_REGEX = Regex("^\\[?\\(?\\s*\\d+\\s*[.\\)\\]]?\\s*")
    private val FB2_NOTE_TITLE_REGEX = Regex(
        "(?si)(<section[^>]*\\bid\\s*=\\s*[\"'][^\"']*note[^\"']*[\"'][^>]*>\\s*)<title>\\s*(.*?)\\s*</title>"
    )

    private val NAMED_ENTITIES = mapOf(
        "nbsp" to " ",
        "ensp" to " ",
        "emsp" to " ",
        "thinsp" to " ",
        "lt" to "<",
        "gt" to ">",
        "quot" to "\"",
        "apos" to "'",
        "mdash" to "—",
        "ndash" to "–",
        "laquo" to "«",
        "raquo" to "»",
        "hellip" to "…",
        "bull" to "",
        "lsquo" to "'",
        "rsquo" to "'",
        "ldquo" to "\"",
        "rdquo" to "\"",
        "copy" to "©",
        "reg" to "®",
        "trade" to "™",
        "deg" to "°",
        "times" to "×",
        "minus" to "−",
        "amp" to "&"
    )

    fun ansiToUtf8(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size)
        for (b in bytes) {
            val unsigned = b.toInt() and 0xFF
            if (unsigned < 128) {
                sb.append(unsigned.toChar())
            } else {
                sb.append(DefaultVoices.CP1251_TO_UTF8[unsigned] ?: unsigned.toChar())
            }
        }
        return sb.toString()
    }

    fun readTextWithFallback(bytes: ByteArray): String {
        if (!isValidUtf8(bytes)) {
            return ansiToUtf8(bytes)
        }
        return String(bytes, Charsets.UTF_8)
    }

    private fun isValidUtf8(bytes: ByteArray): Boolean {
        var i = 0
        val len = bytes.size
        while (i < len) {
            val b0 = bytes[i].toInt() and 0xFF
            if (b0 < 0x80) {
                i++
                continue
            }
            val extra = when {
                (b0 and 0xE0) == 0xC0 -> {
                    if (b0 < 0xC2) return false
                    1
                }
                (b0 and 0xF0) == 0xE0 -> 2
                (b0 and 0xF8) == 0xF0 -> {
                    if (b0 > 0xF4) return false
                    3
                }
                else -> return false
            }
            if (i + extra >= len) return false
            for (k in 1..extra) {
                val bk = bytes[i + k].toInt() and 0xFF
                if ((bk and 0xC0) != 0x80) return false
            }
            i += extra + 1
        }
        return true
    }

    /**
     * Strips trailing <binary> blocks that follow the last </body> in an FB2 document
     * so multi-megabyte embedded base64 images are never parsed or copied.
     */
    private fun stripFb2Binaries(fb2String: String): String {
        val lastBodyClose = fb2String.lastIndexOf("</body>", ignoreCase = true)
        if (lastBodyClose != -1) {
            val cutEnd = lastBodyClose + "</body>".length
            if (cutEnd < fb2String.length) {
                return fb2String.substring(0, cutEnd) + "</FictionBook>"
            }
        }
        return fb2String
    }

    private fun inlineFb2DomNotes(doc: Document) {
        var hrefAttr = "l:href"
        val fictionBook = doc.selectFirst("FictionBook")
        if (fictionBook != null) {
            for (attr in fictionBook.attributes()) {
                if (attr.key.startsWith("xmlns:") && attr.value.endsWith("xlink")) {
                    val xlinkNs = attr.key.removePrefix("xmlns:")
                    hrefAttr = "$xlinkNs:href"
                }
            }
        }

        // Index all <section id="..."> elements in O(N) once instead of O(N*M) linear scans
        val sectionsById = HashMap<String, Element>()
        for (sec in doc.select("section[id]")) {
            val secId = sec.attr("id")
            if (secId.isNotEmpty() && !sectionsById.containsKey(secId)) {
                sectionsById[secId] = sec
            }
        }

        val toRemove = LinkedHashSet<Element>()
        val links = doc.select("a")
        for (link in links) {
            try {
                val href = link.attr(hrefAttr)
                    .ifEmpty { link.attr("l:href") }
                    .ifEmpty { link.attr("xlink:href") }
                    .ifEmpty { link.attr("href") }
                if (href.isEmpty()) continue
                val id = href.removePrefix("#")
                if (id.isEmpty()) continue
                val target = sectionsById[id] ?: doc.getElementById(id) ?: continue
                link.replaceWith(TextNode(" [[ " + target.text() + " ]] "))
                toRemove.add(target)
            } catch (_: Exception) {
            }
        }

        for (section in toRemove) {
            section.remove()
        }

        for (body in doc.select("body")) {
            val nameAttr = body.attr("name")
            if (nameAttr == "notes" || nameAttr == "comments") {
                if (body.selectFirst("section") == null) {
                    body.remove()
                }
            }
        }
    }

    /**
     * Port of fb2_tools/fb2_inline.py:
     * Inlines FB2 footnote links (<a l:href="#note_id">) as " [[ note_text ]] "
     * and removes the inlined note <section> elements and empty notes/comments bodies.
     */
    fun inlineFb2Notes(fb2String: String): String {
        return try {
            val stripped = stripFb2Binaries(fb2String)
            val doc: Document = Jsoup.parse(stripped, "", Parser.xmlParser())
            doc.outputSettings().prettyPrint(false)
            inlineFb2DomNotes(doc)
            doc.outerHtml()
        } catch (_: Exception) {
            fb2String
        }
    }

    fun convertFb2ToTxt(fb2String: String, inlineNotes: Boolean = false): String {
        val stripped = stripFb2Binaries(fb2String)
        if (inlineNotes) {
            try {
                val doc: Document = Jsoup.parse(stripped, "", Parser.xmlParser())
                doc.outputSettings().prettyPrint(false)
                inlineFb2DomNotes(doc)
                val result = StringBuilder()
                doc.selectFirst("title-info")?.let { ti ->
                    result.append(fb2ExtractMeta(ti.html()))
                }
                for (bodyEl in doc.select("body")) {
                    val bodyText = fb2FormatBody(bodyEl.html())
                    if (bodyText.isNotEmpty()) {
                        if (result.isNotEmpty() && !result.endsWith("\n")) {
                            result.append("\n")
                        }
                        result.append(bodyText)
                    }
                }
                return result.toString().trim()
            } catch (_: Exception) {
            }
        }

        val result = StringBuilder()
        val tiStart = stripped.indexOf("<title-info>", ignoreCase = true)
        if (tiStart != -1) {
            val tiEnd = stripped.indexOf("</title-info>", tiStart, ignoreCase = true)
            if (tiEnd != -1) {
                result.append(fb2ExtractMeta(stripped.substring(tiStart + "<title-info>".length, tiEnd)))
            }
        }

        var searchFrom = 0
        while (searchFrom < stripped.length) {
            val bodyOpen = stripped.indexOf("<body", searchFrom, ignoreCase = true)
            if (bodyOpen == -1) break
            val afterBodyName = bodyOpen + 5
            if (afterBodyName < stripped.length) {
                val nextCh = stripped[afterBodyName]
                if (nextCh != '>' && !nextCh.isWhitespace()) {
                    searchFrom = afterBodyName
                    continue
                }
            }
            val bodyTagEnd = stripped.indexOf('>', bodyOpen)
            if (bodyTagEnd == -1) break
            if (stripped[bodyTagEnd - 1] == '/') {
                searchFrom = bodyTagEnd + 1
                continue
            }
            val bodyClose = stripped.indexOf("</body>", bodyTagEnd + 1, ignoreCase = true)
            val bodyInner = if (bodyClose != -1) {
                searchFrom = bodyClose + 7
                stripped.substring(bodyTagEnd + 1, bodyClose)
            } else {
                searchFrom = stripped.length
                stripped.substring(bodyTagEnd + 1)
            }
            val formatted = fb2FormatBody(bodyInner)
            if (formatted.isNotEmpty()) {
                if (result.isNotEmpty() && !result.endsWith("\n")) {
                    result.append("\n")
                }
                result.append(formatted)
            }
        }
        return result.toString().trim()
    }

    private fun fb2ExtractMeta(ti: String): String {
        val parts = mutableListOf<String>()
        for (m in Regex("(?si)<author>(.*?)</author>").findAll(ti)) {
            val name = fb2AuthorName(m.groupValues[1])
            if (name.isNotEmpty()) parts.add(name)
        }
        Regex("(?si)<book-title>(.*?)</book-title>").find(ti)?.let { bt ->
            val text = fb2StripTags(bt.groupValues[1]).trim()
            if (text.isNotEmpty()) parts.add(text)
        }
        for (m in Regex("(?si)<translator>(.*?)</translator>").findAll(ti)) {
            val name = fb2AuthorName(m.groupValues[1])
            if (name.isNotEmpty()) parts.add(name)
        }
        Regex("(?si)<date[^>]*>(.*?)</date>").find(ti)?.let { dt ->
            val text = fb2StripTags(dt.groupValues[1]).trim()
            if (text.isNotEmpty()) parts.add(text)
        }
        Regex("(?si)<annotation>(.*?)</annotation>").find(ti)?.let { ann ->
            val text = fb2StripTags(ann.groupValues[1]).trim()
            if (text.isNotEmpty()) parts.add(text)
        }
        return if (parts.isNotEmpty()) parts.joinToString("\n") + "\n\n" else ""
    }

    private fun fb2AuthorName(authorXml: String): String {
        val nameParts = mutableListOf<String>()
        for (tag in listOf("first-name", "middle-name", "last-name")) {
            Regex("(?si)<$tag>(.*?)</$tag>").find(authorXml)?.let { m ->
                val part = fb2StripTags(m.groupValues[1]).trim()
                if (part.isNotEmpty()) nameParts.add(part)
            }
        }
        if (nameParts.isEmpty()) {
            Regex("(?si)<nickname>(.*?)</nickname>").find(authorXml)?.let { m ->
                val part = fb2StripTags(m.groupValues[1]).trim()
                if (part.isNotEmpty()) nameParts.add(part)
            }
        }
        return nameParts.joinToString(" ")
    }

    private fun fb2StripTags(text: String): String {
        return decodeEntities(stripXmlTagsSinglePass(text))
    }

    private fun stripXmlTagsSinglePass(text: String): String {
        if (!text.contains('<')) return text
        val sb = StringBuilder(text.length)
        var inTag = false
        for (i in 0 until text.length) {
            val c = text[i]
            if (c == '<') {
                inTag = true
            } else if (c == '>') {
                inTag = false
            } else if (!inTag) {
                sb.append(c)
            }
        }
        return sb.toString()
    }

    /**
     * Single-pass FB2 body formatter replacing 9 sequential regex passes + 23 entity passes.
     */
    private fun fb2FormatBody(rawContent: String): String {
        val content = if (rawContent.contains("note", ignoreCase = true)) {
            FB2_NOTE_TITLE_REGEX.replace(rawContent, "$1<empty-line/>$2")
        } else {
            rawContent
        }

        val len = content.length
        val sb = StringBuilder(len / 2 + 64)
        var i = 0
        while (i < len) {
            val c = content[i]
            if (c == '<') {
                val closeIdx = content.indexOf('>', i + 1)
                if (closeIdx == -1) break
                val tagContent = content.substring(i + 1, closeIdx).trim()
                if (tagContent.isNotEmpty()) {
                    val isClosing = tagContent[0] == '/'
                    val rawName = if (isClosing) tagContent.substring(1).trimStart() else tagContent
                    val tagName = extractTagNameLower(rawName)
                    if (!isClosing) {
                        when (tagName) {
                            "binary" -> {
                                val endBin = content.indexOf("</binary>", closeIdx + 1, ignoreCase = true)
                                i = if (endBin != -1) endBin + 9 else closeIdx + 1
                                continue
                            }
                            "empty-line" -> appendNormalizedChar(sb, '\n', maxNewlines = 4)
                            "title" -> {
                                repeat(4) { appendNormalizedChar(sb, '\n', maxNewlines = 4) }
                            }
                        }
                    } else {
                        if (tagName in FB2_LINE_BREAK_END_TAGS) {
                            appendNormalizedChar(sb, '\n', maxNewlines = 4)
                        }
                    }
                }
                i = closeIdx + 1
            } else if (c == '&') {
                val semi = content.indexOf(';', i + 1)
                if (semi != -1 && semi - i <= 12) {
                    val entity = content.substring(i + 1, semi)
                    val decoded = decodeSingleEntity(entity)
                    if (decoded != null) {
                        for (dc in decoded) {
                            val norm = if (dc == '\r' || dc == '\n' || dc == '\t') ' ' else dc
                            appendNormalizedChar(sb, norm, maxNewlines = 4)
                        }
                        i = semi + 1
                        continue
                    }
                }
                appendNormalizedChar(sb, c, maxNewlines = 4)
                i++
            } else if (c == '\r' || c == '\n' || c == '\t') {
                appendNormalizedChar(sb, ' ', maxNewlines = 4)
                i++
            } else {
                appendNormalizedChar(sb, c, maxNewlines = 4)
                i++
            }
        }
        return sb.toString().trim()
    }

    private fun extractTagNameLower(rawTag: String): String {
        var end = 0
        val len = rawTag.length
        while (end < len) {
            val ch = rawTag[end]
            if (ch.isWhitespace() || ch == '/' || ch == '>') break
            end++
        }
        return rawTag.substring(0, end).lowercase(Locale.ROOT)
    }

    private fun appendNormalizedChar(sb: StringBuilder, c: Char, maxNewlines: Int) {
        if (c == '\n') {
            while (sb.isNotEmpty() && sb[sb.length - 1] == ' ') {
                sb.setLength(sb.length - 1)
            }
            var nlCount = 0
            var k = sb.length - 1
            while (k >= 0 && sb[k] == '\n') {
                nlCount++
                k--
            }
            if (nlCount < maxNewlines) {
                sb.append('\n')
            }
        } else if (c == ' ') {
            if (sb.isNotEmpty()) {
                val last = sb[sb.length - 1]
                if (last != ' ' && last != '\n') {
                    sb.append(' ')
                }
            }
        } else {
            sb.append(c)
        }
    }

    fun convertEpubToTxt(epubPath: String, inlineNotes: Boolean = false): String {
        val textContent = StringBuilder()
        try {
            ZipFile(epubPath).use { zip ->
                if (inlineNotes) {
                    return convertEpubWithInlinedNotes(zip)
                }
                var htmlFiles = getEpubSpine(zip)
                if (htmlFiles.isEmpty()) {
                    htmlFiles = getEpubNcx(zip)
                }
                for (path in htmlFiles) {
                    val entry = zip.getEntry(path) ?: continue
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    val page = htmlToText(String(bytes, Charsets.UTF_8))
                    if (page.isNotEmpty()) {
                        if (textContent.isNotEmpty()) textContent.append("\n")
                        textContent.append(page)
                    }
                }
            }
        } catch (_: Exception) {
        }
        return textContent.toString()
    }

    /**
     * Port of fb2_tools/epub_inline.py:
     * Parses each XHTML document once, mutates the cached Jsoup Document in-place,
     * and extracts chapter text directly from the DOM without re-serializing to HTML.
     */
    private fun convertEpubWithInlinedNotes(zip: ZipFile): String {
        val names = mutableListOf<String>()
        val files = mutableMapOf<String, String>()
        for (entry in zip.entries().asSequence()) {
            if (entry.isDirectory) continue
            val name = simplifyPath(entry.name)
            names.add(name)
            val lower = name.lowercase(Locale.ROOT)
            if (lower.endsWith(".opf") || lower.endsWith(".ncx") || XHTML_EXTS.any { lower.endsWith(it) }) {
                try {
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    files[name] = String(bytes, Charsets.UTF_8)
                } catch (_: Exception) {
                }
            }
        }

        val docs = getEpubContentDocuments(names, files)
        val parsedCache = mutableMapOf<String, Document?>()
        val idIndexCache = mutableMapOf<String, Map<String, Element>>()

        fun soupFor(docName: String): Document? {
            if (!parsedCache.containsKey(docName)) {
                val content = files[docName]
                val doc = if (content != null) Jsoup.parse(content, "", Parser.htmlParser()) else null
                parsedCache[docName] = doc
                if (doc != null) {
                    val map = HashMap<String, Element>()
                    for (el in doc.select("[id], [name]")) {
                        val idVal = el.attr("id")
                        if (idVal.isNotEmpty() && !map.containsKey(idVal)) {
                            map[idVal] = el
                        }
                        val nameVal = el.attr("name")
                        if (nameVal.isNotEmpty() && !map.containsKey(nameVal)) {
                            map[nameVal] = el
                        }
                    }
                    idIndexCache[docName] = map
                }
            }
            return parsedCache[docName]
        }

        fun findNoteElement(docName: String, anchor: String): Element? {
            val soup = soupFor(docName) ?: return null
            val indexMap = idIndexCache[docName]
            return indexMap?.get(anchor) ?: soup.getElementById(anchor)
        }

        fun extractNoteText(docName: String, anchor: String): String? {
            val el = findNoteElement(docName, anchor) ?: return null
            val container = epubNoteContainer(el) ?: return null
            val text = epubCleanNoteText(container)
            return text.ifEmpty { null }
        }

        val noteTargets = mutableMapOf<String, MutableSet<String>>()

        for (docName in docs) {
            val soup = soupFor(docName) ?: continue
            val docDir = if (docName.contains("/")) docName.substringBeforeLast("/") else ""

            for (a in soup.select("a").toList()) {
                val href = a.attr("href")
                if (href.isEmpty()) continue
                if (!epubLooksLikeNoteref(a)) continue

                val (path, anchor) = splitHref(href)
                val targetDoc = if (path.isEmpty()) {
                    docName
                } else {
                    resolveHref(docDir, path)
                }
                if (anchor.isEmpty() || targetDoc.isNullOrEmpty()) continue

                val noteText = extractNoteText(targetDoc, anchor) ?: continue
                noteTargets.getOrPut(targetDoc) { mutableSetOf() }.add(anchor)

                val inlineSpan = soup.createElement("span")
                    .attr("class", "inlined-footnote")
                    .text(" ($noteText)")

                var target: Element = a
                val parent = a.parent()
                if (parent != null && parent.tagName().lowercase(Locale.ROOT) == "sup") {
                    if (parent.text().trim() == a.text().trim()) {
                        target = parent
                    }
                }
                target.replaceWith(inlineSpan)
            }
        }

        // Remove inlined note containers in-place on cached Documents
        for ((docName, anchors) in noteTargets) {
            soupFor(docName) ?: continue
            val indexMap = idIndexCache[docName]
            for (anchor in anchors) {
                val el = indexMap?.get(anchor) ?: findNoteElement(docName, anchor) ?: continue
                val container = epubNoteContainer(el)
                container?.remove()
            }
        }

        val textContent = StringBuilder()
        for (docName in docs) {
            val soup = soupFor(docName) ?: continue
            val page = domDocumentToText(soup)
            if (page.isNotEmpty()) {
                if (textContent.isNotEmpty()) textContent.append("\n")
                textContent.append(page)
            }
        }
        return textContent.toString()
    }

    private fun domDocumentToText(doc: Document): String {
        val root: Element = doc.body() ?: doc
        val sb = StringBuilder(1024)
        walkDomNodeToText(root, sb)
        return sb.toString().trim()
    }

    private fun walkDomNodeToText(node: Node, sb: StringBuilder) {
        when (node) {
            is TextNode -> {
                val raw = node.wholeText
                for (i in 0 until raw.length) {
                    val ch = raw[i]
                    val norm = if (ch == '\r' || ch == '\n' || ch == '\t') ' ' else ch
                    appendNormalizedChar(sb, norm, maxNewlines = 1)
                }
            }
            is Element -> {
                val tag = node.tagName().lowercase(Locale.ROOT)
                if (tag == "script" || tag == "style" || tag == "svg") return
                if (tag == "br") {
                    appendNormalizedChar(sb, '\n', maxNewlines = 1)
                    return
                }
                val isBlock = tag in HTML_BLOCK_BREAK_TAGS
                if (isBlock) {
                    appendNormalizedChar(sb, '\n', maxNewlines = 1)
                }
                for (child in node.childNodes()) {
                    walkDomNodeToText(child, sb)
                }
                if (isBlock) {
                    appendNormalizedChar(sb, '\n', maxNewlines = 1)
                }
            }
        }
    }

    private fun getEpubContentDocuments(names: List<String>, files: Map<String, String>): List<String> {
        var opfPath: String? = null
        val containerXml = files["META-INF/container.xml"]
        if (containerXml != null) {
            val soup = Jsoup.parse(containerXml, "", Parser.xmlParser())
            val rootfile = soup.selectFirst("rootfile")
            val fullPath = rootfile?.attr("full-path").orEmpty()
            if (fullPath.isNotEmpty()) {
                opfPath = simplifyPath(fullPath)
            }
        }
        if (opfPath == null) {
            opfPath = names.firstOrNull { it.lowercase(Locale.ROOT).endsWith(".opf") }
        }

        val docs = mutableListOf<String>()
        if (opfPath != null && files.containsKey(opfPath)) {
            val opfDir = if (opfPath.contains("/")) opfPath.substringBeforeLast("/") else ""
            val soup = Jsoup.parse(files[opfPath]!!, "", Parser.xmlParser())
            val manifest = LinkedHashMap<String, String>()
            for (item in soup.select("item")) {
                val id = item.attr("id")
                val href = item.attr("href")
                if (id.isNotEmpty() && href.isNotEmpty()) {
                    val resolved = resolveHref(opfDir, href)
                    if (!resolved.isNullOrEmpty()) {
                        manifest[id] = resolved
                    }
                }
            }
            val spine = soup.selectFirst("spine")
            if (spine != null) {
                for (itemref in spine.select("itemref")) {
                    val idref = itemref.attr("idref")
                    val name = manifest[idref]
                    if (name != null && name !in docs && files.containsKey(name)) {
                        docs.add(name)
                    }
                }
            }
            for (name in manifest.values) {
                val lower = name.lowercase(Locale.ROOT)
                if (name !in docs && files.containsKey(name) && XHTML_EXTS.any { lower.endsWith(it) }) {
                    docs.add(name)
                }
            }
        }

        if (docs.isEmpty()) {
            for (name in names) {
                val lower = name.lowercase(Locale.ROOT)
                if (XHTML_EXTS.any { lower.endsWith(it) } && !name.contains("META-INF")) {
                    docs.add(name)
                }
            }
        }
        return docs
    }

    private fun resolveHref(baseDir: String, rawHref: String): String? {
        val withoutFrag = rawHref.substringBefore("#")
        val decoded = try { URLDecoder.decode(withoutFrag, "UTF-8") } catch (_: Exception) { withoutFrag }
        if (decoded.isEmpty()) return null
        val joined = if (baseDir.isNotEmpty()) "$baseDir/$decoded" else decoded
        return simplifyPath(joined)
    }

    private fun splitHref(rawHref: String): Pair<String, String> {
        val pathPart = rawHref.substringBefore("#")
        val fragPart = if (rawHref.contains("#")) rawHref.substringAfter("#") else ""
        val decodedPath = try { URLDecoder.decode(pathPart, "UTF-8") } catch (_: Exception) { pathPart }
        return decodedPath to fragPart
    }

    private fun epubNoteContainer(el: Element): Element? {
        var node: Element? = el
        while (node != null && node.tagName().lowercase(Locale.ROOT) !in BLOCK_TAGS) {
            val parent = node.parent()
            val parentName = parent?.tagName()?.lowercase(Locale.ROOT)
            if (parent == null || parentName == "body" || parentName == "html" || parentName == "#root") {
                break
            }
            node = parent
        }
        return node ?: el
    }

    private fun epubCleanNoteText(container: Element): String {
        val clone = container.clone()
        for (a in clone.select("a").toList()) {
            val etype = (a.attr("epub:type").ifEmpty { a.attr("type") }).lowercase(Locale.ROOT)
            val txt = a.text().trim()
            if ("backlink" in etype || txt.lowercase(Locale.ROOT) in BACKLINK_TEXTS) {
                if (txt.isNotEmpty() && "backlink" !in etype) {
                    a.unwrap()
                } else {
                    a.remove()
                }
            }
        }
        var text = clone.text().replace(Regex("\\s+"), " ").trim()
        text = LEADING_NOTE_NUM_REGEX.replace(text, "")
        return text
    }

    private fun epubLooksLikeNoteref(a: Element): Boolean {
        for (attr in listOf("epub:type", "type", "role")) {
            val v = a.attr(attr).lowercase(Locale.ROOT)
            if (NOTE_TYPE_ATTRS.any { it in v }) return true
        }
        val classes = a.className().lowercase(Locale.ROOT)
        if (NOTE_TYPE_ATTRS.any { it in classes } || "fn" in classes) return true
        if (a.parent()?.tagName()?.lowercase(Locale.ROOT) == "sup") return true
        val txt = a.text().trim()
        if (txt.isNotEmpty() && txt.length <= 4 && NOTEREF_MARKER_REGEX.matches(txt)) {
            return true
        }
        return false
    }

    /**
     * Single-pass HTML-to-text converter replacing 8 regex passes + 23 entity passes.
     */
    private fun htmlToText(rawHtml: String): String {
        val bodyOpen = rawHtml.indexOf("<body", ignoreCase = true)
        var startIdx = 0
        var endIdx = rawHtml.length
        if (bodyOpen != -1) {
            val bodyTagEnd = rawHtml.indexOf('>', bodyOpen)
            if (bodyTagEnd != -1) {
                startIdx = bodyTagEnd + 1
                val bodyClose = rawHtml.lastIndexOf("</body>", ignoreCase = true)
                if (bodyClose > startIdx) {
                    endIdx = bodyClose
                }
            }
        }

        val sb = StringBuilder((endIdx - startIdx) / 2 + 64)
        var i = startIdx
        while (i < endIdx) {
            val c = rawHtml[i]
            if (c == '<') {
                if (i + 3 < endIdx && rawHtml[i + 1] == '!' && rawHtml[i + 2] == '-' && rawHtml[i + 3] == '-') {
                    val commentEnd = rawHtml.indexOf("-->", i + 4)
                    i = if (commentEnd != -1) commentEnd + 3 else endIdx
                    continue
                }
                val closeIdx = rawHtml.indexOf('>', i + 1)
                if (closeIdx == -1 || closeIdx >= endIdx) break
                val tagContent = rawHtml.substring(i + 1, closeIdx).trim()
                if (tagContent.isNotEmpty()) {
                    val isClosing = tagContent[0] == '/'
                    val rawName = if (isClosing) tagContent.substring(1).trimStart() else tagContent
                    val tagName = extractTagNameLower(rawName)
                    if (!isClosing && (tagName == "script" || tagName == "style" || tagName == "svg")) {
                        val closeTag = "</$tagName"
                        val skipTo = rawHtml.indexOf(closeTag, closeIdx + 1, ignoreCase = true)
                        if (skipTo != -1) {
                            val afterSkip = rawHtml.indexOf('>', skipTo + closeTag.length)
                            i = if (afterSkip != -1) afterSkip + 1 else endIdx
                            continue
                        }
                    } else if (tagName == "br" || tagName in HTML_BLOCK_BREAK_TAGS) {
                        appendNormalizedChar(sb, '\n', maxNewlines = 1)
                    }
                }
                i = closeIdx + 1
            } else if (c == '&') {
                val semi = rawHtml.indexOf(';', i + 1)
                if (semi != -1 && semi < endIdx && semi - i <= 12) {
                    val entity = rawHtml.substring(i + 1, semi)
                    val decoded = decodeSingleEntity(entity)
                    if (decoded != null) {
                        for (dc in decoded) {
                            val norm = if (dc == '\r' || dc == '\n' || dc == '\t') ' ' else dc
                            appendNormalizedChar(sb, norm, maxNewlines = 1)
                        }
                        i = semi + 1
                        continue
                    }
                }
                appendNormalizedChar(sb, c, maxNewlines = 1)
                i++
            } else if (c == '\r' || c == '\n' || c == '\t') {
                appendNormalizedChar(sb, ' ', maxNewlines = 1)
                i++
            } else {
                appendNormalizedChar(sb, c, maxNewlines = 1)
                i++
            }
        }
        return sb.toString().trim()
    }

    private fun getEpubSpine(zip: ZipFile): List<String> {
        val containerEntry = zip.getEntry("META-INF/container.xml") ?: return emptyList()
        val containerXml = zip.getInputStream(containerEntry).use { String(it.readBytes(), Charsets.UTF_8) }
        var opfPath = ""
        try {
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(StringReader(containerXml))
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "rootfile") {
                    opfPath = parser.getAttributeValue(null, "full-path") ?: ""
                    if (opfPath.isNotEmpty()) break
                }
                event = parser.next()
            }
        } catch (_: Exception) {
        }
        if (opfPath.isEmpty()) return emptyList()
        val opfEntry = zip.getEntry(opfPath) ?: return emptyList()
        val opfXml = zip.getInputStream(opfEntry).use { String(it.readBytes(), Charsets.UTF_8) }
        val opfDir = if (opfPath.contains("/")) opfPath.substringBeforeLast("/") + "/" else ""
        val manifest = mutableMapOf<String, String>()
        val spine = mutableListOf<String>()
        try {
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(StringReader(opfXml))
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "item" -> {
                            val id = parser.getAttributeValue(null, "id") ?: ""
                            val href = parser.getAttributeValue(null, "href") ?: ""
                            val mt = parser.getAttributeValue(null, "media-type") ?: ""
                            if (id.isNotEmpty() && href.isNotEmpty() && mt.contains("html")) {
                                val decodedHref = try { URLDecoder.decode(href, "UTF-8") } catch (_: Exception) { href }
                                manifest[id] = simplifyPath(opfDir + decodedHref)
                            }
                        }
                        "itemref" -> {
                            val idref = parser.getAttributeValue(null, "idref") ?: ""
                            if (idref.isNotEmpty()) spine.add(idref)
                        }
                    }
                }
                event = parser.next()
            }
        } catch (_: Exception) {
        }
        return spine.mapNotNull { manifest[it] }
    }

    private fun getEpubNcx(zip: ZipFile): List<String> {
        val result = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (entry in zip.entries().asSequence()) {
            if (entry.name.endsWith(".ncx")) {
                val ncxXml = zip.getInputStream(entry).use { String(it.readBytes(), Charsets.UTF_8) }
                val baseDir = if (entry.name.contains("/")) entry.name.substringBeforeLast("/") + "/" else ""
                try {
                    val parser = XmlPullParserFactory.newInstance().newPullParser()
                    parser.setInput(StringReader(ncxXml))
                    var event = parser.eventType
                    while (event != XmlPullParser.END_DOCUMENT) {
                        if (event == XmlPullParser.START_TAG && parser.name == "content") {
                            val srcRaw = (parser.getAttributeValue(null, "src") ?: "").substringBefore("#")
                            val src = try { URLDecoder.decode(srcRaw, "UTF-8") } catch (_: Exception) { srcRaw }
                            val path = simplifyPath(baseDir + src)
                            if (seen.add(path)) {
                                result.add(path)
                            }
                        }
                        event = parser.next()
                    }
                } catch (_: Exception) {
                }
                break
            }
        }
        return result
    }

    private fun simplifyPath(path: String): String {
        val parts = mutableListOf<String>()
        for (seg in path.replace("\\", "/").split("/")) {
            when (seg) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }

    private fun decodeSingleEntity(entity: String): String? {
        if (entity.isEmpty()) return null
        if (entity[0] == '#') {
            val code = if (entity.length > 1 && (entity[1] == 'x' || entity[1] == 'X')) {
                entity.substring(2).toIntOrNull(16)
            } else {
                entity.substring(1).toIntOrNull(10)
            }
            if (code != null && code in 1..65535) {
                return code.toChar().toString()
            }
            return null
        }
        return NAMED_ENTITIES[entity]
    }

    fun decodeEntities(rawText: String): String {
        if (!rawText.contains('&')) return rawText
        val len = rawText.length
        val sb = StringBuilder(len)
        var i = 0
        while (i < len) {
            val c = rawText[i]
            if (c == '&') {
                val semi = rawText.indexOf(';', i + 1)
                if (semi != -1 && semi - i <= 12) {
                    val entity = rawText.substring(i + 1, semi)
                    val decoded = decodeSingleEntity(entity)
                    if (decoded != null) {
                        sb.append(decoded)
                        i = semi + 1
                        continue
                    }
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    fun extractZipEntries(
        zipPath: String,
        inlineNotes: Boolean = false,
        onTextEntry: (saveName: String, text: String, voice: String, rate: String, pitch: String) -> Unit
    ) {
        try {
            val zipFileObj = File(zipPath)
            val baseStem = zipFileObj.nameWithoutExtension
            ZipFile(zipFileObj).use { zip ->
                var indexFile = 0
                for (entry in zip.entries().asSequence()) {
                    if (entry.isDirectory) continue
                    indexFile++
                    val entryName = entry.name
                    val saveName = if (entryName.contains('\uFFFD')) "$baseStem $indexFile" else File(entryName).nameWithoutExtension
                    val lower = entryName.lowercase()
                    when {
                        lower.endsWith("txt") || lower.endsWith("ini") -> {
                            val bytes = zip.getInputStream(entry).use { it.readBytes() }
                            onTextEntry(saveName, readTextWithFallback(bytes), "", "", "")
                        }
                        lower.endsWith("fb2") -> {
                            val bytes = zip.getInputStream(entry).use { it.readBytes() }
                            onTextEntry(saveName, convertFb2ToTxt(readTextWithFallback(bytes), inlineNotes = inlineNotes), "", "", "")
                        }
                        lower.endsWith(".csv") -> {
                            val bytes = zip.getInputStream(entry).use { it.readBytes() }
                            val csvLines = parseCsv(readTextWithFallback(bytes))
                            csvLines.forEachIndexed { idx, line ->
                                processCsvLine(line, saveName, idx + 1, onTextEntry)
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    fun processCsvLine(
        line: List<String>,
        defaultName: String,
        lineNum: Int,
        onTextEntry: (saveName: String, text: String, voice: String, rate: String, pitch: String) -> Unit
    ) {
        if (line.size >= 5 && line[1].toDoubleOrNull() != null && line[2].toDoubleOrNull() != null) {
            onTextEntry(line[4], line[3], line[0], line[1], line[2])
        } else {
            onTextEntry("$defaultName L-${"%04d".format(lineNum)}", line.joinToString(" "), "", "", "")
        }
    }

    fun parseCsv(rawText: String): List<List<String>> {
        var text = rawText
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1)
        }
        val delim = detectCsvDelimiter(text)
        val result = mutableListOf<List<String>>()
        for (lineStr in text.replace("\r\n", "\n").replace("\r", "\n").split("\n")) {
            if (lineStr.trim().isNotEmpty()) {
                result.add(parseCsvLine(lineStr, delim))
            }
        }
        return result
    }

    private fun detectCsvDelimiter(text: String): Char {
        val sample = text.take(4096)
        val tabs = sample.count { it == '\t' }
        val semis = sample.count { it == ';' }
        val commas = sample.count { it == ',' }
        if (tabs > commas && tabs > semis) return '\t'
        if (semis > commas) return ';'
        return ','
    }

    private fun parseCsvLine(line: String, delim: Char): List<String> {
        val result = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length && line[i + 1] == '"') {
                        field.append('"')
                        i++
                    } else {
                        inQuotes = false
                    }
                } else {
                    field.append(c)
                }
            } else {
                when (c) {
                    '"' -> inQuotes = true
                    delim -> {
                        result.add(field.toString())
                        field.setLength(0)
                    }
                    else -> field.append(c)
                }
            }
            i++
        }
        result.add(field.toString())
        return result
    }
}
