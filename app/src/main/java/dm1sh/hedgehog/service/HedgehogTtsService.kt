package dm1sh.hedgehog.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import dm1sh.hedgehog.MainActivity
import dm1sh.hedgehog.book.ProcessingFile
import dm1sh.hedgehog.book.TextsConverter
import dm1sh.hedgehog.data.SettingsRepository
import dm1sh.hedgehog.model.AppSettings
import dm1sh.hedgehog.model.DefaultVoices
import dm1sh.hedgehog.tts.OfflineTtsEngine
import dm1sh.hedgehog.tts.SocketEdgeTtsWorker
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Locale

class HedgehogTtsService : TextToSpeechService() {

    companion object {
        private const val TAG = "HedgehogTtsService"
        private const val DEFAULT_SAMPLE_RATE = 24000
        const val EXTRA_OPEN_SETTINGS_TAB = "dm1sh.hedgehog.extra.OPEN_SETTINGS_TAB"

        fun collectSupportedIsoLocales(context: Context): ArrayList<String> {
            val result = linkedSetOf<String>()
            val repo = try {
                SettingsRepository(context.applicationContext)
            } catch (_: Throwable) {
                null
            }
            val voices = repo?.onlineVoices ?: DefaultVoices.ONLINE_VOICES
            for (voice in voices) {
                val locale = parseLocaleFromVoice(voice) ?: continue
                toIso3Pair(locale)?.let { result.add(it) }
            }
            // Ensure common locales are always advertised for Offline + Online voices
            val commonLocales = listOf(
                Locale("ru", "RU"),
                Locale.US,
                Locale.UK,
                Locale.GERMANY,
                Locale("es", "ES"),
                Locale("fi", "FI"),
                Locale.FRANCE,
                Locale.ITALY,
                Locale.JAPAN,
                Locale.KOREA,
                Locale("pl", "PL"),
                Locale("pt", "BR"),
                Locale("pt", "PT"),
                Locale.CHINA,
                Locale.TAIWAN,
                Locale("uk", "UA")
            )
            for (loc in commonLocales) {
                toIso3Pair(loc)?.let { result.add(it) }
            }
            return ArrayList(result)
        }

        fun parseLocaleFromVoice(voice: String): Locale? {
            val prefix = voice.substringBefore(',').trim()
            val parts = prefix.split('-', '_')
            if (parts.isEmpty() || parts[0].length !in 2..3) return null
            val lang = parts[0].lowercase(Locale.ROOT)
            val country = if (parts.size >= 2 && parts[1].length in 2..3) parts[1].uppercase(Locale.ROOT) else ""
            return if (country.isNotEmpty()) Locale(lang, country) else Locale(lang)
        }

        private fun toIso3Pair(locale: Locale): String? {
            return try {
                val lang3 = locale.isO3Language
                if (lang3.isNullOrBlank()) return null
                val country3 = try {
                    locale.isO3Country
                } catch (_: Throwable) {
                    ""
                }
                if (!country3.isNullOrBlank()) "$lang3-$country3" else lang3
            } catch (_: Throwable) {
                null
            }
        }
    }

    @Volatile
    private var stopRequested: Boolean = false

    @Volatile
    private var currentLanguage: Array<String> = arrayOf("rus", "RUS", "")

    private var cachedLexxPath: String = ""
    private var cachedLexxMtime: Long = -1L
    private var cachedLexxRules: List<String> = emptyList()

    override fun onCreate() {
        super.onCreate()
        try {
            val repo = SettingsRepository(applicationContext)
            val settings = repo.loadSettings()
            val loc = parseLocaleFromVoice(settings.voicesName) ?: Locale("ru", "RU")
            currentLanguage = arrayOf(
                loc.SafeIso3Language(),
                loc.SafeIso3Country(),
                loc.variant.orEmpty()
            )
        } catch (_: Throwable) {
        }
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        if (lang.isNullOrBlank()) return TextToSpeech.LANG_NOT_SUPPORTED
        val normalizedLang = lang.trim().lowercase(Locale.ROOT)
        if (normalizedLang.length !in 2..3) return TextToSpeech.LANG_NOT_SUPPORTED
        return when {
            !variant.isNullOrBlank() -> TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
            !country.isNullOrBlank() -> TextToSpeech.LANG_COUNTRY_AVAILABLE
            else -> TextToSpeech.LANG_AVAILABLE
        }
    }

