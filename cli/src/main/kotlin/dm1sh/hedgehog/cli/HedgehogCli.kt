package dm1sh.hedgehog.cli

import dm1sh.hedgehog.book.AudioMetadataTagger
import dm1sh.hedgehog.book.BookAudioMetadata
import dm1sh.hedgehog.book.BookConversionIndex
import dm1sh.hedgehog.book.ProcessingFile
import dm1sh.hedgehog.book.TextsConverter
import dm1sh.hedgehog.model.DefaultVoices
import dm1sh.hedgehog.tts.OfflineInstaller
import dm1sh.hedgehog.tts.OfflineTtsEngine
import dm1sh.hedgehog.tts.OpusConverter
import dm1sh.hedgehog.tts.SecMsGecGenerator
import dm1sh.hedgehog.tts.SocketEdgeTtsWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.system.exitProcess

private const val DEFAULT_CHAPTER_REGEX = "глава |раздел |эпизод |рассказ |chapter "
/* Mirrors the Android app defaults so both frontends stream identically. */
private const val DEFAULT_MAX_THREADS = 50
private const val MAX_THREADS_LIMIT = 100
private const val DEFAULT_STALL_TIMEOUT_SEC = 10
private const val HEX_DIGITS = "0123456789abcdef"
private val LEGACY_PART_EXTS = arrayOf("wav", "mp3")
private val PART_FILE_EXTS = arrayOf("wav", "mp3", "opus")
private val NON_OPUS_PART_EXTS = listOf("wav", "mp3")
private val glavaIntroOpusLock = Any()
private val BOOK_EXTENSIONS = setOf("txt", "fb2", "epub", "html", "htm", "xml", "ini", "zip")
private val AUDIO_EXTENSIONS = setOf("mp3", "opus", "wav")
private val TOKEN_VOICE_REGEX = Regex("value=\"(Microsoft Server Speech Text to Speech Voice \\(([^,]+), ([^)]+)\\))\"")

@Volatile
private var interruptedByUser: Boolean = false

@Volatile
private var activeCliScopeJob: Job? = null

private val activeTempDirs = ConcurrentHashMap.newKeySet<File>()
private val cleanupDoneLatch = CountDownLatch(1)

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
    var wavOutput: Boolean = false,
    var lowBitrate: Boolean = false,
    var embedMetadata: Boolean = true,
    /* Same default as the Android app's "Create book folders" setting. */
    var bookDirCreate: Boolean = true,
    var rate: Int = 0,
    var pitch: Int = 0,
    /* Same default as the app (AppSettings.DEFAULT_MAX_THREADS). */
    var maxThreads: Int = DEFAULT_MAX_THREADS,
    var threadsExplicitlySet: Boolean = false,
    var mergeFiles: Int = 0,
    var mergeFilesExplicitlySet: Boolean = false,
    /* Idle (stall) timeout per stream, same "restart delay" setting as the app; <= 0 = infinite. */
    var timeoutSec: Int = DEFAULT_STALL_TIMEOUT_SEC,
    var downloadOfflineVoices: Boolean = false,
    val downloadVoiceNames: MutableList<String> = mutableListOf(),
    var listVoices: Boolean = false,
    var listVoicesFilter: String? = null,
    var configPath: File? = null,
    var saveConfig: Boolean = false,
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
    val textHash: String,
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
    val partIndices: List<Int>,
    val chapterTextHash: String,
    val remainingParts: AtomicInteger,
    val bookMetadata: BookAudioMetadata,
    val voice: String
)

fun main(args: Array<String>) {
    val shutdownHook = Thread({
        interruptedByUser = true
        OfflineTtsEngine.cancelAllActive()
        activeCliScopeJob?.cancel()
        try {
            cleanupDoneLatch.await(1500, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
        }
        for (dir in activeTempDirs) {
            try {
                dir.deleteRecursively()
            } catch (_: Throwable) {
            }
        }
    }, "hedgehog-cli-shutdown")
    Runtime.getRuntime().addShutdownHook(shutdownHook)

    var exitCode = 0
    try {
        runBlocking {
            activeCliScopeJob = coroutineContext[Job]
            val opts = parseArgs(args)

            if (opts.showVersion) {
                val ver = object {}.javaClass.`package`?.implementationVersion ?: "1.3.4"
                println("hedgehog-cli $ver")
                return@runBlocking
            }

            if (opts.showHelp) {
                printHelp()
                return@runBlocking
            }

            if (opts.saveConfig) {
                val targetCfg = opts.configPath ?: defaultConfigFile()
                if (!saveConfigFromOptions(opts, targetCfg)) {
                    exitCode = 1
                    return@runBlocking
                }
                if (!opts.quiet) {
                    println("Saved configuration to ${targetCfg.absolutePath}")
                }
                if (!opts.downloadOfflineVoices && !opts.listVoices && opts.inputs.isEmpty() && opts.inlineText == null) {
                    return@runBlocking
                }
            }

            if (opts.downloadOfflineVoices) {
                val code = runDownloadOfflineVoices(opts)
                if (!opts.listVoices && opts.inputs.isEmpty() && opts.inlineText == null) {
                    exitCode = code
                    return@runBlocking
                }
            }

            if (opts.listVoices) {
                runListVoices(opts)
                if (opts.inputs.isEmpty() && opts.inlineText == null) {
                    return@runBlocking
                }
            }

            exitCode = runConversion(opts)
        }
    } catch (_: CancellationException) {
        exitCode = 130
    } finally {
        for (dir in activeTempDirs) {
            try {
                dir.deleteRecursively()
            } catch (_: Throwable) {
            }
        }
        cleanupDoneLatch.countDown()
    }

    if (!interruptedByUser) {
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook)
        } catch (_: Throwable) {
        }
        exitProcess(exitCode)
    }
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

private fun defaultConfigFile(): File {
    val xdg = System.getenv("XDG_CONFIG_HOME")?.trim().orEmpty()
    val baseDir = if (xdg.isNotEmpty()) File(xdg) else File(System.getProperty("user.home"), ".config")
    return File(File(baseDir, "hedgehog"), "config.ini")
}

private fun parseBoolConfig(raw: String): Boolean {
    return when (raw.trim().lowercase(Locale.ROOT)) {
        "1", "true", "yes", "on" -> true
        else -> false
    }
}

