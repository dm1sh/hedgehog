package dm1sh.hedgehog.localization

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale

class LocalizationManager(context: Context) {
    val loadedLocales: List<String> = listOf(
        "de", "en", "es", "fi", "fr", "it", "ja", "ko", "pl", "pt", "ru", "zh"
    )

    private val table = mutableMapOf<String, Map<String, String>>()
    var currentLocale: String = detectSystemLocale()
        private set

    init {
        loadFromAssets(context)
    }

    fun detectSystemLocale(): String {
        val sys = Locale.getDefault().language.lowercase(Locale.ROOT)
        return if (loadedLocales.contains(sys)) sys else "en"
    }

    fun setLocale(localeOrAuto: String) {
        currentLocale = if (localeOrAuto == "AUTO" || localeOrAuto.isBlank()) {
            detectSystemLocale()
        } else {
            val clean = localelocaleLanguage(localeOrAuto)
            if (loadedLocales.contains(clean)) clean else "en"
        }
    }

    fun tr(key: String): String {
        if (key.isEmpty()) return ""
        val row = table[key] ?: return key
        return row[currentLocale] ?: row["en"] ?: key
    }

    private fun loadFromAssets(context: Context) {
        try {
            context.assets.open("localization/translations.csv").use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                    val headerLine = reader.readLine() ?: return
                    val headers = parseCsvRow(headerLine)
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val rowStr = line ?: continue
                        if (rowStr.isBlank()) continue
                        val cols = parseCsvRow(rowStr)
                        if (cols.isEmpty()) continue
                        val key = cols[0]
                        val map = mutableMapOf<String, String>()
                        for (i in 1 until minOf(headers.size, cols.size)) {
                            map[headers[i].trim()] = cols[i]
                        }
                        table[key] = map
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun parseCsvRow(line: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length && line[i + 1] == '"') {
                        sb.append('"')
                        i++
                    } else {
                        inQuotes = false
                    }
                } else {
                    sb.append(c)
                }
            } else {
                when (c) {
                    '"' -> inQuotes = true
                    ',' -> {
                        result.add(sb.toString())
                        sb.setLength(0)
                    }
                    else -> sb.append(c)
                }
            }
            i++
        }
        result.add(sb.toString())
        return result
    }

    companion object {
        fun localelocaleLanguage(value: String): String {
            return value.replace("-", "_").substringBefore("_").lowercase(Locale.ROOT)
        }
    }
}
