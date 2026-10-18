package dm1sh.hedgehog.cli

import dm1sh.hedgehog.book.AudioMetadataTagger
import dm1sh.hedgehog.book.BookAudioMetadata
import dm1sh.hedgehog.book.ProcessingFile
import dm1sh.hedgehog.book.TextsConverter
import dm1sh.hedgehog.model.DefaultVoices
import dm1sh.hedgehog.tts.OfflineInstaller
import dm1sh.hedgehog.tts.OfflineTtsEngine
import dm1sh.hedgehog.tts.OpusConverter
import dm1sh.hedgehog.tts.SecMsGecGenerator
import dm1sh.hedgehog.tts.SocketEdgeTtsWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.system.exitProcess

private const val DEFAULT_CHAPTER_REGEX = "глава |раздел |эпизод |рассказ |chapter "
private const val MERGED_PREFIX = "F"
private const val MAX_SAVE_PATH_LEN = 220
private val BOOK_EXTENSIONS = setOf("txt", "fb2", "epub", "html", "htm", "xml", "ini", "zip")
private val TOKEN_VOICE_REGEX = Regex("value=\"(Microsoft Server Speech Text to Speech Voice \\(([^,]+), ([^)]+)\\))\"")

data class CliOptions(
    val inputs: MutableList<String> = mutableListOf(),
    var inlineText: String? = null,
    var outputPath: String? = null,
    var offlineMode: Boolean = false,
    var allowOfflineFallback: Boolean = true,
    var onlineVoice: String = "ru-RU, DmitryNeural",
    var offlineVoice: String? = null,
    var voicesDir: File = File(System.getProperty("user.home"), "EdgeTTS/Voices"),
    var lexxPath: String? = null,
    var lexxRegister: Boolean = true,
    var chapters: Boolean = false,
    var chapterTitleSpeak: Boolean = false,
    var chapterRegex: String = DEFAULT_CHAPTER_REGEX,
    var chapterMp3Path: String? = null,
    var inlineNotes: Boolean = false,
    var filterEmojis: Boolean = true,
    var opusOutput: Boolean = false,
    var lowBitrate: Boolean = false,
    var embedMetadata: Boolean = true,
    var bookDirCreate: Boolean = false,
    var rate: Int = 0,
    var pitch: Int = 0,
    var maxThreads: Int = maxOf(10, Runtime.getRuntime().availableProcessors()),
    var threadsExplicitlySet: Boolean = false,
    var mergeFiles: Int = 50,
    var timeoutSec: Int = 60,
    var downloadOfflineVoices: Boolean = false,
    val downloadVoiceNames: MutableList<String> = mutableListOf(),
    var listVoices: Boolean = false,
    var listVoicesFilter: String? = null,
    var quiet: Boolean = false,
    var showHelp: Boolean = false,
    var showVersion: Boolean = false
)

private data class PartJobSpec(
    val index: Int,
    val fileName: String,
    val glava: String,
    val fileNum: String,
    val text: String,
    val voice: String,
    val rate: String,
    val pitch: String
)

private data class ChunkJobSpec(
    val bookFolderName: String,
    val glava: String,
    val chunkIdx: Int,
    val totalChunksForGlava: Int,
    val totalChunksForBook: Int,
    val bookChunkNumber: Int,
    val targetBaseName: String,
    val includeGlavaIntro: Boolean,
    val fileNums: List<String>,
    val remainingParts: AtomicInteger,
    val bookMetadata: BookAudioMetadata,
    val voice: String
)

fun main(args: Array<String>) = runBlocking {
    val opts = parseArgs(args)

    if (opts.showVersion) {
        val ver = object {}.javaClass.`package`?.implementationVersion ?: "1.3.0"
        println("hedgehog-cli $ver")
        return@runBlocking
    }

    if (opts.showHelp) {
        printHelp()
        return@runBlocking
    }

    if (opts.downloadOfflineVoices) {
        val code = runDownloadOfflineVoices(opts)
        if (!opts.listVoices && opts.inputs.isEmpty() && opts.inlineText == null) {
            exitProcess(code)
        }
    }

    if (opts.listVoices) {
        runListVoices(opts)
        if (opts.inputs.isEmpty() && opts.inlineText == null) {
            return@runBlocking
        }
    }

    val exitCode = runConversion(opts)
    exitProcess(exitCode)
}

private fun expandPath(raw: String): File {
    val trimmed = raw.trim()
    if (trimmed == "~") {
        return File(System.getProperty("user.home"))
    }
    if (trimmed.startsWith("~/") || trimmed.startsWith("~\\")) {
        return File(System.getProperty("user.home"), trimmed.substring(2))
    }
    return File(trimmed)
}

private fun parseSignedNumber(raw: String): Int {
    val cleaned = raw.trim()
        .removeSuffix("%")
        .removeSuffix("Hz")
        .removeSuffix("hz")
        .trim()
    return cleaned.toIntOrNull()
        ?: throw IllegalArgumentException("Invalid numeric value: '$raw'")
}

