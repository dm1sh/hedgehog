package dm1sh.hedgehog.tts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Minimal FFmpeg + libopus + libmp3lame JNI wrapper for transcoding audio buffers
 * (from online Edge TTS streams or offline TTS synthesis) to .opus using exact FFmpeg options:
 *   -c:a libopus -b:a 32k -ac 1 -application voip -frame_duration 60 -vbr on
 * or .mp3 via libmp3lame, and merging multiple .opus part files into a single Ogg Opus stream.
 * On desktop JVM (Linux), loads bundled /NATIVE/linux-x64/libhedgehog_opus.so from resources
 * if present, or falls back to invoking system `ffmpeg`.
 */
object OpusConverter {

    private val nativeLoaded: Boolean = loadNativeLibrary()

    private fun loadNativeLibrary(): Boolean {
        try {
            System.loadLibrary("hedgehog_opus")
            return true
        } catch (_: Throwable) {
        }
        return try {
            val osName = System.getProperty("os.name", "").lowercase(Locale.ROOT)
            val osArch = System.getProperty("os.arch", "").lowercase(Locale.ROOT)
            val platformDir = when {
                osName.contains("linux") && (osArch == "amd64" || osArch == "x86_64") -> "linux-x64"
                osName.contains("linux") && (osArch == "aarch64" || osArch == "arm64") -> "linux-arm64"
                else -> null
            } ?: return false
            val resPath = "/NATIVE/$platformDir/libhedgehog_opus.so"
            val stream = OpusConverter::class.java.getResourceAsStream(resPath) ?: return false
            val tmpFile = File.createTempFile("libhedgehog_opus_", ".so")
            tmpFile.deleteOnExit()
            stream.use { input ->
                FileOutputStream(tmpFile).use { output ->
                    input.copyTo(output)
                }
            }
            System.load(tmpFile.absolutePath)
            true
        } catch (_: Throwable) {
            false
        }
    }

    @JvmStatic
    private external fun nativeConvertMp3ToOpus(mp3Bytes: ByteArray): ByteArray?

    @JvmStatic
    private external fun nativeConvertWavToMp3(wavBytes: ByteArray, kbps: Int): ByteArray?

    @JvmStatic
    private external fun nativeMergeOpusFiles(inputPaths: Array<String>, outputPath: String): Boolean

    fun convertMp3ToOpus(mp3Bytes: ByteArray): ByteArray? {
        if (mp3Bytes.isEmpty()) return null
        if (nativeLoaded) {
            return try {
                nativeConvertMp3ToOpus(mp3Bytes)
            } catch (t: Throwable) {
                System.err.println("OpusConverter: convertMp3ToOpus failed: ${t.message}")
                null
            }
        }
        return ffmpegTranscodeBytes(
            inputBytes = mp3Bytes,
            inSuffix = ".bin",
            outSuffix = ".opus",
            ffmpegArgs = listOf(
                "-vn",
                "-c:a", "libopus",
                "-b:a", "32k",
                "-ac", "1",
                "-application", "voip",
                "-frame_duration", "60",
                "-vbr", "on"
            )
        )
    }

    fun convertWavToMp3(wavBytes: ByteArray, kbps: Int): ByteArray? {
        if (wavBytes.isEmpty()) return null
        if (nativeLoaded) {
            return try {
                nativeConvertWavToMp3(wavBytes, kbps)
            } catch (t: Throwable) {
                System.err.println("OpusConverter: convertWavToMp3 failed: ${t.message}")
                null
            }
        }
        return ffmpegTranscodeBytes(
            inputBytes = wavBytes,
            inSuffix = ".wav",
            outSuffix = ".mp3",
            ffmpegArgs = listOf(
                "-vn",
                "-c:a", "libmp3lame",
                "-b:a", "${kbps}k",
                "-ac", "1"
            )
        )
    }