private fun loadConfigIntoOptions(configFile: File, opts: CliOptions) {
    if (!configFile.exists() || !configFile.isFile) return
    try {
        for (rawLine in configFile.readLines(Charsets.UTF_8)) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";") || line.startsWith("[")) {
                continue
            }
            val eqIdx = line.indexOf('=')
            if (eqIdx <= 0) continue
            val key = line.substring(0, eqIdx).trim().lowercase(Locale.ROOT).replace('-', '_')
            val value = line.substring(eqIdx + 1).trim().removeSurrounding("\"").removeSurrounding("'")
            when (key) {
                "output", "output_dir" -> if (value.isNotEmpty()) opts.outputPath = value
                "offline", "offline_mode" -> opts.offlineMode = parseBoolConfig(value)
                "allow_offline_fallback", "fallback" -> opts.allowOfflineFallback = parseBoolConfig(value)
                "voice", "online_voice" -> if (value.isNotEmpty()) opts.onlineVoice = value
                "offline_voice" -> opts.offlineVoice = value.ifEmpty { null }
                "voices_dir" -> if (value.isNotEmpty()) opts.voicesDir = expandPath(value)
                "lexx", "lexx_path" -> opts.lexxPath = value.ifEmpty { null }
                "lexx_register" -> opts.lexxRegister = parseBoolConfig(value)
                "chapters" -> opts.chapters = parseBoolConfig(value)
                "chapter_title" -> opts.chapterTitleSpeak = parseBoolConfig(value)
                "chapter_regex" -> if (value.isNotEmpty()) opts.chapterRegex = value
                "chapter_mp3" -> opts.chapterMp3Path = value.ifEmpty { null }
                "inline_notes" -> opts.inlineNotes = parseBoolConfig(value)
                "filter_emojis" -> opts.filterEmojis = parseBoolConfig(value)
                "opus", "opus_output" -> opts.opusOutput = parseBoolConfig(value)
                "wav", "wav_output" -> opts.wavOutput = parseBoolConfig(value)
                "low_bitrate" -> opts.lowBitrate = parseBoolConfig(value)
                "embed_metadata" -> opts.embedMetadata = parseBoolConfig(value)
                "book_dir" -> opts.bookDirCreate = parseBoolConfig(value)
                "rate", "speed" -> value.let {
                    runCatching { parseSignedNumber(it).coerceIn(-50, 100) }.getOrNull()?.let { r -> opts.rate = r }
                }
                "pitch" -> value.let {
                    runCatching { parseSignedNumber(it).coerceIn(-50, 50) }.getOrNull()?.let { p -> opts.pitch = p }
                }
                "threads", "max_threads" -> value.toIntOrNull()?.coerceIn(1, MAX_THREADS_LIMIT)?.let { t ->
                    opts.maxThreads = t
                    opts.threadsExplicitlySet = true
                }
                "merge_files" -> value.toIntOrNull()?.coerceAtLeast(0)?.let { m ->
                    opts.mergeFiles = m
                    opts.mergeFilesExplicitlySet = true
                }
                "timeout", "timeout_sec", "restart_delay" -> value.toIntOrNull()?.let { t -> opts.timeoutSec = t }
                "quiet" -> opts.quiet = parseBoolConfig(value)
            }
        }
    } catch (e: Exception) {
        System.err.println("Warning: Failed to read config file '${configFile.absolutePath}': ${e.message}")
    }
}

private fun saveConfigFromOptions(opts: CliOptions, targetFile: File): Boolean {
    return try {
        val content = buildString {
            appendLine("# Hedgehog CLI configuration file")
            appendLine("offline = ${opts.offlineMode}")
            appendLine("allow_offline_fallback = ${opts.allowOfflineFallback}")
            appendLine("voice = \"${opts.onlineVoice}\"")
            appendLine("offline_voice = \"${opts.offlineVoice.orEmpty()}\"")
            appendLine("voices_dir = \"${opts.voicesDir.path}\"")
            appendLine("output = \"${opts.outputPath.orEmpty()}\"")
            appendLine("lexx = \"${opts.lexxPath.orEmpty()}\"")
            appendLine("lexx_register = ${opts.lexxRegister}")
            appendLine("chapters = ${opts.chapters}")
            appendLine("chapter_title = ${opts.chapterTitleSpeak}")
            appendLine("chapter_regex = \"${opts.chapterRegex}\"")
            appendLine("chapter_mp3 = \"${opts.chapterMp3Path.orEmpty()}\"")
            appendLine("inline_notes = ${opts.inlineNotes}")
            appendLine("filter_emojis = ${opts.filterEmojis}")
            appendLine("opus = ${opts.opusOutput}")
            appendLine("wav = ${opts.wavOutput}")
            appendLine("low_bitrate = ${opts.lowBitrate}")
            appendLine("embed_metadata = ${opts.embedMetadata}")
            appendLine("book_dir = ${opts.bookDirCreate}")
            appendLine("rate = ${opts.rate}")
            appendLine("pitch = ${opts.pitch}")
            if (opts.threadsExplicitlySet) {
                appendLine("threads = ${opts.maxThreads}")
            }
            if (opts.mergeFilesExplicitlySet) {
                appendLine("merge_files = ${opts.mergeFiles}")
            }
            appendLine("# idle timeout in seconds (alias: restart_delay), same as the app; 0 = no timeout")
            appendLine("timeout = ${opts.timeoutSec}")
            appendLine("quiet = ${opts.quiet}")
        }
        BookConversionIndex.writeBytesAtomically(targetFile, content.toByteArray(Charsets.UTF_8))
    } catch (e: Exception) {
        System.err.println("Error: Failed to write config file '${targetFile.absolutePath}': ${e.message}")
        false
    }
}

