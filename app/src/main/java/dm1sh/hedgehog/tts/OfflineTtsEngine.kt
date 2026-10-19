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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

object OfflineTtsEngine {

    private const val TAG = "OfflineTtsEngine"
    private const val DATA_CHUNK = 1635017060
    private const val KEY_FILE = "voice.key"
    private const val NOTICE_CHECKSUM = 16
    private const val NOTICE_LIMIT = 8192
    private const val SAMPLE_RATE = 24000
    private val TOKEN_REGEX = Regex("value=\"(Microsoft Server Speech Text to Speech Voice \\(([^,]+), [^)]+\\))\"")

    private val defaultJobs = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    @Volatile
    private var maxJobs = defaultJobs

    @Volatile
    private var semaphore = Semaphore(defaultJobs)

    private val sessionInitLock = Any()
    private val sessionPool = ConcurrentLinkedQueue<CachedSession>()
    private val activeSessions = ConcurrentHashMap.newKeySet<CachedSession>()

    @Volatile
    private var poolVoiceDir: String = ""

    private val parsedVoiceCache = ConcurrentHashMap<String, Voice>()
    private val resolvedKeyCache = ConcurrentHashMap<String, String>()

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
            if (shouldAbort()) return ByteArray(0)
            onProcessingStart()
            val rawAudio = synthesizeRawBlocking(
                voiceDir = voiceDir,
                text = text,
                rate = rate,
                pitch = pitch,
                shouldAbort = shouldAbort
            )
            if (rawAudio.isEmpty() || shouldAbort()) ByteArray(0) else normalizeToWav(rawAudio)
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
            if (shouldAbort()) return ByteArray(0)
            onProcessingStart()
            val rawAudio = synthesizeRawBlocking(
                voiceDir = voiceDir,
                text = text,
                rate = rate,
                pitch = pitch,
                shouldAbort = shouldAbort
            )
            if (rawAudio.isEmpty() || shouldAbort()) ByteArray(0) else stripRiff(rawAudio)
        } catch (t: Throwable) {
            if (!shouldAbort()) {
                System.err.println("$TAG: synthesizePcmBlocking failed: ${t.message}")
            }
            ByteArray(0)
        }
    }

    fun cancelAllActive() {
        for (session in activeSessions) {
            try {
                session.synthesizer.StopSpeakingAsync()
            } catch (_: Throwable) {
            }
        }
        releaseIdleSessions()
    }

    fun releaseIdleSessions() {
        synchronized(sessionInitLock) {
            drainPoolLocked()
        }
    }

    private data class Voice(
        val name: String,
        val locale: String,
        val licensed: Boolean
    )

    private fun borrowSession(voiceDir: String): CachedSession {
        if (poolVoiceDir != voiceDir) {
            synchronized(sessionInitLock) {
                if (poolVoiceDir != voiceDir) {
                    drainPoolLocked()
                    poolVoiceDir = voiceDir
                }
            }
        }
        while (true) {
            val pooled = sessionPool.poll() ?: break
            if (pooled.voiceDir == voiceDir) {
                return pooled
            } else {
                synchronized(sessionInitLock) {
                    pooled.closeQuietly()
                }
            }
        }
        return synchronized(sessionInitLock) {
            val v = parsedVoiceCache.getOrPut(voiceDir) { parseVoice(voiceDir) }
            val key = resolvedKeyCache.getOrPut(voiceDir) { resolveKey(voiceDir, v.licensed) }
            val config = EmbeddedSpeechConfig.fromPath(voiceDir)
            try {
                config.setSpeechSynthesisVoice(v.name, key)
                config.setSpeechSynthesisOutputFormat(SpeechSynthesisOutputFormat.Riff24Khz16BitMonoPcm)
                val synthesizer = SpeechSynthesizer(config, null)
                CachedSession(voiceDir, v, config, synthesizer)
            } catch (t: Throwable) {
                try {
                    config.close()
                } catch (_: Throwable) {
                }
                throw t
            }
        }
    }

    private fun returnSession(session: CachedSession) {
        if (poolVoiceDir == session.voiceDir) {
            sessionPool.offer(session)
        } else {
            synchronized(sessionInitLock) {
                session.closeQuietly()
            }
        }
    }

    private fun discardSession(session: CachedSession) {
        synchronized(sessionInitLock) {
            session.closeQuietly()
        }
    }

    private fun drainPoolLocked() {
        while (true) {
            val s = sessionPool.poll() ?: break
            s.closeQuietly()
        }
    }

    private fun synthesizeRawBlocking(
        voiceDir: String,
        text: String,
        rate: String,
        pitch: String,
        shouldAbort: () -> Boolean = { false }
    ): ByteArray {
        for (attempt in 0..1) {
            if (shouldAbort()) return ByteArray(0)
            val session = try {
                borrowSession(voiceDir)
            } catch (t: Throwable) {
                if (attempt == 1 || shouldAbort()) throw t
                continue
            }
            activeSessions.add(session)
            var sessionHealthy = false
            try {
                if (shouldAbort()) return ByteArray(0)
                val ssml = buildSsml(session.voice, text, rate, pitch)
                val result = session.synthesizer.SpeakSsml(ssml)
                try {
                    if (shouldAbort()) return ByteArray(0)
                    if (result.reason == ResultReason.SynthesizingAudioCompleted) {
                        val audioData = result.audioData
                        if (audioData != null && audioData.isNotEmpty()) {
                            sessionHealthy = true
                            return audioData
                        }
                    } else if (!shouldAbort()) {
                        val details = SpeechSynthesisCancellationDetails.fromResult(result)
                        System.err.println("$TAG: FAIL reason=${details.reason} code=${details.errorCode} details=${details.errorDetails}")
                    }
                } finally {
                    result.close()
                }
            } catch (t: Throwable) {
                if (!shouldAbort()) {
                    System.err.println("$TAG: SpeakSsml attempt ${attempt + 1} failed: ${t.message}")
                }
            } finally {
                activeSessions.remove(session)
                if (sessionHealthy && !shouldAbort()) {
                    returnSession(session)
                } else {
                    discardSession(session)
                }
            }
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

    private fun findPcmSlice(riff: ByteArray, validLimit: Int = riff.size): Pair<Int, Int> {
        if (validLimit < 44) return 0 to validLimit.coerceAtLeast(0)
        if (riff[0] != 'R'.code.toByte() ||
            riff[1] != 'I'.code.toByte() ||
            riff[2] != 'F'.code.toByte() ||
            riff[3] != 'F'.code.toByte()
        ) {
            return 0 to validLimit
        }
        val buffer = ByteBuffer.wrap(riff, 0, validLimit).order(ByteOrder.LITTLE_ENDIAN)
        var i = 12
        while (true) {
            val headerEnd = i + 8
            if (headerEnd > validLimit) return 0 to validLimit
            val chunkSize = buffer.getInt(i + 4)
            if (buffer.getInt(i) == DATA_CHUNK) {
                val safeChunkSize = if (chunkSize > 0) chunkSize else (riff.size - headerEnd)
                val end = minOf(headerEnd.toLong() + safeChunkSize.toLong(), riff.size.toLong()).toInt()
                return headerEnd to (end - headerEnd).coerceAtLeast(0)
            }
            if (chunkSize < 0) return 44 to (riff.size - 44).coerceAtLeast(0)
            val next = i.toLong() + chunkSize.toLong() + 8L + (chunkSize.toLong() and 1L)
            if (next <= i || next > validLimit) return 44 to (riff.size - 44).coerceAtLeast(0)
            i = next.toInt()
        }
    }

    private fun writeWavHeaderInPlace(target: ByteArray, pcmSize: Int) {
        ByteBuffer.wrap(target, 0, 44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put('R'.code.toByte()).put('I'.code.toByte()).put('F'.code.toByte()).put('F'.code.toByte())
            putInt(pcmSize + 36)
            put('W'.code.toByte()).put('A'.code.toByte()).put('V'.code.toByte()).put('E'.code.toByte())
            put('f'.code.toByte()).put('m'.code.toByte()).put('t'.code.toByte()).put(' '.code.toByte())
            putInt(16)
            putShort(1).putShort(1)
            putInt(SAMPLE_RATE).putInt(48000)
            putShort(2).putShort(16)
            put('d'.code.toByte()).put('a'.code.toByte()).put('t'.code.toByte()).put('a'.code.toByte())
            putInt(pcmSize)
        }
    }

    private fun normalizeToWav(rawAudio: ByteArray): ByteArray {
        val (offset, len) = findPcmSlice(rawAudio)
        if (len <= 0) return ByteArray(0)
        // Fast path: standard 44-byte WAV header at the beginning of rawAudio — patch in place with 0 copies
        if (offset == 44 && offset + len == rawAudio.size) {
            writeWavHeaderInPlace(rawAudio, len)
            return rawAudio
        }
        val out = ByteArray(44 + len)
        writeWavHeaderInPlace(out, len)
        System.arraycopy(rawAudio, offset, out, 44, len)
        return out
    }

    fun stripRiff(riff: ByteArray): ByteArray {
        val (offset, len) = findPcmSlice(riff)
        if (offset == 0 && len == riff.size) return riff
        return riff.copyOfRange(offset, offset + len)
    }

    fun buildWav(pcm: ByteArray): ByteArray {
        val out = ByteArray(pcm.size + 44)
        writeWavHeaderInPlace(out, pcm.size)
        System.arraycopy(pcm, 0, out, 44, pcm.size)
        return out
    }

    private data class WavFileSlice(
        val file: File,
        val pcmOffset: Int,
        val pcmLength: Int
    )

    private fun inspectWavFileSlice(file: File): WavFileSlice? {
        val fileLen = file.length()
        if (fileLen <= 0L || fileLen > Int.MAX_VALUE) return null
        val headerProbe = ByteArray(minOf(512L, fileLen).toInt())
        var readTotal = 0
        FileInputStream(file).use { fis ->
            while (readTotal < headerProbe.size) {
                val r = fis.read(headerProbe, readTotal, headerProbe.size - readTotal)
                if (r <= 0) break
                readTotal += r
            }
        }
        if (readTotal <= 0) return null
        if (readTotal >= 44 &&
            headerProbe[0] == 'R'.code.toByte() &&
            headerProbe[1] == 'I'.code.toByte() &&
            headerProbe[2] == 'F'.code.toByte() &&
            headerProbe[3] == 'F'.code.toByte()
        ) {
            val buffer = ByteBuffer.wrap(headerProbe, 0, readTotal).order(ByteOrder.LITTLE_ENDIAN)
            var i = 12
            while (i + 8 <= readTotal) {
                val chunkId = buffer.getInt(i)
                val chunkSize = buffer.getInt(i + 4)
                val dataStart = i + 8
                if (chunkId == DATA_CHUNK) {
                    val avail = (fileLen.toInt() - dataStart).coerceAtLeast(0)
                    val pcmLen = if (chunkSize in 1..avail) chunkSize else avail
                    return if (pcmLen > 0) WavFileSlice(file, dataStart, pcmLen) else null
                }
                if (chunkSize < 0) break
                val step = chunkSize.toLong() + 8L + (chunkSize.toLong() and 1L)
                if (step <= 0L || i.toLong() + step > readTotal.toLong()) break
                i += step.toInt()
            }
            val fallbackLen = (fileLen.toInt() - 44).coerceAtLeast(0)
            return if (fallbackLen > 0) WavFileSlice(file, 44, fallbackLen) else null
        }
        return WavFileSlice(file, 0, fileLen.toInt())
    }

    fun mergeWavFiles(wavFiles: List<File>, deleteAfterRead: Boolean = false): ByteArray {
        if (wavFiles.isEmpty()) return ByteArray(0)
        val slices = ArrayList<WavFileSlice>(wavFiles.size)
        var totalPcmBytes = 0L
        for (file in wavFiles) {
            if (file.exists()) {
                try {
                    val slice = inspectWavFileSlice(file)
                    if (slice != null && slice.pcmLength > 0) {
                        slices.add(slice)
                        totalPcmBytes += slice.pcmLength.toLong()
                    }
                } catch (_: Exception) {
                }
            }
        }
        if (totalPcmBytes <= 0L || totalPcmBytes + 44L > Int.MAX_VALUE) {
            if (deleteAfterRead) {
                for (file in wavFiles) {
                    file.delete()
                }
            }
            return ByteArray(0)
        }

        val totalPcmInt = totalPcmBytes.toInt()
        val out = ByteArray(44 + totalPcmInt)
        writeWavHeaderInPlace(out, totalPcmInt)
        var writePos = 44

        for (slice in slices) {
            try {
                FileInputStream(slice.file).use { fis ->
                    var toSkip = slice.pcmOffset.toLong()
                    while (toSkip > 0L) {
                        val skipped = fis.skip(toSkip)
                        if (skipped > 0L) {
                            toSkip -= skipped
                        } else {
                            if (fis.read() < 0) break
                            toSkip--
                        }
                    }
                    var rem = slice.pcmLength
                    while (rem > 0 && writePos < out.size) {
                        val maxRead = minOf(rem, out.size - writePos)
                        val r = fis.read(out, writePos, maxRead)
                        if (r <= 0) break
                        writePos += r
                        rem -= r
                    }
                }
            } catch (_: Exception) {
            }
        }

        if (deleteAfterRead) {
            for (file in wavFiles) {
                file.delete()
            }
        }

        if (writePos <= 44) return ByteArray(0)
        if (writePos < out.size) {
            val actualPcm = writePos - 44
            writeWavHeaderInPlace(out, actualPcm)
            return out.copyOf(writePos)
        }
        return out
    }

    fun mergeMp3AndWavFilesToMp3(
        partFiles: List<File>,
        introBytes: ByteArray = ByteArray(0),
        targetKbps: Int = 96,
        shouldAbort: () -> Boolean = { false },
        convertWavToMp3: (ByteArray, Int) -> ByteArray?
    ): ByteArray {
        if (partFiles.isEmpty()) return ByteArray(0)
        var allMp3 = true
        var totalMp3Bytes = introBytes.size.toLong()
        for (f in partFiles) {
            if (!f.exists()) continue
            if (f.extension.equals("wav", ignoreCase = true)) {
                allMp3 = false
            } else {
                totalMp3Bytes += f.length().coerceAtLeast(0L)
            }
        }
        if (allMp3) {
            if (totalMp3Bytes <= 0L || totalMp3Bytes > Int.MAX_VALUE) return ByteArray(0)
            val out = ByteArray(totalMp3Bytes.toInt())
            var pos = 0
            if (introBytes.isNotEmpty()) {
                System.arraycopy(introBytes, 0, out, 0, introBytes.size)
                pos += introBytes.size
            }
            for (f in partFiles) {
                if (shouldAbort()) return ByteArray(0)
                if (!f.exists()) continue
                try {
                    FileInputStream(f).use { fis ->
                        while (pos < out.size) {
                            val r = fis.read(out, pos, out.size - pos)
                            if (r <= 0) break
                            pos += r
                        }
                    }
                } catch (_: Exception) {
                }
            }
            if (pos <= introBytes.size) return ByteArray(0)
            return if (pos < out.size) out.copyOf(pos) else out
        }

        val estimatedCap = (totalMp3Bytes + 65536L).coerceIn(4096L, 64L * 1024L * 1024L).toInt()
        val mergeBuffer = ByteArrayOutputStream(estimatedCap)
        if (introBytes.isNotEmpty()) {
            mergeBuffer.write(introBytes)
        }
        val copyBuf = ByteArray(16384)
        for (f in partFiles) {
            if (shouldAbort()) return ByteArray(0)
            if (!f.exists()) continue
            try {
                if (f.extension.equals("wav", ignoreCase = true)) {
                    val rawWav = f.readBytes()
                    val mp3Part = convertWavToMp3(rawWav, targetKbps)
                    if (mp3Part != null && mp3Part.isNotEmpty()) {
                        mergeBuffer.write(mp3Part)
                    }
                } else {
                    FileInputStream(f).use { fis ->
                        while (true) {
                            val r = fis.read(copyBuf)
                            if (r <= 0) break
                            mergeBuffer.write(copyBuf, 0, r)
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        return mergeBuffer.toByteArray()
    }
}
