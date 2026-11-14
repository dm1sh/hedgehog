package dm1sh.hedgehog.book

import java.util.Locale

/**
 * Splits converted book text into the per-sentence chunks the built-in player speaks one by one.
 *
 * Kept free of Android imports so the same rules can be exercised by tests: the app maps the
 * returned [Sentence]s onto its player UI model.
 */
object PlayerSentenceSplitter {

    /** Fallback chapter regex, mirroring AppSettings.DEFAULT_REGEX_GLAVA. */
    private const val DEFAULT_CHAPTER_PATTERN = "глава |раздел |эпизод |рассказ |chapter "

    private const val MAX_SPAN_LEN = 340
    private const val MIN_SPAN_BEFORE_SPLIT = 120
    private const val MIN_ALNUM_FOR_SENTENCE = 4

    /** Quote and bracket characters that may open the word following a dot: «"„ ( [ * */
    private const val OPENING_CHARS = "\u00AB\u201E\u0022(\u005B\u002A"

    /**
     * Lower-case abbreviation stems (without the trailing dot) whose period must not end a
     * sentence. Single letters are handled separately: they cover initials such as
     * "А. П. Чехов" and letter abbreviations such as "т. д.", "т. е.", "и пр.", "в 1999 г.".
     */
    private val ABBREVIATIONS = setOf(
        "др", "пр", "гг", "вв", "см", "ср", "напр", "им", "проф", "доц", "акад",
        "рис", "табл", "стр", "изд", "ред", "пер", "млн", "млрд", "тыс", "руб",
        "коп", "тел", "ул", "кв", "обл", "англ", "рус", "нем", "лат", "греч",
        "e.g", "i.e", "etc", "mr", "mrs", "ms", "dr", "prof", "vs", "fig", "st"
    )

    data class Sentence(
        val text: String,
        val startsNewParagraph: Boolean = false,
        val startsNewChapter: Boolean = false,
        val chapterTitle: String? = null
    )

