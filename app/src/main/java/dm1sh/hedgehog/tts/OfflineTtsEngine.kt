package dm1sh.hedgehog.tts

import android.content.Context
import android.util.Log
import com.microsoft.cognitiveservices.speech.EmbeddedSpeechConfig
import com.microsoft.cognitiveservices.speech.ResultReason
import com.microsoft.cognitiveservices.speech.SpeechSynthesisCancellationDetails
import com.microsoft.cognitiveservices.speech.SpeechSynthesisOutputFormat
import com.microsoft.cognitiveservices.speech.SpeechSynthesizer
import de.sciss.jump3r.Main
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
        context: Context,
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
            val mp3 = semaphore.withPermit {
                if (isCancelled() || (!isPreview && isPaused())) {
                    ByteArray(0)
                } else {
                    onStatusUpdate("KEY_PROCESSING")
                    val kbps = if (lowBitrate) "48" else "96"
                    synthesizeBlocking(context, voiceDir.absolutePath, text, rate, pitch, kbps)
                }
            }
            if (isCancelled()) return null
            if (mp3.isEmpty() && !isPreview && isPaused()) {
                continue
            }
            if (mp3.isNotEmpty()) {
                return mp3
            }
            onStatusUpdate("KEY_ERROR_RERUN")
            if (isPreview) return null
            delay(4000)
        }
        return null
    }

    fun synthesizeBlocking(
        context: Context,
        voiceDir: String,
        text: String,
        rate: String,
        pitch: String,
        kbps: String
    ): ByteArray {
        return try {
            val pcmBytes = pcm(voiceDir, text, rate, pitch)
            if (pcmBytes.isEmpty()) pcmBytes else mp3(context, pcmBytes, kbps)
        } catch (t: Throwable) {
            Log.e(TAG, "synthesize failed: ${t.message}", t)
            ByteArray(0)
        }
    }

    private data class Voice(
        val name: String,
        val locale: String,
        val licensed: Boolean
    )

    private fun pcm(voiceDir: String, text: String, rate: String, pitch: String): ByteArray {
        val v = parseVoice(voiceDir)
        val config = EmbeddedSpeechConfig.fromPath(voiceDir)
        try {
            config.setSpeechSynthesisVoice(v.name, resolveKey(voiceDir, v.licensed))
            config.setSpeechSynthesisOutputFormat(SpeechSynthesisOutputFormat.Riff24Khz16BitMonoPcm)
            val synthesizer = SpeechSynthesizer(config, null)
            try {
                val result = synthesizer.SpeakSsml(buildSsml(v, text, rate, pitch))
                try {
                    if (result.reason != ResultReason.SynthesizingAudioCompleted) {
                        val details = SpeechSynthesisCancellationDetails.fromResult(result)
                        Log.e(TAG, "FAIL reason=${details.reason} code=${details.errorCode} details=${details.errorDetails}")
                        return ByteArray(0)
                    }
                    val audioData = result.audioData ?: return ByteArray(0)
                    return stripRiff(audioData)
                } finally {
                    result.close()
                }
            } finally {
                synthesizer.close()
            }
        } finally {
            config.close()
        }
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

    private fun stripRiff(riff: ByteArray): ByteArray {
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

    private fun mp3(context: Context, pcm: ByteArray, kbps: String): ByteArray {
        val cacheDir = context.cacheDir ?: return ByteArray(0)
        val wavFile = File.createTempFile("part_", ".wav", cacheDir)
        val mp3File = File(wavFile.path.removeSuffix(".wav") + ".mp3")
        return try {
            wavFile.writeBytes(buildWav(pcm))
            val code = Main().run(arrayOf("-b", kbps, "--silent", wavFile.path, mp3File.path))
            if (code == 0 && mp3File.exists()) mp3File.readBytes() else ByteArray(0)
        } finally {
            wavFile.delete()
            mp3File.delete()
        }
    }

    private fun buildWav(pcm: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(pcm.size + 44).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(pcm.size + 36)
        buffer.put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
        buffer.putShort(1).putShort(1)
        buffer.putInt(SAMPLE_RATE).putInt(48000)
        buffer.putShort(2).putShort(16)
        buffer.put("data".toByteArray(Charsets.US_ASCII)).putInt(pcm.size).put(pcm)
        return buffer.array()
    }
}