private fun parseArgs(args: Array<String>): CliOptions {
    val opts = CliOptions()
    var i = 0
    while (i < args.size) {
        val arg = args[i]
        fun requireNext(flag: String): String {
            if (i + 1 >= args.size) {
                System.err.println("Error: Missing value for $flag")
                exitProcess(2)
            }
            i++
            return args[i]
        }

        when {
            arg == "-h" || arg == "--help" -> opts.showHelp = true
            arg == "-V" || arg == "--version" -> opts.showVersion = true
            arg == "-q" || arg == "--quiet" -> opts.quiet = true

            arg == "-i" || arg == "--input" -> opts.inputs.add(requireNext(arg))
            arg.startsWith("--input=") -> opts.inputs.add(arg.substringAfter("="))

            arg == "-t" || arg == "--text" -> opts.inlineText = requireNext(arg)
            arg.startsWith("--text=") -> opts.inlineText = arg.substringAfter("=")

            arg == "-o" || arg == "--output" -> opts.outputPath = requireNext(arg)
            arg.startsWith("--output=") -> opts.outputPath = arg.substringAfter("=")

            arg == "--offline" -> opts.offlineMode = true
            arg == "--online" -> opts.offlineMode = false
            arg == "--no-fallback" -> opts.allowOfflineFallback = false

            arg == "-v" || arg == "--voice" -> opts.onlineVoice = requireNext(arg)
            arg.startsWith("--voice=") -> opts.onlineVoice = arg.substringAfter("=")

            arg == "--offline-voice" -> opts.offlineVoice = requireNext(arg)
            arg.startsWith("--offline-voice=") -> opts.offlineVoice = arg.substringAfter("=")

            arg == "--voices-dir" -> opts.voicesDir = expandPath(requireNext(arg))
            arg.startsWith("--voices-dir=") -> opts.voicesDir = expandPath(arg.substringAfter("="))

            arg == "--lexx" -> opts.lexxPath = requireNext(arg)
            arg.startsWith("--lexx=") -> opts.lexxPath = arg.substringAfter("=")
            arg == "--lexx-register" || arg == "--case-sensitive-lexx" -> opts.lexxRegister = true
            arg == "--no-lexx-register" || arg == "--ignore-case-lexx" -> opts.lexxRegister = false

            arg == "--chapters" -> opts.chapters = true
            arg == "--no-chapters" -> opts.chapters = false
            arg == "--chapter-title" || arg == "--glav-text" -> opts.chapterTitleSpeak = true
            arg == "--chapter-regex" -> opts.chapterRegex = requireNext(arg)
            arg.startsWith("--chapter-regex=") -> opts.chapterRegex = arg.substringAfter("=")
            arg == "--chapter-mp3" -> opts.chapterMp3Path = requireNext(arg)
            arg.startsWith("--chapter-mp3=") -> opts.chapterMp3Path = arg.substringAfter("=")

            arg == "--inline-notes" -> opts.inlineNotes = true
            arg == "--no-inline-notes" -> opts.inlineNotes = false

            arg == "--filter-emojis" -> opts.filterEmojis = true
            arg == "--no-filter-emojis" -> opts.filterEmojis = false

            arg == "--opus" -> opts.opusOutput = true
            arg == "--mp3" -> opts.opusOutput = false
            arg == "--low-bitrate" -> opts.lowBitrate = true

            arg == "--embed-metadata" -> opts.embedMetadata = true
            arg == "--no-embed-metadata" -> opts.embedMetadata = false

            arg == "--book-dir" -> opts.bookDirCreate = true
            arg == "--no-book-dir" -> opts.bookDirCreate = false

            arg == "--rate" || arg == "--speed" -> {
                opts.rate = parseSignedNumber(requireNext(arg)).coerceIn(-50, 100)
            }
            arg.startsWith("--rate=") || arg.startsWith("--speed=") -> {
                opts.rate = parseSignedNumber(arg.substringAfter("=")).coerceIn(-50, 100)
            }

            arg == "--pitch" -> {
                opts.pitch = parseSignedNumber(requireNext(arg)).coerceIn(-50, 50)
            }
            arg.startsWith("--pitch=") -> {
                opts.pitch = parseSignedNumber(arg.substringAfter("=")).coerceIn(-50, 50)
            }

            arg == "-j" || arg == "--threads" -> {
                opts.maxThreads = requireNext(arg).toIntOrNull()?.coerceIn(1, 64) ?: 10
                opts.threadsExplicitlySet = true
            }
            arg.startsWith("--threads=") -> {
                opts.maxThreads = arg.substringAfter("=").toIntOrNull()?.coerceIn(1, 64) ?: 10
                opts.threadsExplicitlySet = true
            }

            arg == "--merge-files" -> {
                opts.mergeFiles = requireNext(arg).toIntOrNull()?.coerceAtLeast(0) ?: 50
            }
            arg.startsWith("--merge-files=") -> {
                opts.mergeFiles = arg.substringAfter("=").toIntOrNull()?.coerceAtLeast(0) ?: 50
            }

            arg == "--timeout" -> {
                opts.timeoutSec = requireNext(arg).toIntOrNull() ?: 60
            }
            arg.startsWith("--timeout=") -> {
                opts.timeoutSec = arg.substringAfter("=").toIntOrNull() ?: 60
            }

            arg == "--download-offline-voices" || arg == "--download-offline-voice" -> {
                opts.downloadOfflineVoices = true
                if (i + 1 < args.size && !args[i + 1].startsWith("-")) {
                    i++
                    opts.downloadVoiceNames.addAll(
                        args[i].split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    )
                }
            }
            arg.startsWith("--download-offline-voices=") || arg.startsWith("--download-offline-voice=") -> {
                opts.downloadOfflineVoices = true
                opts.downloadVoiceNames.addAll(
                    arg.substringAfter("=").split(",").map { it.trim() }.filter { it.isNotEmpty() }
                )
            }

            arg == "--list-voices" -> {
                opts.listVoices = true
                if (i + 1 < args.size && !args[i + 1].startsWith("-")) {
                    i++
                    opts.listVoicesFilter = args[i].trim()
                }
            }
            arg.startsWith("--list-voices=") -> {
                opts.listVoices = true
                opts.listVoicesFilter = arg.substringAfter("=").trim()
            }

            !arg.startsWith("-") -> {
                opts.inputs.add(arg)
            }

            else -> {
                System.err.println("Error: Unknown option '$arg'. Use --help to see available options.")
                exitProcess(2)
            }
        }
        i++
    }
    return opts
}