    fun split(
        parts: List<String>,
        glavs: List<String> = emptyList(),
        detectChapters: Boolean = true,
        chapterRegexPattern: String = DEFAULT_CHAPTER_PATTERN
    ): List<Sentence> {
        if (parts.isEmpty()) return emptyList()
        val pattern = chapterRegexPattern.ifEmpty { DEFAULT_CHAPTER_PATTERN }
        val chapterRegex = try {
            Regex(pattern, RegexOption.IGNORE_CASE)
        } catch (_: Exception) {
            Regex(DEFAULT_CHAPTER_PATTERN, RegexOption.IGNORE_CASE)
        }
        val leadingNumPrefixRegex = Regex("^\\d{4}\\s*")
        val result = ArrayList<Sentence>(parts.size * 16)
        var lastPartGlav = ""
        var pendingParagraphBreak = false
        var pendingChapterTitle: String? = null
        var currentChapterTitle: String? = null
        var consecutiveEmptyLines = 0

        fun emitSentence(raw: String) {
            val s = raw.trim()
            if (s.isEmpty()) return
            val isFirstInBook = result.isEmpty()
            val isNewChapter = pendingChapterTitle != null
            if (pendingChapterTitle != null) {
                currentChapterTitle = pendingChapterTitle
            }
            val isNewParagraph = !isFirstInBook && (pendingParagraphBreak || isNewChapter)
            result.add(
                Sentence(
                    text = s,
                    startsNewParagraph = isNewParagraph,
                    startsNewChapter = isNewChapter,
                    chapterTitle = currentChapterTitle
                )
            )
            pendingParagraphBreak = false
            pendingChapterTitle = null
        }

        for (partIdx in parts.indices) {
            val part = parts[partIdx]
            if (detectChapters) {
                val rawGlav = glavs.getOrNull(partIdx)?.trim().orEmpty()
                if (rawGlav.isNotEmpty() && rawGlav != lastPartGlav) {
                    lastPartGlav = rawGlav
                    val stripped = rawGlav.replaceFirst(leadingNumPrefixRegex, "").trim()
                    if (stripped.isNotEmpty()) {
                        pendingChapterTitle = stripped
                    } else if (rawGlav != "0000") {
                        pendingChapterTitle = rawGlav
                    }
                }
            }

            val len = part.length
            var chunkStart = 0
            var alnumCount = 0
            var lastClauseEnd = -1
            var lastSpace = -1
            var lineCheckedForChapter = false
            var i = 0
            while (i < len) {
                if (detectChapters && !lineCheckedForChapter) {
                    lineCheckedForChapter = true
                    var lineEnd = part.indexOf('\n', i)
                    if (lineEnd == -1) lineEnd = len
                    val rawLine = part.substring(i, lineEnd)
                    val trimmedLine = rawLine.trim()
                    if (trimmedLine.isEmpty()) {
                        consecutiveEmptyLines++
                    } else {
                        if (trimmedLine.length < 50 && chapterRegex.containsMatchIn(trimmedLine)) {
                            pendingChapterTitle = trimmedLine
                        } else if (consecutiveEmptyLines >= 3 && result.isNotEmpty()) {
                            pendingChapterTitle = trimmedLine.take(60)
                        }
                        consecutiveEmptyLines = 0
                    }
                }

                val c = part[i]
                if (Character.isLetterOrDigit(c)) {
                    alnumCount++
                } else if (c == ' ' || c == '\t') {
                    lastSpace = i
                } else if (c == ',' || c == ':' || c == '—' || c == '-') {
                    lastClauseEnd = i + 1
                }

                val isNewline = c == '\n' || c == '\r'
                val isSentenceEndPunct = (c == '.' || c == '!' || c == '?' || c == '…' || c == ';') &&
                    (i + 1 == len || part[i + 1].isWhitespace() || part[i + 1] == '"' || part[i + 1] == '»' || part[i + 1] == ')')

                val spanLen = i + 1 - chunkStart
                if (isNewline) {
                    if (alnumCount > 0) {
                        emitSentence(part.substring(chunkStart, i))
                        pendingParagraphBreak = true
                    } else if (result.isNotEmpty()) {
                        pendingParagraphBreak = true
                    }
                    chunkStart = i + 1
                    alnumCount = 0
                    lastClauseEnd = -1
                    lastSpace = -1
                    lineCheckedForChapter = false
                } else if (isSentenceEndPunct && alnumCount >= MIN_ALNUM_FOR_SENTENCE &&
                    !(c == '.' && isAbbreviationDot(part, i))
                ) {
                    var endIdx = i + 1
                    while (endIdx < len && (part[endIdx] == '"' || part[endIdx] == '»' || part[endIdx] == ')')) {
                        endIdx++
                    }
                    emitSentence(part.substring(chunkStart, endIdx))
                    i = endIdx - 1
                    chunkStart = endIdx
                    alnumCount = 0
                    lastClauseEnd = -1
                    lastSpace = -1
                } else if (spanLen >= MAX_SPAN_LEN && alnumCount >= MIN_ALNUM_FOR_SENTENCE) {
                    val splitAt = when {
                        lastClauseEnd > chunkStart + MIN_SPAN_BEFORE_SPLIT -> lastClauseEnd
                        lastSpace > chunkStart + MIN_SPAN_BEFORE_SPLIT -> lastSpace
                        else -> i + 1
                    }
                    // A forced break must not tear "т. д." or "А. П." apart; keep scanning and
                    // break at the next whitespace after the abbreviation instead.
                    if (isTornAbbreviation(part, chunkStart, splitAt)) {
                        i++
                        continue
                    }
                    emitSentence(part.substring(chunkStart, splitAt))
                    chunkStart = splitAt
                    alnumCount = 0
                    lastClauseEnd = -1
                    lastSpace = -1
                }
                i++
            }
            if (chunkStart < len && alnumCount > 0) {
                emitSentence(part.substring(chunkStart, len))
            }
            if (result.isNotEmpty()) {
                pendingParagraphBreak = true
            }
        }
        return result
    }

