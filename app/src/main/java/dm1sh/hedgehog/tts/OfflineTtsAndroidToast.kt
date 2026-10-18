package dm1sh.hedgehog.tts

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import dm1sh.hedgehog.data.SettingsRepository
import dm1sh.hedgehog.localization.LocalizationManager
import java.util.concurrent.atomic.AtomicLong

private const val FALLBACK_TOAST_COOLDOWN_MSEC = 5_000L
private val lastFallbackToastMsec = AtomicLong(0L)
private val mainHandler = Handler(Looper.getMainLooper())

fun OfflineTtsEngine.showFallbackToast(context: Context, message: String? = null, force: Boolean = false) {
    val now = System.currentTimeMillis()
    val prev = lastFallbackToastMsec.get()
    if (!force && prev > 0L && now - prev < FALLBACK_TOAST_COOLDOWN_MSEC) {
        return
    }
    if (!force && !lastFallbackToastMsec.compareAndSet(prev, now)) {
        return
    }
    if (force) {
        lastFallbackToastMsec.set(now)
    }
    val appCtx = context.applicationContext
    val resolvedText = if (!message.isNullOrBlank()) {
        message
    } else {
        try {
            val repo = SettingsRepository(appCtx)
            val settings = repo.loadSettings()
            val loc = LocalizationManager(appCtx)
            val langs = listOf("AUTO") + loc.loadedLocales
            val idx = settings.languagesSelect.coerceIn(0, (langs.size - 1).coerceAtLeast(0))
            loc.setLocale(langs[idx])
            loc.tr("KEY_OFFLINE_FALLBACK_TOAST")
        } catch (_: Throwable) {
            "Online voice unavailable — falling back to offline voice"
        }
    }
    mainHandler.post {
        try {
            Toast.makeText(appCtx, resolvedText, Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
        }
    }
}