private fun printHelp() {
    println(
        """
        Usage: hedgehog-cli [options]

        Input / Output:
          -i, --input <path>               Input book/text file (.fb2, .epub, .txt, .html, .zip), directory, or '-' for stdin
          -t, --text <text>                Synthesize plain text string directly
          -o, --output <path>              Output directory (e.g. ./out) or target audio file (e.g. hello.mp3, hello.opus)

        Voice & TTS Mode:
          --online                         Use online Microsoft Edge TTS (default)
          --offline                        Use offline Microsoft Embedded TTS
          --no-fallback                    Disable automatic fallback from online to offline on network error
          -v, --voice <name>               Online voice name (default: "ru-RU, DmitryNeural")
          --offline-voice <name>           Offline voice name or folder (e.g. Dmitry, Svetlana, Dariya, Ryan)
          --voices-dir <dir>               Directory for offline voice models (default: ~/EdgeTTS/Voices)
          --download-offline-voices [list] Download offline voice models into --voices-dir
          --list-voices [filter]           List installed/available offline voices and online voices

        Book Processing & Formatting:
          --lexx <file>                    Path to .lexx pronunciation dictionary file
          --lexx-register                  Case-sensitive .lexx rules (default)
          --no-lexx-register               Case-insensitive .lexx rules
          --chapters                       Split output audio by book chapters
          --chapter-title                  Speak chapter title at the start of each chapter
          --chapter-regex <pattern>        Custom regex for chapter headings
          --chapter-mp3 <file>             Insert MP3 jingle at the beginning of each chapter
          --inline-notes                   Inline footnotes/endnotes in FB2 and EPUB books
          --no-filter-emojis               Keep emojis in text instead of filtering them out

        Audio Encoding & Prosody:
          --opus                           Encode output to Ogg Opus (32 kbps VBR VoIP mono)
          --mp3                            Encode output to MP3 (default)
          --low-bitrate                    Use 48 kbps instead of 96 kbps for MP3 output
          --rate, --speed <percent>        Speech rate adjustment from -50 to +100 (default: +0)
          --pitch <hz>                     Speech pitch adjustment from -50 to +50 (default: +0)
          --no-embed-metadata              Disable embedding title, author, track tags, and cover art
          -j, --threads <n>                Maximum parallel synthesis workers (default: 10)
          --merge-files <n>                Parts per merged output file when --chapters is off (default: 50, 0 = all in 1)
          -q, --quiet                      Suppress progress output
          -h, --help                       Show this help message
          -V, --version                    Show version

        Examples:
          # Convert a book using an online voice
          hedgehog-cli -i book.fb2 -o ./out --voice "ru-RU, DmitryNeural" --lexx rules.lexx --chapters --inline-notes --opus

          # Convert text or a book completely offline
          hedgehog-cli -i book.epub -o ./out --offline --offline-voice Dmitry --voices-dir ~/EdgeTTS/Voices

          # Read from stdin / plain text to an audio file
          echo "Привет, мир!" | hedgehog-cli --offline --offline-voice Dmitry -o hello.mp3

          # Download offline voices or list available voices on Linux
          hedgehog-cli --download-offline-voices --voices-dir ~/EdgeTTS/Voices
          hedgehog-cli --list-voices
        """.trimIndent()
    )
}

private suspend fun runDownloadOfflineVoices(opts: CliOptions): Int {
    val targetDir = opts.voicesDir
    if (!targetDir.exists() && !targetDir.mkdirs()) {
        System.err.println("Error: Cannot create voices directory: ${targetDir.absolutePath}")
        return 1
    }
    val requested = mutableListOf<String>()
    requested.addAll(opts.downloadVoiceNames)
    if (requested.isEmpty() && !opts.offlineVoice.isNullOrBlank()) {
        requested.add(opts.offlineVoice!!)
    }
    if (!opts.quiet) {
        val filterDesc = if (requested.isEmpty()) "all available voices" else requested.joinToString(", ")
        println("Downloading offline voices ($filterDesc) to ${targetDir.absolutePath} ...")
    }
    var lastFile = ""
    val ok = OfflineInstaller.installToVoicesDir(
        voicesDir = targetDir,
        requestedVoices = requested,
        localeFilter = null,
        onRegisterVoice = { voice ->
            if (!opts.quiet) {
                println("\nInstalled offline voice: $voice -> ${File(targetDir, voice).absolutePath}")
            }
        },
        onProgress = { fileName, downloaded, total ->
            if (!opts.quiet) {
                if (fileName != lastFile && lastFile.isNotEmpty()) {
                    println()
                }
                lastFile = fileName
                val dlMb = String.format(Locale.US, "%.1f MB", downloaded / (1024.0 * 1024.0))
                if (total > 0) {
                    val totMb = String.format(Locale.US, "%.1f MB", total / (1024.0 * 1024.0))
                    val pct = ((downloaded * 100L) / total).coerceIn(0L, 100L)
                    print("\r  $fileName: $dlMb / $totMb ($pct%)   ")
                } else {
                    print("\r  $fileName: $dlMb   ")
                }
                System.out.flush()
            }
        }
    )
    if (!opts.quiet) {
        println()
    }
    return if (ok) {
        if (!opts.quiet) {
            println("Offline voices ready in ${targetDir.absolutePath}")
        }
        0
    } else {
        System.err.println("Error: Failed to download one or more offline voices.")
        1
    }
}

private data class InstalledOfflineVoice(
    val folderName: String,
    val dir: File,
    val fullTokenName: String,
    val locale: String,
    val shortName: String
)

private fun scanInstalledOfflineVoices(voicesDir: File): List<InstalledOfflineVoice> {
    val searchRoots = listOf(voicesDir, File(voicesDir, "Voices")).distinctBy { it.absolutePath }
    val found = mutableListOf<InstalledOfflineVoice>()
    val seenPaths = mutableSetOf<String>()
    for (root in searchRoots) {
        if (!root.exists() || !root.isDirectory) continue
        if (OfflineTtsEngine.voiceInstalled(root)) {
            parseInstalledVoiceDir(root)?.let {
                if (seenPaths.add(it.dir.canonicalPath)) found.add(it)
            }
        }
        val children = root.listFiles()?.sortedBy { it.name.lowercase(Locale.ROOT) }.orEmpty()
        for (child in children) {
            if (child.isDirectory && OfflineTtsEngine.voiceInstalled(child)) {
                parseInstalledVoiceDir(child)?.let {
                    if (seenPaths.add(it.dir.canonicalPath)) found.add(it)
                }
            }
        }
    }
    return found
}

private fun parseInstalledVoiceDir(dir: File): InstalledOfflineVoice? {
    return try {
        val tokensXml = File(dir, "Tokens.xml").readText(Charsets.UTF_8)
        val m = TOKEN_VOICE_REGEX.find(tokensXml)
        val fullName = m?.groupValues?.get(1) ?: dir.name
        val locale = m?.groupValues?.get(2) ?: ""
        val shortName = m?.groupValues?.get(3) ?: dir.name
        InstalledOfflineVoice(
            folderName = dir.name,
            dir = dir,
            fullTokenName = fullName,
            locale = locale,
            shortName = shortName
        )
    } catch (_: Exception) {
        null
    }
}

private fun resolveOfflineVoiceDir(voicesDir: File, requestedName: String?): InstalledOfflineVoice? {
    val installed = scanInstalledOfflineVoices(voicesDir)
    if (installed.isEmpty()) return null
    val query = requestedName?.trim().orEmpty()
    if (query.isEmpty()) return installed.first()

    val qLower = query.lowercase(Locale.ROOT)
    val qSimple = qLower
        .removeSuffix("multilingualneural")
        .removeSuffix("neural")
        .substringAfter(",")
        .substringAfterLast("-")
        .trim()

    return installed.firstOrNull { it.folderName.equals(query, ignoreCase = true) }
        ?: installed.firstOrNull { it.shortName.equals(query, ignoreCase = true) }
        ?: installed.firstOrNull { it.fullTokenName.equals(query, ignoreCase = true) }
        ?: installed.firstOrNull {
            it.shortName.removeSuffix("Neural").equals(qSimple, ignoreCase = true) ||
                it.folderName.removeSuffix("Neural").equals(qSimple, ignoreCase = true)
        }
        ?: installed.firstOrNull {
            it.folderName.lowercase(Locale.ROOT).contains(qSimple) ||
                it.shortName.lowercase(Locale.ROOT).contains(qSimple) ||
                it.fullTokenName.lowercase(Locale.ROOT).contains(qLower)
        }
}

