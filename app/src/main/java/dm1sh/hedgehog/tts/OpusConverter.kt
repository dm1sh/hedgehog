package dm1sh.hedgehog.tts

import android.util.Log

/**
 * Minimal FFmpeg + libopus JNI wrapper for transcoding MP3 audio buffers
 * (from online Edge TTS streams or offline TTS synthesis) to .opus using exact FFmpeg options:
 *   -c:a libopus -b:a 32k -ac 1 -application voip -frame_duration 60 -vbr on
 * and merging multiple .opus part files into a single continuous Ogg Opus stream.
 */
object OpusConverter {
    private const val TAG = "OpusConverter"

    private val nativeLoaded: Boolean = try {
        System.loadLibrary("hedgehog_opus")
        true
    } catch (t: Throwable) {
        Log.e(TAG, "Failed to load libhedgehog_opus.so", t)
        false
    }

    @JvmStatic
    private external fun nativeConvertMp3ToOpus(mp3Bytes: ByteArray): ByteArray?

    @JvmStatic
    private external fun nativeMergeOpusFiles(inputPaths: Array<String>, outputPath: String): Boolean

    fun convertMp3ToOpus(mp3Bytes: ByteArray): ByteArray? {
        if (!nativeLoaded || mp3Bytes.isEmpty()) return null
        return try {
            nativeConvertMp3ToOpus(mp3Bytes)
        } catch (t: Throwable) {
            Log.e(TAG, "convertMp3ToOpus failed", t)
            null
        }
    }

    fun mergeOpusFiles(inputPaths: List<String>, outputPath: String): Boolean {
        if (!nativeLoaded || inputPaths.isEmpty()) return false
        return try {
            nativeMergeOpusFiles(inputPaths.toTypedArray(), outputPath)
        } catch (t: Throwable) {
            Log.e(TAG, "mergeOpusFiles failed", t)
            false
        }
    }
}
