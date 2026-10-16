package dm1sh.hedgehog.accessibility

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

class ScreenReaderTts(
    private val context: Context,
    private val onReady: () -> Unit = {}
) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var initialized = false
    private var availableVoices: List<Voice> = emptyList()
    var voiceNames: List<String> = emptyList()
        private set

    init {
        if (canUseTTS(context)) {
            try {
                tts = TextToSpeech(context.applicationContext, this)
            } catch (_: Exception) {
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            initialized = true
            reloadVoices(Locale.getDefault().language)
            onReady()
        }
    }

    fun reloadVoices(localeCode: String) {
        val engine = tts ?: return
        if (!initialized) return
        try {
            val allVoices = engine.voices?.toList().orEmpty()
            val langPrefix = localeCode.replace("-", "_").substringBefore("_").lowercase(Locale.ROOT)
            var filtered = allVoices.filter { v ->
                v.locale?.language?.lowercase(Locale.ROOT) == langPrefix
            }
            if (filtered.isEmpty()) {
                filtered = allVoices
            }
            availableVoices = filtered.sortedBy { it.name }
            voiceNames = availableVoices.map { it.name.substringAfterLast("\\") }
        } catch (_: Exception) {
            availableVoices = emptyList()
            voiceNames = emptyList()
        }
    }

    fun speak(
        message: String,
        selectedUiTtsIndex: Int,
        ratePercent: Int,
        hard: Boolean = false,
        stopFirst: Boolean = true
    ) {
        if (message.isBlank()) return
        if (selectedUiTtsIndex <= 0 && !hard) return
        val engine = tts ?: return
        if (!initialized) return
        try {
            val voiceIdx = (selectedUiTtsIndex - 1).coerceIn(0, (availableVoices.size - 1).coerceAtLeast(0))
            if (availableVoices.isNotEmpty()) {
                engine.voice = availableVoices[voiceIdx]
            }
            engine.setSpeechRate((1.0f + ratePercent / 100.0f).coerceIn(0.5f, 3.0f))
            val queueMode = if (stopFirst) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            engine.speak(message, queueMode, null, "uitts_${System.currentTimeMillis()}")
        } catch (_: Exception) {
        }
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }
    }

    companion object {
        fun canUseTTS(context: Context): Boolean {
            return try {
                val intent = Intent(TextToSpeech.Engine.ACTION_CHECK_TTS_DATA)
                val list = context.packageManager?.queryIntentActivities(intent, 0)
                !list.isNullOrEmpty()
            } catch (_: Exception) {
                false
            }
        }
    }
}