private fun resolveOnlineVoiceName(requested: String, availableOnline: List<String>): String {
    val trimmed = requested.trim()
    if (trimmed.isEmpty()) return "ru-RU, DmitryNeural"
    if (availableOnline.any { it.equals(trimmed, ignoreCase = true) }) {
        return availableOnline.first { it.equals(trimmed, ignoreCase = true) }
    }
    val dashRegex = Regex("^([a-zA-Z]{2,3}-[a-zA-Z]{2,4}(?:-[a-zA-Z]+)?)-([A-Za-z]+Neural)$")
    val dashMatch = dashRegex.find(trimmed)
    if (dashMatch != null) {
        return "${dashMatch.groupValues[1]}, ${dashMatch.groupValues[2]}"
    }
    val qLower = trimmed.lowercase(Locale.ROOT).removeSuffix("neural")
    val matched = availableOnline.firstOrNull { candidate ->
        val voicePart = candidate.substringAfter(",").trim().lowercase(Locale.ROOT)
        voicePart == "${qLower}neural" || voicePart == qLower || voicePart.startsWith(qLower)
    }
    return matched ?: trimmed
}

private fun fetchOnlineVoicesOrDefault(): List<String> {
    val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()
    val url = "https://speech.platform.bing.com/consumer/speech/synthesize/readaloud/voices/list?trustedclienttoken=${SecMsGecGenerator.TRUSTED_CLIENT_TOKEN}"
    val req = Request.Builder().url(url).build()
    return try {
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return DefaultVoices.ONLINE_VOICES
            val body = resp.body?.string() ?: return DefaultVoices.ONLINE_VOICES
            val arr = JSONArray(body)
            val list = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val fullName = obj.optString("Name", "")
                val start = fullName.indexOf('(')
                val end = fullName.indexOf(')')
                if (start != -1 && end != -1 && end > start + 1) {
                    list.add(fullName.substring(start + 1, end))
                }
            }
            if (list.isNotEmpty()) list else DefaultVoices.ONLINE_VOICES
        }
    } catch (_: Exception) {
        DefaultVoices.ONLINE_VOICES
    }
}

private fun runListVoices(opts: CliOptions) {
    val filter = opts.listVoicesFilter?.lowercase(Locale.ROOT)?.trim().orEmpty()
    val installedOffline = scanInstalledOfflineVoices(opts.voicesDir)
    val installedNames = installedOffline.map { it.folderName.lowercase(Locale.ROOT) }.toSet()

    println("=== Offline Voices (${opts.voicesDir.absolutePath}) ===")
    if (installedOffline.isEmpty()) {
        println("  (no offline voices installed yet; run with --download-offline-voices)")
    } else {
        for (v in installedOffline) {
            val line = "${v.folderName.padEnd(12)} [${v.locale}]  ${v.fullTokenName}  (installed)"
            if (filter.isEmpty() || line.lowercase(Locale.ROOT).contains(filter)) {
                println("  $line")
            }
        }
    }
    for ((voiceName, langs) in OfflineInstaller.DEFAULT_VOICES) {
        if (voiceName.lowercase(Locale.ROOT) !in installedNames) {
            val langTag = if (langs.isEmpty()) "multi/en" else langs.joinToString(",")
            val line = "${voiceName.padEnd(12)} [$langTag]  (available via --download-offline-voices)"
            if (filter.isEmpty() || line.lowercase(Locale.ROOT).contains(filter)) {
                println("  $line")
            }
        }
    }

    println()
    val onlineVoices = fetchOnlineVoicesOrDefault()
    val filteredOnline = if (filter.isEmpty()) {
        onlineVoices
    } else {
        onlineVoices.filter { it.lowercase(Locale.ROOT).contains(filter) }
    }
    println("=== Online Voices (${filteredOnline.size}) ===")
    for (v in filteredOnline) {
        println("  $v")
    }
}

private fun loadLexxRules(lexxPath: String?): List<String> {
    if (lexxPath.isNullOrBlank()) return emptyList()
    val f = expandPath(lexxPath)
    if (!f.exists() || !f.isFile) {
        System.err.println("Warning: .lexx file not found: ${f.absolutePath}")
        return emptyList()
    }
    return try {
        val raw = TextsConverter.readTextWithFallback(f.readBytes())
        raw.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
    } catch (e: Exception) {
        System.err.println("Warning: Failed to read .lexx file '${f.absolutePath}': ${e.message}")
        emptyList()
    }
}

private fun formatSigned(value: Int): String {
    val sign = if (value >= 0) "+" else "-"
    return "$sign${abs(value)}"
}

private fun bookStem(name: String): String {
    val f = File(name)
    val ext = f.extension.lowercase(Locale.ROOT)
    return if (BOOK_EXTENSIONS.contains(ext)) f.nameWithoutExtension else name
}

private fun stripBookName(inBookDir: Boolean, dir: String, name: String, glava: String): Boolean {
    val candidate = "$dir/${mp3BaseName(false, name, glava, "0000", merged = true)}.mp3"
    return inBookDir && candidate.length > MAX_SAVE_PATH_LEN
}

private fun mp3BaseName(
    stripName: Boolean,
    name: String,
    glava: String,
    num: String = "",
    merged: Boolean = false
): String {
    val cleanName = if (!stripName) File(bookStem(name)).name.trim() else ""
    val cleanGlava = glava.trim()
    var stem = when {
        cleanName.isNotEmpty() && cleanGlava.isNotEmpty() -> "$cleanName - $cleanGlava"
        cleanName.isNotEmpty() -> cleanName
        else -> cleanGlava
    }
    stem = stem.trim()
    if (stem.isEmpty()) {
        if (num.isEmpty()) return "${MERGED_PREFIX}0001"
        return if (merged) "$MERGED_PREFIX$num" else num
    }
    if (num.isEmpty()) return stem
    return stem + (if (merged) " - " else " ") + num
}

