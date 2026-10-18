package dm1sh.hedgehog.tts

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
                "-ar", "24000",
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
