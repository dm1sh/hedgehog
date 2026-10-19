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
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

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

    fun readTextWithFallback(bytes: ByteArray, length: Int = bytes.size): String {
        val safeLen = length.coerceIn(0, bytes.size)
        if (!isValidUtf8(bytes, safeLen)) {
            return ansiToUtf8(bytes, safeLen)
        }
        return String(bytes, 0, safeLen, Charsets.UTF_8)
    }

    fun ansiToUtf8(bytes: ByteArray, length: Int = bytes.size): String {
        val safeLen = length.coerceIn(0, bytes.size)
        val sb = StringBuilder(safeLen)
        for (idx in 0 until safeLen) {
            val unsigned = bytes[idx].toInt() and 0xFF
            if (unsigned < 128) {
                sb.append(unsigned.toChar())
            } else {
                sb.append(DefaultVoices.CP1251_TO_UTF8[unsigned] ?: unsigned.toChar())
            }
        }
        return sb.toString()
    }

    private fun isValidUtf8(bytes: ByteArray, len: Int = bytes.size): Boolean {
        var i = 0
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

    data class LoadedBookResult(
        val bookName: String,
        val metadata: BookAudioMetadata,
        val text: String,
        val isFb2: Boolean = true
    )

    private fun findLastAsciiTagInBytes(bytes: ByteArray, tagLowerAscii: String): Int {
        val tLen = tagLowerAscii.length
        if (bytes.size < tLen) return -1
        for (i in bytes.size - tLen downTo 0) {
            var match = true
            for (k in 0 until tLen) {
                val b = bytes[i + k].toInt() and 0xFF
                val lower = if (b in 'A'.code..'Z'.code) b + 32 else b
                if (lower != tagLowerAscii[k].code) {
                    match = false
                    break
                }
            }
            if (match) return i
        }
        return -1
    }

    private fun findAsciiTagForwardInBytes(bytes: ByteArray, startPos: Int, tagLowerAscii: String): Int {
        val tLen = tagLowerAscii.length
        val limit = bytes.size - tLen
        if (startPos > limit) return -1
        for (i in startPos.coerceAtLeast(0)..limit) {
            var match = true
            for (k in 0 until tLen) {
                val b = bytes[i + k].toInt() and 0xFF
                val lower = if (b in 'A'.code..'Z'.code) b + 32 else b
                if (lower != tagLowerAscii[k].code) {
                    match = false
                    break
                }
            }
            if (match) return i
        }
        return -1
    }

    private fun extractFb2CoverBinaryFromBytes(
        bytes: ByteArray,
        startOffset: Int,
        targetId: String
    ): CoverBinaryResult {
        var searchPos = startOffset.coerceAtLeast(0)
        val cleanTarget = targetId.removePrefix("#").trim()
        var fallbackRange: IntRange? = null
        var fallbackMime = ""

        while (searchPos < bytes.size) {
            val binOpen = findAsciiTagForwardInBytes(bytes, searchPos, "<binary")
            if (binOpen == -1) break
            var tagClose = -1
            val maxAttrEnd = minOf(bytes.size, binOpen + 512)
            for (k in (binOpen + 7) until maxAttrEnd) {
                if (bytes[k] == '>'.code.toByte()) {
                    tagClose = k
                    break
                }
            }
            if (tagClose == -1) break
            val binEnd = findAsciiTagForwardInBytes(bytes, tagClose + 1, "</binary>")
            if (binEnd == -1) break

            val attrs = String(bytes, binOpen + 7, tagClose - (binOpen + 7), Charsets.ISO_8859_1)
            val idVal = Regex("(?i)\\bid\\s*=\\s*[\"']#?([^\"']+)[\"']")
                .find(attrs)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            val ctVal = Regex("(?i)\\bcontent-type\\s*=\\s*[\"']([^\"']+)[\"']")
                .find(attrs)?.groupValues?.getOrNull(1)?.trim().orEmpty()

            val matchesTarget = cleanTarget.isNotEmpty() && idVal.equals(cleanTarget, ignoreCase = true)
            if (matchesTarget) {
                val base64Ascii = String(bytes, tagClose + 1, binEnd - (tagClose + 1), Charsets.US_ASCII)
                val decoded = decodeBase64Quietly(base64Ascii)
                if (decoded != null && decoded.isNotEmpty()) {
                    val info = AudioMetadataTagger.inspectImage(decoded, ctVal)
                    return CoverBinaryResult(decoded, info.mimeType, info.width, info.height)
                }
            } else if (fallbackRange == null &&
                (idVal.contains("cover", ignoreCase = true) || (cleanTarget.isEmpty() && ctVal.startsWith("image/", ignoreCase = true)))
            ) {
                fallbackRange = (tagClose + 1) until binEnd
                fallbackMime = ctVal
            }

            searchPos = binEnd + 9
        }

        if (fallbackRange != null) {
            val len = fallbackRange.last - fallbackRange.first + 1
            if (len > 0) {
                val base64Ascii = String(bytes, fallbackRange.first, len, Charsets.US_ASCII)
                val decoded = decodeBase64Quietly(base64Ascii)
                if (decoded != null && decoded.isNotEmpty()) {
                    val info = AudioMetadataTagger.inspectImage(decoded, fallbackMime)
                    return CoverBinaryResult(decoded, info.mimeType, info.width, info.height)
                }
            }
        }
        return CoverBinaryResult(null, "", 0, 0)
    }

    /**
     * Loads an FB2 (or XML) byte array without decoding trailing <binary> Base64 blocks into a UTF-16 String.
     */
    fun loadFb2OrXmlFromBytes(
        bytes: ByteArray,
        fallbackName: String,
        forceFb2: Boolean,
        inlineNotes: Boolean = false,
        embedMetadata: Boolean = true
    ): LoadedBookResult {
        val lastBodyClose = findLastAsciiTagInBytes(bytes, "</body>")
        val bodyEndOffset = if (lastBodyClose != -1) lastBodyClose + 7 else bytes.size
        val bodyXml = if (lastBodyClose != -1 && bodyEndOffset < bytes.size) {
            readTextWithFallback(bytes, bodyEndOffset) + "</FictionBook>"
        } else {
            readTextWithFallback(bytes, bytes.size)
        }
        val isFb2Doc = forceFb2 || bodyXml.contains("<FictionBook", ignoreCase = true)
        if (!isFb2Doc) {
            return LoadedBookResult(
                bookName = fallbackName,
                metadata = BookAudioMetadata(title = fallbackName),
                text = bodyXml,
                isFb2 = false
            )
        }
        val bookName = extractFb2BookName(bodyXml, fallbackName)
        val baseMeta = extractFb2Metadata(bodyXml, fallbackName, extractCoverFromXml = false)
        val meta = if (embedMetadata) {
            val coverHrefId = extractFb2CoverHrefId(bodyXml)
            val coverSearchStart = if (lastBodyClose != -1) bodyEndOffset else 0
            val (coverBytes, coverMime, coverW, coverH) = extractFb2CoverBinaryFromBytes(
                bytes = bytes,
                startOffset = coverSearchStart,
                targetId = coverHrefId
            )
            baseMeta.copy(
                coverBytes = coverBytes,
                coverMimeType = coverMime,
                coverWidth = coverW,
                coverHeight = coverH
            )
        } else {
            baseMeta
        }
        val text = convertFb2ToTxt(bodyXml, inlineNotes = inlineNotes)
        return LoadedBookResult(
            bookName = bookName,
            metadata = meta,
            text = text,
            isFb2 = true
        )
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

    private fun sanitizeBookDisplayName(name: String): String {
        val sb = StringBuilder(name.length)
        var lastWasSpace = false
        for (c in name) {
            val mapped = when {
                c.code < 32 -> ' '
                c in "/\\?%*:|\"<>" -> ' '
                c.isWhitespace() -> ' '
                else -> c
            }
            if (mapped == ' ') {
                if (!lastWasSpace && sb.isNotEmpty()) {
                    sb.append(' ')
                    lastWasSpace = true
                }
            } else {
                sb.append(mapped)
                lastWasSpace = false
            }
        }
        return sb.toString().trim().trimEnd('.')
    }

    fun extractFb2BookName(fb2String: String, fallbackName: String): String {
        try {
            val tiStart = fb2String.indexOf("<title-info>", ignoreCase = true)
            if (tiStart != -1) {
                val tiEnd = fb2String.indexOf("</title-info>", tiStart, ignoreCase = true)
                if (tiEnd != -1) {
                    val ti = fb2String.substring(tiStart + "<title-info>".length, tiEnd)
                    val authors = mutableListOf<String>()
                    for (m in Regex("(?si)<author>(.*?)</author>").findAll(ti)) {
                        val name = fb2AuthorName(m.groupValues[1]).trim()
                        if (name.isNotEmpty()) authors.add(name)
                    }
                    val authorStr = authors.joinToString(", ")
                    val titleStr = Regex("(?si)<book-title>(.*?)</book-title>")
                        .find(ti)
                        ?.let { fb2StripTags(it.groupValues[1]).trim() }
                        .orEmpty()

                    val combined = when {
                        authorStr.isNotEmpty() && titleStr.isNotEmpty() -> "$authorStr - $titleStr"
                        titleStr.isNotEmpty() -> titleStr
                        authorStr.isNotEmpty() -> authorStr
                        else -> ""
                    }
                    val clean = sanitizeBookDisplayName(combined)
                    if (clean.isNotEmpty()) return clean
                }
            }
        } catch (_: Exception) {
        }
        return fallbackName
    }

    private fun extractFb2CoverHrefId(fb2String: String): String {
        val tiStart = fb2String.indexOf("<title-info>", ignoreCase = true)
        if (tiStart == -1) return ""
        val tiEnd = fb2String.indexOf("</title-info>", tiStart, ignoreCase = true)
        if (tiEnd == -1) return ""
        val ti = fb2String.substring(tiStart + "<title-info>".length, tiEnd)
        val cm = Regex("(?si)<coverpage>(.*?)</coverpage>").find(ti) ?: return ""
        val hrefMatch = Regex("(?i)\\b(?:[a-z0-9_]+:)?href\\s*=\\s*[\"']#?([^\"']+)[\"']")
            .find(cm.groupValues[1])
        return hrefMatch?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }

    fun extractFb2Metadata(
        fb2String: String,
        fallbackName: String,
        extractCoverFromXml: Boolean = true
    ): BookAudioMetadata {
        return try {
            var title = ""
            var author = ""
            var date = ""
            var genre = ""
            var description = ""
            var language = ""
            var publisher = ""
            var series = ""
            var seriesIndex = ""
            var coverHrefId = ""

            val tiStart = fb2String.indexOf("<title-info>", ignoreCase = true)
            if (tiStart != -1) {
                val tiEnd = fb2String.indexOf("</title-info>", tiStart, ignoreCase = true)
                if (tiEnd != -1) {
                    val ti = fb2String.substring(tiStart + "<title-info>".length, tiEnd)
                    val authors = mutableListOf<String>()
                    for (m in Regex("(?si)<author>(.*?)</author>").findAll(ti)) {
                        val name = fb2AuthorName(m.groupValues[1]).trim()
                        if (name.isNotEmpty()) authors.add(name)
                    }
                    author = authors.joinToString(", ")

                    title = Regex("(?si)<book-title>(.*?)</book-title>")
                        .find(ti)
                        ?.let { fb2StripTags(it.groupValues[1]).trim() }
                        .orEmpty()

                    val genres = Regex("(?si)<genre[^>]*>(.*?)</genre>")
                        .findAll(ti)
                        .map { fb2StripTags(it.groupValues[1]).trim() }
                        .filter { it.isNotEmpty() }
                        .toList()
                    genre = genres.joinToString(", ")

                    Regex("(?si)<date([^>]*)>(.*?)</date>").find(ti)?.let { dm ->
                        val attrPart = dm.groupValues[1]
                        val valAttr = Regex("(?i)\\bvalue\\s*=\\s*[\"']([^\"']+)[\"']")
                            .find(attrPart)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.trim()
                            .orEmpty()
                        val innerText = fb2StripTags(dm.groupValues[2]).trim()
                        date = valAttr.ifEmpty { innerText }
                    }

                    Regex("(?si)<annotation>(.*?)</annotation>").find(ti)?.let { ann ->
                        description = fb2StripTags(ann.groupValues[1])
                            .replace(Regex("\\s+"), " ")
                            .trim()
                    }

                    Regex("(?si)<lang>(.*?)</lang>").find(ti)?.let { lm ->
                        language = fb2StripTags(lm.groupValues[1]).trim()
                    }

                    Regex("(?si)<sequence\\b([^>]*)/?>").find(ti)?.let { sm ->
                        val attrs = sm.groupValues[1]
                        series = Regex("(?i)\\bname\\s*=\\s*[\"']([^\"']+)[\"']")
                            .find(attrs)?.groupValues?.getOrNull(1)?.let { decodeEntities(it).trim() }.orEmpty()
                        seriesIndex = Regex("(?i)\\bnumber\\s*=\\s*[\"']([^\"']+)[\"']")
                            .find(attrs)?.groupValues?.getOrNull(1)?.trim().orEmpty()
                    }

                    Regex("(?si)<coverpage>(.*?)</coverpage>").find(ti)?.let { cm ->
                        val coverInner = cm.groupValues[1]
                        val hrefMatch = Regex("(?i)\\b(?:[a-z0-9_]+:)?href\\s*=\\s*[\"']#?([^\"']+)[\"']")
                            .find(coverInner)
                        if (hrefMatch != null) {
                            coverHrefId = hrefMatch.groupValues[1].trim()
                        }
                    }
                }
            }

            val piStart = fb2String.indexOf("<publish-info>", ignoreCase = true)
            if (piStart != -1) {
                val piEnd = fb2String.indexOf("</publish-info>", piStart, ignoreCase = true)
                if (piEnd != -1) {
                    val pi = fb2String.substring(piStart + "<publish-info>".length, piEnd)
                    publisher = Regex("(?si)<publisher>(.*?)</publisher>")
                        .find(pi)
                        ?.let { fb2StripTags(it.groupValues[1]).trim() }
                        .orEmpty()
                    if (date.isBlank()) {
                        date = Regex("(?si)<year>(.*?)</year>")
                            .find(pi)
                            ?.let { fb2StripTags(it.groupValues[1]).trim() }
                            .orEmpty()
                    }
                }
            }

            val (coverBytes, coverMime, coverW, coverH) = if (extractCoverFromXml) {
                extractFb2CoverBinary(fb2String, coverHrefId)
            } else {
                CoverBinaryResult(null, "", 0, 0)
            }
            BookAudioMetadata(
                title = title.ifBlank { fallbackName },
                author = author,
                date = date,
                genre = genre,
                description = description,
                language = language,
                publisher = publisher,
                series = series,
                seriesIndex = seriesIndex,
                coverBytes = coverBytes,
                coverMimeType = coverMime,
                coverWidth = coverW,
                coverHeight = coverH
            )
        } catch (_: Exception) {
            BookAudioMetadata(title = fallbackName)
        }
    }

    private data class CoverBinaryResult(
        val bytes: ByteArray?,
        val mimeType: String,
        val width: Int,
        val height: Int
    )

    private fun extractFb2CoverBinary(fb2String: String, targetId: String): CoverBinaryResult {
        var searchPos = 0
        val cleanTarget = targetId.removePrefix("#").trim()
        var fallbackBody: String? = null
        var fallbackMime = ""

        while (true) {
            val binOpen = fb2String.indexOf("<binary", searchPos, ignoreCase = true)
            if (binOpen == -1) break
            val tagClose = fb2String.indexOf('>', binOpen + 7)
            if (tagClose == -1) break
            val binEnd = fb2String.indexOf("</binary>", tagClose + 1, ignoreCase = true)
            if (binEnd == -1) break

            val attrs = fb2String.substring(binOpen + 7, tagClose)
            val idVal = Regex("(?i)\\bid\\s*=\\s*[\"']#?([^\"']+)[\"']")
                .find(attrs)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            val ctVal = Regex("(?i)\\bcontent-type\\s*=\\s*[\"']([^\"']+)[\"']")
                .find(attrs)?.groupValues?.getOrNull(1)?.trim().orEmpty()

            val matchesTarget = cleanTarget.isNotEmpty() && idVal.equals(cleanTarget, ignoreCase = true)
            if (matchesTarget) {
                val base64Str = fb2String.substring(tagClose + 1, binEnd)
                val decoded = decodeBase64Quietly(base64Str)
                if (decoded != null && decoded.isNotEmpty()) {
                    val info = AudioMetadataTagger.inspectImage(decoded, ctVal)
                    return CoverBinaryResult(decoded, info.mimeType, info.width, info.height)
                }
            } else if (fallbackBody == null &&
                (idVal.contains("cover", ignoreCase = true) || (cleanTarget.isEmpty() && ctVal.startsWith("image/", ignoreCase = true)))
            ) {
                fallbackBody = fb2String.substring(tagClose + 1, binEnd)
                fallbackMime = ctVal
            }

            searchPos = binEnd + 9
        }

        if (fallbackBody != null) {
            val decoded = decodeBase64Quietly(fallbackBody)
            if (decoded != null && decoded.isNotEmpty()) {
                val info = AudioMetadataTagger.inspectImage(decoded, fallbackMime)
                return CoverBinaryResult(decoded, info.mimeType, info.width, info.height)
            }
        }
        return CoverBinaryResult(null, "", 0, 0)
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeBase64Quietly(rawBase64: String): ByteArray? {
        return try {
            Base64.Mime.decode(rawBase64.trim())
        } catch (_: Throwable) {
            null
        }
    }

    fun extractEpubBookName(epubPath: String, fallbackName: String): String {
        try {
            ZipFile(epubPath).use { zip ->
                var opfEntryName: String? = null
                val containerEntry = zip.getEntry("META-INF/container.xml")
                if (containerEntry != null) {
                    val containerXml = zip.getInputStream(containerEntry).use { String(it.readBytes(), Charsets.UTF_8) }
                    val soup = Jsoup.parse(containerXml, "", Parser.xmlParser())
                    val fullPath = soup.selectFirst("rootfile")?.attr("full-path").orEmpty()
                    if (fullPath.isNotEmpty()) {
                        opfEntryName = simplifyPath(fullPath)
                    }
                }
                if (opfEntryName == null) {
                    opfEntryName = zip.entries().asSequence()
                        .firstOrNull { !it.isDirectory && it.name.lowercase(Locale.ROOT).endsWith(".opf") }
                        ?.name
                }
                if (opfEntryName != null) {
                    val opfEntry = zip.getEntry(opfEntryName)
                        ?: zip.entries().asSequence().firstOrNull { simplifyPath(it.name) == opfEntryName }
                    if (opfEntry != null) {
                        val opfXml = zip.getInputStream(opfEntry).use { String(it.readBytes(), Charsets.UTF_8) }
                        val soup = Jsoup.parse(opfXml, "", Parser.xmlParser())
                        val creators = soup.select("dc|creator, creator")
                            .map { it.text().trim() }
                            .filter { it.isNotEmpty() }
                        val authorStr = creators.joinToString(", ")
                        val titleStr = soup.selectFirst("dc|title, title")?.text()?.trim().orEmpty()
                        val combined = when {
                            authorStr.isNotEmpty() && titleStr.isNotEmpty() -> "$authorStr - $titleStr"
                            titleStr.isNotEmpty() -> titleStr
                            authorStr.isNotEmpty() -> authorStr
                            else -> ""
                        }
                        val clean = sanitizeBookDisplayName(combined)
                        if (clean.isNotEmpty()) return clean
                    }
                }
            }
        } catch (_: Exception) {
        }
        return fallbackName
    }

    fun extractEpubMetadata(epubPath: String, fallbackName: String): BookAudioMetadata {
        return try {
            ZipFile(epubPath).use { zip ->
                val entriesByNormPath = LinkedHashMap<String, java.util.zip.ZipEntry>()
                for (entry in zip.entries().asSequence()) {
                    if (!entry.isDirectory) {
                        entriesByNormPath[simplifyPath(entry.name)] = entry
                    }
                }

                var opfEntryName: String? = null
                val containerEntry = zip.getEntry("META-INF/container.xml")
                    ?: entriesByNormPath["META-INF/container.xml"]
                if (containerEntry != null) {
                    val containerXml = zip.getInputStream(containerEntry).use { String(it.readBytes(), Charsets.UTF_8) }
                    val soup = Jsoup.parse(containerXml, "", Parser.xmlParser())
                    val fullPath = soup.selectFirst("rootfile")?.attr("full-path").orEmpty()
                    if (fullPath.isNotEmpty()) {
                        opfEntryName = simplifyPath(fullPath)
                    }
                }
                if (opfEntryName == null) {
                    opfEntryName = entriesByNormPath.keys
                        .firstOrNull { it.lowercase(Locale.ROOT).endsWith(".opf") }
                }

                var title = ""
                var author = ""
                var date = ""
                var genre = ""
                var description = ""
                var language = ""
                var publisher = ""
                var series = ""
                var seriesIndex = ""
                var coverBytes: ByteArray? = null
                var coverMime = ""
                var coverW = 0
                var coverH = 0

                fun findZipEntry(resolvedPath: String): java.util.zip.ZipEntry? {
                    val norm = simplifyPath(resolvedPath)
                    return entriesByNormPath[norm]
                        ?: zip.getEntry(norm)
                        ?: entriesByNormPath.entries.firstOrNull { it.key.equals(norm, ignoreCase = true) }?.value
                }

                fun tryReadCoverFromEntryPath(path: String, declaredMime: String = ""): Boolean {
                    val entry = findZipEntry(path) ?: return false
                    val lowerPath = path.lowercase(Locale.ROOT)
                    val isHtml = XHTML_EXTS.any { lowerPath.endsWith(it) } || declaredMime.contains("html", ignoreCase = true)
                    if (isHtml) {
                        val htmlDir = if (path.contains("/")) path.substringBeforeLast("/") else ""
                        val htmlStr = zip.getInputStream(entry).use { String(it.readBytes(), Charsets.UTF_8) }
                        val htmlDoc = Jsoup.parse(htmlStr, "", Parser.xmlParser())
                        val imgEl = htmlDoc.selectFirst("img[src], image[xlink:href], image[href], image[l:href]")
                        val imgHref = imgEl?.let {
                            it.attr("src")
                                .ifEmpty { it.attr("xlink:href") }
                                .ifEmpty { it.attr("href") }
                                .ifEmpty { it.attr("l:href") }
                        }.orEmpty()
                        if (imgHref.isNotEmpty()) {
                            val resolvedImg = resolveHref(htmlDir, imgHref)
                            if (!resolvedImg.isNullOrEmpty()) {
                                return tryReadCoverFromEntryPath(resolvedImg)
                            }
                        }
                        return false
                    }
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    if (bytes.isEmpty()) return false
                    val info = AudioMetadataTagger.inspectImage(bytes, declaredMime)
                    coverBytes = bytes
                    coverMime = info.mimeType
                    coverW = info.width
                    coverH = info.height
                    return true
                }

                if (opfEntryName != null) {
                    val opfEntry = findZipEntry(opfEntryName)
                    if (opfEntry != null) {
                        val opfDir = if (opfEntryName.contains("/")) opfEntryName.substringBeforeLast("/") else ""
                        val opfXml = zip.getInputStream(opfEntry).use { String(it.readBytes(), Charsets.UTF_8) }
                        val soup = Jsoup.parse(opfXml, "", Parser.xmlParser())

                        title = soup.selectFirst("dc|title, title")?.text()?.trim().orEmpty()
                        author = soup.select("dc|creator, creator")
                            .map { it.text().trim() }
                            .filter { it.isNotEmpty() }
                            .joinToString(", ")
                        date = soup.selectFirst("dc|date, date")?.text()?.trim().orEmpty()
                        genre = soup.select("dc|subject, subject")
                            .map { it.text().trim() }
                            .filter { it.isNotEmpty() }
                            .joinToString(", ")
                        val rawDesc = soup.selectFirst("dc|description, description")?.text()?.trim().orEmpty()
                        if (rawDesc.isNotEmpty()) {
                            description = fb2StripTags(rawDesc).replace(Regex("\\s+"), " ").trim()
                        }
                        language = soup.selectFirst("dc|language, language")?.text()?.trim().orEmpty()
                        publisher = soup.selectFirst("dc|publisher, publisher")?.text()?.trim().orEmpty()

                        for (metaEl in soup.select("meta")) {
                            val nameAttr = metaEl.attr("name").lowercase(Locale.ROOT)
                            val propAttr = metaEl.attr("property").lowercase(Locale.ROOT)
                            val contentAttr = metaEl.attr("content").trim()
                            when {
                                nameAttr == "calibre:series" && contentAttr.isNotEmpty() -> series = contentAttr
                                nameAttr == "calibre:series_index" && contentAttr.isNotEmpty() -> seriesIndex = contentAttr
                                propAttr == "belongs-to-collection" && series.isEmpty() -> series = metaEl.text().trim()
                                propAttr == "group-position" && seriesIndex.isEmpty() -> seriesIndex = metaEl.text().trim()
                                propAttr == "dcterms:modified" && date.isEmpty() -> date = metaEl.text().trim()
                            }
                        }

                        data class ManifestItem(
                            val id: String,
                            val href: String,
                            val resolvedPath: String,
                            val mediaType: String,
                            val properties: String
                        )
                        val manifestItems = mutableListOf<ManifestItem>()
                        val manifestById = HashMap<String, ManifestItem>()
                        for (item in soup.select("manifest > item, item")) {
                            val id = item.attr("id").trim()
                            val href = item.attr("href").trim()
                            val mt = item.attr("media-type").trim()
                            val props = item.attr("properties").trim()
                            val resolved = resolveHref(opfDir, href).orEmpty()
                            if (resolved.isNotEmpty()) {
                                val mi = ManifestItem(id, href, resolved, mt, props)
                                manifestItems.add(mi)
                                if (id.isNotEmpty()) manifestById[id] = mi
                            }
                        }

                        // 1. EPUB 3 cover-image property
                        val epub3Cover = manifestItems.firstOrNull {
                            it.properties.split(Regex("\\s+")).any { p -> p.equals("cover-image", ignoreCase = true) }
                        }
                        if (epub3Cover != null) {
                            tryReadCoverFromEntryPath(epub3Cover.resolvedPath, epub3Cover.mediaType)
                        }

                        // 2. EPUB 2 <meta name="cover" content="..."/>
                        if (coverBytes == null) {
                            val coverMetaContent = soup.select("meta")
                                .firstOrNull { it.attr("name").equals("cover", ignoreCase = true) }
                                ?.attr("content")
                                ?.trim()
                                .orEmpty()
                            if (coverMetaContent.isNotEmpty()) {
                                val targetItem = manifestById[coverMetaContent]
                                    ?: manifestItems.firstOrNull { it.href == coverMetaContent || it.resolvedPath == coverMetaContent }
                                if (targetItem != null) {
                                    tryReadCoverFromEntryPath(targetItem.resolvedPath, targetItem.mediaType)
                                } else {
                                    val resolvedDirect = resolveHref(opfDir, coverMetaContent).orEmpty()
                                    if (resolvedDirect.isNotEmpty()) {
                                        tryReadCoverFromEntryPath(resolvedDirect)
                                    }
                                }
                            }
                        }

                        // 3. <guide><reference type="cover" href="..."/></guide>
                        if (coverBytes == null) {
                            val guideCoverHref = soup.select("guide > reference, reference")
                                .firstOrNull { it.attr("type").equals("cover", ignoreCase = true) }
                                ?.attr("href")
                                ?.trim()
                                .orEmpty()
                            if (guideCoverHref.isNotEmpty()) {
                                val resolvedGuide = resolveHref(opfDir, guideCoverHref).orEmpty()
                                if (resolvedGuide.isNotEmpty()) {
                                    tryReadCoverFromEntryPath(resolvedGuide)
                                }
                            }
                        }

                        // 4. Manifest image item with "cover" in id or href
                        if (coverBytes == null) {
                            val candidate = manifestItems.firstOrNull { item ->
                                val lowerHref = item.href.lowercase(Locale.ROOT)
                                val isImg = item.mediaType.startsWith("image/", ignoreCase = true) ||
                                    lowerHref.endsWith(".jpg") || lowerHref.endsWith(".jpeg") ||
                                    lowerHref.endsWith(".png") || lowerHref.endsWith(".webp")
                                isImg && (item.id.contains("cover", ignoreCase = true) || lowerHref.contains("cover"))
                            }
                            if (candidate != null) {
                                tryReadCoverFromEntryPath(candidate.resolvedPath, candidate.mediaType)
                            }
                        }
                    }
                }

                // 5. Fallback scan in zip entries for cover.(jpg|jpeg|png|webp)
                if (coverBytes == null) {
                    val fallbackEntryKey = entriesByNormPath.keys.firstOrNull { key ->
                        val fileName = key.substringAfterLast('/').lowercase(Locale.ROOT)
                        fileName == "cover.jpg" || fileName == "cover.jpeg" ||
                            fileName == "cover.png" || fileName == "cover.webp"
                    }
                    if (fallbackEntryKey != null) {
                        tryReadCoverFromEntryPath(fallbackEntryKey)
                    }
                }

                BookAudioMetadata(
                    title = title.ifBlank { fallbackName },
                    author = author,
                    date = date,
                    genre = genre,
                    description = description,
                    language = language,
                    publisher = publisher,
                    series = series,
                    seriesIndex = seriesIndex,
                    coverBytes = coverBytes,
                    coverMimeType = coverMime,
                    coverWidth = coverW,
                    coverHeight = coverH
                )
            }
        } catch (_: Exception) {
            BookAudioMetadata(title = fallbackName)
        }
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

    /**
     * Opens an EPUB archive once and extracts its display name, metadata/cover, and converted text.
     */
    fun loadEpubBook(
        epubPath: String,
        fallbackName: String,
        inlineNotes: Boolean = false,
        embedMetadata: Boolean = true
    ): LoadedBookResult {
        val meta = if (embedMetadata) {
            extractEpubMetadata(epubPath, fallbackName)
        } else {
            val name = extractEpubBookName(epubPath, fallbackName)
            BookAudioMetadata(title = name)
        }
        val combinedName = when {
            meta.author.isNotBlank() && meta.title.isNotBlank() && meta.title != fallbackName ->
                sanitizeBookDisplayName("${meta.author} - ${meta.title}").ifEmpty { fallbackName }
            meta.title.isNotBlank() -> sanitizeBookDisplayName(meta.title).ifEmpty { fallbackName }
            else -> extractEpubBookName(epubPath, fallbackName)
        }
        val text = convertEpubToTxt(epubPath, inlineNotes = inlineNotes)
        return LoadedBookResult(
            bookName = combinedName,
            metadata = meta,
            text = text,
            isFb2 = false
        )
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
                val content = files.remove(docName)
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
        idIndexCache.clear()
        files.clear()

        val textContent = StringBuilder()
        for (docName in docs) {
            val soup = soupFor(docName)
            parsedCache.remove(docName)
            if (soup == null) continue
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
        onTextEntry: (saveName: String, text: String, voice: String, rate: String, pitch: String, metadata: BookAudioMetadata) -> Unit
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
                            onTextEntry(saveName, readTextWithFallback(bytes), "", "", "", BookAudioMetadata(title = saveName))
                        }
                        lower.endsWith("fb2") -> {
                            val bytes = zip.getInputStream(entry).use { it.readBytes() }
                            val loaded = loadFb2OrXmlFromBytes(
                                bytes = bytes,
                                fallbackName = saveName,
                                forceFb2 = true,
                                inlineNotes = inlineNotes,
                                embedMetadata = true
                            )
                            onTextEntry(loaded.bookName, loaded.text, "", "", "", loaded.metadata)
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
        onTextEntry: (saveName: String, text: String, voice: String, rate: String, pitch: String, metadata: BookAudioMetadata) -> Unit
    ) {
        if (line.size >= 5 && line[1].toDoubleOrNull() != null && line[2].toDoubleOrNull() != null) {
            onTextEntry(line[4], line[3], line[0], line[1], line[2], BookAudioMetadata(title = line[4]))
        } else {
            val lineTitle = "$defaultName L-${"%04d".format(lineNum)}"
            onTextEntry(lineTitle, line.joinToString(" "), "", "", "", BookAudioMetadata(title = lineTitle))
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