    /**
     * Splits a 24 kHz 16-bit mono WAV buffer along MPEG-2 Layer III frame boundaries
     * (576 samples = 1152 PCM bytes) and encodes slices in parallel across CPU cores.
     */
    suspend fun convertWavToMp3Parallel(
        wavBytes: ByteArray,
        kbps: Int,
        maxWorkers: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    ): ByteArray? = coroutineScope {
        if (wavBytes.isEmpty()) return@coroutineScope null
        val pcm = OfflineTtsEngine.stripRiff(wavBytes)
        // 1152 bytes = 576 16-bit mono samples (1 MPEG-2 MP3 frame at 24 kHz)
        val frameAlignBytes = 1152
        val minSliceBytes = frameAlignBytes * 200 // ~4.8s per slice minimum
        val workers = minOf(maxWorkers.coerceAtLeast(1), (pcm.size / minSliceBytes).coerceAtLeast(1))
        if (workers <= 1 || pcm.size <= minSliceBytes) {
            return@coroutineScope convertWavToMp3(wavBytes, kbps)
        }

        val rawSliceSize = pcm.size / workers
        val alignedSliceSize = ((rawSliceSize + frameAlignBytes - 1) / frameAlignBytes) * frameAlignBytes
        val ranges = ArrayList<IntRange>(workers)
        var offset = 0
        while (offset < pcm.size) {
            val end = if (ranges.size == workers - 1) pcm.size else minOf(offset + alignedSliceSize, pcm.size)
            ranges.add(offset until end)
            offset = end
        }

        val encodedSlices = ranges.map { range ->
            async(Dispatchers.Default) {
                val slicePcm = pcm.copyOfRange(range.first, range.last + 1)
                val sliceWav = OfflineTtsEngine.buildWav(slicePcm)
                convertWavToMp3(sliceWav, kbps)
            }
        }.awaitAll()

        var totalBytes = 0
        for (slice in encodedSlices) {
            if (slice == null || slice.isEmpty()) return@coroutineScope null
            totalBytes += slice.size
        }
        val out = ByteArrayOutputStream(totalBytes)
        for (slice in encodedSlices) {
            if (slice != null) out.write(slice)
        }
        out.toByteArray()
    }

    /**
     * Splits a 24 kHz 16-bit mono WAV buffer along 60ms Opus frame boundaries
     * (1440 samples @ 24kHz = 2880 PCM bytes), encodes slices to .opus in parallel,
     * and remuxes them (plus optional introOpus) into a single continuous Ogg Opus buffer.
     */
    suspend fun convertWavToOpusParallel(
        wavBytes: ByteArray,
        tempDir: File,
        introOpus: ByteArray? = null,
        maxWorkers: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    ): ByteArray? = coroutineScope {
        if (wavBytes.isEmpty()) return@coroutineScope null
        val pcm = OfflineTtsEngine.stripRiff(wavBytes)
        // 2880 bytes = 1440 16-bit mono samples @ 24kHz = 60ms Opus frame (2880 samples @ 48kHz)
        val frameAlignBytes = 2880
        val minSliceBytes = frameAlignBytes * 100 // ~6s per slice minimum
        val workers = minOf(maxWorkers.coerceAtLeast(1), (pcm.size / minSliceBytes).coerceAtLeast(1))

        if (workers <= 1 && (introOpus == null || introOpus.isEmpty())) {
            return@coroutineScope convertMp3ToOpus(wavBytes)
        }

        val ranges = ArrayList<IntRange>(workers)
        if (workers <= 1) {
            ranges.add(0 until pcm.size)
        } else {
            val rawSliceSize = pcm.size / workers
            val alignedSliceSize = ((rawSliceSize + frameAlignBytes - 1) / frameAlignBytes) * frameAlignBytes
            var offset = 0
            while (offset < pcm.size) {
                val end = if (ranges.size == workers - 1) pcm.size else minOf(offset + alignedSliceSize, pcm.size)
                ranges.add(offset until end)
                offset = end
            }
        }

        val encodedSlices = ranges.map { range ->
            async(Dispatchers.Default) {
                val slicePcm = pcm.copyOfRange(range.first, range.last + 1)
                val sliceWav = OfflineTtsEngine.buildWav(slicePcm)
                convertMp3ToOpus(sliceWav)
            }
        }.awaitAll()

        val tempFiles = ArrayList<File>(encodedSlices.size + 1)
        val mergedFile = File.createTempFile("opus_merged_", ".opus", tempDir)
        try {
            if (introOpus != null && introOpus.isNotEmpty()) {
                val introFile = File.createTempFile("opus_intro_", ".opus", tempDir)
                introFile.writeBytes(introOpus)
                tempFiles.add(introFile)
            }
            for (slice in encodedSlices) {
                if (slice == null || slice.isEmpty()) return@coroutineScope null
                val f = File.createTempFile("opus_slice_", ".opus", tempDir)
                f.writeBytes(slice)
                tempFiles.add(f)
            }
            if (tempFiles.size == 1) {
                return@coroutineScope tempFiles[0].readBytes()
            }
            val ok = mergeOpusFiles(tempFiles.map { it.absolutePath }, mergedFile.absolutePath)
            if (ok && mergedFile.exists() && mergedFile.length() > 0L) {
                mergedFile.readBytes()
            } else {
                null
            }
        } finally {
            tempFiles.forEach { it.delete() }
            mergedFile.delete()
        }
    }