private fun rawPartFileName(glava: String, fileNum: String, ext: String): String {
    val cleanGlava = glava.trim()
    return if (cleanGlava.isEmpty()) {
        ".part_c${fileNum}.$ext"
    } else {
        ".part_${cleanGlava}_c${fileNum}.$ext"
    }
}

private fun loadBooksIntoProcessingFile(
    opts: CliOptions,
    lexxRules: List<String>,
    resolvedVoice: String,
    rateStr: String,
    pitchStr: String
): ProcessingFile? {
    var currentBook: ProcessingFile? = null

    fun appendEntry(
        saveName: String,
        content: String,
        metadata: BookAudioMetadata = BookAudioMetadata(title = saveName)
    ) {
        if (content.isBlank()) return
        val b = currentBook
        if (b == null) {
            currentBook = ProcessingFile(
                fileName = saveName,
                text = content,
                langLexx = lexxRules,
                lexxRegister = opts.lexxRegister,
                filterEmojis = opts.filterEmojis,
                bookChapter = opts.chapters,
                glavTextUse = opts.chapterTitleSpeak,
                regexGlavaPattern = opts.chapterRegex,
                voice = resolvedVoice,
                rate = rateStr,
                pitch = pitchStr,
                metadata = metadata
            )
        } else {
            b.addNewText(saveName, content, resolvedVoice, rateStr, pitchStr, metadata)
        }
    }

    if (!opts.inlineText.isNullOrBlank()) {
        appendEntry("output", opts.inlineText!!, BookAudioMetadata(title = "output"))
    }

    val expandedFiles = mutableListOf<File>()
    var readStdin = false
    for (rawInput in opts.inputs) {
        if (rawInput == "-") {
            readStdin = true
            continue
        }
        val f = expandPath(rawInput)
        if (!f.exists()) {
            System.err.println("Error: Input path does not exist: ${f.absolutePath}")
            return null
        }
        if (f.isDirectory) {
            val dirBooks = f.listFiles()
                ?.filter { it.isFile && BOOK_EXTENSIONS.contains(it.extension.lowercase(Locale.ROOT)) }
                ?.sortedBy { it.name.lowercase(Locale.ROOT) }
                .orEmpty()
            expandedFiles.addAll(dirBooks)
        } else {
            expandedFiles.add(f)
        }
    }

    if (opts.inputs.isEmpty() && opts.inlineText.isNullOrBlank()) {
        readStdin = true
    }

    if (readStdin) {
        val stdinText = System.`in`.bufferedReader(Charsets.UTF_8).readText().trim()
        if (stdinText.isEmpty() && currentBook == null) {
            System.err.println("Error: No input provided. Pass -i <file>, -t <text>, or pipe text to stdin. Use --help for usage.")
            return null
        }
        if (stdinText.isNotEmpty()) {
            appendEntry("stdin", stdinText, BookAudioMetadata(title = "stdin"))
        }
    }

    for (file in expandedFiles) {
        val ext = file.extension.lowercase(Locale.ROOT)
        val baseName = file.nameWithoutExtension
        try {
            when (ext) {
                "txt", "ini" -> {
                    val text = TextsConverter.readTextWithFallback(file.readBytes())
                    appendEntry(baseName, text, BookAudioMetadata(title = baseName))
                }
                "fb2", "xml" -> {
                    val raw = TextsConverter.readTextWithFallback(file.readBytes())
                    if (ext == "fb2" || raw.contains("<FictionBook", ignoreCase = true)) {
                        val bookName = TextsConverter.extractFb2BookName(raw, baseName)
                        val meta = TextsConverter.extractFb2Metadata(raw, baseName)
                        val text = TextsConverter.convertFb2ToTxt(raw, inlineNotes = opts.inlineNotes)
                        appendEntry(bookName, text, meta)
                    } else {
                        appendEntry(baseName, raw, BookAudioMetadata(title = baseName))
                    }
                }
                "html", "htm" -> {
                    val raw = TextsConverter.readTextWithFallback(file.readBytes())
                    val text = org.jsoup.Jsoup.parse(raw).text()
                    appendEntry(baseName, text, BookAudioMetadata(title = baseName))
                }
                "epub" -> {
                    val bookName = TextsConverter.extractEpubBookName(file.absolutePath, baseName)
                    val meta = TextsConverter.extractEpubMetadata(file.absolutePath, baseName)
                    val text = TextsConverter.convertEpubToTxt(file.absolutePath, inlineNotes = opts.inlineNotes)
                    appendEntry(bookName, text, meta)
                }
                "zip" -> {
                    TextsConverter.extractZipEntries(
                        file.absolutePath,
                        inlineNotes = opts.inlineNotes
                    ) { saveName, text, _, _, _, meta ->
                        appendEntry(saveName, text, meta)
                    }
                }
                "csv" -> {
                    val raw = TextsConverter.readTextWithFallback(file.readBytes())
                    val lines = TextsConverter.parseCsv(raw)
                    lines.forEachIndexed { idx, row ->
                        TextsConverter.processCsvLine(row, baseName, idx + 1) { saveName, text, _, _, _, meta ->
                            appendEntry(saveName, text, meta)
                        }
                    }
                }
                else -> {
                    val text = TextsConverter.readTextWithFallback(file.readBytes())
                    appendEntry(baseName, text, BookAudioMetadata(title = baseName))
                }
            }
        } catch (e: Exception) {
            System.err.println("Error reading '${file.absolutePath}': ${e.message}")
            return null
        }
    }

    return currentBook
}

