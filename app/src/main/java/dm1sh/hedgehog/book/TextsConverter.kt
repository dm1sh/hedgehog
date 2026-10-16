package dm1sh.hedgehog.book

import dm1sh.hedgehog.model.DefaultVoices
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
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
    private val BACKLINK_TEXTS = setOf("↑", "⤴", "↩", "←", "back", "return", "")
    private val NOTEREF_MARKER_REGEX = Regex("^[(\\[{]?[\\d*†‡§¶]+[)\\]}]?$")
    private val LEADING_NOTE_NUM_REGEX = Regex("^\\[?\\(?\\s*\\d+\\s*[.\\)\\]]?\\s*")

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
        val utf8 = String(bytes, Charsets.UTF_8)
        return if (utf8.contains('\uFFFD')) ansiToUtf8(bytes) else utf8
    }

    /**
     * Port of fb2_tools/fb2_inline.py:
     * Inlines FB2 footnote links (<a l:href="#note_id">) as " [[ note_text ]] "
     * and removes the inlined note <section> elements and empty notes/comments bodies.
     */
    fun inlineFb2Notes(fb2String: String): String {
        return try {
            val doc: Document = Jsoup.parse(fb2String, "", Parser.xmlParser())
            doc.outputSettings().prettyPrint(false)

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
                    val target = doc.select("section").firstOrNull { it.attr("id") == id }
                        ?: doc.getElementById(id)
                        ?: continue
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

            doc.outerHtml()
        } catch (_: Exception) {
            fb2String
        }
    }

    fun convertFb2ToTxt(fb2String: String, inlineNotes: Boolean = false): String {
        val sourceFb2 = if (inlineNotes) inlineFb2Notes(fb2String) else fb2String
        val result = StringBuilder()
        val tiRegex = Regex("(?si)<title-info>(.*?)</title-info>")
        val tiMatch = tiRegex.find(sourceFb2)
        if (tiMatch != null) {
            result.append(fb2ExtractMeta(tiMatch.groupValues[1]))
        }
        val bodyRegex = Regex("(?si)<body[^>]*>(.*?)</body>")
        for (m in bodyRegex.findAll(sourceFb2)) {
            val body = fb2FormatBody(m.groupValues[1])
            if (body.isNotEmpty()) {
                if (result.isNotEmpty() && !result.endsWith("\n")) {
                    result.append("\n")
                }
                result.append(body)
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
        return decodeEntities(Regex("<[^>]+>").replace(text, ""))
    }

    private fun fb2FormatBody(rawContent: String): String {
        var content = rawContent
        content = Regex("(?si)(<section[^>]*\\bid\\s*=\\s*[\"'][^\"']*note[^\"']*[\"'][^>]*>\\s*)<title>\\s*(.*?)\\s*</title>")
            .replace(content, "$1<empty-line/>$2")
        content = Regex("(?si)<binary[^>]*>.*?</binary>").replace(content, "")
        content = Regex("(?i)<image[^>]*/?>").replace(content, "")
        content = Regex("[\\r\\n\\t]+").replace(content, " ")
        content = Regex("(?i)<empty-line\\s*/?>").replace(content, "\n")
        content = Regex("(?i)<title(?=[\\s>])[^>]*>").replace(content, "\n\n\n\n")
        content = Regex("(?i)</(?:p|subtitle|text-author|stanza|date|v|tr)>").replace(content, "\n")
        content = Regex("<[^>]+>").replace(content, "")
        content = decodeEntities(content)
        content = Regex(" {2,}").replace(content, " ")
        content = Regex(" *\\n *").replace(content, "\n")
        content = Regex("\\n{5,}").replace(content, "\n\n\n\n")
        return content.trim()
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
     * Resolves footnote links across EPUB XHTML documents, replaces references inline
     * with " (note_text)", removes inlined note source blocks, and extracts chapter text.
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

        fun soupFor(docName: String): Document? {
            if (!parsedCache.containsKey(docName)) {
                val content = files[docName]
                parsedCache[docName] = if (content != null) Jsoup.parse(content, "", Parser.htmlParser()) else null
            }
            return parsedCache[docName]
        }

        fun findNoteElement(docName: String, anchor: String): Element? {
            val soup = soupFor(docName) ?: return null
            return soup.getElementById(anchor)
                ?: soup.selectFirst("[id=\"${anchor.replace("\"", "\\\"")}\"]")
                ?: soup.selectFirst("[name=\"${anchor.replace("\"", "\\\"")}\"]")
        }

        fun extractNoteText(docName: String, anchor: String): String? {
            val el = findNoteElement(docName, anchor) ?: return null
            val container = epubNoteContainer(el) ?: return null
            val text = epubCleanNoteText(container)
            return text.ifEmpty { null }
        }

        val noteTargets = mutableMapOf<String, MutableSet<String>>()

        for (docName in docs) {
            val rawHtml = files[docName] ?: continue
            val docDir = if (docName.contains("/")) docName.substringBeforeLast("/") else ""
            val soup = Jsoup.parse(rawHtml, "", Parser.htmlParser())
            var count = 0

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
                count++
            }

            if (count > 0) {
                files[docName] = soup.outerHtml()
            }
        }

        // Remove inlined note containers so footnotes are not read a second time at the end
        for ((docName, anchors) in noteTargets) {
            val rawHtml = files[docName] ?: continue
            val soup = Jsoup.parse(rawHtml, "", Parser.htmlParser())
            var changed = false
            for (anchor in anchors) {
                val el = soup.getElementById(anchor)
                    ?: soup.selectFirst("[id=\"${anchor.replace("\"", "\\\"")}\"]")
                    ?: soup.selectFirst("[name=\"${anchor.replace("\"", "\\\"")}\"]")
                    ?: continue
                val container = epubNoteContainer(el)
                if (container != null) {
                    container.remove()
                    changed = true
                }
            }
            if (changed) {
                files[docName] = soup.outerHtml()
            }
        }

        val textContent = StringBuilder()
        for (docName in docs) {
            val html = files[docName] ?: continue
            val page = htmlToText(html)
            if (page.isNotEmpty()) {
                if (textContent.isNotEmpty()) textContent.append("\n")
                textContent.append(page)
            }
        }
        return textContent.toString()
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

    private fun htmlToText(rawHtml: String): String {
        var html = rawHtml
        Regex("(?si)<body[^>]*>(.*)</body>").find(html)?.let { m ->
            html = m.groupValues[1]
        }
        html = Regex("(?s)<!--.*?-->").replace(html, "")
        html = Regex("(?si)<(script|style|svg)[^>]*>.*?</\\1>").replace(html, "")
        html = Regex("[\\r\\n\\t]+").replace(html, " ")
        html = Regex("(?i)<br\\s*/?>").replace(html, "\n")
        html = Regex("(?i)</?(?:p|div|h[1-6]|li|ol|ul|tr|td|th|blockquote|pre|hr|section|article|header|footer|figcaption|dt|dd|table|caption)\\b[^>]*>")
            .replace(html, "\n")
        html = Regex("<[^>]+>").replace(html, "")
        html = decodeEntities(html)
        html = Regex(" {2,}").replace(html, " ")
        html = Regex(" *\\n *").replace(html, "\n")
        html = Regex("\\n{2,}").replace(html, "\n")
        return html.trim()
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

    fun decodeEntities(rawText: String): String {
        var text = rawText
            .replace("&nbsp;", " ")
            .replace("&ensp;", " ")
            .replace("&emsp;", " ")
            .replace("&thinsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&mdash;", "—")
            .replace("&ndash;", "–")
            .replace("&laquo;", "«")
            .replace("&raquo;", "»")
            .replace("&hellip;", "…")
            .replace("&bull;", "")
            .replace("&lsquo;", "'")
            .replace("&rsquo;", "'")
            .replace("&ldquo;", "\"")
            .replace("&rdquo;", "\"")
            .replace("&copy;", "©")
            .replace("&reg;", "®")
            .replace("&trade;", "™")
            .replace("&deg;", "°")
            .replace("&times;", "×")
            .replace("&minus;", "−")
            .replace("&amp;", "&")

        text = Regex("&#(\\d+);").replace(text) { m ->
            val code = m.groupValues[1].toIntOrNull() ?: 0
            if (code in 1..65535) code.toChar().toString() else m.value
        }
        text = Regex("&#x([0-9a-fA-F]+);").replace(text) { m ->
            val code = m.groupValues[1].toIntOrNull(16) ?: 0
            if (code in 1..65535) code.toChar().toString() else m.value
        }
        return text
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
