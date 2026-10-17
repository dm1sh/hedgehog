package dm1sh.hedgehog.book

import java.util.Locale

data class FileBookMeta(
    var fileName: String,
    var endSentenceIndex: Int,
    val voice: String,
    val rate: String,
    val pitch: String,
    val metadata: BookAudioMetadata = BookAudioMetadata()
)

class ProcessingFile(
    fileName: String,
    text: String,
    val firstStringsLength: Int = 800,
    val lastStringsLength: Int = 4200,
    var langLexx: List<String>,
    var lexxRegister: Boolean,
    val bookChapter: Boolean,
    val glavTextUse: Boolean,
    regexGlavaPattern: String,
    voice: String,
    rate: String,
    pitch: String,
    metadata: BookAudioMetadata = BookAudioMetadata()
) {
    private val regexGlava: Regex = try {
        Regex(regexGlavaPattern.ifEmpty { "глава |раздел |эпизод |рассказ |chapter " })
    } catch (_: Exception) {
        Regex("глава |раздел |эпизод |рассказ |chapter ")
    }

    val fileNames = mutableListOf(FileBookMeta(fileName, 0, voice, rate, pitch, metadata))
    val allSentences = mutableListOf<String>()
    val allGlavs = mutableListOf<String>()

    init {
        val pre = getFixPoints(text)
        val (sentences, glavs) = getFixSection(pre)
        allSentences.addAll(sentences)
        allGlavs.addAll(glavs)
    }

    fun addNewText(
        fileName: String,
        text: String,
        voice: String,
        rate: String,
        pitch: String,
        metadata: BookAudioMetadata = BookAudioMetadata()
    ) {
        if (fileNames.isNotEmpty()) {
            fileNames[fileNames.lastIndex].endSentenceIndex = allSentences.size
        }
        fileNames.add(FileBookMeta(fileName, 0, voice, rate, pitch, metadata))
        val pre = getFixPoints(text)
        val (sentences, glavs) = getFixSection(pre)
        allSentences.addAll(sentences)
        allGlavs.addAll(glavs)
    }

    /**
     * Single-pass character normalization replacing 13 full-book String.replace() passes.
     */
    private fun normalizeBaseCharacters(text: String): String {
        val len = text.length
        val sb = StringBuilder(len + 64)
        var i = 0
        while (i < len) {
            when (val c = text[i]) {
                '~', '^', '*', '|' -> sb.append('-')
                '\\' -> sb.append('/')
                '[', '<' -> sb.append('(')
                ']', '>' -> sb.append(')')
                '&' -> sb.append(" and ")
                '•', '\u00AD', '\u200B', '\u200C', '\u200D', '\u2060', '\uFEFF' -> {}
                '\t', '\u00A0', '\u1680',
                '\u2000', '\u2001', '\u2002', '\u2003', '\u2004', '\u2005',
                '\u2006', '\u2007', '\u2008', '\u2009', '\u200A',
                '\u202F', '\u205F', '\u3000' -> sb.append(' ')
                '\r', '\n' -> {
                    if (c == '\r' && i + 1 < len && text[i + 1] == '\n') {
                        i++
                    }
                    sb.append('\n')
                    var skippedSpaces = 0
                    while (skippedSpaces < 3 && i + 1 < len && isSpaceLike(text[i + 1])) {
                        i++
                        skippedSpaces++
                    }
                }
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    private fun isSpaceLike(c: Char): Boolean {
        return c == ' ' || c == '\t' || c == '\u00A0' || c == '\u202F' || c == '\u2007' ||
            (c in '\u2000'..'\u200A')
    }

    fun getFixPoints(text: String): List<String> {
        var fixText = normalizeBaseCharacters(text)

        if (langLexx.isNotEmpty()) {
            for (rawRule in langLexx) {
                val rule = rawRule.trimStart('\uFEFF').trimEnd('\r')
                if (rule.isEmpty() || rule.trimStart().startsWith("#")) continue
                if (rule.startsWith("regex\"") && rule.contains("\"=")) {
                    val matchArr = rule.removePrefix("regex\"").split("\"=", limit = 2)
                    if (matchArr.size == 2 && matchArr[0].isNotEmpty()) {
                        try {
                            val r = Regex(matchArr[0])
                            val repl = matchArr[1].removePrefix("\"").removeSuffix("\"")
                                .replace("\\r", "\r")
                                .replace("\\n", "\n")
                            fixText = r.replace(fixText, repl)
                        } catch (_: Exception) {
                        }
                    }
                } else if (rule.startsWith("\"")) {
                    val matchArr = if (rule.contains("\"=")) {
                        rule.removePrefix("\"").split("\"=", limit = 2)
                    } else {
                        rule.replace("\"", "").split("=", limit = 2)
                    }
                    if (matchArr.size == 2 && matchArr[0].isNotEmpty()) {
                        val key = matchArr[0]
                        val repl = matchArr[1].removePrefix("\"").removeSuffix("\"")
                        if (lexxRegister) {
                            fixText = fixText.replace(key, repl)
                        } else if (fixText.contains(key, ignoreCase = true)) {
                            try {
                                val escaped = getEscaped(key)
                                fixText = Regex("(?i)$escaped").replace(fixText, Regex.escapeReplacement(repl))
                            } catch (_: Exception) {
                            }
                        }
                    }
                } else {
                    val matchArr = rule.split("=", limit = 2)
                    if (matchArr.size == 2 && matchArr[0].isNotEmpty()) {
                        val key = matchArr[0]
                        val repl = matchArr[1]
                        if (lexxRegister) {
                            fixText = fixText.replace(key, repl)
                        } else if (fixText.contains(key, ignoreCase = true)) {
                            try {
                                val escaped = getEscaped(key)
                                fixText = if (escaped.length == 2 && escaped[0] == '\\' && isPunctChar(escaped[1])) {
                                    Regex("(?i)$escaped").replace(fixText, Regex.escapeReplacement(repl))
                                } else {
                                    val leftPrefix = if (key.first().isWhitespace()) "()" else "(^|\\s|[\\p{P}\\p{S}])"
                                    val rightLookahead = if (key.last().isWhitespace()) "" else "(?=[\\p{P}\\p{S}]|\\s|$)"
                                    Regex("(?i)$leftPrefix$escaped$rightLookahead")
                                        .replace(fixText) { m -> m.groupValues[1] + repl }
                                }
                            } catch (_: Exception) {
                            }
                        }
                    }
                }
            }
        }

        return fixText.split("\n")
    }

    private fun isPunctChar(c: Char): Boolean {
        val t = Character.getType(c)
        return t == Character.CONNECTOR_PUNCTUATION.toInt() ||
            t == Character.DASH_PUNCTUATION.toInt() ||
            t == Character.START_PUNCTUATION.toInt() ||
            t == Character.END_PUNCTUATION.toInt() ||
            t == Character.INITIAL_QUOTE_PUNCTUATION.toInt() ||
            t == Character.FINAL_QUOTE_PUNCTUATION.toInt() ||
            t == Character.OTHER_PUNCTUATION.toInt()
    }

    private fun containsLetterOrDigit(cs: CharSequence): Boolean {
        for (i in 0 until cs.length) {
            if (Character.isLetterOrDigit(cs[i])) return true
        }
        return false
    }

    private fun getEscaped(text: String): String {
        val sb = StringBuilder(text.length + 8)
        for (c in text) {
            if (c in "\\?^*|[]{}+$().") {
                sb.append('\\')
            }
            sb.append(c)
        }
        return sb.toString()
    }

    private fun getGlavText(text: String): String {
        val sb = StringBuilder()
        val trimmedInput = text.trim()
        val length = minOf(trimmedInput.length, 50)
        for (i in 0 until length) {
            val c = trimmedInput[i]
            if (isValidFilenameChar(c) && c != '.') {
                sb.append(c)
            } else if (sb.isNotEmpty() && sb[sb.length - 1] != ' ') {
                sb.append(' ')
            }
        }
        return sb.toString().trim()
    }

    private fun isValidFilenameChar(c: Char): Boolean {
        return c.code >= 32 && c !in "/\\?%*|\"<>:"
    }

    private fun getFixSection(sentences: List<String>): Pair<List<String>, List<String>> {
        val outSentences = mutableListOf<String>()
        val outGlavs = mutableListOf<String>()
        val currentText = StringBuilder()
        var glavText = ""
        var glavaNum = 0
        val hardLimit = (lastStringsLength * 1.2).toInt()
        val chapterLabelByNum = HashMap<Int, String>()

        fun glavaLabel(num: Int, title: String): String {
            if (!bookChapter) return ""
            chapterLabelByNum[num]?.let { return it }
            val numStr = "%04d".format(num)
            val cleanTitle = title.trim()
            val resolved = if (cleanTitle.isNotEmpty()) "$numStr $cleanTitle" else numStr
            chapterLabelByNum[num] = resolved
            return resolved
        }

        for (i in sentences.indices) {
            val line = sentences[i]
            if (bookChapter &&
                line.isNotEmpty() &&
                line.trim().length < 50 &&
                regexGlava.containsMatchIn(line.lowercase(Locale.ROOT))
            ) {
                currentText.append("\n")
                val str = currentText.toString()
                if (str.trim().isNotEmpty() && containsLetterOrDigit(str)) {
                    outSentences.add(str)
                    outGlavs.add(glavaLabel(glavaNum, glavText))
                    currentText.setLength(0)
                }
                glavText = getGlavText(line)
                glavaNum++
            } else if (bookChapter &&
                i > 2 &&
                line.isNotEmpty() &&
                sentences[i - 1].trim().isEmpty() &&
                sentences[i - 2].trim().isEmpty() &&
                sentences[i - 3].trim().isEmpty() &&
                containsLetterOrDigit(currentText)
            ) {
                currentText.append("\n")
                val str = currentText.toString()
                if (str.trim().isNotEmpty()) {
                    outSentences.add(str)
                    outGlavs.add(glavaLabel(glavaNum, glavText))
                    currentText.setLength(0)
                }
                val sliceEnd = minOf(i + 3, sentences.size)
                glavText = getGlavText(sentences.subList(i, sliceEnd).joinToString(" "))
                glavaNum++
            }

            // Index-based word scan avoiding per-line split(" ") list/string allocations
            var wordStart = 0
            val lineLen = line.length
            while (wordStart <= lineLen) {
                val spaceIdx = line.indexOf(' ', wordStart)
                val wordEnd = if (spaceIdx == -1) lineLen else spaceIdx
                val wordLen = wordEnd - wordStart
                val lastChar = if (wordLen > 0) line[wordEnd - 1] else ' '
                val isBreakPunct = lastChar == '.' || lastChar == ',' || lastChar == '!' ||
                    lastChar == '?' || lastChar == ':' || lastChar == ';' || lastChar == '-'
                if (currentText.length + wordLen > lastStringsLength &&
                    wordLen > 0 &&
                    (isBreakPunct || currentText.length > hardLimit)
                ) {
                    currentText.append(' ')
                    currentText.append(line, wordStart, wordEnd)
                    val textToAdd = currentText.toString()
                    if (containsLetterOrDigit(textToAdd)) {
                        outSentences.add(textToAdd)
                        outGlavs.add(glavaLabel(glavaNum, glavText))
                    }
                    currentText.setLength(0)
                } else {
                    if (currentText.isNotEmpty() && currentText[currentText.length - 1] != '\n') {
                        currentText.append(' ')
                    }
                    if (wordLen > 0) {
                        currentText.append(line, wordStart, wordEnd)
                    }
                }
                if (spaceIdx == -1) break
                wordStart = spaceIdx + 1
            }
            if (currentText.isNotEmpty()) {
                currentText.append("\n")
            }
        }

        if (currentText.isNotEmpty() && containsLetterOrDigit(currentText)) {
            outSentences.add(currentText.toString())
            outGlavs.add(glavaLabel(glavaNum, glavText))
        }

        return outSentences to outGlavs
    }

    fun splitPartText(text: String): List<String> {
        val sentences = getFixPoints(text)
        val result = mutableListOf<String>()
        val currentText = StringBuilder()
        val hardLimit = (lastStringsLength * 1.2).toInt()

        for (line in sentences) {
            var wordStart = 0
            val lineLen = line.length
            while (wordStart <= lineLen) {
                val spaceIdx = line.indexOf(' ', wordStart)
                val wordEnd = if (spaceIdx == -1) lineLen else spaceIdx
                val wordLen = wordEnd - wordStart
                val lastChar = if (wordLen > 0) line[wordEnd - 1] else ' '
                val isBreakPunct = lastChar == '.' || lastChar == ',' || lastChar == '!' ||
                    lastChar == '?' || lastChar == ':' || lastChar == ';' || lastChar == '-'
                if (currentText.length + wordLen > lastStringsLength &&
                    wordLen > 0 &&
                    (isBreakPunct || currentText.length > hardLimit)
                ) {
                    currentText.append(' ')
                    currentText.append(line, wordStart, wordEnd)
                    val textToAdd = currentText.toString()
                    if (containsLetterOrDigit(textToAdd)) {
                        result.add(textToAdd)
                    }
                    currentText.setLength(0)
                } else {
                    if (currentText.isNotEmpty() && currentText[currentText.length - 1] != '\n') {
                        currentText.append(' ')
                    }
                    if (wordLen > 0) {
                        currentText.append(line, wordStart, wordEnd)
                    }
                }
                if (spaceIdx == -1) break
                wordStart = spaceIdx + 1
            }
            if (currentText.isNotEmpty()) {
                currentText.append("\n")
            }
        }
        if (currentText.isNotEmpty() && containsLetterOrDigit(currentText)) {
            result.add(currentText.toString())
        }
        return result
    }

    fun replacePart(index: Int, newText: String): Int {
        if (index < 0 || index >= allSentences.size) return -1
        val oldGlav = allGlavs[index]
        val newParts = splitPartText(newText)
        val k = newParts.size
        val delta = k - 1

        allSentences.removeAt(index)
        allGlavs.removeAt(index)
        for (i in 0 until k) {
            allSentences.add(index + i, newParts[i])
            allGlavs.add(index + i, oldGlav)
        }
        for (meta in fileNames) {
            if (meta.endSentenceIndex > index) {
                meta.endSentenceIndex += delta
            }
        }
        return k
    }
}