private suspend fun runConversion(opts: CliOptions): Int {
    val outRaw = opts.outputPath?.trim().orEmpty()
    val explicitOutFile: File? = if (outRaw.isNotEmpty()) {
        val candidate = expandPath(outRaw)
        val ext = candidate.extension.lowercase(Locale.ROOT)
        if (!candidate.isDirectory && (ext == "mp3" || ext == "opus" || ext == "wav")) {
            if (ext == "opus") {
                opts.opusOutput = true
            }
            candidate
        } else {
            null
        }
    } else {
        null
    }

    val targetWavDirect = explicitOutFile?.extension?.equals("wav", ignoreCase = true) == true
    val saveDirFile: File = when {
        explicitOutFile != null -> explicitOutFile.absoluteFile.parentFile ?: File(".")
        outRaw.isNotEmpty() -> expandPath(outRaw)
        else -> File(".")
    }
    if (!saveDirFile.exists() && !saveDirFile.mkdirs()) {
        System.err.println("Error: Cannot create output directory: ${saveDirFile.absolutePath}")
        return 1
    }

    val offlineInstalled = resolveOfflineVoiceDir(
        voicesDir = opts.voicesDir,
        requestedName = opts.offlineVoice ?: if (opts.offlineMode) opts.onlineVoice else null
    )
    if (opts.offlineMode && offlineInstalled == null) {
        System.err.println(
            "Error: Offline voice not found in '${opts.voicesDir.absolutePath}'.\n" +
                "Run: hedgehog-cli --download-offline-voices --voices-dir ${opts.voicesDir.path}"
        )
        return 1
    }

    val resolvedOnlineVoice = resolveOnlineVoiceName(opts.onlineVoice, DefaultVoices.ONLINE_VOICES)
    val activeVoiceDisplay = if (opts.offlineMode) {
        offlineInstalled!!.fullTokenName
    } else {
        resolvedOnlineVoice
    }

    val rateStr = "${formatSigned(opts.rate)}%"
    val pitchStr = "${formatSigned(opts.pitch)}Hz"
    val lexxRules = loadLexxRules(opts.lexxPath)

    val activeBook = loadBooksIntoProcessingFile(
        opts = opts,
        lexxRules = lexxRules,
        resolvedVoice = activeVoiceDisplay,
        rateStr = rateStr,
        pitchStr = pitchStr
    ) ?: return 1

    val total = activeBook.allSentences.size
    if (total == 0) {
        System.err.println("Error: Input contains no text to synthesize.")
        return 1
    }

    if (explicitOutFile != null && activeBook.fileNames.size == 1) {
        activeBook.fileNames[0].fileName = explicitOutFile.nameWithoutExtension
    }

    val cpuCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    val effectiveThreads = if (opts.offlineMode && !opts.threadsExplicitlySet) {
        cpuCores
    } else if (opts.offlineMode) {
        opts.maxThreads.coerceIn(1, cpuCores)
    } else {
        opts.maxThreads
    }
    OfflineTtsEngine.setMaxJobs(effectiveThreads)

    val soundGlavaBytes = if (!opts.chapterMp3Path.isNullOrBlank()) {
        val f = expandPath(opts.chapterMp3Path!!)
        if (f.exists() && f.isFile) {
            try {
                f.readBytes()
            } catch (_: Exception) {
                ByteArray(0)
            }
        } else {
            ByteArray(0)
        }
    } else {
        ByteArray(0)
    }

    val specs = ArrayList<PartJobSpec>(total)
    var bookMetaIdx = 0
    for (i in 0 until total) {
        while (bookMetaIdx < activeBook.fileNames.size - 1 &&
            i >= activeBook.fileNames[bookMetaIdx].endSentenceIndex
        ) {
            bookMetaIdx++
        }
        val meta = activeBook.fileNames[bookMetaIdx]
        val glava = activeBook.allGlavs.getOrElse(i) { "" }
        val fileNum = "%04d".format(i + 1)
        specs.add(
            PartJobSpec(
                index = i,
                fileName = meta.fileName,
                glava = glava,
                fileNum = fileNum,
                text = activeBook.allSentences[i],
                voice = meta.voice,
                rate = meta.rate,
                pitch = meta.pitch
            )
        )
    }

    val chunkSpecs = ArrayList<ChunkJobSpec>()
    val partToChunkMap = arrayOfNulls<ChunkJobSpec>(total)
    val specsByBook = specs.groupBy { File(bookStem(it.fileName)).name.trim().ifEmpty { "Book" } }
    val multipleBooks = specsByBook.size > 1
    val bookMetaByFolder = activeBook.fileNames.associateBy(
        keySelector = { File(bookStem(it.fileName)).name.trim().ifEmpty { "Book" } },
        valueTransform = { it.metadata }
    )
    val bookVoiceByFolder = activeBook.fileNames.associateBy(
        keySelector = { File(bookStem(it.fileName)).name.trim().ifEmpty { "Book" } },
        valueTransform = { it.voice.ifBlank { activeVoiceDisplay } }
    )

    for ((bookFolderName, bookParts) in specsByBook) {
        val bookMeta = bookMetaByFolder[bookFolderName] ?: BookAudioMetadata(title = bookFolderName)
        val bookVoice = bookVoiceByFolder[bookFolderName] ?: activeVoiceDisplay
        val partsByGlava = bookParts.groupBy { it.glava }
        val rawChunksForBook = ArrayList<Triple<String, Int, List<PartJobSpec>>>()
        for ((glava, glavaParts) in partsByGlava) {
            val chunkSize = when {
                glava.isNotEmpty() -> glavaParts.size
                opts.mergeFiles <= 0 -> glavaParts.size
                else -> opts.mergeFiles
            }.coerceAtLeast(1)
            val chunked = glavaParts.chunked(chunkSize)
            for ((cIdx, cParts) in chunked.withIndex()) {
                rawChunksForBook.add(Triple(glava, cIdx, cParts))
            }
        }

        val totalChunksForBook = rawChunksForBook.size
        val chunksPerGlavaCount = rawChunksForBook.groupingBy { it.first }.eachCount()

        for ((bookChunkIdx, triple) in rawChunksForBook.withIndex()) {
            val (glava, cIdx, cParts) = triple
            val totalForGlava = chunksPerGlavaCount[glava] ?: 1
            val stripName = stripBookName(opts.bookDirCreate, saveDirFile.absolutePath, bookFolderName, glava)
            val targetBaseName = when {
                glava.isEmpty() -> {
                    val numStr = "%04d".format(cIdx + 1)
                    mp3BaseName(stripName, bookFolderName, "", numStr, merged = true)
                }
                totalForGlava == 1 -> {
                    mp3BaseName(stripName, bookFolderName, glava, "", merged = true)
                }
                else -> {
                    val numStr = "%04d".format(cIdx + 1)
                    mp3BaseName(stripName, bookFolderName, glava, numStr, merged = true)
                }
            }
            val cSpec = ChunkJobSpec(
                bookFolderName = bookFolderName,
                glava = glava,
                chunkIdx = cIdx,
                totalChunksForGlava = totalForGlava,
                totalChunksForBook = totalChunksForBook,
                bookChunkNumber = bookChunkIdx + 1,
                targetBaseName = targetBaseName,
                includeGlavaIntro = (cIdx == 0 && glava.isNotEmpty() && soundGlavaBytes.isNotEmpty()),
                fileNums = cParts.map { it.fileNum },
                remainingParts = AtomicInteger(cParts.size),
                bookMetadata = bookMeta,
                voice = bookVoice
            )
            chunkSpecs.add(cSpec)
            for (p in cParts) {
                partToChunkMap[p.index] = cSpec
            }
        }
    }

    val totalChunksCount = chunkSpecs.size
    val workTempRoot = Files.createTempDirectory("hedgehog_cli_work_").toFile()

    if (!opts.quiet) {
        val modeLabel = if (opts.offlineMode) {
            "OFFLINE (${offlineInstalled?.folderName}, $effectiveThreads threads)"
        } else {
            "ONLINE ($resolvedOnlineVoice, $effectiveThreads threads)"
        }
        val fmtLabel = when {
            targetWavDirect -> "WAV"
            opts.opusOutput -> "OPUS (32 kbps)"
            opts.lowBitrate -> "MP3 (48 kbps)"
            else -> "MP3 (96 kbps)"
        }
        System.err.println("Hedgehog CLI: $total parts • $totalChunksCount chapters/chunks • Mode: $modeLabel • Format: $fmtLabel")
    }

    val savedPartsAtomic = AtomicInteger(0)
    val postprocessedChunksAtomic = AtomicInteger(0)
    val launchedAtomic = AtomicInteger(0)
    val failedAtomic = AtomicInteger(0)
    val mergeMutex = Mutex()
    val outputFiles = mutableListOf<File>()
    val timeoutMsec = if (opts.timeoutSec <= 0) Long.MAX_VALUE else opts.timeoutSec * 1000L

    fun reportProgress() {
        if (opts.quiet) return
        val doneParts = savedPartsAtomic.get()
        val doneChunks = postprocessedChunksAtomic.get()
        val pRatio = doneParts.toDouble() / total.toDouble()
        val cRatio = doneChunks.toDouble() / totalChunksCount.coerceAtLeast(1).toDouble()
        val pct = ((0.5 * (pRatio + cRatio)) * 100.0).roundToInt().coerceIn(0, 100)
        System.err.print("\rProgress: parts $doneParts/$total • chapters $doneChunks/$totalChunksCount ($pct%)   ")
        System.err.flush()
    }

    try {
        kotlinx.coroutines.coroutineScope {
            val childJobs = mutableListOf<Job>()
            while (isActive && savedPartsAtomic.get() < total && failedAtomic.get() == 0) {
                val savedCount = savedPartsAtomic.get()
                val maxWindow = savedCount + effectiveThreads
                val nextIdx = launchedAtomic.get()
                if (nextIdx < maxWindow && nextIdx < total) {
                    launchedAtomic.incrementAndGet()
                    val spec = specs[nextIdx]
                    val job = launch(Dispatchers.IO) {
                        var partData: ByteArray? = null
                        var partExt = "mp3"
                        val hasOfflineFallback = opts.allowOfflineFallback && offlineInstalled != null

                        if (!opts.offlineMode) {
                            partData = SocketEdgeTtsWorker.synthesizePart(
                                voice = resolvedOnlineVoice,
                                pitch = spec.pitch,
                                rate = spec.rate,
                                text = spec.text,
                                lowBitrate = opts.lowBitrate,
                                timeoutMsec = timeoutMsec,
                                isPreview = false,
                                fallbackToOfflineOnError = hasOfflineFallback,
                                isPaused = { false },
                                isCancelled = { !isActive || failedAtomic.get() > 0 },
                                onStatusUpdate = {}
                            )
                        }

                        if ((partData == null || partData.isEmpty()) && isActive && failedAtomic.get() == 0) {
                            if (offlineInstalled != null && (opts.offlineMode || opts.allowOfflineFallback)) {
                                if (!opts.offlineMode && !opts.quiet) {
                                    System.err.println("\nWarning: Online synthesis failed for part ${spec.index + 1}, falling back to offline voice '${offlineInstalled.folderName}'.")
                                }
                                partData = OfflineTtsEngine.synthesizePart(
                                    voiceDir = offlineInstalled.dir,
                                    text = spec.text,
                                    rate = spec.rate,
                                    pitch = spec.pitch,
                                    lowBitrate = opts.lowBitrate,
                                    isPreview = false,
                                    isPaused = { false },
                                    isCancelled = { !isActive || failedAtomic.get() > 0 },
                                    onStatusUpdate = {}
                                )
                                partExt = "wav"
                            }
                        }

                        if (partData == null || partData.isEmpty()) {
                            failedAtomic.incrementAndGet()
                            return@launch
                        }

                        val bookTempDir = File(workTempRoot, File(bookStem(spec.fileName)).name.trim().ifEmpty { "Book" })
                        bookTempDir.mkdirs()
                        val partFile = File(bookTempDir, rawPartFileName(spec.glava, spec.fileNum, partExt))
                        FileOutputStream(partFile).use { it.write(partData) }

                        savedPartsAtomic.incrementAndGet()
                        reportProgress()

                        val chunkSpec = partToChunkMap[spec.index]
                        if (chunkSpec != null && chunkSpec.remainingParts.decrementAndGet() == 0) {
                            mergeMutex.withLock {
                                val outFile = assembleCliChunk(
                                    chunkSpec = chunkSpec,
                                    workTempRoot = workTempRoot,
                                    saveDirFile = saveDirFile,
                                    explicitOutFile = explicitOutFile,
                                    targetWavDirect = targetWavDirect,
                                    opts = opts,
                                    multipleBooks = multipleBooks,
                                    soundGlavaBytes = soundGlavaBytes
                                )
                                if (outFile != null) {
                                    outputFiles.add(outFile)
                                    postprocessedChunksAtomic.incrementAndGet()
                                    reportProgress()
                                } else {
                                    failedAtomic.incrementAndGet()
                                }
                            }
                        }
                    }
                    childJobs.add(job)
                } else {
                    delay(50)
                }
            }
            childJobs.forEach { it.join() }
        }
    } finally {
        workTempRoot.deleteRecursively()
    }

    if (!opts.quiet) {
        System.err.println()
    }

    if (failedAtomic.get() > 0) {
        System.err.println("Error: Synthesis or encoding failed.")
        return 1
    }

    if (!opts.quiet) {
        for (f in outputFiles) {
            println(f.absolutePath)
        }
    }
    return 0
}