    override fun onGetLanguage(): Array<String> {
        return currentLanguage
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val availability = onIsLanguageAvailable(lang, country, variant)
        if (availability >= TextToSpeech.LANG_AVAILABLE) {
            currentLanguage = arrayOf(
                lang.orEmpty(),
                country.orEmpty(),
                variant.orEmpty()
            )
        }
        return availability
    }

    override fun onGetVoices(): MutableList<Voice> {
        val voices = mutableListOf<Voice>()
        return try {
            val repo = SettingsRepository(applicationContext)
            val settings = repo.loadSettings()
            val readyOffline = repo.readyOfflineVoices()
            val defaultLocale = parseLocaleFromVoice(settings.voicesName) ?: Locale("ru", "RU")

            for (offName in readyOffline) {
                voices.add(
                    Voice(
                        "offline:$offName",
                        defaultLocale,
                        Voice.QUALITY_VERY_HIGH,
                        Voice.LATENCY_LOW,
                        false,
                        emptySet()
                    )
                )
            }
            for (onName in repo.onlineVoices) {
                val loc = parseLocaleFromVoice(onName) ?: defaultLocale
                voices.add(
                    Voice(
                        "online:$onName",
                        loc,
                        Voice.QUALITY_VERY_HIGH,
                        Voice.LATENCY_NORMAL,
                        true,
                        emptySet()
                    )
                )
            }
            if (voices.isEmpty()) {
                voices.add(
                    Voice(
                        "hedgehog-default",
                        defaultLocale,
                        Voice.QUALITY_VERY_HIGH,
                        Voice.LATENCY_NORMAL,
                        settings.useOnlineVoicesSystemTts,
                        emptySet()
                    )
                )
            }
            voices
        } catch (_: Throwable) {
            voices
        }
    }

    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String {
        return try {
            val repo = SettingsRepository(applicationContext)
            val settings = repo.loadSettings()
            if (settings.useOnlineVoicesSystemTts) {
                "online:${settings.voicesName.ifBlank { "ru-RU, DmitryNeural" }}"
            } else {
                val off = resolveOfflineVoice(repo, settings)
                if (!off.isNullOrBlank()) "offline:$off" else "online:${settings.voicesName.ifBlank { "ru-RU, DmitryNeural" }}"
            }
        } catch (_: Throwable) {
            "hedgehog-default"
        }
    }

    override fun onIsValidVoiceName(voiceName: String?): Int {
        return if (voiceName.isNullOrBlank()) TextToSpeech.ERROR else TextToSpeech.SUCCESS
    }

    override fun onLoadVoice(voiceName: String?): Int {
        return if (voiceName.isNullOrBlank()) TextToSpeech.ERROR else TextToSpeech.SUCCESS
    }

