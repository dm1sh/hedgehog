package dm1sh.hedgehog.tts

import com.microsoft.cognitiveservices.speech.EmbeddedSpeechConfig
import com.microsoft.cognitiveservices.speech.ResultReason
import com.microsoft.cognitiveservices.speech.SpeechSynthesisCancellationDetails
import com.microsoft.cognitiveservices.speech.SpeechSynthesisOutputFormat
import com.microsoft.cognitiveservices.speech.SpeechSynthesizer
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object OfflineTtsEngine {

    private const val TAG = "OfflineTtsEngine"
    private const val DATA_CHUNK = 1635017060
    private const val KEY_FILE = "voice.key"
    private const val NOTICE_CHECKSUM = 16
    private const val NOTICE_LIMIT = 8192
    private const val SAMPLE_RATE = 24000
    private val TOKEN_REGEX = Regex("value=\"(Microsoft Server Speech Text to Speech Voice \\(([^,]+), [^)]+\\))\"")

    private var maxJobs = 1
    private var semaphore = Semaphore(1)
    private val nativeLock = Any()

    private class CachedSession(
        val voiceDir: String,
        val voice: Voice,
        val config: EmbeddedSpeechConfig,
        val synthesizer: SpeechSynthesizer
    ) {
        fun closeQuietly() {
            try {
                synthesizer.close()
            } catch (_: Throwable) {
            }
            try {
                config.close()
            } catch (_: Throwable) {
            }
        }
    }

    private var cachedSession: CachedSession? = null

    fun setMaxJobs(value: Int) {
        val processors = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val clamped = value.coerceIn(1, processors)
        if (clamped != maxJobs) {
            maxJobs = clamped
            semaphore = Semaphore(clamped)
        }
    }

    fun voiceInstalled(voiceDir: File): Boolean {
        return voiceDir.name.isNotEmpty() && File(voiceDir, "Tokens.xml").exists()
    }

    suspend fun synthesizePart(
        context: Any? = null,
        voiceDir: File,
        text: String,
        rate: String,
        pitch: String,
        lowBitrate: Boolean,
        isPreview: Boolean = false,
        isPaused: () -> Boolean,
        isCancelled: () -> Boolean,
        onStatusUpdate: (String) -> Unit
    ): ByteArray? {
        while (!isCancelled()) {
            if (!isPreview && isPaused()) {
                onStatusUpdate("KEY_PAUSE")
                delay(200)
                continue
            }
            onStatusUpdate("KEY_STARTED")
            val wav = semaphore.withPermit {
                if (isCancelled() || (!isPreview && isPaused())) {
                    ByteArray(0)
                } else {
                    synthesizeWavBlocking(
                        voiceDir = voiceDir.absolutePath,
                        text = text,
                        rate = rate,
                        pitch = pitch,
                        shouldAbort = { isCancelled() || (!isPreview && isPaused()) },
                        onProcessingStart = { onStatusUpdate("KEY_PROCESSING") }
                    )
                }
            }
            if (isCancelled()) return null
            if (wav.isEmpty() && !isPreview && isPaused()) {
                continue
            }
            if (wav.isNotEmpty()) {
                return wav
            }
            onStatusUpdate("KEY_ERROR_RERUN")
            if (isPreview) return null
            delay(4000)
        }
        return null
    }

    fun synthesizeWavBlocking(
        voiceDir: String,
        text: String,
        rate: String,
        pitch: String,
        shouldAbort: () -> Boolean = { false },
        onProcessingStart: () -> Unit = {}
    ): ByteArray {
        return try {
            val pcmBytes = synthesizePcmBlocking(
                voiceDir = voiceDir,
                text = text,
                rate = rate,
                pitch = pitch,
                shouldAbort = shouldAbort,
                onProcessingStart = onProcessingStart
            )
            if (pcmBytes.isEmpty() || shouldAbort()) ByteArray(0) else buildWav(pcmBytes)
        } catch (t: Throwable) {
            System.err.println("$TAG: synthesizeWavBlocking failed: ${t.message}")
            ByteArray(0)
        }
    }

    fun synthesizePcmBlocking(
        voiceDir: String,
        text: String,
        rate: String,
        pitch: String,
        shouldAbort: () -> Boolean = { false },
        onProcessingStart: () -> Unit = {}
    ): ByteArray {
        return try {
            synchronized(nativeLock) {
                if (shouldAbort()) return ByteArray(0)
                onProcessingStart()
                pcmLocked(voiceDir, text, rate, pitch)
            }
        } catch (t: Throwable) {
            System.err.println("$TAG: synthesizePcmBlocking failed: ${t.message}")
            ByteArray(0)
        }
    }

    private data class Voice(
        val name: String,
        val locale: String,
        val licensed: Boolean
    )

    private fun getOrCreateSessionLocked(voiceDir: String): CachedSession {
        val existing = cachedSession
        if (existing != null && existing.voiceDir == voiceDir) {
            return existing
        }
        existing?.closeQuietly()
        cachedSession = null

        val v = parseVoice(voiceDir)
        val config = EmbeddedSpeechConfig.fromPath(voiceDir)
        try {
            config.setSpeechSynthesisVoice(v.name, resolveKey(voiceDir, v.licensed))
            config.setSpeechSynthesisOutputFormat(SpeechSynthesisOutputFormat.Riff24Khz16BitMonoPcm)
            val synthesizer = SpeechSynthesizer(config, null)
            val created = CachedSession(voiceDir, v, config, synthesizer)
            cachedSession = created
            return created
        } catch (t: Throwable) {
            try {
                config.close()
            } catch (_: Throwable) {
            }
            throw t
        }
    }

    private fun resetSessionLocked() {
        cachedSession?.closeQuietly()
        cachedSession = null
    }

    private fun pcmLocked(voiceDir: String, text: String, rate: String, pitch: String): ByteArray {
        for (attempt in 0..1) {
            val session = try {
                getOrCreateSessionLocked(voiceDir)
            } catch (t: Throwable) {
                resetSessionLocked()
                if (attempt == 1) throw t
                continue
            }
            try {
                val ssml = buildSsml(session.voice, text, rate, pitch)
                val result = session.synthesizer.SpeakSsml(ssml)
                try {
                    if (result.reason == ResultReason.SynthesizingAudioCompleted) {
                        val audioData = result.audioData
                        if (audioData != null && audioData.isNotEmpty()) {
                            return stripRiff(audioData)
                        }
                    } else {
                        val details = SpeechSynthesisCancellationDetails.fromResult(result)
                        System.err.println("$TAG: FAIL reason=${details.reason} code=${details.errorCode} details=${details.errorDetails}")
                    }
                } finally {
                    result.close()
                }
            } catch (t: Throwable) {
                System.err.println("$TAG: SpeakSsml attempt ${attempt + 1} failed: ${t.message}")
            }
            resetSessionLocked()
        }
        return ByteArray(0)
    }

    private fun parseVoice(voiceDir: String): Voice {
        val tokensText = File(voiceDir, "Tokens.xml").readText(Charsets.UTF_8)
        val match = TOKEN_REGEX.find(tokensText)
            ?: throw IllegalStateException("Tokens.xml: voice name not found in $voiceDir")
        return Voice(
            name = match.groupValues[1],
            locale = match.groupValues[2],
            licensed = tokensText.contains("LicenseVersion")
        )
    }

    private fun resolveKey(voiceDir: String, licensed: Boolean): String {
        if (licensed) {
            return extractNotice(voiceDir)
        }
        val file = File(voiceDir, KEY_FILE)
        return if (file.exists()) file.readText(Charsets.UTF_8).trim() else ""
    }

    private fun extractNotice(voiceDir: String): String {
        val binFiles = File(voiceDir).listFiles { file -> file.name.endsWith(".bin") } ?: emptyArray()
        for (model in binFiles) {
            val buf = ByteArray(NOTICE_LIMIT)
            var totalRead = 0
            FileInputStream(model).use { fis ->
                while (totalRead < NOTICE_LIMIT) {
                    val r = fis.read(buf, totalRead, NOTICE_LIMIT - totalRead)
                    if (r <= 0) break
                    totalRead += r
                }
            }
            var asciiLen = 0
            while (asciiLen < totalRead) {
                val b = buf[asciiLen].toInt()
                if (b < 32 || b >= 127) break
                asciiLen++
            }
            if (asciiLen > NOTICE_CHECKSUM && asciiLen < totalRead) {
                return String(buf, 0, asciiLen - NOTICE_CHECKSUM, Charsets.US_ASCII)
            }
        }
        throw IllegalStateException("license notice not found in $voiceDir")
    }

    private fun buildSsml(voice: Voice, text: String, rate: String, pitch: String): String {
        val escaped = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='${voice.locale}'>" +
            "<voice name='${voice.name}'><prosody pitch='$pitch' rate='$rate' volume='+0%'>" +
            "$escaped</prosody></voice></speak>"
    }

    fun stripRiff(riff: ByteArray): ByteArray {
        if (riff.size < 44) return riff
        val buffer = ByteBuffer.wrap(riff).order(ByteOrder.LITTLE_ENDIAN)
        var i = 12
        while (true) {
            val headerEnd = i + 8
            if (headerEnd > riff.size) return riff
            val chunkSize = buffer.getInt(i + 4)
            if (buffer.getInt(i) == DATA_CHUNK) {
                return riff.copyOfRange(headerEnd, minOf(headerEnd + chunkSize, riff.size))
            }
            i += chunkSize + 8 + (chunkSize and 1)
        }
    }

    fun buildWav(pcm: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(pcm.size + 44).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(pcm.size + 36)
        buffer.put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
        buffer.putShort(1).putShort(1)
        buffer.putInt(SAMPLE_RATE).putInt(48000)
        buffer.putShort(2).putShort(16)
        buffer.put("data".toByteArray(Charsets.US_ASCII)).putInt(pcm.size).put(pcm)
        return buffer.array()
    }

    fun mergeWavFiles(wavFiles: List<File>, deleteAfterRead: Boolean = false): ByteArray {
        if (wavFiles.isEmpty()) return ByteArray(0)
        val pcmOut = ByteArrayOutputStream()
        for (file in wavFiles) {
            if (file.exists()) {
                try {
                    val raw = file.readBytes()
                    if (raw.isNotEmpty()) {
                        val pcm = stripRiff(raw)
                        if (pcm.isNotEmpty()) {
                            pcmOut.write(pcm)
                        }
                    }
                } catch (_: Exception) {
                }
                if (deleteAfterRead) {
                    file.delete()
                }
            }
        }
        val mergedPcm = pcmOut.toByteArray()
        return if (mergedPcm.isEmpty()) ByteArray(0) else buildWav(mergedPcm)
    }
}