    /**
     * True when an over-long span would be cut at [splitAt] right after an abbreviation token
     * ("…и т. | д.", "…А. П. | Чехов"), i.e. leaving a lone "т." or "П." at the end of a chunk.
     * [chunkStart] is where the span began, so the token lookup cannot run past the chunk.
     */
    private fun isTornAbbreviation(text: String, chunkStart: Int, splitAt: Int): Boolean {
        if (splitAt <= chunkStart || splitAt > text.length) return false
        var tokenStart = splitAt
        while (tokenStart > chunkStart && !text[tokenStart - 1].isWhitespace()) tokenStart--
        val raw = text.substring(tokenStart, splitAt)
        val token = raw.trimEnd(')', ']', '»', '"', ',', ';', ':', '!', '?', '…')
        /* A closing bracket or comma after the dot means the abbreviation chain has ended. */
        if (token.length != raw.trimEnd().length) return false
        if (!token.endsWith('.')) return false
        val word = token.dropLast(1).trimStart('(', '«', '"', '[')
        if (word.isEmpty() || !word.all { it.isLetter() }) return false
        val isInitial = word.length == 1 && word[0].isUpperCase()
        /* Initials continue with a name ("А. П. Чехов"); everything else continues lowercase. */
        if (!isInitial && !isLowercaseCapitalRule(text, splitAt)) {
            if (word.length != 1 && word.lowercase(Locale.ROOT) !in ABBREVIATIONS) return false
        }
        val nextChar = firstSignificantCharAfter(text, splitAt) ?: return false
        return if (isInitial) nextChar.isLetter() else nextChar.isLowerCase()
    }

    /**
     * True when the '.' at [dotIndex] closes an abbreviation, an initial or any shortening rather
     * than a sentence, so "т. д.", "т. е.", "и пр.", "см. рис.", "напр. так" and "А. П. Чехов"
     * stay in one player sentence.
     */
    fun isAbbreviationDot(text: String, dotIndex: Int): Boolean {
        var start = dotIndex - 1
        while (start >= 0 && (text[start].isLetterOrDigit() || text[start] == '.')) {
            start--
        }
        start++
        if (start >= dotIndex) return false
        val raw = text.substring(start, dotIndex).trimEnd('.')
        if (raw.isEmpty()) return false
        /* A lone letter is an initial ("А. П. Чехов") or a letter abbreviation ("т. д."). */
        if (raw.length == 1 && raw[0].isLetter()) return true
        /* Anything in the list, whatever follows it. */
        if (raw.lowercase(Locale.ROOT) in ABBREVIATIONS) return true
        /*
         * Unknown shortening: a real sentence starts with a capital letter, so "слово. слово"
         * is a shortening ("напр. это", "т. к. так") and not the end of a sentence.
         */
        return raw.any { it.isLetter() } && isLowercaseCapitalRule(text, dotIndex + 1)
    }

    /**
     * True when the text right after [afterIndex] (the first index past the dot) starts with a
     * lowercase letter, which in normal prose means the dot belongs to a shortening and not to
     * the end of a sentence.
     */
    private fun isLowercaseCapitalRule(text: String, afterIndex: Int): Boolean {
        val next = firstSignificantCharAfter(text, afterIndex) ?: return false
        return next.isLetter() && next.isLowerCase()
    }

    /**
     * First character after [index] that is not whitespace or an opening quote/bracket, or null
     * when the text ends first.
     */
    private fun firstSignificantCharAfter(text: String, index: Int): Char? {
        var i = index
        while (i < text.length) {
            val c = text[i]
            if (!c.isWhitespace() && c !in OPENING_CHARS) return c
            i++
        }
        return null
    }
}
