package dm1sh.hedgehog.book

import java.util.Locale

data class FileBookMeta(
    var fileName: String,
    var endSentenceIndex: Int,
    val voice: String,
    val rate: String,
    val pitch: String
)

class ProcessingFile(
    fileName: String,
    text: String,
    val firstStringsLength: Int = 800,
    val lastStringsLength: Int = 4200,
    private val langLexx: List<String>,
    private val lexxRegister: Boolean,
    private val bookChapter: Boolean,
    private val glavTextUse: Boolean,
    regexGlavaPattern: String,
    voice: String,
    rate: String,
    pitch: String
) {
    private val regexGlava: Regex = try {
        Regex(regexGlavaPattern.ifEmpty { "глава |раздел |эпизод |рассказ |chapter " })
    } catch (_: Exception) {
        Regex("глава |раздел |эпизод |рассказ |chapter ")
    }

    val fileNames = mutableListOf(FileBookMeta(fileName, 0, voice, rate, pitch))
    val allSentences = mutableListOf<String>()
    val allGlavs = mutableListOf<String>()

    init {
        val pre = getFixPoints(text)
        val (sentences, glavs) = getFixSection(pre)
        allSentences.addAll(sentences)
        allGlavs.addAll(glavs)
    }

    fun addNewText(fileName: String, text: String, voice: String, rate: String, pitch: String) {
        if (fileNames.isNotEmpty()) {
            fileNames[fileNames.lastIndex].endSentenceIndex = allSentences.size
        }
        fileNames.add(FileBookMeta(fileName, 0, voice, rate, pitch))
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
                '•' -> {}
                '\n' -> {
                    sb.append('\n')
                    var skippedSpaces = 0
                    while (skippedSpaces < 3 && i + 1 < len && text[i + 1] == ' ') {
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

    fun getFixPoints(text: String): List<String> {
        var fixText = normalizeBaseCharacters(text)

        if (langLexx.isNotEmpty()) {
            for (rawRule in langLexx) {
                val rule = rawRule.trimEnd('\r')
                if (rule.isEmpty()) continue
                if (rule.startsWith("regex\"") && rule.contains("\"=")) {
                    val matchArr = rule.replaceFirst("regex\"", "\"").split("=", limit = 2)
                    if (matchArr.size == 2) {
                        try {
                            val r = Regex(matchArr[0].replace("\"", ""))
                            val repl = matchArr[1].replace("\"", "")
                                .replace("\\r", "\r")
                                .replace("\\n", "\n")
                            fixText = r.replace(fixText, repl)
                        } catch (_: Exception) {
                        }
                    }
                } else if (rule.startsWith("\"")) {
                    val matchArr = rule.replace("\"", "").split("=", limit = 2)
                    if (matchArr.size == 2 && matchArr[0].isNotEmpty()) {
                        val key = matchArr[0]
                        if (lexxRegister) {
                            fixText = fixText.replace(key, matchArr[1])
                        } else if (fixText.contains(key, ignoreCase = true)) {
                            try {
                                val escaped = getEscaped(key)
                                fixText = Regex("(?i)$escaped").replace(fixText, matchArr[1])
                            } catch (_: Exception) {
                            }
                        }
                    }
                } else {
                    val matchArr = rule.split("=", limit = 2)
                    if (matchArr.size == 2 && matchArr[0].isNotEmpty()) {
                        val key = matchArr[0]
                        if (lexxRegister) {
                            fixText = fixText.replace(key, matchArr[1])
                        } else if (fixText.contains(key, ignoreCase = true)) {
                            try {
                                val escaped = getEscaped(key)
                                fixText = if (escaped.length == 2 && escaped[0] == '\\' && isPunctChar(escaped[1])) {
                                    Regex("(?i)$escaped").replace(fixText, matchArr[1])
                                } else {
                                    Regex("(?i)(^|\\s|[\\p{P}\\p{S}])$escaped(?=[\\p{P}\\p{S}]|\\s|$)")
                                        .replace(fixText, "$1${matchArr[1]}")
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
        val length = minOf(text.length, 20)
        for (i in 0 until length) {
            val c = text[i]
            if (isValidFilenameChar(c) && c != '.') {
                sb.append(c)
            } else if (sb.isNotEmpty() && sb[sb.length - 1] != ' ') {
                sb.append(' ')
            }
        }
        val trimmed = sb.toString().trimEnd(' ')
        return if (trimmed.isNotEmpty()) " $trimmed" else ""
    }

    private fun isValidFilenameChar(c: Char): Boolean {
        return c.code >= 32 && c !in "/\\?%*|\"<>:"
    }

    private fun glavaNumFormat(glavaNum: Int, glavText: String): String {
        if (!bookChapter) return ""
        val numStr = "%04d".format(glavaNum)
        return if (glavTextUse) " c_$numStr$glavText" else " c_$numStr"
    }

    private fun getFixSection(sentences: List<String>): Pair<List<String>, List<String>> {
        val outSentences = mutableListOf<String>()
        val outGlavs = mutableListOf<String>()
        val currentText = StringBuilder()
        var glavText = ""
        var glavaNum = 0
        val hardLimit = (lastStringsLength * 1.2).toInt()

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
                    outGlavs.add(glavaNumFormat(glavaNum, glavText))
                    glavText = getGlavText(line)
                    currentText.setLength(0)
                }
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
                    outGlavs.add(glavaNumFormat(glavaNum, glavText))
                    val sliceEnd = minOf(i + 3, sentences.size)
                    glavText = getGlavText(sentences.subList(i, sliceEnd).joinToString(" "))
                    currentText.setLength(0)
                }
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
                        outGlavs.add(glavaNumFormat(glavaNum, glavText))
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
            outGlavs.add(glavaNumFormat(glavaNum, glavText))
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
