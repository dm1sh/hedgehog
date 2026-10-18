package dm1sh.hedgehog.model

import androidx.compose.ui.graphics.Color

data class AppSettings(
    val voicesSelect: Int = 0,
    val voicesName: String = "ru-RU, DmitryNeural",
    val offlineVoicesSelect: Int = 0,
    val offlineVoicesName: String = "",
    val useOnlineVoices: Boolean = true,
    val useOnlineVoicesPlayer: Boolean = false,
    val useOnlineVoicesSystemTts: Boolean = false,
    val rate: Int = 100,
    val pitch: Int = 0,
    val maxThreads: Int = DEFAULT_MAX_THREADS,
    val restartDelay: Int = DEFAULT_RESTART_DELAY,
    val mergeFiles: Int = 50,
    val loadDir: String = "",
    val saveDir: String = "",
    val lexxPath: String = "",
    val regexGlavaCompile: String = DEFAULT_REGEX_GLAVA,
    val lexxUse: Boolean = false,
    val lexxRegister: Boolean = true,
    val bookDirCreate: Boolean = true,
    val lowBitrate: Boolean = false,
    val opusOutput: Boolean = false,
    val inlineNotes: Boolean = false,
    val embedMetadata: Boolean = true,
    val filterEmojis: Boolean = true,
    val voicePreview: Boolean = false,
    val bookChapter: Boolean = false,
    val dopSettingsPressed: Boolean = false,
    val showTextAreaPressed: Boolean = false,
    val group4Visible: Boolean = false,
    val loadDirUse: Boolean = false,
    val recycleBinUse: Boolean = false,
    val zoomUi: Float = 1.0f,
    val fontSize: Int = 16,
    val glavMp3Path: String = "",
    val glavMp3Use: Boolean = false,
    val glavTextUse: Boolean = false,
    val languagesSelect: Int = 0,
    val group1Visible: Boolean = true,
    val group2Visible: Boolean = true,
    val group6Visible: Boolean = false,
    val group7Visible: Boolean = false,
    val group8Visible: Boolean = false,
    val languagesUiTtsSelect: Int = 0,
    val appVersion: String = "1.0.1.24"
) {
    companion object {
        const val DEFAULT_MAX_THREADS = 50
        const val DEFAULT_RESTART_DELAY = 10000
        const val DEFAULT_REGEX_GLAVA = "глава |раздел |эпизод |рассказ |chapter "

        val RESTART_DELAY_STEPS = listOf(
            10, 20, 30, 40,
            60, 80, 100,
            200, 300, 400,
            600, 800, 1000,
            2000, 3000, 4000,
            6000, 8000, 10000
        )

        val BOOK_EXTENSIONS = setOf("txt", "fb2", "epub", "ini", "csv", "zip")

        fun restartDelaySecondsFromIndex(index: Int): Int {
            return if (index in RESTART_DELAY_STEPS.indices) RESTART_DELAY_STEPS[index] else -1
        }

        fun restartDelayIndexFromSeconds(seconds: Int): Int {
            if (seconds < 0) return RESTART_DELAY_STEPS.size
            var best = 0
            for (i in RESTART_DELAY_STEPS.indices) {
                if (kotlin.math.abs(RESTART_DELAY_STEPS[i] - seconds) < kotlin.math.abs(RESTART_DELAY_STEPS[best] - seconds)) {
                    best = i
                }
            }
            return best
        }
    }
}

enum class PartStatus(val code: Int, val trKey: String, val color: Color) {
    OPENED(0, "KEY_OPENED", Color(0xFF2E2E2E)),
    STARTED(1, "KEY_STARTED", Color(0xFFD97706)),
    PROCESSING(2, "KEY_PROCESSING", Color(0xFFB45309)),
    ERROR_RERUN(3, "KEY_ERROR_RERUN", Color(0xFFDC2626)),
    SAVED(4, "KEY_SAVED", Color(0xFF059669)),
    PAUSE(5, "KEY_PAUSE", Color(0xFF6E6E6E));

    companion object {
        fun fromCode(code: Int): PartStatus = entries.getOrElse(code) { OPENED }
        fun fromKey(key: String): PartStatus = entries.firstOrNull { it.trKey == key } ?: OPENED
    }
}