private fun assembleCliChunk(
    chunkSpec: ChunkJobSpec,
    workTempRoot: File,
    saveDirFile: File,
    explicitOutFile: File?,
    targetWavDirect: Boolean,
    opts: CliOptions,
    multipleBooks: Boolean,
    soundGlavaBytes: ByteArray
): File? {
    val bookTempDir = File(workTempRoot, chunkSpec.bookFolderName)
    val outDir = if (chunkSpec.totalChunksForBook == 1 || (!opts.bookDirCreate && !multipleBooks)) {
        saveDirFile
    } else {
        File(saveDirFile, chunkSpec.bookFolderName)
    }
    outDir.mkdirs()

    val finalBaseName = if (chunkSpec.totalChunksForBook == 1) {
        chunkSpec.bookFolderName
    } else {
        chunkSpec.targetBaseName
    }

    val ext = when {
        targetWavDirect -> "wav"
        opts.opusOutput -> "opus"
        else -> "mp3"
    }

    val targetFile = if (explicitOutFile != null && chunkSpec.totalChunksForBook == 1 && !multipleBooks) {
        explicitOutFile.parentFile?.mkdirs()
        explicitOutFile
    } else {
        File(outDir, "$finalBaseName.$ext")
    }

    val partFiles = ArrayList<File>(chunkSpec.fileNums.size)
    var allWav = true
    for (fileNum in chunkSpec.fileNums) {
        val wavFile = File(bookTempDir, rawPartFileName(chunkSpec.glava, fileNum, "wav"))
        val mp3File = File(bookTempDir, rawPartFileName(chunkSpec.glava, fileNum, "mp3"))
        if (wavFile.exists()) {
            partFiles.add(wavFile)
        } else if (mp3File.exists()) {
            allWav = false
            partFiles.add(mp3File)
        }
    }

    if (partFiles.isEmpty()) return null

    if (targetWavDirect && allWav) {
        val mergedWav = OfflineTtsEngine.mergeWavFiles(partFiles, deleteAfterRead = true)
        if (mergedWav.isEmpty()) return null
        FileOutputStream(targetFile).use { it.write(mergedWav) }
        return targetFile
    }

    val targetKbps = if (opts.lowBitrate) 48 else 96
    val albumTitle = chunkSpec.bookMetadata.title.ifBlank { chunkSpec.bookFolderName }
    val cleanChapter = chunkSpec.glava.trim().replaceFirst(Regex("^\\d{4}\\s*"), "").trim()
    val trackTitle = when {
        cleanChapter.isNotEmpty() && chunkSpec.totalChunksForGlava > 1 ->
            "$cleanChapter (${chunkSpec.chunkIdx + 1}/${chunkSpec.totalChunksForGlava})"
        cleanChapter.isNotEmpty() -> cleanChapter
        chunkSpec.totalChunksForBook > 1 -> "$albumTitle - ${"%04d".format(chunkSpec.bookChunkNumber)}"
        else -> albumTitle
    }

    if (allWav) {
        val mergedWav = OfflineTtsEngine.mergeWavFiles(partFiles, deleteAfterRead = true)
        if (mergedWav.isEmpty()) return null

        if (opts.opusOutput) {
            val opusBytes = OpusConverter.convertMp3ToOpus(mergedWav)
            if (opusBytes == null || opusBytes.isEmpty()) return null
            val finalOpus = if (opts.embedMetadata) {
                AudioMetadataTagger.tagOpus(
                    opusBytes = opusBytes,
                    bookMeta = chunkSpec.bookMetadata,
                    trackTitle = trackTitle,
                    albumTitle = albumTitle,
                    trackNumber = chunkSpec.bookChunkNumber,
                    totalTracks = chunkSpec.totalChunksForBook,
                    voiceName = chunkSpec.voice
                )
            } else {
                opusBytes
            }
            FileOutputStream(targetFile).use { it.write(finalOpus) }
            return targetFile
        } else {
            val encodedMp3 = OpusConverter.convertWavToMp3(mergedWav, targetKbps) ?: return null
            val assembledMp3 = if (chunkSpec.includeGlavaIntro && soundGlavaBytes.isNotEmpty()) {
                val buf = ByteArrayOutputStream(soundGlavaBytes.size + encodedMp3.size)
                buf.write(soundGlavaBytes)
                buf.write(encodedMp3)
                buf.toByteArray()
            } else {
                encodedMp3
            }
            val finalMp3 = if (opts.embedMetadata) {
                AudioMetadataTagger.tagMp3(
                    mp3Bytes = assembledMp3,
                    bookMeta = chunkSpec.bookMetadata,
                    trackTitle = trackTitle,
                    albumTitle = albumTitle,
                    trackNumber = chunkSpec.bookChunkNumber,
                    totalTracks = chunkSpec.totalChunksForBook,
                    voiceName = chunkSpec.voice
                )
            } else {
                assembledMp3
            }
            FileOutputStream(targetFile).use { it.write(finalMp3) }
            return targetFile
        }
    }

    val mergeBuffer = ByteArrayOutputStream()
    if (chunkSpec.includeGlavaIntro && soundGlavaBytes.isNotEmpty()) {
        mergeBuffer.write(soundGlavaBytes)
    }
    for (pFile in partFiles) {
        if (pFile.exists()) {
            val raw = pFile.readBytes()
            pFile.delete()
            val bytes = if (pFile.extension.equals("wav", ignoreCase = true)) {
                OpusConverter.convertWavToMp3(raw, targetKbps) ?: return null
            } else {
                raw
            }
            mergeBuffer.write(bytes)
        }
    }
    val assembledMp3 = mergeBuffer.toByteArray()
    if (assembledMp3.isEmpty()) return null

    if (opts.opusOutput) {
        val opusBytes = OpusConverter.convertMp3ToOpus(assembledMp3) ?: return null
        val finalOpus = if (opts.embedMetadata) {
            AudioMetadataTagger.tagOpus(
                opusBytes = opusBytes,
                bookMeta = chunkSpec.bookMetadata,
                trackTitle = trackTitle,
                albumTitle = albumTitle,
                trackNumber = chunkSpec.bookChunkNumber,
                totalTracks = chunkSpec.totalChunksForBook,
                voiceName = chunkSpec.voice
            )
        } else {
            opusBytes
        }
        FileOutputStream(targetFile).use { it.write(finalOpus) }
        return targetFile
    } else {
        val finalMp3 = if (opts.embedMetadata) {
            AudioMetadataTagger.tagMp3(
                mp3Bytes = assembledMp3,
                bookMeta = chunkSpec.bookMetadata,
                trackTitle = trackTitle,
                albumTitle = albumTitle,
                trackNumber = chunkSpec.bookChunkNumber,
                totalTracks = chunkSpec.totalChunksForBook,
                voiceName = chunkSpec.voice
            )
        } else {
            assembledMp3
        }
        FileOutputStream(targetFile).use { it.write(finalMp3) }
        return targetFile
    }
}
