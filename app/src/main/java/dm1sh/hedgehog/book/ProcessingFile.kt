package dm1sh.hedgehog.book

import java.util.Locale

data class FileBookMeta(
    val fileName: String,
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
    private val regexChar = Regex("[\\p{L}\\p{N}]")
    private val regexPunct = Regex("\\p{P}")
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

    fun getFixPoints(text: String): List<String> {
        var fixText = text
            .replace("~", "-")
            .replace("\\", "/")
            .replace("^", "-")
            .replace("*", "-")
            .replace("|", "-")
            .replace("[", "(")
            .replace("]", ")")
            .replace("<", "(")
            .replace(">", ")")
            .replace("&", " and ")
            .replace("•", "")
            .replace("\n ", "\n")
            .replace("\n ", "\n")
            .replace("\n ", "\n")

        if (langLexx.isNotEmpty()) {
            for (rawRule in langLexx) {
                val rule = rawRule.trimEnd('\r')
                if (rule.isEmpty()) continue
                if (Regex("^regex\".*\"=\".*").matches(rule)) {
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
                        if (lexxRegister) {
                            fixText = fixText.replace(matchArr[0], matchArr[1])
                        } else {
                            try {
                                val escaped = getEscaped(matchArr[0])
                                fixText = Regex("(?i)$escaped").replace(fixText, matchArr[1])
                            } catch (_: Exception) {
                            }
                        }
                    }
                } else {
                    val matchArr = rule.split("=", limit = 2)
                    if (matchArr.size == 2 && matchArr[0].isNotEmpty()) {
                        if (lexxRegister) {
                            fixText = fixText.replace(matchArr[0], matchArr[1])
                        } else {
                            try {
                                val escaped = getEscaped(matchArr[0])
                                fixText = if (escaped.length == 2 && escaped[0] == '\\' && regexPunct.containsMatchIn(escaped[1].toString())) {
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

    private fun getEscaped(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace("?", "\\?")
            .replace("^", "\\^")
            .replace("*", "\\*")
            .replace("|", "\\|")
            .replace("[", "\\[")
            .replace("]", "\\]")
            .replace("{", "\\{")
            .replace("}", "\\}")
            .replace("+", "\\+")
            .replace("$", "\\$")
            .replace("(", "\\(")
            .replace(")", "\\)")
            .replace(".", "\\.")
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
        val splitter = " "
        val currentText = StringBuilder()
        var glavText = ""
        var glavaNum = 0

        for (i in sentences.indices) {
            val line = sentences[i]
            if (bookChapter &&
                line.isNotEmpty() &&
                line.trim().length < 50 &&
                regexGlava.containsMatchIn(line.lowercase(Locale.ROOT))
            ) {
                currentText.append("\n")
                val str = currentText.toString()
                if (str.trim().isNotEmpty() && regexChar.containsMatchIn(str)) {
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
                regexChar.containsMatchIn(currentText)
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

            val words = line.split(splitter)
            for (word in words) {
                val lastChar = if (word.isNotEmpty()) word[word.length - 1] else ' '
                val isBreakPunct = lastChar == '.' || lastChar == ',' || lastChar == '!' ||
                    lastChar == '?' || lastChar == ':' || lastChar == ';' || lastChar == '-'
                if (currentText.length + word.length > lastStringsLength &&
                    word.isNotEmpty() &&
                    (isBreakPunct || currentText.length > lastStringsLength * 1.2)
                ) {
                    val textToAdd = currentText.toString() + splitter + word
                    if (regexChar.containsMatchIn(textToAdd)) {
                        outSentences.add(textToAdd)
                        outGlavs.add(glavaNumFormat(glavaNum, glavText))
                    }
                    currentText.setLength(0)
                } else {
                    if (currentText.isNotEmpty() && currentText[currentText.length - 1] != '\n') {
                        currentText.append(splitter)
                    }
                    currentText.append(word)
                }
            }
            if (currentText.isNotEmpty()) {
                currentText.append("\n")
            }
        }

        if (currentText.isNotEmpty() && regexChar.containsMatchIn(currentText)) {
            outSentences.add(currentText.toString())
            outGlavs.add(glavaNumFormat(glavaNum, glavText))
        }

        return outSentences to outGlavs
    }

    fun splitPartText(text: String): List<String> {
        val sentences = getFixPoints(text)
        val result = mutableListOf<String>()
        val splitter = " "
        val currentText = StringBuilder()
        for (line in sentences) {
            val words = line.split(splitter)
            for (word in words) {
                val lastChar = if (word.isNotEmpty()) word[word.length - 1] else ' '
                val isBreakPunct = lastChar == '.' || lastChar == ',' || lastChar == '!' ||
                    lastChar == '?' || lastChar == ':' || lastChar == ';' || lastChar == '-'
                if (currentText.length + word.length > lastStringsLength &&
                    word.isNotEmpty() &&
                    (isBreakPunct || currentText.length > lastStringsLength * 1.2)
                ) {
                    val textToAdd = currentText.toString() + splitter + word
                    if (regexChar.containsMatchIn(textToAdd)) {
                        result.add(textToAdd)
                    }
                    currentText.setLength(0)
                } else {
                    if (currentText.isNotEmpty() && currentText[currentText.length - 1] != '\n') {
                        currentText.append(splitter)
                    }
                    currentText.append(word)
                }
            }
            if (currentText.isNotEmpty()) {
                currentText.append("\n")
            }
        }
        if (currentText.isNotEmpty() && regexChar.containsMatchIn(currentText)) {
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