    override fun onStop() {
        stopRequested = true
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return
        stopRequested = false

        val rawText = request.charSequenceText?.toString().orEmpty().trim()
        if (rawText.isEmpty()) {
            callback.start(DEFAULT_SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
            callback.done()
            return
        }

        try {
            val repo = SettingsRepository(applicationContext)
            val settings = repo.loadSettings()

            val processedText = preprocessTextForTts(rawText, settings).trim()
            if (processedText.isEmpty()) {
                callback.start(DEFAULT_SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
                callback.done()
                return
            }

            // Combine Hedgehog's rate/pitch settings with any offset requested by the client app
            val reqRateOffset = if (request.speechRate > 0 && request.speechRate != 100) {
                request.speechRate - 100
            } else {
                0
            }
            val effectiveRate = (settings.rate + reqRateOffset).coerceIn(-50, 100)
            val rateStr = "${formatSigned(effectiveRate)}%"

            val reqPitchOffset = if (request.pitch > 0 && request.pitch != 100) {
                (request.pitch - 100) / 2
            } else {
                0
            }
            val effectivePitch = (settings.pitch + reqPitchOffset).coerceIn(-50, 50)
            val pitchStr = "${formatSigned(effectivePitch)}Hz"

            val offlineVoiceName = resolveOfflineVoice(repo, settings)
            val hasOfflineVoice = !offlineVoiceName.isNullOrBlank()
            val onlineVoiceName = settings.voicesName.ifBlank { "ru-RU, DmitryNeural" }

            val useOnline = settings.useOnlineVoicesSystemTts &&
                !repo.isVoiceOffline(onlineVoiceName)

            if (useOnline) {
                val timeoutMsec = if (settings.restartDelay < 0) 30_000L else (settings.restartDelay * 1000L).coerceAtMost(30_000L)
                val mp3Bytes = runBlocking {
                    SocketEdgeTtsWorker.synthesizePart(
                        voice = onlineVoiceName,
                        pitch = pitchStr,
                        rate = rateStr,
                        text = processedText,
                        lowBitrate = settings.lowBitrate,
                        timeoutMsec = timeoutMsec,
                        isPreview = true,
                        fallbackToOfflineOnError = hasOfflineVoice,
                        isPaused = { false },
                        isCancelled = { stopRequested },
                        onStatusUpdate = {}
                    )
                }

                if (stopRequested) {
                    callback.done()
                    return
                }

                if (mp3Bytes != null && mp3Bytes.isNotEmpty()) {
                    if (streamMp3ToCallback(mp3Bytes, callback)) {
                        return
                    }
                }

                if (!hasOfflineVoice) {
                    callback.error()
                    return
                }
                OfflineTtsEngine.showFallbackToast(applicationContext)
            }

            if (!hasOfflineVoice) {
                Log.w(TAG, "System TTS configured for offline voices, but no offline voice is installed")
                callback.error()
                return
            }
            val voiceDir = File(File(repo.programDir, "Voices"), offlineVoiceName!!)
            val pcm = OfflineTtsEngine.synthesizePcmBlocking(
                voiceDir = voiceDir.absolutePath,
                text = processedText,
                rate = rateStr,
                pitch = pitchStr,
                shouldAbort = { stopRequested }
            )
            if (stopRequested) {
                callback.done()
                return
            }
            if (pcm.isEmpty()) {
                callback.error()
                return
            }
            streamPcmToCallback(pcm, DEFAULT_SAMPLE_RATE, 1, callback)
        } catch (t: Throwable) {
            Log.e(TAG, "onSynthesizeText failed: ${t.message}", t)
            try {
                callback.error()
            } catch (_: Throwable) {
            }
        }
    }

    private fun resolveOfflineVoice(repo: SettingsRepository, settings: AppSettings): String? {
        val ready = repo.readyOfflineVoices()
        if (ready.isEmpty()) return null
        if (settings.offlineVoicesName.isNotBlank() && ready.contains(settings.offlineVoicesName)) {
            return settings.offlineVoicesName
        }
        if (settings.offlineVoicesSelect in ready.indices) {
            return ready[settings.offlineVoicesSelect]
        }
        return ready.first()
    }

    private fun preprocessTextForTts(rawText: String, settings: AppSettings): String {
        val lexxRules = if (settings.lexxUse && settings.lexxPath.isNotBlank()) {
            loadLexxRulesCached(settings.lexxPath)
        } else {
            emptyList()
        }
        return try {
            val cleanedText = TextsConverter.preprocessSharedText(
                rawText = rawText,
                inlineNotes = settings.inlineNotes
            )
            val processor = ProcessingFile(
                fileName = "tts",
                text = cleanedText,
                langLexx = lexxRules,
                lexxRegister = settings.lexxRegister,
                bookChapter = false,
                glavTextUse = false,
                regexGlavaPattern = settings.regexGlavaCompile,
                voice = "",
                rate = "",
                pitch = ""
            )
            processor.allSentences.joinToString(" ").ifBlank { cleanedText.ifBlank { rawText } }
        } catch (_: Throwable) {
            rawText
        }
    }

    @Synchronized
    private fun loadLexxRulesCached(path: String): List<String> {
        return try {
            val file = File(path)
            if (!file.exists() || !file.isFile) return emptyList()
            val mtime = file.lastModified()
            if (path == cachedLexxPath && mtime == cachedLexxMtime) {
                return cachedLexxRules
            }
            val rules = TextsConverter.readTextWithFallback(file.readBytes()).lines()
            cachedLexxPath = path
            cachedLexxMtime = mtime
            cachedLexxRules = rules
            rules
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun streamPcmToCallback(
        pcmBytes: ByteArray,
        sampleRate: Int,
        channelCount: Int,
        callback: SynthesisCallback
    ) {
        callback.start(sampleRate, AudioFormat.ENCODING_PCM_16BIT, channelCount)
        val frameAlign = (2 * channelCount).coerceAtLeast(2)
        val maxChunk = (callback.maxBufferSize.coerceAtLeast(1024) / frameAlign) * frameAlign
        var offset = 0
        while (offset < pcmBytes.size && !stopRequested) {
            val len = minOf(maxChunk, pcmBytes.size - offset)
            if (callback.audioAvailable(pcmBytes, offset, len) == TextToSpeech.ERROR) {
                break
            }
            offset += len
        }
        callback.done()
    }

    private fun streamMp3ToCallback(mp3Bytes: ByteArray, callback: SynthesisCallback): Boolean {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var tempFile: File? = null
        return try {
            var dataSourceReady = false
            try {
                extractor.setDataSource(object : MediaDataSource() {
                    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                        if (position >= mp3Bytes.size) return -1
                        val avail = (mp3Bytes.size - position).toInt()
                        val toCopy = minOf(size, avail)
                        System.arraycopy(mp3Bytes, position.toInt(), buffer, offset, toCopy)
                        return toCopy
                    }

                    override fun getSize(): Long = mp3Bytes.size.toLong()
                    override fun close() {}
                })
                dataSourceReady = extractor.trackCount > 0
            } catch (_: Throwable) {
                dataSourceReady = false
            }

            if (!dataSourceReady) {
                val f = File.createTempFile("sys_tts_", ".mp3", cacheDir)
                f.writeBytes(mp3Bytes)
                tempFile = f
                extractor.setDataSource(f.absolutePath)
            }

            if (extractor.trackCount <= 0) return false
            extractor.selectTrack(0)
            val inputFormat = extractor.getTrackFormat(0)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return false
            var sampleRate = if (inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
                DEFAULT_SAMPLE_RATE
            }
            var channelCount = if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else {
                1
            }

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            var startedCallback = false
            var inputDone = false
            var outputDone = false
            val bufferInfo = MediaCodec.BufferInfo()

            while (!outputDone && !stopRequested) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000L)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex)
                        if (inBuf != null) {
                            val sampleSize = extractor.readSampleData(inBuf, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(
                                    inIndex,
                                    0,
                                    0,
                                    0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                val sampleTime = extractor.sampleTime.coerceAtLeast(0L)
                                codec.queueInputBuffer(inIndex, 0, sampleSize, sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFormat = codec.outputFormat
                        if (outFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            sampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (outFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channelCount = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                    }
                    outIndex >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIndex)
                        if (outBuf != null && bufferInfo.size > 0) {
                            if (!startedCallback) {
                                callback.start(sampleRate, AudioFormat.ENCODING_PCM_16BIT, channelCount)
                                startedCallback = true
                            }
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            val chunk = ByteArray(bufferInfo.size)
                            outBuf.get(chunk)

                            val frameAlign = (2 * channelCount).coerceAtLeast(2)
                            val maxChunk = (callback.maxBufferSize.coerceAtLeast(1024) / frameAlign) * frameAlign
                            var off = 0
                            while (off < chunk.size && !stopRequested) {
                                val len = minOf(maxChunk, chunk.size - off)
                                if (callback.audioAvailable(chunk, off, len) == TextToSpeech.ERROR) {
                                    stopRequested = true
                                    break
                                }
                                off += len
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            if (!startedCallback) {
                callback.start(sampleRate, AudioFormat.ENCODING_PCM_16BIT, channelCount)
            }
            callback.done()
            true
        } catch (t: Throwable) {
            Log.e(TAG, "streamMp3ToCallback failed: ${t.message}", t)
            false
        } finally {
            try {
                codec?.stop()
            } catch (_: Throwable) {
            }
            try {
                codec?.release()
            } catch (_: Throwable) {
            }
            try {
                extractor.release()
            } catch (_: Throwable) {
            }
            try {
                tempFile?.delete()
            } catch (_: Throwable) {
            }
        }
    }

    private fun isNetworkAvailable(): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Throwable) {
            true
        }
    }

    private fun formatSigned(value: Int): String {
        val sign = if (value >= 0) "+" else "-"
        return "$sign${kotlin.math.abs(value)}"
    }

    private fun Locale.SafeIso3Language(): String {
        return try {
            isO3Language.ifBlank { "rus" }
        } catch (_: Throwable) {
            "rus"
        }
    }

    private fun Locale.SafeIso3Country(): String {
        return try {
            isO3Country
        } catch (_: Throwable) {
            ""
        }
    }
}

/**
 * Handles [TextToSpeech.Engine.ACTION_CHECK_TTS_DATA] for Android's TTS subsystem.
 */
class CheckTtsDataActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val availableVoices = HedgehogTtsService.collectSupportedIsoLocales(this)
        val resultIntent = Intent().apply {
            putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, availableVoices)
            putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, arrayListOf())
        }
        setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, resultIntent)
        finish()
    }
}