private fun parseArgs(args: Array<String>): CliOptions {
    val opts = CliOptions()

    // First pre-scan for --config so custom config path is loaded before CLI overrides
    var explicitConfigFile: File? = null
    var scanIdx = 0
    while (scanIdx < args.size) {
        val a = args[scanIdx]
        if ((a == "--config" || a == "-c") && scanIdx + 1 < args.size) {
            explicitConfigFile = expandPath(args[scanIdx + 1])
            scanIdx++
        } else if (a.startsWith("--config=")) {
            explicitConfigFile = expandPath(a.substringAfter("="))
        }
        scanIdx++
    }
    val configToLoad = explicitConfigFile ?: defaultConfigFile()
    if (explicitConfigFile != null && !explicitConfigFile.exists()) {
        System.err.println("Error: Config file does not exist: ${explicitConfigFile.absolutePath}")
        exitProcess(2)
    }
    loadConfigIntoOptions(configToLoad, opts)
    opts.configPath = explicitConfigFile

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

            arg == "--opus" -> {
                opts.opusOutput = true
                opts.wavOutput = false
            }
            arg == "--mp3" -> {
                opts.opusOutput = false
                opts.wavOutput = false
            }
            arg == "--wav" -> {
                opts.wavOutput = true
                opts.opusOutput = false
            }
            arg == "--output-format" -> setOutputFormat(opts, requireNext(arg))
            arg.startsWith("--output-format=") -> setOutputFormat(opts, arg.substringAfter("="))
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
                opts.maxThreads = requireNext(arg).toIntOrNull()?.coerceIn(1, MAX_THREADS_LIMIT) ?: DEFAULT_MAX_THREADS
                opts.threadsExplicitlySet = true
            }
            arg.startsWith("--threads=") -> {
                opts.maxThreads = arg.substringAfter("=").toIntOrNull()?.coerceIn(1, MAX_THREADS_LIMIT) ?: DEFAULT_MAX_THREADS
                opts.threadsExplicitlySet = true
            }

            arg == "--merge-files" -> {
                opts.mergeFiles = requireNext(arg).toIntOrNull()?.coerceAtLeast(0) ?: 0
                opts.mergeFilesExplicitlySet = true
            }
            arg.startsWith("--merge-files=") -> {
                opts.mergeFiles = arg.substringAfter("=").toIntOrNull()?.coerceAtLeast(0) ?: 0
                opts.mergeFilesExplicitlySet = true
            }

            arg == "--timeout" || arg == "--restart-delay" -> {
                opts.timeoutSec = requireNext(arg).toIntOrNull() ?: DEFAULT_STALL_TIMEOUT_SEC
            }
            arg.startsWith("--timeout=") || arg.startsWith("--restart-delay=") -> {
                opts.timeoutSec = arg.substringAfter("=").toIntOrNull() ?: DEFAULT_STALL_TIMEOUT_SEC
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

            arg == "-c" || arg == "--config" -> {
                opts.configPath = expandPath(requireNext(arg))
            }
            arg.startsWith("--config=") -> {
                opts.configPath = expandPath(arg.substringAfter("="))
            }

            arg == "--save-config" || arg == "--generate-config" -> {
                opts.saveConfig = true
                if (i + 1 < args.size && !args[i + 1].startsWith("-")) {
                    i++
                    opts.configPath = expandPath(args[i])
                }
            }
            arg.startsWith("--save-config=") || arg.startsWith("--generate-config=") -> {
                opts.saveConfig = true
                val pathVal = arg.substringAfter("=").trim()
                if (pathVal.isNotEmpty()) {
                    opts.configPath = expandPath(pathVal)
                }
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

private fun setOutputFormat(opts: CliOptions, raw: String) {
    when (raw.trim().lowercase(Locale.ROOT)) {
        "mp3" -> {
            opts.opusOutput = false
            opts.wavOutput = false
        }
        "opus", "ogg" -> {
            opts.opusOutput = true
            opts.wavOutput = false
        }
        "wav", "pcm" -> {
            opts.wavOutput = true
            opts.opusOutput = false
        }
        else -> {
            System.err.println("Error: Unknown output format '$raw'. Use mp3, opus, or wav.")
            exitProcess(2)
        }
    }
}

private fun printHelp() {
    println(
        """
        Usage: hedgehog-cli [options]

        Input / Output:
          -i, --input <path>               Input book/text file (.fb2, .epub, .txt, .html, .zip), directory, or '-' for stdin
          -t, --text <text>                Synthesize plain text string directly
          -o, --output <path>              Save folder (like the Save folder setting in the app) or a single output
                                           file such as book.mp3, book.opus, or book.wav (whole book in one file)

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
          --wav                            Write raw WAV audio (offline voices only)
          --output-format <mp3|opus|wav>   Select the output format explicitly (folder output only;
                                           a file given to -o selects the format by its extension)
          --book-dir / --no-book-dir       Put multi-chapter books into their own folder (default: on, as in the app)
          --low-bitrate                    Use 48 kbps instead of 96 kbps for MP3 output
          --rate, --speed <percent>        Speech rate adjustment from -50 to +100 (default: +0)
          --pitch <hz>                     Speech pitch adjustment from -50 to +50 (default: +0)
          --no-embed-metadata              Disable embedding title, author, track tags, and cover art
          -j, --threads <n>                Maximum parallel synthesis streams (default: 50, same as the app;
                                           lower it when the endpoint keeps resetting streams)
          --timeout, --restart-delay <sec>  Restart a stream when it stays silent for <sec> seconds (default: 10, same as the app; 0 = no timeout)
                                          Network errors and timeouts are reported on stderr
          --merge-files <n>                Parts per merged output file when --chapters is off (default: 0 = whole book in 1)
          -c, --config <file>              Load configuration file (default: ~/.config/hedgehog/config.ini)
          --save-config [file]             Generate/save configuration file from the passed CLI arguments
          -q, --quiet                      Suppress progress output
          -h, --help                       Show this help message
          -V, --version                    Show version

        Examples:
          # Convert a book using an online voice
          hedgehog-cli -i book.fb2 -o ./out --voice "ru-RU, DmitryNeural" --lexx rules.lexx --chapters --inline-notes --opus

          # Convert text or a book completely offline
          hedgehog-cli -i book.epub -o ./out --offline --offline-voice Dmitry --voices-dir ~/EdgeTTS/Voices

          # Merge the whole book into one output file (format taken from the extension)
          hedgehog-cli -i book.fb2 -o "Book.opus" --voice "ru-RU, DmitryNeural"

          # Read from stdin / plain text into the save folder
          echo "Привет, мир!" | hedgehog-cli -i - --offline --offline-voice Dmitry -o ./out --mp3

          # Generate default config file from CLI arguments
          hedgehog-cli --offline --offline-voice Dmitry --voices-dir ~/EdgeTTS/Voices --chapters --opus --save-config

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

private fun rawPartFileName(glava: String, fileNum: String, ext: String): String {
    val cleanGlava = glava.trim()
    return if (cleanGlava.isEmpty()) {
        ".part_c${fileNum}.$ext"
    } else {
        ".part_${cleanGlava}_c${fileNum}.$ext"
    }
}

private fun sha256HexBytes(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    val sb = StringBuilder(digest.size * 2)
    for (b in digest) {
        val v = b.toInt() and 0xFF
        sb.append(HEX_DIGITS[v ushr 4]).append(HEX_DIGITS[v and 0x0F])
    }
    return sb.toString()
}

/**
 * Encodes the shared chapter intro (glav.mp3) to Ogg Opus once per book work directory so that
 * every chapter can stream-copy it instead of decoding and re-encoding it.
 */
private fun ensureGlavaIntroOpusFile(dir: File, introBytes: ByteArray): File? {
    if (introBytes.isEmpty()) return null
    val outFile = File(dir, ".glav_intro_${sha256HexBytes(introBytes).take(16)}.opus")
    if (outFile.exists() && outFile.length() > 0L) return outFile
    synchronized(glavaIntroOpusLock) {
        if (outFile.exists() && outFile.length() > 0L) return outFile
        val opus = OpusConverter.convertMp3ToOpus(introBytes) ?: return null
        if (opus.isEmpty()) return null
        return if (BookConversionIndex.writeBytesAtomically(outFile, opus)) outFile else null
    }
}

/**
 * Returns the `.part` file for [fileNum] as Ogg Opus, transcoding a legacy `.wav`/`.mp3` part
 * once (for folders produced by older versions) and replacing it with `.part_...opus`.
 */
private fun resolveOpusPartFile(
    glava: String,
    fileNum: String,
    bookWorkDir: File,
    bookIndex: BookConversionIndex? = null
): File? {
    val opusFile = File(bookWorkDir, rawPartFileName(glava, fileNum, "opus"))
    if (opusFile.exists() && opusFile.length() > 0L) return opusFile
    for (legacyExt in LEGACY_PART_EXTS) {
        val legacyFile = File(bookWorkDir, rawPartFileName(glava, fileNum, legacyExt))
        if (!legacyFile.exists() || legacyFile.length() <= 0L) continue
        val bytes = try {
            legacyFile.readBytes()
        } catch (_: Throwable) {
            continue
        }
        val opus = OpusConverter.convertMp3ToOpus(bytes) ?: return null
        if (opus.isEmpty() || !BookConversionIndex.writeBytesAtomically(opusFile, opus)) return null
        bookIndex?.replacePartFile(
            partKey = BookConversionIndex.partKey(glava, fileNum),
            fileName = opusFile.name,
            ext = "opus"
        )
        try {
            legacyFile.delete()
        } catch (_: Throwable) {
        }
        return opusFile
    }
    return null
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
        System.err.println("Error: No input provided. Pass -i <file>, -i - for stdin, or -t <text>. Use --help for usage.")
        return null
    }

    if (readStdin) {
        val stdinText = System.`in`.bufferedReader(Charsets.UTF_8).readText().trim()
        if (stdinText.isEmpty() && currentBook == null) {
            System.err.println("Error: Stdin is empty.")
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
                    val loaded = TextsConverter.loadFb2OrXmlFromBytes(
                        bytes = file.readBytes(),
                        fallbackName = baseName,
                        forceFb2 = (ext == "fb2"),
                        inlineNotes = opts.inlineNotes,
                        embedMetadata = opts.embedMetadata
                    )
                    appendEntry(loaded.bookName, loaded.text, loaded.metadata)
                }
                "html", "htm" -> {
                    val raw = TextsConverter.readTextWithFallback(file.readBytes())
                    val text = org.jsoup.Jsoup.parse(raw).text()
                    appendEntry(baseName, text, BookAudioMetadata(title = baseName))
                }
                "epub" -> {
                    val loaded = TextsConverter.loadEpubBook(
                        epubPath = file.absolutePath,
                        fallbackName = baseName,
                        inlineNotes = opts.inlineNotes,
                        embedMetadata = opts.embedMetadata
                    )
                    appendEntry(loaded.bookName, loaded.text, loaded.metadata)
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
    val outCandidate = if (outRaw.isNotEmpty()) expandPath(outRaw) else null

    /*
     * `-o/--output` behaves like the Android "Save folder" setting when it points at a folder,
     * and like an explicit output file when it carries a .mp3/.opus/.wav extension. In the file
     * case the whole book is written into that single file.
     */
    val explicitOutFile: File? = outCandidate?.takeIf {
        !it.isDirectory && AUDIO_EXTENSIONS.contains(it.extension.lowercase(Locale.ROOT))
    }
    if (explicitOutFile != null) {
        when (explicitOutFile.extension.lowercase(Locale.ROOT)) {
            "opus" -> {
                opts.opusOutput = true
                opts.wavOutput = false
            }
            "wav" -> {
                opts.wavOutput = true
                opts.opusOutput = false
            }
            else -> {
                opts.opusOutput = false
                opts.wavOutput = false
            }
        }
        if (opts.chapters) {
            System.err.println(
                "Error: --chapters splits the book into one file per chapter and cannot be used with " +
                    "a single output file ('${explicitOutFile.name}').\n" +
                    "Pass a save folder instead (e.g. -o ./out) or drop --chapters."
            )
            return 1
        }
        if (opts.mergeFilesExplicitlySet && opts.mergeFiles > 0) {
            System.err.println(
                "Error: --merge-files ${opts.mergeFiles} splits the book into several files and cannot be " +
                    "used with a single output file ('${explicitOutFile.name}').\n" +
                    "Pass a save folder instead (e.g. -o ./out), or use --merge-files 0."
            )
            return 1
        }
    }

    val targetWavDirect = opts.wavOutput
    val saveDirFile: File = when {
        explicitOutFile != null -> explicitOutFile.absoluteFile.parentFile ?: File(".")
        outCandidate != null -> outCandidate
        else -> File(".")
    }
    if (outCandidate != null && explicitOutFile == null && outCandidate.exists() && !outCandidate.isDirectory) {
        System.err.println(
            "Error: --output must be a save folder or an audio file name, but " +
                "'${outCandidate.absolutePath}' is not a folder.\n" +
                "Use a folder (e.g. -o ./out) or an audio file with a .mp3/.opus/.wav extension."
        )
        return 1
    }
    if (!saveDirFile.exists() && !saveDirFile.mkdirs()) {
        System.err.println("Error: Cannot create output directory: ${saveDirFile.absolutePath}")
        return 1
    }

    if (targetWavDirect && !opts.offlineMode) {
        System.err.println(
            "Error: WAV output requires --offline (online voices synthesize MP3 audio).\n" +
                "Use --offline, or save as MP3/Opus instead."
        )
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

    /* Same as the app: the thread count is passed through and OfflineTtsEngine bounds it to CPU cores. */
    val effectiveThreads = opts.maxThreads
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
        val partText = activeBook.allSentences[i]
        specs.add(
            PartJobSpec(
                index = i,
                fileName = meta.fileName,
                glava = glava,
                fileNum = fileNum,
                text = partText,
                textHash = BookConversionIndex.sha256Hex(partText),
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
    if (explicitOutFile != null && multipleBooks) {
        System.err.println(
            "Error: a single output file cannot receive several books " +
                "(${specsByBook.size} inputs). Pass a save folder instead (e.g. -o ./out)."
        )
        return 1
    }
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
        val rawChunksForBook = ArrayList<Triple<String, Int, List<PartJobSpec>>>()
        if (explicitOutFile != null) {
            /* Single output file: the whole book becomes one chunk. */
            rawChunksForBook.add(Triple("", 0, bookParts))
        } else {
            val partsByGlava = bookParts.groupBy { it.glava }
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
        }

        val totalChunksForBook = rawChunksForBook.size
        val chunksPerGlavaCount = rawChunksForBook.groupingBy { it.first }.eachCount()

        for ((bookChunkIdx, triple) in rawChunksForBook.withIndex()) {
            val (glava, cIdx, cParts) = triple
            val totalForGlava = chunksPerGlavaCount[glava] ?: 1
            val partNumberStr = "%04d".format(cIdx + 1)
            val targetBaseName = if (glava.isEmpty()) {
                "c$partNumberStr"
            } else if (totalForGlava == 1) {
                glava
            } else {
                "${glava}_c$partNumberStr"
            }
            val cSpec = ChunkJobSpec(
                bookFolderName = bookFolderName,
                glava = glava,
                chunkIdx = cIdx,
                totalChunksForGlava = totalForGlava,
                totalChunksForBook = totalChunksForBook,
                bookChunkNumber = bookChunkIdx + 1,
                targetBaseName = targetBaseName,
                /* A single output file gets the chapter jingle once, at its beginning. */
                includeGlavaIntro = if (explicitOutFile != null) {
                    soundGlavaBytes.isNotEmpty()
                } else {
                    cIdx == 0 && glava.isNotEmpty() && soundGlavaBytes.isNotEmpty()
                },
                fileNums = cParts.map { it.fileNum },
                partIndices = cParts.map { it.index },
                chapterTextHash = BookConversionIndex.computeChapterTextHash(cParts.map { it.text }),
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

    val bookWorkDirs = HashMap<String, File>()
    val bookIndices = HashMap<String, BookConversionIndex>()
    val bookRemainingChunks = ConcurrentHashMap<String, AtomicInteger>()

    for (cSpec in chunkSpecs) {
        val bName = cSpec.bookFolderName
        if (!bookWorkDirs.containsKey(bName)) {
            val wDir = resolveCliBookOutDir(
                saveDirFile = saveDirFile,
                bookFolderName = bName,
                totalChunksForBook = cSpec.totalChunksForBook,
                bookDirCreate = opts.bookDirCreate,
                multipleBooks = multipleBooks
            )
            wDir.mkdirs()
            bookWorkDirs[bName] = wDir
            val idxFileName = if (multipleBooks && wDir == saveDirFile) {
                ".${bName}.hedgehog_index.json"
            } else {
                BookConversionIndex.INDEX_FILE_NAME
            }
            bookIndices[bName] = BookConversionIndex.load(wDir, idxFileName)
            bookRemainingChunks[bName] = AtomicInteger(cSpec.totalChunksForBook)
        }
    }

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
        val timeoutLabel = if (opts.timeoutSec <= 0) "default" else opts.timeoutSec.toString()
        System.err.println(
            "Hedgehog CLI: $total parts • $totalChunksCount chapters/chunks • Mode: $modeLabel • Format: $fmtLabel • " +
                "Streams: $timeoutLabel s idle timeout, retry backoff 4-30 s"
        )
    }

    val savedPartsAtomic = AtomicInteger(0)
    val postprocessedChunksAtomic = AtomicInteger(0)
    val newlySynthesizedPartsAtomic = AtomicInteger(0)
    val assembledChaptersCountAtomic = AtomicInteger(0)
    val totalChapterAssembleMsAtomic = AtomicLong(0L)
    val chapterAssemblySemaphore = Semaphore(Runtime.getRuntime().availableProcessors().coerceAtLeast(1))
    val launchedAtomic = AtomicInteger(0)
    val failedAtomic = AtomicInteger(0)
    val progressPrintLock = Any()
    val outputFiles = mutableListOf<File>()
    val networkDownReported = java.util.concurrent.atomic.AtomicBoolean(false)
    val streamErrorCount = AtomicInteger(0)

    /* Keeps the progress line readable while reporting stream diagnostics on stderr. */
    fun cliDiagnostic(message: String) {
        synchronized(progressPrintLock) {
            System.err.print("\r")
            System.err.println("Warning: $message")
            System.err.flush()
        }
    }

    val timeoutMsec = if (opts.timeoutSec <= 0) Long.MAX_VALUE else opts.timeoutSec * 1000L

    /* Pre-flight the endpoint once so the first workers reuse the cached probe result. */
    if (!opts.offlineMode && !SocketEdgeTtsWorker.isOnlineReachable(forceRefresh = true)) {
        networkDownReported.set(true)
        if (offlineInstalled != null && opts.allowOfflineFallback) {
            cliDiagnostic(
                "speech.platform.bing.com:443 is unreachable, using offline voice '${offlineInstalled.folderName}'"
            )
        } else {
            cliDiagnostic(
                "speech.platform.bing.com:443 is unreachable and no offline voice is installed, " +
                    "still trying online synthesis"
            )
        }
    }
    val startTimeNanos = System.nanoTime()
    val preCompletedParts = HashSet<Int>()

    for (cSpec in chunkSpecs) {
        val bName = cSpec.bookFolderName
        val wDir = bookWorkDirs[bName] ?: saveDirFile
        val bIndex = bookIndices[bName] ?: continue
        val targetFile = resolveCliChunkTargetFile(
            chunkSpec = cSpec,
            outDir = wDir,
            explicitOutFile = explicitOutFile,
            targetWavDirect = targetWavDirect,
            opts = opts
        )
        if (bIndex.isChapterReusableByHash(cSpec.targetBaseName, cSpec.chapterTextHash, targetFile)) {
            for (fileNum in cSpec.fileNums) {
                for (ext in PART_FILE_EXTS) {
                    File(wDir, rawPartFileName(cSpec.glava, fileNum, ext)).delete()
                }
            }
            cSpec.remainingParts.set(0)
            preCompletedParts.addAll(cSpec.partIndices)
            savedPartsAtomic.addAndGet(cSpec.partIndices.size)
            postprocessedChunksAtomic.incrementAndGet()
            synchronized(outputFiles) {
                outputFiles.add(targetFile)
            }
            if (bookRemainingChunks[bName]?.decrementAndGet() == 0) {
                bIndex.deleteIndexFile()
            }
        }
    }

    fun elapsedMillis(): Long = ((System.nanoTime() - startTimeNanos) / 1_000_000L).coerceAtLeast(0L)

    fun computeCombinedEtaMillis(doneParts: Int, doneChunks: Int, elapsedMs: Long): Long {
        val remParts = (total - doneParts).coerceAtLeast(0)
        val remChunks = (totalChunksCount - doneChunks).coerceAtLeast(0)
        if (remParts == 0 && remChunks == 0) return 0L

        val synthParts = newlySynthesizedPartsAtomic.get()
        val assembledChunks = assembledChaptersCountAtomic.get()
        val assembledChunksMs = totalChapterAssembleMsAtomic.get()

        if (synthParts <= 0 && assembledChunks <= 0) return -1L

        val avgPartMs = if (synthParts > 0) {
            elapsedMs.toDouble() / synthParts.toDouble()
        } else {
            0.0
        }
        val partsEtaMs = if (remParts > 0 && avgPartMs > 0.0) {
            (avgPartMs * remParts.toDouble()).roundToLong()
        } else {
            0L
        }

        val chaptersEtaMs = when {
            remChunks == 0 -> 0L
            assembledChunks > 0 -> {
                val avgChunkMs = (assembledChunksMs.toDouble() / assembledChunks.toDouble()).coerceAtLeast(50.0)
                (avgChunkMs * remChunks.toDouble()).roundToLong()
            }
            avgPartMs > 0.0 -> {
                val avgPartsPerChunk = total.toDouble() / totalChunksCount.coerceAtLeast(1).toDouble()
                (avgPartMs * 0.08 * avgPartsPerChunk * remChunks.toDouble()).roundToLong()
            }
            else -> 0L
        }

        return (partsEtaMs + chaptersEtaMs).coerceAtLeast(0L)
    }

    fun reportProgress() {
        if (opts.quiet) return
        val doneParts = savedPartsAtomic.get()
        val doneChunks = postprocessedChunksAtomic.get()
        val pRatio = doneParts.toDouble() / total.toDouble()
        val cRatio = doneChunks.toDouble() / totalChunksCount.coerceAtLeast(1).toDouble()
        val pct = ((0.5 * (pRatio + cRatio)) * 100.0).roundToInt().coerceIn(0, 100)
        val elapsedMs = elapsedMillis()
        val elapsedStr = formatClockDuration(elapsedMs)
        val etaMs = computeCombinedEtaMillis(doneParts, doneChunks, elapsedMs)
        val etaStr = if (etaMs >= 0L) formatClockDuration(etaMs) else "--:--"
        synchronized(progressPrintLock) {
            System.err.print("\rProgress: parts $doneParts/$total • chapters $doneChunks/$totalChunksCount ($pct%) • Elapsed: $elapsedStr • ETA: $etaStr   ")
            System.err.flush()
        }
    }

    reportProgress()
    var lastTickReportMs = 0L

    try {
        kotlinx.coroutines.coroutineScope {
            val masterScope = this
            val childJobs = java.util.Collections.synchronizedList(mutableListOf<Job>())
            while (isActive && !interruptedByUser && savedPartsAtomic.get() < total && failedAtomic.get() == 0) {
                val savedCount = savedPartsAtomic.get()
                val maxWindow = savedCount + effectiveThreads
                val nextIdx = launchedAtomic.get()
                if (nextIdx < maxWindow && nextIdx < total) {
                    launchedAtomic.incrementAndGet()
                    if (preCompletedParts.contains(nextIdx)) {
                        continue
                    }
                    val spec = specs[nextIdx]
                    val bookFolderName = File(bookStem(spec.fileName)).name.trim().ifEmpty { "Book" }
                    val bookWorkDir = bookWorkDirs[bookFolderName] ?: saveDirFile
                    val bookIndex = bookIndices[bookFolderName]
                    val partKey = BookConversionIndex.partKey(spec.glava, spec.fileNum)
                    val opusPartsExpected = opts.opusOutput && !targetWavDirect
                    val reusablePartFile = bookIndex?.getReusablePartByHash(
                        partKey = partKey,
                        expectedHash = spec.textHash,
                        partDir = bookWorkDir,
                        allowedExts = if (opusPartsExpected) null else NON_OPUS_PART_EXTS
                    )

                    if (reusablePartFile != null) {
                        savedPartsAtomic.incrementAndGet()
                        if (!interruptedByUser) {
                            reportProgress()
                        }
                        val chunkSpec = partToChunkMap[spec.index]
                        if (!interruptedByUser && chunkSpec != null && chunkSpec.remainingParts.decrementAndGet() == 0) {
                            val job = masterScope.launch(Dispatchers.IO) {
                                var chunkTookMs = 0L
                                val outFile = chapterAssemblySemaphore.withPermit {
                                    val chunkStartNs = System.nanoTime()
                                    val f = assembleCliChunk(
                                        chunkSpec = chunkSpec,
                                        bookWorkDir = bookWorkDir,
                                        bookIndex = bookIndex,
                                        explicitOutFile = explicitOutFile,
                                        targetWavDirect = targetWavDirect,
                                        opts = opts,
                                        soundGlavaBytes = soundGlavaBytes
                                    )
                                    chunkTookMs = ((System.nanoTime() - chunkStartNs) / 1_000_000L).coerceAtLeast(1L)
                                    f
                                }
                                if (outFile != null) {
                                    totalChapterAssembleMsAtomic.addAndGet(chunkTookMs)
                                    assembledChaptersCountAtomic.incrementAndGet()
                                    synchronized(outputFiles) {
                                        outputFiles.add(outFile)
                                    }
                                    postprocessedChunksAtomic.incrementAndGet()
                                    if (bookRemainingChunks[chunkSpec.bookFolderName]?.decrementAndGet() == 0) {
                                        bookIndex.deleteIndexFile()
                                    }
                                    if (!interruptedByUser) {
                                        reportProgress()
                                    }
                                } else if (!interruptedByUser) {
                                    failedAtomic.incrementAndGet()
                                }
                            }
                            childJobs.add(job)
                        }
                        continue
                    }

                    val job = masterScope.launch(Dispatchers.IO) {
                        var partData: ByteArray? = null
                        var partExt = "mp3"
                        /*
                         * Dozens of streams fail together when the endpoint throttles, so a part
                         * reports its first error and then every fifth retry instead of every one.
                         */
                        val partStreamErrors = AtomicInteger(0)
                        val hasOfflineFallback = opts.allowOfflineFallback && offlineInstalled != null

                        for (staleExt in PART_FILE_EXTS) {
                            File(bookWorkDir, rawPartFileName(spec.glava, spec.fileNum, staleExt)).delete()
                        }

                        /*
                         * Same gate as the app: only go online when the endpoint is actually reachable.
                         * Without an offline voice we still try, so no fallback path is lost.
                         */
                        var shouldUseOnline = !opts.offlineMode
                        if (shouldUseOnline) {
                            val reachable = SocketEdgeTtsWorker.isOnlineReachable()
                            if (!reachable) {
                                if (hasOfflineFallback) {
                                    shouldUseOnline = false
                                    if (networkDownReported.compareAndSet(false, true)) {
                                        cliDiagnostic(
                                            "speech.platform.bing.com:443 is unreachable, using offline voice " +
                                                "'${offlineInstalled?.folderName}'"
                                        )
                                    }
                                } else if (networkDownReported.compareAndSet(false, true)) {
                                    cliDiagnostic(
                                        "speech.platform.bing.com:443 is unreachable and no offline voice is " +
                                            "installed, still trying online synthesis"
                                    )
                                }
                            } else {
                                networkDownReported.set(false)
                            }
                        }

                        if (shouldUseOnline) {
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
                                isCancelled = { interruptedByUser || !isActive || failedAtomic.get() > 0 },
                                onStatusUpdate = {},
                                onDiagnostic = { msg ->
                                    val errors = partStreamErrors.incrementAndGet()
                                    streamErrorCount.incrementAndGet()
                                    if (errors == 1 || errors % 5 == 0) {
                                        cliDiagnostic("part ${spec.index + 1}: $msg")
                                    }
                                }
                            )
                            val retried = partStreamErrors.get()
                            if (retried > 0 && (partData?.isNotEmpty() == true)) {
                                cliDiagnostic("part ${spec.index + 1}: succeeded after $retried retried attempt(s)")
                            }
                        }

                        if ((partData == null || partData.isEmpty()) && isActive && !interruptedByUser && failedAtomic.get() == 0) {
                            if (offlineInstalled != null && (opts.offlineMode || opts.allowOfflineFallback)) {
                                if (!opts.offlineMode && !interruptedByUser) {
                                    cliDiagnostic(
                                        "part ${spec.index + 1}: online synthesis failed, " +
                                            "falling back to offline voice '${offlineInstalled.folderName}'"
                                    )
                                }
                                partData = OfflineTtsEngine.synthesizePart(
                                    voiceDir = offlineInstalled.dir,
                                    text = spec.text,
                                    rate = spec.rate,
                                    pitch = spec.pitch,
                                    lowBitrate = opts.lowBitrate,
                                    isPreview = false,
                                    isPaused = { false },
                                    isCancelled = { interruptedByUser || !isActive || failedAtomic.get() > 0 },
                                    onStatusUpdate = {}
                                )
                                partExt = "wav"
                            }
                        }

                        if (interruptedByUser || !isActive) {
                            return@launch
                        }

                        val rawPartData = partData
                        if (rawPartData == null || rawPartData.isEmpty()) {
                            failedAtomic.incrementAndGet()
                            return@launch
                        }

                        /* Encode the part to Ogg Opus right away: chapters become a cheap stream copy. */
                        val saveData: ByteArray = if (opts.opusOutput && !targetWavDirect) {
                            val opusData = OpusConverter.convertMp3ToOpus(rawPartData)
                            if (opusData != null && opusData.isNotEmpty()) {
                                partExt = "opus"
                                opusData
                            } else {
                                /* Keep the raw part: chapter assembly transcodes it later. */
                                if (!opts.quiet) {
                                    synchronized(progressPrintLock) {
                                        System.err.println(
                                            "\nWarning: Opus encoding failed for part ${spec.index + 1}, keeping .$partExt part."
                                        )
                                    }
                                }
                                rawPartData
                            }
                        } else {
                            rawPartData
                        }

                        bookWorkDir.mkdirs()
                        val partFile = File(bookWorkDir, rawPartFileName(spec.glava, spec.fileNum, partExt))
                        val savedOk = BookConversionIndex.writeBytesAtomically(
                            targetFile = partFile,
                            data = saveData,
                            shouldAbort = { interruptedByUser || !isActive }
                        )
                        if (!savedOk) {
                            if (!interruptedByUser && isActive) {
                                failedAtomic.incrementAndGet()
                            }
                            return@launch
                        }
                        bookIndex?.recordPartByHash(partKey, spec.textHash, partFile.name, partExt)

                        newlySynthesizedPartsAtomic.incrementAndGet()
                        savedPartsAtomic.incrementAndGet()
                        if (!interruptedByUser) {
                            reportProgress()
                        }

                        val chunkSpec = partToChunkMap[spec.index]
                        if (!interruptedByUser && chunkSpec != null && chunkSpec.remainingParts.decrementAndGet() == 0) {
                            val asmJob = masterScope.launch(Dispatchers.IO) {
                                var chunkTookMs = 0L
                                val outFile = chapterAssemblySemaphore.withPermit {
                                    val chunkStartNs = System.nanoTime()
                                    val f = assembleCliChunk(
                                        chunkSpec = chunkSpec,
                                        bookWorkDir = bookWorkDir,
                                        bookIndex = bookIndex,
                                        explicitOutFile = explicitOutFile,
                                        targetWavDirect = targetWavDirect,
                                        opts = opts,
                                        soundGlavaBytes = soundGlavaBytes
                                    )
                                    chunkTookMs = ((System.nanoTime() - chunkStartNs) / 1_000_000L).coerceAtLeast(1L)
                                    f
                                }
                                if (outFile != null) {
                                    totalChapterAssembleMsAtomic.addAndGet(chunkTookMs)
                                    assembledChaptersCountAtomic.incrementAndGet()
                                    synchronized(outputFiles) {
                                        outputFiles.add(outFile)
                                    }
                                    postprocessedChunksAtomic.incrementAndGet()
                                    if (bookRemainingChunks[chunkSpec.bookFolderName]?.decrementAndGet() == 0) {
                                        bookIndex?.deleteIndexFile()
                                    }
                                    if (!interruptedByUser) {
                                        reportProgress()
                                    }
                                } else if (!interruptedByUser) {
                                    failedAtomic.incrementAndGet()
                                }
                            }
                            childJobs.add(asmJob)
                        }
                    }
                    childJobs.add(job)
                    if (!opts.offlineMode) {
                        delay(100)
                    }
                } else {
                    delay(50)
                    val nowElapsed = elapsedMillis()
                    if (!interruptedByUser && nowElapsed - lastTickReportMs >= 1000L) {
                        lastTickReportMs = nowElapsed
                        reportProgress()
                    }
                }
            }
            if (interruptedByUser) {
                val snapshot = synchronized(childJobs) { childJobs.toList() }
                snapshot.forEach { it.cancel() }
            } else {
                while (synchronized(childJobs) { childJobs.any { it.isActive } } && !interruptedByUser) {
                    delay(50)
                    val nowElapsed = elapsedMillis()
                    if (nowElapsed - lastTickReportMs >= 1000L) {
                        lastTickReportMs = nowElapsed
                        reportProgress()
                    }
                }
                val snapshot = synchronized(childJobs) { childJobs.toList() }
                if (interruptedByUser) {
                    snapshot.forEach { it.cancel() }
                } else {
                    snapshot.forEach { it.join() }
                }
            }
        }
    } catch (_: CancellationException) {
    } finally {
        for (bIdx in bookIndices.values) {
            bIdx.flush()
        }
        /* Drop shared per-book chapter intros encoded to Opus. */
        for (wDir in bookWorkDirs.values.toSet()) {
            wDir.listFiles { f -> f.name.startsWith(".glav_intro_") }?.forEach { intro ->
                try {
                    intro.delete()
                } catch (_: Throwable) {
                }
            }
        }
    }

    val totalElapsedMs = elapsedMillis()
    val sortedOutputFiles = synchronized(outputFiles) {
        outputFiles.sortedBy { it.absolutePath }
    }

    if (interruptedByUser) {
        synchronized(progressPrintLock) {
            System.err.println(
                "\nInterrupted by user (Ctrl+C) after ${formatClockDuration(totalElapsedMs)} • " +
                    "saved ${sortedOutputFiles.size} completed file(s)."
            )
        }
        return 130
    }

    if (!opts.quiet) {
        reportProgress()
        System.err.println()
    }

    if (failedAtomic.get() > 0) {
        System.err.println("Error: Synthesis or encoding failed after ${formatClockDuration(totalElapsedMs)}.")
        return 1
    }

    if (!opts.quiet) {
        val streamErrorsTotal = streamErrorCount.get()
        if (streamErrorsTotal > 0) {
            System.err.println("Stream errors: $streamErrorsTotal retried attempt(s) in total.")
        }
        System.err.println(
            "Completed in ${formatClockDuration(totalElapsedMs)} • " +
                "$total parts • ${sortedOutputFiles.size} file(s)"
        )
        for (f in sortedOutputFiles) {
            println(f.absolutePath)
        }
    }
    return 0
}

private fun formatClockDuration(elapsedMs: Long): String {
    val totalSeconds = (elapsedMs / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(Locale.US, hours, minutes, seconds)
    } else {
        "%02d:%02d".format(Locale.US, minutes, seconds)
    }
}

private fun formatHumanDuration(elapsedMs: Long): String {
    if (elapsedMs < 60_000L) {
        return "%.1fs".format(Locale.US, elapsedMs / 1000.0)
    }
    val totalSeconds = elapsedMs / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "${hours}h ${minutes}m ${seconds}s"
    } else {
        "${minutes}m ${seconds}s"
    }
}

private fun resolveCliBookOutDir(
    saveDirFile: File,
    bookFolderName: String,
    totalChunksForBook: Int,
    bookDirCreate: Boolean,
    multipleBooks: Boolean
): File {
    return if (totalChunksForBook == 1 || (!bookDirCreate && !multipleBooks)) {
        saveDirFile
    } else {
        File(saveDirFile, bookFolderName)
    }
}

private fun resolveCliChunkTargetFile(
    chunkSpec: ChunkJobSpec,
    outDir: File,
    explicitOutFile: File?,
    targetWavDirect: Boolean,
    opts: CliOptions
): File {
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
    return explicitOutFile ?: File(outDir, "$finalBaseName.$ext")
}

private fun finalizeCliChunkOutput(
    targetFile: File,
    data: ByteArray,
    partFilesToDelete: List<File>,
    chunkSpec: ChunkJobSpec,
    bookIndex: BookConversionIndex?
): File? {
    val savedOk = BookConversionIndex.writeBytesAtomically(
        targetFile = targetFile,
        data = data,
        shouldAbort = { interruptedByUser }
    )
    if (!savedOk) return null
    finalizeCliPartBookkeeping(targetFile, partFilesToDelete, chunkSpec, bookIndex)
    return targetFile
}

/**
 * Atomically moves an already assembled chapter file into place (no JVM-heap copy) and records
 * the completed chapter in the resumable index.
 */
private fun finalizeCliChunkFile(
    targetFile: File,
    sourceFile: File,
    partFilesToDelete: List<File>,
    chunkSpec: ChunkJobSpec,
    bookIndex: BookConversionIndex?
): File? {
    if (!BookConversionIndex.moveFileAtomically(sourceFile, targetFile)) return null
    finalizeCliPartBookkeeping(targetFile, partFilesToDelete, chunkSpec, bookIndex)
    return targetFile
}

private fun finalizeCliPartBookkeeping(
    targetFile: File,
    partFilesToDelete: List<File>,
    chunkSpec: ChunkJobSpec,
    bookIndex: BookConversionIndex?
) {
    val mergedPartKeys = chunkSpec.fileNums.map { BookConversionIndex.partKey(chunkSpec.glava, it) }
    for (pFile in partFilesToDelete) {
        try {
            pFile.delete()
        } catch (_: Throwable) {
        }
    }
    bookIndex?.recordChapterCompletedByHash(
        chapterKey = chunkSpec.targetBaseName,
        chapterTextHash = chunkSpec.chapterTextHash,
        targetFileName = targetFile.name,
        mergedPartKeys = mergedPartKeys
    )
}

private fun assembleCliChunk(
    chunkSpec: ChunkJobSpec,
    bookWorkDir: File,
    bookIndex: BookConversionIndex?,
    explicitOutFile: File?,
    targetWavDirect: Boolean,
    opts: CliOptions,
    soundGlavaBytes: ByteArray
): File? {
    if (interruptedByUser) return null
    bookWorkDir.mkdirs()

    val targetFile = resolveCliChunkTargetFile(
        chunkSpec = chunkSpec,
        outDir = bookWorkDir,
        explicitOutFile = explicitOutFile,
        targetWavDirect = targetWavDirect,
        opts = opts
    )

    val partFiles = ArrayList<File>(chunkSpec.fileNums.size)
    var allWav = true
    if (opts.opusOutput && !targetWavDirect) {
        for (fileNum in chunkSpec.fileNums) {
            val opusFile = resolveOpusPartFile(chunkSpec.glava, fileNum, bookWorkDir, bookIndex) ?: return null
            partFiles.add(opusFile)
        }
        allWav = false
    } else {
        for (fileNum in chunkSpec.fileNums) {
            val wavFile = File(bookWorkDir, rawPartFileName(chunkSpec.glava, fileNum, "wav"))
            val mp3File = File(bookWorkDir, rawPartFileName(chunkSpec.glava, fileNum, "mp3"))
            if (wavFile.exists()) {
                partFiles.add(wavFile)
            } else if (mp3File.exists()) {
                allWav = false
                partFiles.add(mp3File)
            }
        }
    }

    if (partFiles.isEmpty()) return null

    if (targetWavDirect && allWav) {
        val mergedWav = OfflineTtsEngine.mergeWavFiles(partFiles, deleteAfterRead = false)
        if (mergedWav.isEmpty()) return null
        return finalizeCliChunkOutput(targetFile, mergedWav, partFiles, chunkSpec, bookIndex)
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

    val introBytes = if (chunkSpec.includeGlavaIntro && soundGlavaBytes.isNotEmpty()) soundGlavaBytes else ByteArray(0)

    if (opts.opusOutput && !targetWavDirect) {
        val opusInputs = ArrayList<String>(partFiles.size + 1)
        if (introBytes.isNotEmpty()) {
            val introFile = ensureGlavaIntroOpusFile(bookWorkDir, introBytes) ?: return null
            opusInputs.add(introFile.absolutePath)
        }
        opusInputs.addAll(partFiles.map { it.absolutePath })
        if (interruptedByUser) return null

        /* Single source without tagging: move the file rather than copying it through the heap. */
        if (!opts.embedMetadata && opusInputs.size == 1) {
            return finalizeCliChunkFile(targetFile, File(opusInputs[0]), partFiles, chunkSpec, bookIndex)
        }

        val tmpDir = targetFile.parentFile ?: bookWorkDir
        tmpDir.mkdirs()
        val tmpFile = File(tmpDir, ".${targetFile.name}.tmp")
        try {
            if (!OpusConverter.mergeOpusFiles(opusInputs, tmpFile.absolutePath)) return null
            if (interruptedByUser) return null
            if (opts.embedMetadata) {
                val mergedBytes = try {
                    tmpFile.readBytes()
                } catch (_: Throwable) {
                    return null
                }
                val finalOpus = AudioMetadataTagger.tagOpus(
                    opusBytes = mergedBytes,
                    bookMeta = chunkSpec.bookMetadata,
                    trackTitle = trackTitle,
                    albumTitle = albumTitle,
                    trackNumber = chunkSpec.bookChunkNumber,
                    totalTracks = chunkSpec.totalChunksForBook,
                    voiceName = chunkSpec.voice
                )
                return finalizeCliChunkOutput(targetFile, finalOpus, partFiles, chunkSpec, bookIndex)
            }
            return finalizeCliChunkFile(targetFile, tmpFile, partFiles, chunkSpec, bookIndex)
        } finally {
            try {
                tmpFile.delete()
            } catch (_: Throwable) {
            }
        }
    }

    val assembledMp3 = if (allWav) {
        OpusConverter.convertWavFilesToMp3(partFiles, introBytes, targetKbps) ?: return null
    } else {
        OfflineTtsEngine.mergeMp3AndWavFilesToMp3(
            partFiles = partFiles,
            introBytes = introBytes,
            targetKbps = targetKbps,
            shouldAbort = { interruptedByUser },
            convertWavToMp3 = { wav, kbps -> OpusConverter.convertWavToMp3(wav, kbps) }
        )
    }
    if (assembledMp3.isEmpty() || interruptedByUser) return null

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
    return finalizeCliChunkOutput(targetFile, finalMp3, partFiles, chunkSpec, bookIndex)
}
