package dm1sh.hedgehog.book

import dm1sh.hedgehog.model.DefaultVoices
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.StringReader
import java.net.URLDecoder
import java.util.zip.ZipFile

object TextsConverter {

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

    fun convertFb2ToTxt(fb2String: String): String {
        val result = StringBuilder()
        val tiRegex = Regex("(?si)<title-info>(.*?)</title-info>")
        val tiMatch = tiRegex.find(fb2String)
        if (tiMatch != null) {
            result.append(fb2ExtractMeta(tiMatch.groupValues[1]))
        }
        val bodyRegex = Regex("(?si)<body[^>]*>(.*?)</body>")
        for (m in bodyRegex.findAll(fb2String)) {
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

    fun convertEpubToTxt(epubPath: String): String {
        val textContent = StringBuilder()
        try {
            ZipFile(epubPath).use { zip ->
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
                            onTextEntry(saveName, convertFb2ToTxt(readTextWithFallback(bytes)), "", "", "")
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