/**
 * Handles [TextToSpeech.Engine.ACTION_GET_SAMPLE_TEXT] for Android's "Listen to an example" button.
 */
class GetSampleTextActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val lang = intent?.getStringExtra("language")?.lowercase(Locale.ROOT).orEmpty()
        val sample = when {
            lang.startsWith("ru") -> "Это пример синтеза речи на русском языке."
            lang.startsWith("de") || lang.startsWith("ger") -> "Dies ist ein Beispiel für die Sprachsynthese auf Deutsch."
            lang.startsWith("es") || lang.startsWith("spa") -> "Este es un ejemplo de síntesis de voz en español."
            lang.startsWith("fi") -> "Tämä on esimerkki suomenkielisestä puhesynteesistä."
            lang.startsWith("fr") -> "Ceci est un exemple de synthèse vocale en français."
            lang.startsWith("it") -> "Questo è un esempio di sintesi vocale in italiano."
            lang.startsWith("ja") || lang.startsWith("jpn") -> "これは日本語の音声合成のサンプルです。"
            lang.startsWith("ko") -> "이것은 한국어 음성 합성 예제입니다."
            lang.startsWith("pl") || lang.startsWith("pol") -> "To jest przykład syntezy mowy w języku polskim."
            lang.startsWith("pt") || lang.startsWith("por") -> "Este é um exemplo de síntese de voz em português."
            lang.startsWith("zh") || lang.startsWith("chi") -> "这是中文语音合成的示例。"
            lang.startsWith("uk") -> "Це приклад синтезу мовлення українською мовою."
            else -> "This is an example of speech synthesis in English."
        }
        val resultIntent = Intent().apply {
            putExtra(TextToSpeech.Engine.EXTRA_SAMPLE_TEXT, sample)
        }
        setResult(RESULT_OK, resultIntent)
        finish()
    }
}

/**
 * Opens Hedgehog directly on the Settings tab when launched from Android's System TTS settings.
 */
class TtsSettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(HedgehogTtsService.EXTRA_OPEN_SETTINGS_TAB, true)
        }
        startActivity(launchIntent)
        finish()
    }
}