    /**
     * Partitions a list of MP3 part buffers into up to [maxWorkers] contiguous batches,
     * transcodes each batch to .opus in parallel across CPU cores, and remuxes into a single .opus buffer.
     */
    suspend fun convertMp3PartsToOpusParallel(
        mp3Parts: List<ByteArray>,
        tempDir: File,
        maxWorkers: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    ): ByteArray? = coroutineScope {
        val nonEmpty = mp3Parts.filter { it.isNotEmpty() }
        if (nonEmpty.isEmpty()) return@coroutineScope null
        val workers = minOf(maxWorkers.coerceAtLeast(1), nonEmpty.size)
        if (workers <= 1) {
            val out = ByteArrayOutputStream()
            for (part in nonEmpty) out.write(part)
            return@coroutineScope convertMp3ToOpus(out.toByteArray())
        }

        val groups = ArrayList<List<ByteArray>>(workers)
        val total = nonEmpty.size
        var idx = 0
        for (w in 0 until workers) {
            val remItems = total - idx
            val remWorkers = workers - w
            val take = (remItems + remWorkers - 1) / remWorkers
            if (take > 0) {
                groups.add(nonEmpty.subList(idx, idx + take))
                idx += take
            }
        }

        val encodedGroups = groups.map { group ->
            async(Dispatchers.Default) {
                val buf = ByteArrayOutputStream()
                for (b in group) buf.write(b)
                convertMp3ToOpus(buf.toByteArray())
            }
        }.awaitAll()

        if (encodedGroups.size == 1) {
            return@coroutineScope encodedGroups[0]
        }

        val tempFiles = ArrayList<File>(encodedGroups.size)
        val mergedFile = File.createTempFile("opus_mp3_merged_", ".opus", tempDir)
        try {
            for (slice in encodedGroups) {
                if (slice == null || slice.isEmpty()) return@coroutineScope null
                val f = File.createTempFile("opus_mp3_slice_", ".opus", tempDir)
                f.writeBytes(slice)
                tempFiles.add(f)
            }
            val ok = mergeOpusFiles(tempFiles.map { it.absolutePath }, mergedFile.absolutePath)
            if (ok && mergedFile.exists() && mergedFile.length() > 0L) {
                mergedFile.readBytes()
            } else {
                null
            }
        } finally {
            tempFiles.forEach { it.delete() }
            mergedFile.delete()
        }
    }

    fun mergeOpusFiles(inputPaths: List<String>, outputPath: String): Boolean {
        if (inputPaths.isEmpty()) return false
        if (nativeLoaded) {
            return try {
                nativeMergeOpusFiles(inputPaths.toTypedArray(), outputPath)
            } catch (t: Throwable) {
                System.err.println("OpusConverter: mergeOpusFiles failed: ${t.message}")
                false
            }
        }
        if (inputPaths.size == 1) {
            return try {
                val src = File(inputPaths[0])
                val dst = File(outputPath)
                dst.parentFile?.mkdirs()
                src.copyTo(dst, overwrite = true)
                true
            } catch (_: Throwable) {
                false
            }
        }
        val listFile = try {
            File.createTempFile("hedgehog_concat_", ".txt")
        } catch (_: Throwable) {
            return false
        }
        return try {
            val content = inputPaths.joinToString("\n") { p ->
                val escaped = File(p).absolutePath.replace("'", "'\\''")
                "file '$escaped'"
            }
            listFile.writeText(content, Charsets.UTF_8)
            val dst = File(outputPath)
            dst.parentFile?.mkdirs()
            val cmd = listOf(
                "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                "-f", "concat", "-safe", "0",
                "-i", listFile.absolutePath,
                "-c", "copy",
                dst.absolutePath
            )
            val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            val code = proc.waitFor()
            if (code == 0 && dst.exists() && dst.length() > 0L) {
                true
            } else {
                System.err.println("OpusConverter: ffmpeg concat failed ($code): $out")
                false
            }
        } catch (t: Throwable) {
            System.err.println("OpusConverter: mergeOpusFiles via ffmpeg failed: ${t.message}")
            false
        } finally {
            listFile.delete()
        }
    }

    private fun ffmpegTranscodeBytes(
        inputBytes: ByteArray,
        inSuffix: String,
        outSuffix: String,
        ffmpegArgs: List<String>
    ): ByteArray? {
        val inFile = try {
            File.createTempFile("hedgehog_in_", inSuffix)
        } catch (_: Throwable) {
            return null
        }
        val outFile = try {
            File.createTempFile("hedgehog_out_", outSuffix)
        } catch (_: Throwable) {
            inFile.delete()
            return null
        }
        return try {
            inFile.writeBytes(inputBytes)
            val cmd = mutableListOf(
                "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                "-i", inFile.absolutePath
            )
            cmd.addAll(ffmpegArgs)
            cmd.add(outFile.absolutePath)
            val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val errOut = proc.inputStream.bufferedReader().readText()
            val code = proc.waitFor()
            if (code == 0 && outFile.exists() && outFile.length() > 0L) {
                outFile.readBytes()
            } else {
                System.err.println("OpusConverter: ffmpeg failed ($code): $errOut. Please ensure 'ffmpeg' is installed on your system.")
                null
            }
        } catch (t: Throwable) {
            System.err.println("OpusConverter: failed to run 'ffmpeg' (${t.message}). Please ensure 'ffmpeg' is installed on your system.")
            null
        } finally {
            inFile.delete()
            outFile.delete()
        }
    }
}
