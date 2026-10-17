package dm1sh.hedgehog.ui

import android.app.Application
import android.media.MediaPlayer
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dm1sh.hedgehog.accessibility.ScreenReaderTts
import dm1sh.hedgehog.book.AudioMetadataTagger
import dm1sh.hedgehog.book.BookAudioMetadata
import dm1sh.hedgehog.book.ProcessingFile
import dm1sh.hedgehog.book.TextsConverter
import dm1sh.hedgehog.data.SettingsRepository
import dm1sh.hedgehog.localization.LocalizationManager
import dm1sh.hedgehog.model.AppSettings
import dm1sh.hedgehog.model.PartStatus
import dm1sh.hedgehog.service.ForegroundWorkService
import dm1sh.hedgehog.service.PlayerNotificationService
import dm1sh.hedgehog.tts.OfflineInstaller
import dm1sh.hedgehog.tts.OfflineTtsEngine
import dm1sh.hedgehog.tts.OpusConverter
import dm1sh.hedgehog.tts.SecMsGecGenerator
import dm1sh.hedgehog.tts.SocketEdgeTtsWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.math.roundToInt

enum class MainTab {
    BOOK, PARTS, PLAYER, SETTINGS
}

data class PlayerSentenceItem(
    val text: String,
    val startsNewParagraph: Boolean = false,
    val startsNewChapter: Boolean = false,
    val chapterTitle: String? = null
)

enum class SettingsTab {
    VOICE, BOOK, TOOL, INTERFACE
}

enum class NativePickerType {
    OPEN_BOOKS, SAVE_DIR, LOAD_DIR, LEXX_FILE, GLAV_MP3
}

enum class DopModal {
    NONE, REGEX_GLAVA, RECYCLE_BIN
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val repo = SettingsRepository(application)
    val localization = LocalizationManager(application)
    val screenReader = ScreenReaderTts(application) {
        refreshUiTtsList()
    }

    var settings by mutableStateOf(repo.loadSettings())
        private set

    var localeVersion by mutableIntStateOf(0)
        private set

    // Dropdown lists
    val voicesList = mutableStateListOf<String>()
    val offlineVoicesList = mutableStateListOf<String>()
    val languagesList = mutableStateListOf<String>()
    val uiTtsList = mutableStateListOf<String>()

    var voicesDropdownOpen by mutableStateOf(false)
    var offlineVoicesDropdownOpen by mutableStateOf(false)
    var languagesDropdownOpen by mutableStateOf(false)
    var uiTtsDropdownOpen by mutableStateOf(false)

    // UI navigation states
    var activeMainTab by mutableStateOf(MainTab.BOOK)
    var activeSettingsTab by mutableStateOf(SettingsTab.VOICE)
    var dopModal by mutableStateOf(DopModal.NONE)

    // Files inside Load directory (shown on Book tab when no book is loaded)
    val loadDirFiles = mutableStateListOf<File>()

    // Chapter modal temp states
    var tempRegexGlava by mutableStateOf("")
    var tempGlavMp3Use by mutableStateOf(false)
    var tempGlavTextUse by mutableStateOf(false)
    var tempGlavMp3Path by mutableStateOf("")

    // Status & progress states
    var statInfo by mutableStateOf("")
    var statInfoDoneHighlight by mutableStateOf(false)
    var statStr by mutableStateOf("0/0")
    var chunksStatStr by mutableStateOf("0/0")
    var processedPartsCount by mutableIntStateOf(0)
    var totalPartsCount by mutableIntStateOf(0)
    var postprocessedChunksCount by mutableIntStateOf(0)
    var totalChunksCount by mutableIntStateOf(0)
    var elapsedTimeText by mutableStateOf("")
    var elapsedVisible by mutableStateOf(false)
    var workFinished by mutableStateOf(true)

    var progressBarVisible by mutableStateOf(false)
    var progressBarValue by mutableIntStateOf(0)
    var progressBarMax by mutableIntStateOf(1000)

    val partStatuses = mutableStateListOf<PartStatus>()

    // Conversion state
    var runWork by mutableStateOf(false)
        private set
    var workPaused by mutableStateOf(false)
        private set

    var onlineVoicesBusy by mutableStateOf(false)
        private set
    var offlineVoicesBusy by mutableStateOf(false)
        private set

    // Book & Part Editor state
    private var book: ProcessingFile? = null
    var bookLoaded by mutableStateOf(false)
        private set
    var isBookLoading by mutableStateOf(false)
        private set
    var loadedBooksCount by mutableIntStateOf(0)
        private set
    val bookTitles = mutableStateListOf<String>()
    private val filesList = mutableListOf<String>()
    private var lexxRules: List<String> = emptyList()
    private var numTextCounter = 0

    var textAreaText by mutableStateOf("")
    var editorChapterTitleText by mutableStateOf("")
    var editorHasChapters by mutableStateOf(false)
        private set
    var editorHasPrevChapter by mutableStateOf(false)
        private set
    var editorHasNextChapter by mutableStateOf(false)
        private set
    var editorPartIndex by mutableIntStateOf(0)
    var editorPartFieldText by mutableStateOf("0")
    var partPreviewActive by mutableStateOf(false)
        private set

    private var editorSnapshot: List<String> = emptyList()
    private var editorSnapshotGlavs: List<String> = emptyList()
    private var editorSnapshotIndex = 0
    private var editorSnapshotBook: ProcessingFile? = null

    // Built-in Player state
    var playerSentences by mutableStateOf<List<PlayerSentenceItem>>(emptyList())
        private set
    val playerSentenceStatuses = mutableStateMapOf<Int, PartStatus>()
    var playerSelectedIndex by mutableIntStateOf(0)
        private set
    var playerIsPlaying by mutableStateOf(false)
        private set
    var playerBookKey by mutableStateOf<String?>(null)
        private set
    var playerIsSharedText by mutableStateOf(false)
        private set
    private var lastSharedInput: Pair<String, String?>? = null
    val playerBookmarks = mutableStateListOf<Int>()
    private val playerRunIdGen = AtomicInteger(0)
    private val playerSessionIdGen = AtomicInteger(0)
    private var playerCoordinatorJob: Job? = null
    private var playerMediaPlayer: MediaPlayer? = null
    private val playerCachedFiles = ConcurrentHashMap<Int, File>()

    // Jobs & synchronization
    private var conversionMasterJob: Job? = null
    private var elapsedJob: Job? = null
    private var previewJob: Job? = null
    private var mediaPlayer: MediaPlayer? = null
    private var voicePreviewPrevStatus: String? = null

    private var workStartMsec = 0L
    private var pauseIdleSince = 0L
    private val runIdGen = AtomicInteger(0)
    private val mergeMutex = Mutex()

    private val httpClient = SecMsGecGenerator.createBingHttpClient(20, 20)

    // 10-fast-click screen reader toggle
    private var lastTapMsec = 0L
    private var fastTapCount = 0

    init {
        OfflineTtsEngine.setMaxJobs(settings.maxThreads)
        PlayerNotificationService.actionListener = object : PlayerNotificationService.ActionListener {
            override fun onPrevious() {
                viewModelScope.launch(Dispatchers.Main) {
                    onPlayerPrevPressed()
                }
            }

            override fun onPlayPause() {
                viewModelScope.launch(Dispatchers.Main) {
                    onPlayerPlayPausePressed()
                }
            }

            override fun onNext() {
                viewModelScope.launch(Dispatchers.Main) {
                    onPlayerNextPressed()
                }
            }
        }
        setupInitialLists()
        loadLexx()
        refreshLoadDirFiles()
        activeSettingsTab = when {
            settings.group8Visible -> SettingsTab.INTERFACE
            settings.group7Visible -> SettingsTab.TOOL
            settings.group6Visible -> SettingsTab.BOOK
            else -> SettingsTab.VOICE
        }
    }

    fun refreshLoadDirFiles() {
        val dirPath = settings.loadDir.ifBlank { repo.defaultDownloadsDir }
        val dirFile = File(dirPath)
        val found = if (dirFile.exists() && dirFile.isDirectory) {
            dirFile.listFiles()
                ?.filter { it.isFile && AppSettings.BOOK_EXTENSIONS.contains(it.extension.lowercase()) }
                ?.sortedBy { it.name.lowercase() }
                .orEmpty()
        } else {
            emptyList()
        }
        loadDirFiles.clear()
        loadDirFiles.addAll(found)
    }

    fun selectMainTab(tab: MainTab) {
        if (activeMainTab == tab) return
        if (activeMainTab == MainTab.PARTS && tab != MainTab.PARTS) {
            if (partPreviewActive) voicePreviewStop()
            if (!runWork) {
                revertEditorSnapshotIfPossible()
            } else {
                clearEditorSnapshot()
            }
        }
        if (tab == MainTab.PARTS && activeMainTab != MainTab.PARTS) {
            val b = book
            if (b != null) {
                editorPartIndex = editorPartIndex.coerceIn(0, (b.allSentences.size - 1).coerceAtLeast(0))
            }
            editorLoadCurrentPart()
            takeEditorSnapshot()
        }
        if (tab == MainTab.PLAYER && book == null && textAreaText.isNotBlank()) {
            onEditorSavePressed()
        }
        if (tab == MainTab.BOOK) {
            refreshLoadDirFiles()
        }
        activeMainTab = tab
        updateSettings {
            it.copy(
                showTextAreaPressed = (tab == MainTab.PARTS),
                dopSettingsPressed = (tab == MainTab.SETTINGS)
            )
        }
        val key = when (tab) {
            MainTab.BOOK -> "KEY_BOOK"
            MainTab.PARTS -> "KEY_VIEW_TEXT"
            MainTab.PLAYER -> "KEY_PLAYER"
            MainTab.SETTINGS -> "KEY_SETTINGS"
        }
        ttsSay(tr(key))
    }

    fun tr(key: String): String {
        @Suppress("UNUSED_EXPRESSION")
        localeVersion
        return localization.tr(key)
    }

    private fun setupInitialLists() {
        languagesList.clear()
        languagesList.add("AUTO")
        languagesList.addAll(localization.loadedLocales)
        val langIdx = settings.languagesSelect.coerceIn(0, (languagesList.size - 1).coerceAtLeast(0))
        val selectedLang = languagesList.getOrElse(langIdx) { "AUTO" }
        localization.setLocale(selectedLang)
        localeVersion++

        refreshVoicesList()
        refreshUiTtsList()
    }

    fun refreshVoicesList() {
        val online = repo.onlineVoices
        voicesList.clear()
        voicesList.addAll(online)

        val offline = repo.readyOfflineVoices()
        offlineVoicesList.clear()
        offlineVoicesList.addAll(offline)

        var migratedOfflineName = settings.offlineVoicesName
        if (migratedOfflineName.isBlank() && repo.isVoiceOffline(settings.voicesName)) {
            migratedOfflineName = settings.voicesName
        }

        var onlineSelect = settings.voicesSelect
        var onlineName = settings.voicesName
        if (voicesList.isNotEmpty()) {
            onlineSelect = onlineSelect.coerceIn(0, voicesList.size - 1)
            val foundIdx = voicesList.indexOfFirst { it == onlineName }
            if (foundIdx >= 0) {
                onlineSelect = foundIdx
            } else {
                onlineSelect = 0
            }
            onlineName = voicesList[onlineSelect]
        }

        var offlineSelect = settings.offlineVoicesSelect
        var offlineName = migratedOfflineName
        if (offlineVoicesList.isNotEmpty()) {
            offlineSelect = offlineSelect.coerceIn(0, offlineVoicesList.size - 1)
            val foundIdx = offlineVoicesList.indexOfFirst { it == offlineName }
            if (foundIdx >= 0) {
                offlineSelect = foundIdx
            } else {
                offlineSelect = 0
            }
            offlineName = offlineVoicesList[offlineSelect]
        } else {
            offlineSelect = 0
            offlineName = ""
        }

        updateSettings {
            it.copy(
                voicesSelect = onlineSelect,
                voicesName = onlineName,
                offlineVoicesSelect = offlineSelect,
                offlineVoicesName = offlineName
            )
        }
    }

    fun refreshUiTtsList() {
        screenReader.reloadVoices(localization.currentLocale)
        uiTtsList.clear()
        uiTtsList.add("KEY_OFF")
        uiTtsList.addAll(screenReader.voiceNames)
        val sel = settings.languagesUiTtsSelect.coerceIn(0, (uiTtsList.size - 1).coerceAtLeast(0))
        if (sel != settings.languagesUiTtsSelect) {
            updateSettings { it.copy(languagesUiTtsSelect = sel) }
        }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val oldLoadDir = settings.loadDir
        settings = transform(settings)
        repo.saveSettings(settings)
        if (settings.loadDir != oldLoadDir) {
            refreshLoadDirFiles()
        }
    }

    fun ttsSay(message: String, hard: Boolean = false, stopFirst: Boolean = true) {
        if (message.isNotBlank()) {
            screenReader.speak(
                message = message,
                selectedUiTtsIndex = settings.languagesUiTtsSelect,
                ratePercent = settings.rate,
                hard = hard,
                stopFirst = stopFirst
            )
        }
    }

    fun voiceSayText(voice: String): String {
        return if (repo.isVoiceOffline(voice)) {
            "$voice. ${tr("KEY_VOICE_OFFLINE")}"
        } else {
            voice
        }
    }

    fun onBackgroundTap() {
        closeAllDropdowns()
        val now = System.currentTimeMillis()
        if (now - lastTapMsec < 300L) {
            fastTapCount++
            if (fastTapCount >= 10) {
                fastTapCount = 0
                toggleScreenReader()
            }
        } else {
            fastTapCount = 0
        }
        lastTapMsec = now
    }

    fun toggleScreenReader() {
        if (settings.languagesUiTtsSelect > 0) {
            updateSettings { it.copy(languagesUiTtsSelect = 0) }
            ttsSay("${tr("KEY_UITTS")} ${tr("KEY_OFF")}", hard = true)
        } else {
            refreshUiTtsList()
            val newIdx = if (uiTtsList.size > 1) 1 else 0
            updateSettings { it.copy(languagesUiTtsSelect = newIdx) }
            ttsSay("${tr("KEY_UITTS")} ${tr("KEY_ON")}", hard = true)
        }
    }

    fun closeAllDropdowns() {
        voicesDropdownOpen = false
        offlineVoicesDropdownOpen = false
        languagesDropdownOpen = false
        uiTtsDropdownOpen = false
    }

    fun selectVoice(index: Int) {
        if (index !in voicesList.indices) return
        val newVoice = voicesList[index]
        updateSettings { it.copy(voicesSelect = index, voicesName = newVoice) }
        invalidatePlayerCache()
        voicesDropdownOpen = false
        ttsSay(newVoice)
        if (!runWork && settings.voicePreview) {
            previewStart(
                previewText = tr("KEY_VOICE_PREVIEW_TEXT"),
                isPartPreview = false,
                forceVoice = newVoice,
                forceOnline = true
            )
        }
    }

    fun selectOfflineVoice(index: Int) {
        if (index !in offlineVoicesList.indices) return
        val newVoice = offlineVoicesList[index]
        updateSettings { it.copy(offlineVoicesSelect = index, offlineVoicesName = newVoice) }
        invalidatePlayerCache()
        offlineVoicesDropdownOpen = false
        ttsSay(voiceSayText(newVoice))
        if (!runWork && settings.voicePreview) {
            previewStart(
                previewText = tr("KEY_VOICE_PREVIEW_TEXT"),
                isPartPreview = false,
                forceVoice = newVoice,
                forceOnline = false
            )
        }
    }

    fun isNetworkAvailable(): Boolean {
        return try {
            val cm = getApplication<Application>().getSystemService(ConnectivityManager::class.java) ?: return true
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            true
        }
    }

    private fun resolveOfflineFallbackVoice(): String? {
        val chosen = settings.offlineVoicesName
        val voicesRoot = File(repo.programDir, "Voices")
        if (chosen.isNotBlank() && OfflineTtsEngine.voiceInstalled(File(voicesRoot, chosen))) {
            return chosen
        }
        return repo.readyOfflineVoices().firstOrNull()
    }

    fun selectLanguage(index: Int) {
        if (index !in languagesList.indices) return
        val lang = languagesList[index]
        languagesDropdownOpen = false
        localization.setLocale(lang)
        localeVersion++
        val newUiTts = if (settings.languagesUiTtsSelect > 0) 1 else 0
        updateSettings { it.copy(languagesSelect = index, languagesUiTtsSelect = newUiTts) }
        refreshVoicesList()
        refreshUiTtsList()
        ttsSay(lang)
    }

    fun selectUiTts(index: Int) {
        if (index !in uiTtsList.indices) return
        uiTtsDropdownOpen = false
        updateSettings { it.copy(languagesUiTtsSelect = index) }
        val text = if (index == 0) tr("KEY_OFF") else uiTtsList[index]
        ttsSay(text, hard = true)
    }

    fun setRate(value: Int) {
        val stepped = ((value + 50) / 5) * 5 - 50
        val clamped = stepped.coerceIn(-50, 100)
        if (clamped != settings.rate) {
            updateSettings { it.copy(rate = clamped) }
            invalidatePlayerCache()
            ttsSay("${tr("KEY_SPEED")} ${formatSignedValue(clamped)}%")
        }
    }

    fun setPitch(value: Int) {
        val clamped = value.coerceIn(-50, 50)
        if (clamped != settings.pitch) {
            updateSettings { it.copy(pitch = clamped) }
            invalidatePlayerCache()
            ttsSay("${tr("KEY_PITCH")} ${formatSignedValue(clamped)}Hz")
        }
    }

    fun setMaxThreads(value: Int) {
        val clamped = value.coerceIn(1, 100)
        if (clamped != settings.maxThreads) {
            OfflineTtsEngine.setMaxJobs(clamped)
            updateSettings { it.copy(maxThreads = clamped) }
            ttsSay("${tr("KEY_THREADSCOUNT")} $clamped")
        }
    }

    fun setRestartDelayIndex(index: Int) {
        val clampedIdx = index.coerceIn(0, AppSettings.RESTART_DELAY_STEPS.size)
        val secs = AppSettings.restartDelaySecondsFromIndex(clampedIdx)
        if (secs != settings.restartDelay) {
            updateSettings { it.copy(restartDelay = secs) }
            val label = if (secs < 0) "∞" else secs.toString()
            ttsSay("${tr("KEY_RESTARTDELAY")} $label")
        }
    }

    fun setMergeFiles(value: Int) {
        val clamped = value.coerceIn(1, 100)
        if (clamped != settings.mergeFiles) {
            updateSettings { it.copy(mergeFiles = clamped) }
            if (!runWork) {
                totalChunksCount = computeTotalChunks()
                chunksStatStr = "$postprocessedChunksCount/$totalChunksCount"
            }
            val label = if (clamped == 100) tr("KEY_ALL") else "$clamped ${tr("KEY_UNIT")}"
            ttsSay("${tr("KEY_MARGE_FILES")} $label")
        }
    }

    fun setZoomUi(value: Float) {
        val stepped = kotlin.math.round(value * 10f) / 10f
        val clamped = stepped.coerceIn(0.5f, 4.0f)
        if (clamped != settings.zoomUi) {
            updateSettings { it.copy(zoomUi = clamped) }
            ttsSay("${tr("KEY_ZOOM")} ${(clamped * 100).toInt()}%")
        }
    }

    fun setFontSize(value: Int) {
        val clamped = value.coerceIn(16, 24)
        if (clamped != settings.fontSize) {
            updateSettings { it.copy(fontSize = clamped) }
            ttsSay("${tr("KEY_FONTSIZE")} $clamped")
        }
    }

    fun toggleDopSettings() {
        val next = !settings.dopSettingsPressed
        updateSettings { it.copy(dopSettingsPressed = next) }
        ttsSay(tr(if (next) "KEY_SETTINGS" else "KEY_SETTINGS_BACK"))
    }

    fun selectSettingsTab(tab: SettingsTab) {
        activeSettingsTab = tab
        updateSettings {
            it.copy(
                group1Visible = tab == SettingsTab.VOICE,
                group2Visible = tab == SettingsTab.VOICE,
                group6Visible = tab == SettingsTab.BOOK,
                group7Visible = tab == SettingsTab.TOOL,
                group8Visible = tab == SettingsTab.INTERFACE
            )
        }
        val key = when (tab) {
            SettingsTab.VOICE -> "KEY_SETTINGS_VOICE"
            SettingsTab.BOOK -> "KEY_SETTINGS_BOOK"
            SettingsTab.TOOL -> "KEY_SETTINGS_TOOL"
            SettingsTab.INTERFACE -> "KEY_SETTINGS_INTERFACE"
        }
        ttsSay(tr(key))
    }

    fun toggleStatusGridVisible() {
        if (partStatuses.isNotEmpty()) {
            val next = !settings.group4Visible
            updateSettings { it.copy(group4Visible = next) }
        }
    }

    fun openChapterSettingsModal() {
        tempRegexGlava = settings.regexGlavaCompile
        tempGlavMp3Use = settings.glavMp3Use
        tempGlavTextUse = settings.glavTextUse
        tempGlavMp3Path = settings.glavMp3Path
        dopModal = DopModal.REGEX_GLAVA
        ttsSay(tr("KEY_REGEX_GLAVA"))
    }

    fun confirmChapterSettingsModal() {
        val regex = tempRegexGlava.ifEmpty { AppSettings.DEFAULT_REGEX_GLAVA }
        val mp3Path = if (tempGlavMp3Use) tempGlavMp3Path else ""
        updateSettings {
            it.copy(
                regexGlavaCompile = regex,
                glavMp3Use = tempGlavMp3Use,
                glavTextUse = tempGlavTextUse,
                glavMp3Path = mp3Path
            )
        }
        dopModal = DopModal.NONE
        reprocessBookForCurrentSettings()
        ttsSay(tr("KEY_OK"))
    }

    fun cancelChapterSettingsModal() {
        dopModal = DopModal.NONE
        ttsSay(tr("KEY_CANCEL"))
    }

    fun openRecycleBinModal() {
        dopModal = DopModal.RECYCLE_BIN
        ttsSay(tr("KEY_CLEAR_BIN"))
    }

    fun confirmEmptyRecycleBin() {
        viewModelScope.launch(Dispatchers.IO) {
            val binDir = File(repo.programDir, "Bin")
            if (binDir.exists()) {
                binDir.deleteRecursively()
            }
        }
        dopModal = DopModal.NONE
        ttsSay(tr("KEY_YES"))
    }

    fun cancelEmptyRecycleBin() {
        dopModal = DopModal.NONE
        ttsSay(tr("KEY_NO"))
    }

    private fun readLexxRulesFromPath(path: String): List<String> {
        if (path.isBlank()) return emptyList()
        val file = File(path)
        if (!file.exists() || !file.canRead()) return emptyList()
        return try {
            TextsConverter.readTextWithFallback(file.readBytes()).lines()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun resolveActiveLexxRules(): List<String> {
        if (!settings.lexxUse) return emptyList()
        if (lexxRules.isEmpty() && settings.lexxPath.isNotBlank()) {
            lexxRules = readLexxRulesFromPath(settings.lexxPath)
        }
        return lexxRules
    }

    fun loadLexx() {
        lexxRules = readLexxRulesFromPath(settings.lexxPath)
        reprocessBookForCurrentSettings()
    }

    fun reprocessBookForCurrentSettings() {
        if (runWork || isBookLoading) return
        val shared = lastSharedInput
        if (playerIsSharedText && shared != null) {
            onSharedPlainText(
                sharedText = shared.first,
                sharedTitle = shared.second,
                openPlayerTab = false
            )
        } else if (filesList.isNotEmpty()) {
            onOpenBooks(
                paths = filesList.toList(),
                announceLoad = false
            )
        }
    }

    // Online & Offline Voices Download
    fun fetchOnlineVoices() {
        if (onlineVoicesBusy) return
        onlineVoicesBusy = true
        statInfoDoneHighlight = false
        statInfo = tr("KEY_GET_VOICES")
        ttsSay(tr("KEY_GET_VOICES"))
        viewModelScope.launch(Dispatchers.IO) {
            var updatedOk = false
            try {
                val url = "https://speech.platform.bing.com/consumer/speech/synthesize/readaloud/voices/list?trustedclienttoken=${SecMsGecGenerator.TRUSTED_CLIENT_TOKEN}"
                val req = Request.Builder().url(url).build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val bodyStr = resp.body?.string().orEmpty()
                        val arr = JSONArray(bodyStr)
                        val names = mutableListOf<String>()
                        for (i in 0 until arr.length()) {
                            val obj = arr.optJSONObject(i) ?: continue
                            val fullName = obj.optString("Name", "")
                            val start = fullName.indexOf('(')
                            val end = fullName.indexOf(')')
                            if (start != -1 && end != -1 && end > start + 1) {
                                names.add(fullName.substring(start + 1, end))
                            }
                        }
                        if (names.isNotEmpty()) {
                            val sorted = repo.sortVoices(names, localization.currentLocale)
                            withContext(Dispatchers.Main) {
                                repo.updateOnlineVoices(sorted)
                                refreshVoicesList()
                            }
                            updatedOk = true
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                withContext(Dispatchers.Main) {
                    onlineVoicesBusy = false
                    statInfoDoneHighlight = updatedOk
                    statInfo = if (updatedOk) "${tr("KEY_GET_VOICES")}: ${tr("KEY_OK")}" else ""
                }
            }
        }
    }

    fun fetchOfflineVoices() {
        if (runWork || offlineVoicesBusy) return
        offlineVoicesBusy = true
        ttsSay(tr("KEY_GET_OFFLINE_VOICES"))
        viewModelScope.launch {
            val ok = OfflineInstaller.install(
                programDir = repo.programDir,
                currentLocale = localization.currentLocale,
                onRegisterVoice = { voice ->
                    repo.registerOfflineVoice(voice)
                },
                onProgress = { fileName, downloaded, total ->
                    val shortName = if (fileName.length > 24) fileName.take(24) + "…" else fileName
                    val progressSuffix = when {
                        total > 0 -> " ${downloaded * 100 / total}%"
                        downloaded > 0 -> " ${downloaded / 1048576} MB"
                        else -> ""
                    }
                    viewModelScope.launch(Dispatchers.Main) {
                        statInfoDoneHighlight = false
                        statInfo = tr("KEY_OFFLINE_DOWNLOADING") + shortName + progressSuffix
                    }
                }
            )
            refreshVoicesList()
            offlineVoicesBusy = false
            statInfoDoneHighlight = false
            statInfo = tr(if (ok) "KEY_OFFLINE_READY" else "KEY_OFFLINE_FAILED")
            ttsSay(statInfo)
        }
    }

    fun bookLoadedCaption(): String {
        return tr(if (loadedBooksCount <= 1) "KEY_SINGLE_BOOK_LOADED" else "KEY_BOOK_LOADED")
    }

    private fun syncBookTitlesFromBook() {
        bookTitles.clear()
        book?.fileNames?.forEach { meta ->
            bookTitles.add(meta.fileName)
        }
    }

    fun updateBookTitle(index: Int, newTitle: String) {
        if (index in bookTitles.indices) {
            bookTitles[index] = newTitle
        }
        book?.fileNames?.getOrNull(index)?.let { meta ->
            meta.fileName = newTitle
        }
    }

    // Book Open / Clear
    fun clearBook() {
        stopConversion(announce = false)
        stopPlayerPlayback(clearCache = true)
        playerSentences = emptyList()
        playerSentenceStatuses.clear()
        playerSelectedIndex = 0
        playerBookKey = null
        playerIsSharedText = false
        lastSharedInput = null
        playerBookmarks.clear()
        isBookLoading = false
        bookLoaded = false
        loadedBooksCount = 0
        bookTitles.clear()
        partStatuses.clear()
        textAreaText = ""
        editorChapterTitleText = ""
        editorHasChapters = false
        editorHasPrevChapter = false
        editorHasNextChapter = false
        processedPartsCount = 0
        totalPartsCount = 0
        postprocessedChunksCount = 0
        totalChunksCount = 0
        statStr = "0/0"
        chunksStatStr = "0/0"
        book = null
        editorPartIndex = 0
        clearEditorSnapshot()
        editorRefreshFields()
        refreshLoadDirFiles()
        ttsSay(tr("KEY_BOOK_CLEAR"))
    }

    fun onFileButtonClicked(onLaunchSystemPicker: () -> Unit) {
        if (book != null || bookLoaded) {
            clearBook()
        } else {
            onLaunchSystemPicker()
        }
    }

    fun onSharedUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        selectMainTab(MainTab.BOOK)
        onOpenBookUris(uris)
    }

    fun onSharedPlainText(
        sharedText: String,
        sharedTitle: String? = null,
        openPlayerTab: Boolean = true
    ) {
        val text = sharedText.trim()
        if (text.isEmpty()) return
        val previousIdx = if (playerIsSharedText && !openPlayerTab) playerSelectedIndex else 0
        stopConversion(announce = false)
        stopPlayerPlayback(clearCache = true)
        clearEditorSnapshot()
        if (openPlayerTab) {
            selectMainTab(MainTab.PLAYER)
        }
        isBookLoading = true
        bookLoaded = false
        statInfoDoneHighlight = false
        statInfo = tr("KEY_LOAD")
        partStatuses.clear()
        playerSentences = emptyList()
        playerSentenceStatuses.clear()
        playerSelectedIndex = 0
        playerBookKey = null
        playerIsSharedText = true
        lastSharedInput = sharedText to sharedTitle
        playerBookmarks.clear()
        textAreaText = ""
        processedPartsCount = 0
        totalPartsCount = 0
        postprocessedChunksCount = 0
        totalChunksCount = 0
        statStr = "0/0"
        chunksStatStr = "0/0"
        resetElapsedLabel()
        filesList.clear()
        bookTitles.clear()
        book = null
        editorPartIndex = 0
        editorRefreshFields()

        val saveName = sharedTitle
            ?.replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: run {
                numTextCounter++
                "Text ${"%04d".format(numTextCounter)}"
            }

        viewModelScope.launch(Dispatchers.IO) {
            val activeLexx = resolveActiveLexxRules()
            val sharedMeta = BookAudioMetadata(title = saveName)
            val createdBook = ProcessingFile(
                fileName = saveName,
                text = text,
                langLexx = activeLexx,
                lexxRegister = settings.lexxRegister,
                filterEmojis = settings.filterEmojis,
                bookChapter = false,
                glavTextUse = false,
                regexGlavaPattern = settings.regexGlavaCompile,
                voice = "",
                rate = "",
                pitch = "",
                metadata = sharedMeta
            )
            val extractedPlayerSentences = splitIntoPlayerSentences(
                parts = createdBook.allSentences,
                glavs = emptyList(),
                detectChapters = false
            )

            withContext(Dispatchers.Main) {
                isBookLoading = false
                book = createdBook
                val count = createdBook.allSentences.size
                syncBookTitlesFromBook()
                loadedBooksCount = bookTitles.size.coerceAtLeast(if (count > 0) 1 else 0)
                bookLoaded = count > 0
                processedPartsCount = 0
                totalPartsCount = count
                postprocessedChunksCount = 0
                totalChunksCount = computeTotalChunks(createdBook)
                partStatuses.clear()
                if (count > 0) {
                    partStatuses.addAll(java.util.Collections.nCopies(count, PartStatus.OPENED))
                }
                applyPlayerSentences(
                    sentences = extractedPlayerSentences,
                    savedState = if (previousIdx > 0) {
                        SettingsRepository.PlayerBookState(lastPlayedIndex = previousIdx)
                    } else null
                )
                statStr = "0/$count"
                chunksStatStr = "0/$totalChunksCount"
                statInfo = ""
                editorPartIndex = 0
                if (settings.showTextAreaPressed) {
                    editorLoadCurrentPart()
                } else {
                    editorRefreshFields()
                }
                if (openPlayerTab) {
                    ttsSay("${bookLoadedCaption()}. $statStr")
                }
            }
        }
    }

    fun onOpenBookUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        isBookLoading = true
        statInfoDoneHighlight = false
        statInfo = tr("KEY_LOAD")
        viewModelScope.launch(Dispatchers.IO) {
            val paths = uris.map { repo.resolveAndroidUriToPath(it) }.filter { it.isNotBlank() }
            withContext(Dispatchers.Main) {
                if (paths.isEmpty()) {
                    isBookLoading = false
                    statInfo = ""
                } else {
                    onOpenBooks(paths)
                }
            }
        }
    }

    fun onOpenBooks(paths: List<String>, announceLoad: Boolean = true) {
        if (paths.isEmpty()) {
            isBookLoading = false
            return
        }
        stopConversion(announce = false)
        stopPlayerPlayback(clearCache = true)
        clearEditorSnapshot()
        isBookLoading = true
        bookLoaded = false
        statInfoDoneHighlight = false
        statInfo = tr("KEY_LOAD")
        partStatuses.clear()
        playerSentences = emptyList()
        playerSentenceStatuses.clear()
        playerSelectedIndex = 0
        playerBookKey = null
        playerIsSharedText = false
        lastSharedInput = null
        playerBookmarks.clear()
        textAreaText = ""
        processedPartsCount = 0
        totalPartsCount = 0
        postprocessedChunksCount = 0
        totalChunksCount = 0
        statStr = "0/0"
        chunksStatStr = "0/0"
        resetElapsedLabel()
        filesList.clear()
        bookTitles.clear()
        book = null
        editorPartIndex = 0
        editorRefreshFields()

        viewModelScope.launch(Dispatchers.IO) {
            var currentBook: ProcessingFile? = null
            val activeLexx = resolveActiveLexxRules()

            fun appendTextEntry(
                saveName: String,
                content: String,
                voice: String,
                rate: String,
                pitch: String,
                metadata: BookAudioMetadata = BookAudioMetadata(title = saveName)
            ) {
                if (content.isEmpty()) return
                val b = currentBook
                if (b == null) {
                    currentBook = ProcessingFile(
                        fileName = saveName,
                        text = content,
                        langLexx = activeLexx,
                        lexxRegister = settings.lexxRegister,
                        filterEmojis = settings.filterEmojis,
                        bookChapter = settings.bookChapter,
                        glavTextUse = settings.glavTextUse,
                        regexGlavaPattern = settings.regexGlavaCompile,
                        voice = voice,
                        rate = rate,
                        pitch = pitch,
                        metadata = metadata
                    )
                } else {
                    b.addNewText(saveName, content, voice, rate, pitch, metadata)
                }
            }

            var lastSentencesSize = 0
            for (path in paths) {
                val file = File(path)
                if (!file.exists() || !file.isFile) continue
                val ext = file.extension.lowercase()
                val baseName = file.nameWithoutExtension
                try {
                    when (ext) {
                        "txt", "ini" -> {
                            val text = TextsConverter.readTextWithFallback(file.readBytes())
                            appendTextEntry(baseName, text, "", "", "", BookAudioMetadata(title = baseName))
                        }
                        "fb2", "xml" -> {
                            val raw = TextsConverter.readTextWithFallback(file.readBytes())
                            if (ext == "fb2" || raw.contains("<FictionBook", ignoreCase = true)) {
                                val bookName = TextsConverter.extractFb2BookName(raw, baseName)
                                val meta = TextsConverter.extractFb2Metadata(raw, baseName)
                                appendTextEntry(
                                    bookName,
                                    TextsConverter.convertFb2ToTxt(raw, inlineNotes = settings.inlineNotes),
                                    "",
                                    "",
                                    "",
                                    meta
                                )
                            } else {
                                appendTextEntry(baseName, raw, "", "", "", BookAudioMetadata(title = baseName))
                            }
                        }
                        "epub" -> {
                            val bookName = TextsConverter.extractEpubBookName(file.absolutePath, baseName)
                            val meta = TextsConverter.extractEpubMetadata(file.absolutePath, baseName)
                            val text = TextsConverter.convertEpubToTxt(
                                file.absolutePath,
                                inlineNotes = settings.inlineNotes
                            )
                            appendTextEntry(bookName, text, "", "", "", meta)
                        }
                        "zip" -> {
                            TextsConverter.extractZipEntries(
                                file.absolutePath,
                                inlineNotes = settings.inlineNotes
                            ) { saveName, text, v, r, p, meta ->
                                appendTextEntry(saveName, text, v, r, p, meta)
                            }
                        }
                        "csv" -> {
                            val raw = TextsConverter.readTextWithFallback(file.readBytes())
                            val lines = TextsConverter.parseCsv(raw)
                            lines.forEachIndexed { idx, row ->
                                TextsConverter.processCsvLine(row, baseName, idx + 1) { saveName, text, v, r, p, meta ->
                                    appendTextEntry(saveName, text, v, r, p, meta)
                                }
                            }
                        }
                    }
                    val newSize = currentBook?.allSentences?.size ?: 0
                    if (newSize != lastSentencesSize) {
                        lastSentencesSize = newSize
                        filesList.add(path)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            val extractedPlayerSentences = splitIntoPlayerSentences(
                parts = currentBook?.allSentences.orEmpty(),
                glavs = currentBook?.allGlavs.orEmpty()
            )
            val computedBookKey = filesList
                .map { p ->
                    val f = File(p)
                    "${f.name}:${f.length()}"
                }
                .joinToString("|")
                .takeIf { it.isNotEmpty() }
            val savedPlayerState = computedBookKey?.let { repo.loadPlayerBookState(it) }

            withContext(Dispatchers.Main) {
                isBookLoading = false
                book = currentBook
                val count = currentBook?.allSentences?.size ?: 0
                syncBookTitlesFromBook()
                loadedBooksCount = bookTitles.size.coerceAtLeast(if (count > 0) 1 else 0)
                bookLoaded = count > 0
                processedPartsCount = 0
                totalPartsCount = count
                postprocessedChunksCount = 0
                totalChunksCount = computeTotalChunks(currentBook)
                partStatuses.clear()
                if (count > 0) {
                    partStatuses.addAll(java.util.Collections.nCopies(count, PartStatus.OPENED))
                }
                applyPlayerSentences(
                    sentences = extractedPlayerSentences,
                    bookKey = computedBookKey,
                    savedState = savedPlayerState
                )
                statStr = "0/$count"
                chunksStatStr = "0/$totalChunksCount"
                statInfo = ""
                editorPartIndex = 0
                if (settings.showTextAreaPressed) {
                    editorLoadCurrentPart()
                } else {
                    editorRefreshFields()
                }
                if (announceLoad) {
                    ttsSay("${bookLoadedCaption()}. $statStr")
                }
            }
        }
    }

    // Editor functions
    fun toggleTextAreaPanel() {
        val next = !settings.showTextAreaPressed
        updateSettings { it.copy(showTextAreaPressed = next) }
        if (next) {
            val b = book
            if (b != null) {
                editorPartIndex = editorPartIndex.coerceIn(0, (b.allSentences.size - 1).coerceAtLeast(0))
            }
            editorLoadCurrentPart()
            takeEditorSnapshot()
        } else {
            if (partPreviewActive) voicePreviewStop()
            if (!runWork) {
                revertEditorSnapshotIfPossible()
            } else {
                clearEditorSnapshot()
            }
        }
    }

    fun onEditorClosePressed() {
        if (partPreviewActive) voicePreviewStop()
        if (!runWork) {
            revertEditorSnapshotIfPossible()
        } else {
            clearEditorSnapshot()
        }
        activeMainTab = MainTab.BOOK
        updateSettings { it.copy(showTextAreaPressed = false, dopSettingsPressed = false) }
        refreshLoadDirFiles()
        ttsSay(tr("KEY_EDITOR_CLOSE"))
    }

    private fun findBookBounds(activeBook: ProcessingFile, index: Int): IntRange {
        var start = 0
        val lastIdx = activeBook.allSentences.lastIndex
        for (meta in activeBook.fileNames) {
            val endExclusive = meta.endSentenceIndex
            if (endExclusive > 0) {
                if (index < endExclusive) {
                    return start until minOf(endExclusive, activeBook.allSentences.size)
                }
                start = endExclusive
            }
        }
        return start..lastIdx
    }

    private fun findChapterBounds(activeBook: ProcessingFile, index: Int): IntRange {
        if (index !in activeBook.allSentences.indices) return 0..0
        val bookBounds = findBookBounds(activeBook, index)
        val targetGlav = activeBook.allGlavs.getOrNull(index)?.trim().orEmpty()
        var start = index
        while (start > bookBounds.first &&
            activeBook.allGlavs.getOrNull(start - 1)?.trim().orEmpty() == targetGlav
        ) {
            start--
        }
        var end = index
        while (end < bookBounds.last &&
            activeBook.allGlavs.getOrNull(end + 1)?.trim().orEmpty() == targetGlav
        ) {
            end++
        }
        return start..end
    }

    private fun extractDisplayChapterTitle(rawGlav: String): String {
        val trimmed = rawGlav.trim()
        if (trimmed.isEmpty()) return ""
        return trimmed.replaceFirst(Regex("^\\d{4}\\s*"), "").trim()
    }

    private fun sanitizeChapterTitleInput(raw: String): String {
        val sb = StringBuilder()
        val trimmed = raw.trim()
        for (c in trimmed) {
            if (c.code >= 32 && c !in "/\\?%*|\"<>:") {
                sb.append(c)
            } else if (sb.isNotEmpty() && sb[sb.length - 1] != ' ') {
                sb.append(' ')
            }
        }
        return sb.toString().trim()
    }

    private fun commitCurrentChapterTitleToBook() {
        val b = book ?: return
        if (runWork || editorPartIndex !in b.allSentences.indices) return
        while (b.allGlavs.size < b.allSentences.size) {
            b.allGlavs.add("")
        }
        val bounds = findChapterBounds(b, editorPartIndex)
        val oldRaw = b.allGlavs.getOrNull(editorPartIndex)?.trim().orEmpty()
        val currentDisplayed = extractDisplayChapterTitle(oldRaw)
        val cleanTitle = sanitizeChapterTitleInput(editorChapterTitleText)
        if (cleanTitle == currentDisplayed) return
        val prefixMatch = Regex("^(\\d{4})(?:\\s+.*)?$").find(oldRaw)
        val newRaw = if (prefixMatch != null) {
            val prefix = prefixMatch.groupValues[1]
            if (cleanTitle.isNotEmpty()) "$prefix $cleanTitle" else prefix
        } else {
            cleanTitle
        }
        for (i in bounds) {
            if (i in b.allGlavs.indices) {
                b.allGlavs[i] = newRaw
            }
        }
    }

    fun onEditorChapterTitleSubmitted() {
        if (runWork) return
        commitCurrentChapterTitleToBook()
        editorRefreshFields()
    }

    fun onEditorPrevChapterPressed() {
        val b = book ?: return
        if (b.allSentences.isEmpty()) return
        val idx = editorPartIndex.coerceIn(0, b.allSentences.lastIndex)
        val bounds = findChapterBounds(b, idx)
        if (bounds.first <= 0) return
        val prevBounds = findChapterBounds(b, bounds.first - 1)
        editorGotoPart(prevBounds.first)
    }

    fun onEditorNextChapterPressed() {
        val b = book ?: return
        if (b.allSentences.isEmpty()) return
        val idx = editorPartIndex.coerceIn(0, b.allSentences.lastIndex)
        val bounds = findChapterBounds(b, idx)
        val nextStart = bounds.last + 1
        if (nextStart >= b.allSentences.size) return
        editorGotoPart(nextStart)
    }

    fun onEditorSavePressed() {
        if (runWork) return
        clearEditorSnapshot()
        val currentBook = book
        val nBefore = currentBook?.allSentences?.size ?: 0
        if (currentBook == null || nBefore == 0) {
            val seed = textAreaText.ifEmpty { " " }
            val defaultTitle = bookTitles.firstOrNull()?.takeIf { it.isNotBlank() }
                ?: "Text ${"%04d".format(numTextCounter + 1)}"
            val newBook = ProcessingFile(
                fileName = defaultTitle,
                text = seed,
                langLexx = resolveActiveLexxRules(),
                lexxRegister = settings.lexxRegister,
                filterEmojis = settings.filterEmojis,
                bookChapter = settings.bookChapter,
                glavTextUse = settings.glavTextUse,
                regexGlavaPattern = settings.regexGlavaCompile,
                voice = "",
                rate = "",
                pitch = ""
            )
            if (newBook.allSentences.isEmpty()) {
                newBook.allSentences.add("")
                newBook.allGlavs.add("")
            }
            book = newBook
            editorPartIndex = 0
            commitCurrentChapterTitleToBook()
        } else {
            commitCurrentChapterTitleToBook()
            currentBook.langLexx = resolveActiveLexxRules()
            currentBook.lexxRegister = settings.lexxRegister
            currentBook.filterEmojis = settings.filterEmojis
            val k = currentBook.replacePart(editorPartIndex, textAreaText)
            if (k <= 0 && currentBook.allSentences.isEmpty()) {
                currentBook.allSentences.add("")
                currentBook.allGlavs.add("")
                editorPartIndex = 0
            } else {
                editorPartIndex = editorPartIndex.coerceIn(0, (currentBook.allSentences.size - 1).coerceAtLeast(0))
            }
        }
        val finalCount = book?.allSentences?.size ?: 0
        syncBookTitlesFromBook()
        loadedBooksCount = bookTitles.size.coerceAtLeast(if (finalCount > 0) 1 else 0)
        bookLoaded = finalCount > 0
        processedPartsCount = 0
        totalPartsCount = finalCount
        postprocessedChunksCount = 0
        totalChunksCount = computeTotalChunks()
        statStr = "0/$finalCount"
        chunksStatStr = "0/$totalChunksCount"
        partStatuses.clear()
        if (finalCount > 0) {
            partStatuses.addAll(java.util.Collections.nCopies(finalCount, PartStatus.OPENED))
        }
        applyPlayerSentences(
            sentences = splitIntoPlayerSentences(
                parts = book?.allSentences.orEmpty(),
                glavs = book?.allGlavs.orEmpty(),
                detectChapters = !playerIsSharedText
            ),
            bookKey = playerBookKey,
            savedState = playerBookKey?.let {
                SettingsRepository.PlayerBookState(
                    lastPlayedIndex = playerSelectedIndex,
                    bookmarks = playerBookmarks.toList()
                )
            }
        )
        editorLoadCurrentPart()
        takeEditorSnapshot()
        ttsSay(tr("KEY_SAVED"))
    }

    fun onEditorPrevPressed() {
        val b = book ?: return
        if (b.allSentences.isEmpty()) return
        editorGotoPart(editorPartIndex - 1)
    }

    fun onEditorNextPressed() {
        val b = book ?: return
        if (b.allSentences.isEmpty()) return
        editorGotoPart(editorPartIndex + 1)
    }

    fun onEditorPartNumberSubmitted(raw: String) {
        val b = book
        if (b == null || b.allSentences.isEmpty()) {
            editorRefreshFields()
            return
        }
        val human = raw.trim().toIntOrNull()
        if (human == null) {
            editorRefreshFields()
            return
        }
        val target = (human - 1).coerceIn(0, b.allSentences.size - 1)
        if (target == editorPartIndex) {
            editorRefreshFields()
            return
        }
        editorGotoPart(target)
    }

    private fun editorGotoPart(newIndex: Int) {
        val b = book ?: return
        if (b.allSentences.isEmpty()) return
        if (!runWork && editorPartIndex in b.allSentences.indices) {
            b.allSentences[editorPartIndex] = textAreaText
            commitCurrentChapterTitleToBook()
        }
        editorPartIndex = newIndex.coerceIn(0, b.allSentences.size - 1)
        editorLoadCurrentPart()
    }

    private fun editorLoadCurrentPart() {
        if (partPreviewActive) voicePreviewStop()
        val b = book
        if (b != null) {
            if (b.allSentences.isEmpty()) {
                textAreaText = ""
                editorChapterTitleText = ""
            } else {
                editorPartIndex = editorPartIndex.coerceIn(0, b.allSentences.size - 1)
                textAreaText = b.allSentences[editorPartIndex]
                val rawGlav = b.allGlavs.getOrNull(editorPartIndex).orEmpty()
                editorChapterTitleText = extractDisplayChapterTitle(rawGlav)
            }
        } else {
            editorChapterTitleText = ""
        }
        editorRefreshFields()
    }

    private fun bookHasChapters(activeBook: ProcessingFile?): Boolean {
        val b = activeBook ?: return false
        if (b.allSentences.isEmpty() || b.allGlavs.isEmpty()) return false
        val firstBounds = findChapterBounds(b, 0)
        if (firstBounds.last + 1 < b.allSentences.size) return true
        return b.allGlavs.any { raw ->
            val trimmed = raw.trim()
            trimmed.isNotEmpty() && trimmed != "0000"
        }
    }

    private fun editorRefreshFields() {
        val b = book
        val count = b?.allSentences?.size ?: 0
        totalPartsCount = count
        if (!runWork) {
            totalChunksCount = computeTotalChunks(b)
            statStr = "$processedPartsCount/$totalPartsCount"
            chunksStatStr = "$postprocessedChunksCount/$totalChunksCount"
        }
        editorPartFieldText = if (count == 0) "0" else (editorPartIndex + 1).toString()
        if (b != null && count > 0) {
            val idx = editorPartIndex.coerceIn(0, count - 1)
            val bounds = findChapterBounds(b, idx)
            editorHasChapters = bookHasChapters(b)
            editorHasPrevChapter = bounds.first > 0
            editorHasNextChapter = bounds.last + 1 < count
        } else {
            editorHasChapters = false
            editorHasPrevChapter = false
            editorHasNextChapter = false
        }
    }

    private fun computeTotalChunks(activeBook: ProcessingFile? = book): Int {
        val b = activeBook ?: return 0
        val total = b.allSentences.size
        if (total == 0) return 0
        val perFile = settings.mergeFiles
        val byBook = LinkedHashMap<Int, LinkedHashMap<String, Int>>()
        var fileNameInd = 0
        for (idx in 0 until total) {
            val currentMeta = b.fileNames[fileNameInd]
            if (currentMeta.endSentenceIndex in 1..idx && fileNameInd + 1 < b.fileNames.size) {
                fileNameInd++
            }
            val glava = b.allGlavs[idx].trim()
            val byGlava = byBook.getOrPut(fileNameInd) { LinkedHashMap() }
            byGlava[glava] = (byGlava[glava] ?: 0) + 1
        }
        var totalChunks = 0
        for ((_, byGlava) in byBook) {
            for ((_, count) in byGlava) {
                val chunkSize = when {
                    perFile <= 1 -> 1
                    perFile >= 100 -> count
                    else -> perFile
                }
                val step = chunkSize.coerceAtLeast(1)
                totalChunks += (count + step - 1) / step
            }
        }
        return totalChunks
    }

    fun computeCompositeProgress(
        doneParts: Int = processedPartsCount,
        totalParts: Int = totalPartsCount,
        doneChunks: Int = postprocessedChunksCount,
        totalChunks: Int = totalChunksCount
    ): Float {
        val partsRatio = if (totalParts > 0) doneParts.toFloat() / totalParts.toFloat() else 0f
        val chunksRatio = if (totalChunks > 0) doneChunks.toFloat() / totalChunks.toFloat() else partsRatio
        return (0.5f * (partsRatio + chunksRatio)).coerceIn(0f, 1f)
    }

    private fun takeEditorSnapshot() {
        clearEditorSnapshot()
        val b = book ?: return
        if (runWork) return
        editorSnapshot = b.allSentences.toList()
        editorSnapshotGlavs = b.allGlavs.toList()
        editorSnapshotIndex = editorPartIndex
        editorSnapshotBook = b
    }

    private fun revertEditorSnapshotIfPossible() {
        val snapBook = editorSnapshotBook ?: return
        val currentBook = book
        if (snapBook !== currentBook || runWork) {
            clearEditorSnapshot()
            return
        }
        currentBook.allSentences.clear()
        currentBook.allSentences.addAll(editorSnapshot)
        currentBook.allGlavs.clear()
        currentBook.allGlavs.addAll(editorSnapshotGlavs)
        editorPartIndex = editorSnapshotIndex.coerceIn(0, (currentBook.allSentences.size - 1).coerceAtLeast(0))
        clearEditorSnapshot()
    }

    private fun clearEditorSnapshot() {
        editorSnapshot = emptyList()
        editorSnapshotGlavs = emptyList()
        editorSnapshotIndex = 0
        editorSnapshotBook = null
    }

    // Built-in Player (per-sentence chunking, parallel synthesis, sequential playback, caching)
    private fun splitIntoPlayerSentences(
        parts: List<String>,
        glavs: List<String> = emptyList(),
        detectChapters: Boolean = true
    ): List<PlayerSentenceItem> {
        if (parts.isEmpty()) return emptyList()
        val chapterRegex = try {
            Regex(
                settings.regexGlavaCompile.ifEmpty { AppSettings.DEFAULT_REGEX_GLAVA },
                RegexOption.IGNORE_CASE
            )
        } catch (_: Exception) {
            Regex(AppSettings.DEFAULT_REGEX_GLAVA, RegexOption.IGNORE_CASE)
        }
        val leadingNumPrefixRegex = Regex("^\\d{4}\\s*")
        val result = ArrayList<PlayerSentenceItem>(parts.size * 16)
        var lastPartGlav = ""
        var pendingParagraphBreak = false
        var pendingChapterTitle: String? = null
        var currentChapterTitle: String? = null
        var consecutiveEmptyLines = 0

        fun emitSentence(raw: String) {
            val s = raw.trim()
            if (s.isEmpty()) return
            val isFirstInBook = result.isEmpty()
            val isNewChapter = pendingChapterTitle != null
            if (pendingChapterTitle != null) {
                currentChapterTitle = pendingChapterTitle
            }
            val isNewParagraph = !isFirstInBook && (pendingParagraphBreak || isNewChapter)
            result.add(
                PlayerSentenceItem(
                    text = s,
                    startsNewParagraph = isNewParagraph,
                    startsNewChapter = isNewChapter,
                    chapterTitle = currentChapterTitle
                )
            )
            pendingParagraphBreak = false
            pendingChapterTitle = null
        }

        for (partIdx in parts.indices) {
            val part = parts[partIdx]
            if (detectChapters) {
                val rawGlav = glavs.getOrNull(partIdx)?.trim().orEmpty()
                if (rawGlav.isNotEmpty() && rawGlav != lastPartGlav) {
                    lastPartGlav = rawGlav
                    val stripped = rawGlav.replaceFirst(leadingNumPrefixRegex, "").trim()
                    if (stripped.isNotEmpty()) {
                        pendingChapterTitle = stripped
                    } else if (rawGlav != "0000") {
                        pendingChapterTitle = rawGlav
                    }
                }
            }

            val len = part.length
            var chunkStart = 0
            var alnumCount = 0
            var lastClauseEnd = -1
            var lastSpace = -1
            var lineCheckedForChapter = false
            var i = 0
            while (i < len) {
                if (detectChapters && !lineCheckedForChapter) {
                    lineCheckedForChapter = true
                    var lineEnd = part.indexOf('\n', i)
                    if (lineEnd == -1) lineEnd = len
                    val rawLine = part.substring(i, lineEnd)
                    val trimmedLine = rawLine.trim()
                    if (trimmedLine.isEmpty()) {
                        consecutiveEmptyLines++
                    } else {
                        if (trimmedLine.length < 50 && chapterRegex.containsMatchIn(trimmedLine)) {
                            pendingChapterTitle = trimmedLine
                        } else if (consecutiveEmptyLines >= 3 && result.isNotEmpty()) {
                            pendingChapterTitle = trimmedLine.take(60)
                        }
                        consecutiveEmptyLines = 0
                    }
                }

                val c = part[i]
                if (Character.isLetterOrDigit(c)) {
                    alnumCount++
                } else if (c == ' ' || c == '\t') {
                    lastSpace = i
                } else if (c == ',' || c == ':' || c == '—' || c == '-') {
                    lastClauseEnd = i + 1
                }

                val isNewline = c == '\n' || c == '\r'
                val isSentenceEndPunct = (c == '.' || c == '!' || c == '?' || c == '…' || c == ';') &&
                    (i + 1 == len || part[i + 1].isWhitespace() || part[i + 1] == '"' || part[i + 1] == '»' || part[i + 1] == ')')

                val spanLen = i + 1 - chunkStart
                if (isNewline) {
                    if (alnumCount > 0) {
                        emitSentence(part.substring(chunkStart, i))
                        pendingParagraphBreak = true
                    } else if (result.isNotEmpty()) {
                        pendingParagraphBreak = true
                    }
                    chunkStart = i + 1
                    alnumCount = 0
                    lastClauseEnd = -1
                    lastSpace = -1
                    lineCheckedForChapter = false
                } else if (isSentenceEndPunct && alnumCount >= 4) {
                    var endIdx = i + 1
                    while (endIdx < len && (part[endIdx] == '"' || part[endIdx] == '»' || part[endIdx] == ')')) {
                        endIdx++
                    }
                    emitSentence(part.substring(chunkStart, endIdx))
                    i = endIdx - 1
                    chunkStart = endIdx
                    alnumCount = 0
                    lastClauseEnd = -1
                    lastSpace = -1
                } else if (spanLen >= 340 && alnumCount >= 4) {
                    val splitAt = when {
                        lastClauseEnd > chunkStart + 120 -> lastClauseEnd
                        lastSpace > chunkStart + 120 -> lastSpace
                        else -> i + 1
                    }
                    emitSentence(part.substring(chunkStart, splitAt))
                    chunkStart = splitAt
                    alnumCount = 0
                    lastClauseEnd = -1
                    lastSpace = -1
                }
                i++
            }
            if (chunkStart < len && alnumCount > 0) {
                emitSentence(part.substring(chunkStart, len))
            }
            if (result.isNotEmpty()) {
                pendingParagraphBreak = true
            }
        }
        return result
    }

    private fun applyPlayerSentences(
        sentences: List<PlayerSentenceItem>,
        bookKey: String? = null,
        savedState: SettingsRepository.PlayerBookState? = null
    ) {
        stopPlayerPlayback(clearCache = true)
        playerSentences = sentences
        playerSentenceStatuses.clear()
        playerBookKey = bookKey
        playerBookmarks.clear()
        if (savedState != null && sentences.isNotEmpty()) {
            val validBookmarks = savedState.bookmarks
                .filter { it in sentences.indices }
                .distinct()
                .sorted()
            playerBookmarks.addAll(validBookmarks)
            playerSelectedIndex = savedState.lastPlayedIndex.coerceIn(0, sentences.lastIndex)
        } else {
            playerSelectedIndex = 0
        }
    }

    private fun persistCurrentPlayerBookState() {
        val key = playerBookKey ?: return
        val lastIdx = playerSelectedIndex
        val bookmarksSnapshot = playerBookmarks.toList()
        viewModelScope.launch(Dispatchers.IO) {
            repo.savePlayerBookState(key, lastIdx, bookmarksSnapshot)
        }
    }

    private fun syncPlayerNotification(forceShow: Boolean = false) {
        if (playerSentences.isEmpty()) {
            PlayerNotificationService.dismiss(getApplication())
            return
        }
        if (!playerIsPlaying && !forceShow && !PlayerNotificationService.isNotificationVisible) {
            return
        }
        val bookTitle = bookTitles.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: book?.fileNames?.joinToString(", ") { it.fileName }.orEmpty()
        val total = playerSentences.size
        val current = (playerSelectedIndex + 1).coerceIn(1, total)
        val pct = ((current.toFloat() / total.toFloat()) * 100f).roundToInt().coerceIn(0, 100)
        val counter = "$current/$total ($pct%)"
        val title = if (bookTitle.isNotBlank()) "$bookTitle • $counter" else counter
        val sentenceText = playerSentences.getOrNull(playerSelectedIndex)?.text.orEmpty()
        PlayerNotificationService.showOrUpdate(
            context = getApplication(),
            title = title,
            sentenceText = sentenceText,
            isPlaying = playerIsPlaying,
            progress = current,
            max = total,
            prevLabel = tr("KEY_EDITOR_PREV"),
            playPauseLabel = tr(if (playerIsPlaying) "KEY_PAUSE" else "KEY_RESUME"),
            nextLabel = tr("KEY_EDITOR_NEXT")
        )
    }

    fun onTogglePlayerBookmark(index: Int = playerSelectedIndex) {
        if (playerBookKey == null || index !in playerSentences.indices) return
        if (playerBookmarks.contains(index)) {
            playerBookmarks.remove(index)
        } else {
            playerBookmarks.add(index)
            playerBookmarks.sort()
        }
        persistCurrentPlayerBookState()
    }

    fun onRemovePlayerBookmark(index: Int) {
        if (playerBookKey == null) return
        if (playerBookmarks.remove(index)) {
            persistCurrentPlayerBookState()
        }
    }

    fun getCachedPlayerChunkCount(): Int {
        var count = 0
        for (i in playerSentences.indices) {
            val f = playerCachedFiles[i]
            if (f != null && f.exists() && f.length() > 0L) {
                count++
            }
        }
        return count
    }

    fun defaultSharedPlayerSaveFileName(): String {
        val rawTitle = bookTitles.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: book?.fileNames?.firstOrNull()?.fileName?.takeIf { it.isNotBlank() }
            ?: "Text"
        val cleanStem = File(bookStem(rawTitle)).name.trim().ifEmpty { "Text" }
        val ext = if (settings.opusOutput) ".opus" else ".mp3"
        return "$cleanStem$ext"
    }

    fun saveCachedPlayerChunksToUri(targetUri: Uri) {
        val totalSentences = playerSentences.size
        if (totalSentences == 0) return
        val useOpus = settings.opusOutput
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val baos = java.io.ByteArrayOutputStream()
                var includedCount = 0
                for (i in 0 until totalSentences) {
                    val f = playerCachedFiles[i]
                    if (f != null && f.exists() && f.length() > 0L) {
                        baos.write(f.readBytes())
                        includedCount++
                    }
                }
                val mergedMp3 = baos.toByteArray()
                if (includedCount == 0 || mergedMp3.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        statInfoDoneHighlight = false
                        statInfo = tr("KEY_NO_CACHED_CHUNKS")
                        ttsSay(statInfo)
                    }
                    return@launch
                }

                val embedMeta = settings.embedMetadata
                val bookMeta = book?.fileNames?.firstOrNull()?.metadata
                    ?: BookAudioMetadata(title = bookTitles.firstOrNull().orEmpty())
                val albumTitle = bookMeta.title.ifBlank { bookTitles.firstOrNull().orEmpty() }.ifBlank { "Audiobook" }
                val voiceName = if (settings.useOnlineVoicesPlayer) {
                    settings.voicesName
                } else {
                    resolveOfflineFallbackVoice().orEmpty()
                }

                val finalBytes = if (useOpus) {
                    val opus = OpusConverter.convertMp3ToOpus(mergedMp3)
                    if (opus != null && opus.isNotEmpty()) {
                        if (embedMeta) {
                            AudioMetadataTagger.tagOpus(
                                opusBytes = opus,
                                bookMeta = bookMeta,
                                trackTitle = albumTitle,
                                albumTitle = albumTitle,
                                trackNumber = 1,
                                totalTracks = 1,
                                voiceName = voiceName
                            )
                        } else {
                            opus
                        }
                    } else if (embedMeta) {
                        AudioMetadataTagger.tagMp3(
                            mp3Bytes = mergedMp3,
                            bookMeta = bookMeta,
                            trackTitle = albumTitle,
                            albumTitle = albumTitle,
                            trackNumber = 1,
                            totalTracks = 1,
                            voiceName = voiceName
                        )
                    } else {
                        mergedMp3
                    }
                } else if (embedMeta) {
                    AudioMetadataTagger.tagMp3(
                        mp3Bytes = mergedMp3,
                        bookMeta = bookMeta,
                        trackTitle = albumTitle,
                        albumTitle = albumTitle,
                        trackNumber = 1,
                        totalTracks = 1,
                        voiceName = voiceName
                    )
                } else {
                    mergedMp3
                }

                getApplication<Application>().contentResolver.openOutputStream(targetUri)?.use { out ->
                    out.write(finalBytes)
                    out.flush()
                }

                withContext(Dispatchers.Main) {
                    statInfoDoneHighlight = true
                    statInfo = tr("KEY_SAVED")
                    ttsSay(tr("KEY_SAVED"))
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    statInfoDoneHighlight = false
                    statInfo = tr("KEY_FD_CANCELLED")
                    ttsSay(tr("KEY_FD_CANCELLED"))
                }
            }
        }
    }

    fun invalidatePlayerCache() {
        val wasPlaying = playerIsPlaying
        val currentIdx = playerSelectedIndex
        stopPlayerPlayback(clearCache = true)
        playerSentenceStatuses.clear()
        if (wasPlaying && playerSentences.isNotEmpty()) {
            startPlayerFrom(currentIdx)
        }
    }

    fun onPlayerPlayPausePressed() {
        if (playerSentences.isEmpty()) return
        if (playerIsPlaying) {
            pausePlayerPlayback()
        } else {
            startPlayerFrom(playerSelectedIndex)
        }
    }

    fun onPlayerPrevPressed() {
        if (playerSentences.isEmpty()) return
        val target = (playerSelectedIndex - 1).coerceIn(0, playerSentences.lastIndex)
        if (playerIsPlaying) {
            startPlayerFrom(target)
        } else {
            playerSelectedIndex = target
            persistCurrentPlayerBookState()
            syncPlayerNotification()
        }
    }

    fun onPlayerNextPressed() {
        if (playerSentences.isEmpty()) return
        val target = (playerSelectedIndex + 1).coerceIn(0, playerSentences.lastIndex)
        if (playerIsPlaying) {
            startPlayerFrom(target)
        } else {
            playerSelectedIndex = target
            persistCurrentPlayerBookState()
            syncPlayerNotification()
        }
    }

    fun onPlayerCardClicked(index: Int) {
        if (index !in playerSentences.indices) return
        startPlayerFrom(index)
    }

    private fun updatePlayerSentenceStatus(index: Int, status: PartStatus, expectedRunId: Int) {
        viewModelScope.launch(Dispatchers.Main) {
            if (playerRunIdGen.get() == expectedRunId && index in playerSentences.indices) {
                if (status == PartStatus.OPENED) {
                    playerSentenceStatuses.remove(index)
                } else if (playerSentenceStatuses[index] != status) {
                    playerSentenceStatuses[index] = status
                }
            }
        }
    }

    private fun clearTransientPlayerStatuses() {
        val toRemove = playerSentenceStatuses.entries
            .filter { (idx, st) -> st != PartStatus.SAVED || !playerCachedFiles.containsKey(idx) }
            .map { it.key }
        for (idx in toRemove) {
            playerSentenceStatuses.remove(idx)
        }
    }

    private fun startPlayerFrom(startIndex: Int) {
        if (playerSentences.isEmpty()) return
        val wantOnline = settings.useOnlineVoicesPlayer
        val offlineVoice = resolveOfflineFallbackVoice()
        if (!wantOnline) {
            if (offlineVoice.isNullOrBlank() || isOfflineUnavailable(listOf(offlineVoice))) {
                if (offlineVoice.isNullOrBlank()) {
                    statInfoDoneHighlight = false
                    statInfo = tr("KEY_OFFLINE_UNAVAILABLE")
                    ttsSay(statInfo)
                }
                return
            }
        }

        voicePreviewStop()
        stopPlayerMediaOnly()

        val targetStart = startIndex.coerceIn(0, playerSentences.lastIndex)
        playerSelectedIndex = targetStart
        playerIsPlaying = true
        persistCurrentPlayerBookState()
        syncPlayerNotification(forceShow = true)

        val runId = playerRunIdGen.incrementAndGet()
        playerCoordinatorJob?.cancel()

        clearTransientPlayerStatuses()

        val sentencesSnapshot = playerSentences
        val total = sentencesSnapshot.size
        val sessionId = playerSessionIdGen.get()
        val maxParallel = settings.maxThreads.coerceAtLeast(1)
        val pitchStr = "${formatSignedValue(settings.pitch)}Hz"
        val rateStr = "${formatSignedValue(settings.rate)}%"
        val lowBitrate = settings.lowBitrate
        val onlineVoice = settings.voicesName
        val timeoutMsec = if (settings.restartDelay < 0) Long.MAX_VALUE else settings.restartDelay * 1000L

        playerCoordinatorJob = viewModelScope.launch(Dispatchers.IO) {
            val cacheDir = File(getApplication<Application>().cacheDir, "player_cache").apply { mkdirs() }
            val currentPlayIdxAtomic = AtomicInteger(targetStart)
            val nextSynthIdxAtomic = AtomicInteger(targetStart)
            val activeSynthCount = AtomicInteger(0)

            val producerJob = launch {
                val workerJobs = mutableListOf<Job>()
                while (isActive && playerRunIdGen.get() == runId) {
                    val playIdx = currentPlayIdxAtomic.get()
                    if (playIdx >= total) break
                    val candidate = nextSynthIdxAtomic.get()
                    if (candidate < playIdx) {
                        nextSynthIdxAtomic.compareAndSet(candidate, playIdx)
                        continue
                    }
                    if (candidate >= total) {
                        delay(40)
                        continue
                    }
                    val existing = playerCachedFiles[candidate]
                    if (existing != null && existing.exists()) {
                        updatePlayerSentenceStatus(candidate, PartStatus.SAVED, runId)
                        nextSynthIdxAtomic.compareAndSet(candidate, candidate + 1)
                        continue
                    }
                    if (activeSynthCount.get() < maxParallel && candidate < playIdx + maxParallel) {
                        val claimIdx = nextSynthIdxAtomic.getAndIncrement()
                        if (claimIdx >= total) continue
                        val alreadyCached = playerCachedFiles[claimIdx]
                        if (alreadyCached != null && alreadyCached.exists()) {
                            updatePlayerSentenceStatus(claimIdx, PartStatus.SAVED, runId)
                            continue
                        }
                        val sentenceText = sentencesSnapshot.getOrNull(claimIdx)?.text ?: continue
                        activeSynthCount.incrementAndGet()
                        workerJobs.removeAll { it.isCompleted }
                        val wJob = launch {
                            try {
                                val isCancelledFn = {
                                    !isActive || playerRunIdGen.get() != runId || claimIdx < currentPlayIdxAtomic.get()
                                }
                                if (isCancelledFn()) return@launch
                                var mp3Bytes: ByteArray? = null
                                val hasOfflineFallback = !offlineVoice.isNullOrBlank()
                                val shouldUseOnline = settings.useOnlineVoicesPlayer &&
                                    !repo.isVoiceOffline(onlineVoice)

                                if (shouldUseOnline) {
                                    mp3Bytes = SocketEdgeTtsWorker.synthesizePart(
                                        voice = onlineVoice,
                                        pitch = pitchStr,
                                        rate = rateStr,
                                        text = sentenceText,
                                        lowBitrate = lowBitrate,
                                        timeoutMsec = timeoutMsec,
                                        isPreview = false,
                                        fallbackToOfflineOnError = hasOfflineFallback,
                                        isPaused = { false },
                                        isCancelled = isCancelledFn,
                                        onStatusUpdate = { key ->
                                            updatePlayerSentenceStatus(claimIdx, PartStatus.fromKey(key), runId)
                                        }
                                    )
                                }
                                if ((mp3Bytes == null || mp3Bytes.isEmpty()) && !isCancelledFn()) {
                                    if (!offlineVoice.isNullOrBlank()) {
                                        val voiceDir = File(File(repo.programDir, "Voices"), offlineVoice)
                                        if (OfflineTtsEngine.voiceInstalled(voiceDir)) {
                                            if (shouldUseOnline) {
                                                OfflineTtsEngine.showFallbackToast(
                                                    getApplication(),
                                                    tr("KEY_OFFLINE_FALLBACK_TOAST")
                                                )
                                            }
                                            mp3Bytes = OfflineTtsEngine.synthesizePart(
                                                context = getApplication(),
                                                voiceDir = voiceDir,
                                                text = sentenceText,
                                                rate = rateStr,
                                                pitch = pitchStr,
                                                lowBitrate = lowBitrate,
                                                isPreview = false,
                                                isPaused = { false },
                                                isCancelled = isCancelledFn,
                                                onStatusUpdate = { key ->
                                                    updatePlayerSentenceStatus(claimIdx, PartStatus.fromKey(key), runId)
                                                }
                                            )
                                        }
                                    }
                                }
                                if (mp3Bytes != null && mp3Bytes.isNotEmpty() && !isCancelledFn()) {
                                    val outFile = File(cacheDir, "player_${sessionId}_$claimIdx.mp3")
                                    FileOutputStream(outFile).use { it.write(mp3Bytes) }
                                    playerCachedFiles[claimIdx] = outFile
                                    updatePlayerSentenceStatus(claimIdx, PartStatus.SAVED, runId)
                                } else {
                                    updatePlayerSentenceStatus(claimIdx, PartStatus.OPENED, runId)
                                }
                            } catch (_: Exception) {
                            } finally {
                                activeSynthCount.decrementAndGet()
                            }
                        }
                        workerJobs.add(wJob)
                    } else {
                        delay(35)
                    }
                }
                workerJobs.forEach { it.join() }
            }

            var playIdx = targetStart
            while (isActive && playerRunIdGen.get() == runId && playIdx < total) {
                if (playIdx > targetStart) {
                    val nextItem = sentencesSnapshot.getOrNull(playIdx)
                    val transitionPauseMs = when {
                        nextItem?.startsNewChapter == true -> 1000L
                        nextItem?.startsNewParagraph == true -> 450L
                        else -> 0L
                    }
                    if (transitionPauseMs > 0L) {
                        delay(transitionPauseMs)
                    }
                    if (!isActive || playerRunIdGen.get() != runId) break
                }
                currentPlayIdxAtomic.set(playIdx)
                withContext(Dispatchers.Main) {
                    if (playerRunIdGen.get() == runId) {
                        playerSelectedIndex = playIdx
                        persistCurrentPlayerBookState()
                        syncPlayerNotification(forceShow = true)
                    }
                }
                var audioFile = playerCachedFiles[playIdx]
                while ((audioFile == null || !audioFile.exists()) && isActive && playerRunIdGen.get() == runId) {
                    delay(35)
                    audioFile = playerCachedFiles[playIdx]
                }
                if (!isActive || playerRunIdGen.get() != runId || audioFile == null || !audioFile.exists()) {
                    break
                }
                val finishedOk = playSinglePlayerFileSuspend(audioFile, runId)
                if (!finishedOk || !isActive || playerRunIdGen.get() != runId) {
                    break
                }
                playIdx++
            }

            producerJob.cancel()
            withContext(Dispatchers.Main) {
                if (playerRunIdGen.get() == runId) {
                    playerIsPlaying = false
                    PlayerNotificationService.dismiss(getApplication())
                }
            }
        }
    }

    private suspend fun playSinglePlayerFileSuspend(audioFile: File, expectedRunId: Int): Boolean =
        withContext(Dispatchers.Main) {
            if (playerRunIdGen.get() != expectedRunId || !audioFile.exists()) {
                return@withContext false
            }
            suspendCancellableCoroutine { cont ->
                val mp = MediaPlayer()
                playerMediaPlayer = mp
                cont.invokeOnCancellation {
                    try {
                        mp.stop()
                    } catch (_: Exception) {
                    }
                    try {
                        mp.release()
                    } catch (_: Exception) {
                    }
                    if (playerMediaPlayer === mp) {
                        playerMediaPlayer = null
                    }
                }
                try {
                    mp.setDataSource(audioFile.absolutePath)
                    mp.setOnCompletionListener { player ->
                        try {
                            player.release()
                        } catch (_: Exception) {
                        }
                        if (playerMediaPlayer === player) {
                            playerMediaPlayer = null
                        }
                        if (cont.isActive) {
                            cont.resume(true)
                        }
                    }
                    mp.setOnErrorListener { player, _, _ ->
                        try {
                            player.release()
                        } catch (_: Exception) {
                        }
                        if (playerMediaPlayer === player) {
                            playerMediaPlayer = null
                        }
                        if (cont.isActive) {
                            cont.resume(true)
                        }
                        true
                    }
                    mp.prepare()
                    mp.start()
                } catch (_: Exception) {
                    try {
                        mp.release()
                    } catch (_: Exception) {
                    }
                    if (playerMediaPlayer === mp) {
                        playerMediaPlayer = null
                    }
                    if (cont.isActive) {
                        cont.resume(false)
                    }
                }
            }
        }

    private fun stopPlayerMediaOnly() {
        try {
            playerMediaPlayer?.stop()
        } catch (_: Exception) {
        }
        try {
            playerMediaPlayer?.release()
        } catch (_: Exception) {
        }
        playerMediaPlayer = null
    }

    fun pausePlayerPlayback() {
        playerRunIdGen.incrementAndGet()
        playerCoordinatorJob?.cancel()
        playerCoordinatorJob = null
        stopPlayerMediaOnly()
        playerIsPlaying = false
        clearTransientPlayerStatuses()
        syncPlayerNotification()
    }

    fun stopPlayerPlayback(clearCache: Boolean = false) {
        pausePlayerPlayback()
        PlayerNotificationService.dismiss(getApplication())
        if (clearCache) {
            playerSessionIdGen.incrementAndGet()
            playerSentenceStatuses.clear()
            val filesToDelete = ArrayList(playerCachedFiles.values)
            playerCachedFiles.clear()
            viewModelScope.launch(Dispatchers.IO) {
                filesToDelete.forEach {
                    try {
                        it.delete()
                    } catch (_: Exception) {
                    }
                }
                try {
                    File(getApplication<Application>().cacheDir, "player_cache")
                        .listFiles()
                        ?.forEach { it.delete() }
                } catch (_: Exception) {
                }
            }
        }
    }

    // Voice & Part Preview
    fun onEditorPlayPressed() {
        if (partPreviewActive) {
            voicePreviewStop()
        } else if (!runWork && textAreaText.trim().isNotEmpty()) {
            val sanitized = textAreaText
                .replace("&", " and ")
                .replace("<", "(")
                .replace(">", ")")
            if (previewStart(sanitized, isPartPreview = true)) {
                partPreviewActive = true
            }
        }
    }

    private fun previewStart(
        previewText: String,
        isPartPreview: Boolean,
        forceVoice: String? = null,
        forceOnline: Boolean? = null
    ): Boolean {
        val wantOnline = forceOnline ?: settings.useOnlineVoices
        val chosenVoice = when {
            forceVoice != null -> forceVoice
            wantOnline -> settings.voicesName
            else -> resolveOfflineFallbackVoice().orEmpty()
        }
        if (!wantOnline || repo.isVoiceOffline(chosenVoice)) {
            if (chosenVoice.isBlank() || isOfflineUnavailable(listOf(chosenVoice))) {
                if (chosenVoice.isBlank()) {
                    statInfoDoneHighlight = false
                    statInfo = tr("KEY_OFFLINE_UNAVAILABLE")
                    ttsSay(statInfo)
                }
                return false
            }
        }

        voicePreviewStop()
        voicePreviewPrevStatus = statInfo
        statInfoDoneHighlight = false
        statInfo = tr("KEY_VOICE_LOADING")
        partPreviewActive = isPartPreview

        val pitchStr = "${formatSignedValue(settings.pitch)}Hz"
        val rateStr = "${formatSignedValue(settings.rate)}%"
        val lowBitrate = settings.lowBitrate

        previewJob = viewModelScope.launch(Dispatchers.IO) {
            var mp3Bytes: ByteArray? = null
            val offlineVoice = if (repo.isVoiceOffline(chosenVoice)) chosenVoice else resolveOfflineFallbackVoice()
            val hasOfflineFallback = !offlineVoice.isNullOrBlank()
            val useOnlineNow = wantOnline && !repo.isVoiceOffline(chosenVoice)
            if (useOnlineNow) {
                mp3Bytes = SocketEdgeTtsWorker.synthesizePart(
                    voice = chosenVoice,
                    pitch = pitchStr,
                    rate = rateStr,
                    text = previewText,
                    lowBitrate = lowBitrate,
                    timeoutMsec = 30_000L,
                    isPreview = true,
                    fallbackToOfflineOnError = hasOfflineFallback,
                    isPaused = { false },
                    isCancelled = { !isActive },
                    onStatusUpdate = {}
                )
            }
            if ((mp3Bytes == null || mp3Bytes.isEmpty()) && isActive) {
                if (!offlineVoice.isNullOrBlank()) {
                    val voiceDir = File(File(repo.programDir, "Voices"), offlineVoice)
                    if (OfflineTtsEngine.voiceInstalled(voiceDir)) {
                        if (useOnlineNow) {
                            OfflineTtsEngine.showFallbackToast(
                                getApplication(),
                                tr("KEY_OFFLINE_FALLBACK_TOAST"),
                                force = true
                            )
                        }
                        mp3Bytes = OfflineTtsEngine.synthesizePart(
                            context = getApplication(),
                            voiceDir = voiceDir,
                            text = previewText,
                            rate = rateStr,
                            pitch = pitchStr,
                            lowBitrate = lowBitrate,
                            isPreview = true,
                            isPaused = { false },
                            isCancelled = { !isActive },
                            onStatusUpdate = {}
                        )
                    }
                }
            }

            withContext(Dispatchers.Main) {
                restorePreviewStatus()
                if (mp3Bytes == null || mp3Bytes.isEmpty() || !isActive) {
                    partPreviewActive = false
                    return@withContext
                }
                playPreviewBytes(mp3Bytes)
            }
        }
        return true
    }

    private fun playPreviewBytes(mp3Bytes: ByteArray) {
        try {
            val tempFile = File.createTempFile("preview_", ".mp3", getApplication<Application>().cacheDir)
            tempFile.writeBytes(mp3Bytes)
            val mp = MediaPlayer()
            mediaPlayer = mp
            mp.setDataSource(tempFile.absolutePath)
            mp.setOnCompletionListener { player ->
                player.release()
                tempFile.delete()
                if (mediaPlayer === player) {
                    mediaPlayer = null
                    partPreviewActive = false
                }
            }
            mp.setOnErrorListener { player, _, _ ->
                player.release()
                tempFile.delete()
                if (mediaPlayer === player) {
                    mediaPlayer = null
                    partPreviewActive = false
                }
                true
            }
            mp.prepare()
            mp.start()
        } catch (_: Exception) {
            partPreviewActive = false
        }
    }

    fun voicePreviewStop() {
        restorePreviewStatus()
        previewJob?.cancel()
        previewJob = null
        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {
        }
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {
        }
        mediaPlayer = null
        partPreviewActive = false
    }

    private fun restorePreviewStatus() {
        val prev = voicePreviewPrevStatus
        if (prev != null && statInfo == tr("KEY_VOICE_LOADING")) {
            statInfo = prev
        }
        voicePreviewPrevStatus = null
    }

    // Conversion Run / Pause / Stop
    fun onSaveOrStopButtonPressed() {
        if (runWork || progressBarVisible) {
            stopConversion(announce = true)
        } else if (bookLoaded && totalPartsCount > 0) {
            startConversion()
        }
    }

    fun onPauseOrResumeButtonPressed() {
        if (!runWork) return
        workPaused = !workPaused
        val composite = computeCompositeProgress()
        val pct = (composite * 100f).roundToInt().coerceIn(0, 100)
        progressBarMax = 1000
        progressBarValue = (composite * 1000f).roundToInt().coerceIn(0, 1000)
        val notifTitle = book?.fileNames?.joinToString(", ") { it.fileName }.orEmpty()
        if (workPaused) {
            pauseIdleSince = System.currentTimeMillis()
            statInfoDoneHighlight = false
            statInfo = tr("KEY_PAUSE")
            ttsSay("${tr("KEY_PAUSE")}. $statStr")
        } else {
            if (pauseIdleSince > 0L) {
                workStartMsec += System.currentTimeMillis() - pauseIdleSince
            }
            pauseIdleSince = 0L
            statInfoDoneHighlight = false
            statInfo = tr("KEY_PROCESSED")
            ttsSay(tr("KEY_WORK_ON"))
        }
        ForegroundWorkService.updateProgress(
            context = getApplication(),
            text = "$statInfo $statStr • $chunksStatStr ($pct%)",
            progress = progressBarValue,
            max = progressBarMax,
            title = notifTitle
        )
        updateElapsedLabel()
    }

    private fun isOfflineUnavailable(voicesToCheck: List<String>): Boolean {
        for (v in voicesToCheck) {
            val voiceDir = File(File(repo.programDir, "Voices"), v)
            if (repo.isVoiceOffline(v) && (offlineVoicesBusy || !OfflineTtsEngine.voiceInstalled(voiceDir))) {
                statInfoDoneHighlight = false
                statInfo = tr("KEY_OFFLINE_UNAVAILABLE")
                ttsSay(statInfo)
                return true
            }
        }
        return false
    }

    private fun startConversion() {
        val currentBook = book
        val wantOnlineNow = settings.useOnlineVoices && isNetworkAvailable()
        val defaultVoice = if (wantOnlineNow) {
            settings.voicesName
        } else {
            resolveOfflineFallbackVoice().orEmpty()
        }
        val usedVoices = if (currentBook == null) {
            listOf(defaultVoice)
        } else {
            currentBook.fileNames.map { it.voice.ifEmpty { defaultVoice } }
        }
        if (!wantOnlineNow && usedVoices.any { it.isBlank() }) {
            statInfoDoneHighlight = false
            statInfo = tr("KEY_OFFLINE_UNAVAILABLE")
            ttsSay(statInfo)
            return
        }
        if (isOfflineUnavailable(usedVoices)) return

        stopConversion(announce = false)
        pausePlayerPlayback()
        voicePreviewStop()

        if (book == null) {
            numTextCounter++
            val seedText = textAreaText
            val defaultTitle = bookTitles.firstOrNull()?.takeIf { it.isNotBlank() }
                ?: "Text ${"%04d".format(numTextCounter)}"
            val created = ProcessingFile(
                fileName = defaultTitle,
                text = seedText,
                langLexx = resolveActiveLexxRules(),
                lexxRegister = settings.lexxRegister,
                filterEmojis = settings.filterEmojis,
                bookChapter = settings.bookChapter,
                glavTextUse = settings.glavTextUse,
                regexGlavaPattern = settings.regexGlavaCompile,
                voice = "",
                rate = "",
                pitch = ""
            )
            book = created
            syncBookTitlesFromBook()
            loadedBooksCount = bookTitles.size.coerceAtLeast(1)
            bookLoaded = created.allSentences.isNotEmpty()
        }

        val activeBook = book ?: return
        activeBook.fileNames.forEachIndexed { i, meta ->
            val edited = bookTitles.getOrNull(i)?.trim().orEmpty()
            if (edited.isNotEmpty()) {
                meta.fileName = edited
            } else if (meta.fileName.isBlank()) {
                meta.fileName = "Text ${"%04d".format(i + 1)}"
            }
        }
        val total = activeBook.allSentences.size
        processedPartsCount = 0
        totalPartsCount = total
        postprocessedChunksCount = 0
        totalChunksCount = computeTotalChunks(activeBook)
        statStr = "0/$totalPartsCount"
        chunksStatStr = "0/$totalChunksCount"
        partStatuses.clear()
        if (total > 0) {
            partStatuses.addAll(java.util.Collections.nCopies(total, PartStatus.OPENED))
        }

        workPaused = false
        pauseIdleSince = 0L
        runWork = true
        val currentRunId = runIdGen.incrementAndGet()
        val notifTitle = activeBook.fileNames.map { it.fileName }.distinct().joinToString(", ")
        ForegroundWorkService.setActive(
            context = getApplication(),
            active = true,
            text = "${tr("KEY_PROCESSED")} $statStr • $chunksStatStr (0%)",
            progress = 0,
            max = 1000,
            title = notifTitle
        )

        statInfoDoneHighlight = false
        statInfo = tr("KEY_PROCESSED")
        ttsSay("${tr("KEY_WORK_ON")}. $statInfo $statStr")

        workStartMsec = System.currentTimeMillis()
        workFinished = false
        elapsedVisible = true
        updateElapsedLabel()
        startElapsedLoop()

        val runMergeFiles = settings.mergeFiles
        val runBookDirCreate = settings.bookDirCreate
        val runLowBitrate = settings.lowBitrate
        val runOpusOutput = settings.opusOutput
        val runEmbedMetadata = settings.embedMetadata
        val runSaveDir = settings.saveDir.ifEmpty { repo.defaultDownloadsDir }
        val timeoutMsec = if (settings.restartDelay < 0) Long.MAX_VALUE else settings.restartDelay * 1000L

        progressBarMax = 1000
        progressBarValue = 0
        progressBarVisible = true

        if (total == 0) {
            finalizeConversion(currentRunId, runSaveDir, emptyList())
            return
        }

        // Precompute per-part metadata matching index.gd thread_work_run
        data class PartJobSpec(
            val index: Int,
            val fileName: String,
            val glava: String,
            val fileNum: String,
            val voice: String,
            val pitch: String,
            val rate: String,
            val text: String,
            val metadata: BookAudioMetadata
        )

        // Ensure each loaded book has a clean, unique book folder/stem name
        val usedBookStems = HashSet<String>()
        val bookFolderNames = activeBook.fileNames.mapIndexed { idx, meta ->
            val baseStem = File(bookStem(meta.fileName)).name.trim().ifEmpty { "Text ${"%04d".format(idx + 1)}" }
            if (usedBookStems.add(baseStem)) {
                baseStem
            } else {
                val disambiguated = "$baseStem (${idx + 1})"
                usedBookStems.add(disambiguated)
                disambiguated
            }
        }

        val specs = mutableListOf<PartJobSpec>()
        var fileNameInd = 0
        var fixNumBook = 0
        for (idx in 0 until total) {
            val currentMeta = activeBook.fileNames[fileNameInd]
            if (currentMeta.endSentenceIndex in 1..idx && fileNameInd + 1 < activeBook.fileNames.size) {
                fileNameInd++
                fixNumBook = idx
            }
            val meta = activeBook.fileNames[fileNameInd]
            val bookFolderName = bookFolderNames[fileNameInd]
            val fVoice = meta.voice.ifEmpty {
                if (settings.useOnlineVoices) settings.voicesName else resolveOfflineFallbackVoice().orEmpty()
            }
            val fRate = meta.rate.ifEmpty { formatSignedValue(settings.rate) }
            val fPitch = meta.pitch.ifEmpty { formatSignedValue(settings.pitch) }
            specs.add(
                PartJobSpec(
                    index = idx,
                    fileName = bookFolderName,
                    glava = activeBook.allGlavs[idx],
                    fileNum = "%04d".format(idx + 1 - fixNumBook),
                    voice = fVoice,
                    pitch = "${fPitch}Hz",
                    rate = "${fRate}%",
                    text = activeBook.allSentences[idx],
                    metadata = meta.metadata
                )
            )
        }

        val addGlavMp3 = settings.bookChapter && settings.glavMp3Use
        val soundGlavaBytes = if (addGlavMp3) {
            try {
                if (settings.glavMp3Path.isBlank()) {
                    val assetName = if (runLowBitrate) "Silence_48.mp3" else "Silence_96.mp3"
                    getApplication<Application>().assets.open(assetName).use { it.readBytes() }
                } else {
                    val f = File(settings.glavMp3Path)
                    if (f.exists()) f.readBytes() else ByteArray(0)
                }
            } catch (_: Exception) {
                ByteArray(0)
            }
        } else {
            ByteArray(0)
        }

        val specsByBook = LinkedHashMap<String, LinkedHashMap<String, MutableList<PartJobSpec>>>()
        for (spec in specs) {
            val bookFolderName = File(bookStem(spec.fileName)).name.trim().ifEmpty { "Book" }
            val byGlava = specsByBook.getOrPut(bookFolderName) { LinkedHashMap() }
            val glavaParts = byGlava.getOrPut(spec.glava.trim()) { mutableListOf() }
            glavaParts.add(spec)
        }

        val multipleBooks = specsByBook.size > 1 || loadedBooksCount.coerceAtLeast(1) > 1
        val partToChunkMap = arrayOfNulls<ChunkJobSpec>(total)
        var totalChunksAcrossBooks = 0

        for ((bookFolderName, byGlava) in specsByBook) {
            val glavaChunkedList = mutableListOf<Pair<String, List<List<PartJobSpec>>>>()
            var totalChunksForBook = 0
            for ((glava, glavaParts) in byGlava) {
                val sortedParts = glavaParts.sortedBy { it.fileNum }
                val chunkSize = when {
                    runMergeFiles <= 1 -> 1
                    runMergeFiles >= 100 -> sortedParts.size
                    else -> runMergeFiles
                }
                val chunks = sortedParts.chunked(chunkSize.coerceAtLeast(1))
                totalChunksForBook += chunks.size
                glavaChunkedList.add(glava to chunks)
            }
            totalChunksAcrossBooks += totalChunksForBook

            var bookChunkCounter = 0
            for ((glava, chunks) in glavaChunkedList) {
                val totalChunksForGlava = chunks.size
                for ((chunkIdx, chunkParts) in chunks.withIndex()) {
                    bookChunkCounter++
                    val partNumberStr = "%04d".format(chunkIdx + 1)
                    val targetBaseName = if (glava.isEmpty()) {
                        "c$partNumberStr"
                    } else if (totalChunksForGlava == 1) {
                        glava
                    } else {
                        "${glava}_c$partNumberStr"
                    }
                    val firstPart = chunkParts.first()
                    val chunkSpec = ChunkJobSpec(
                        bookFolderName = bookFolderName,
                        glava = glava,
                        chunkIdx = chunkIdx,
                        totalChunksForGlava = totalChunksForGlava,
                        totalChunksForBook = totalChunksForBook,
                        bookChunkNumber = bookChunkCounter,
                        targetBaseName = targetBaseName,
                        includeGlavaIntro = addGlavMp3 && chunkIdx == 0,
                        fileNums = chunkParts.map { it.fileNum },
                        remainingParts = AtomicInteger(chunkParts.size),
                        bookMetadata = firstPart.metadata,
                        voice = firstPart.voice
                    )
                    for (partSpec in chunkParts) {
                        partToChunkMap[partSpec.index] = chunkSpec
                    }
                }
            }
        }

        totalChunksCount = totalChunksAcrossBooks
        chunksStatStr = "0/$totalChunksCount"

        val savedPartsAtomic = AtomicInteger(0)
        val postprocessedChunksAtomic = AtomicInteger(0)
        val launchedAtomic = AtomicInteger(0)

        suspend fun publishConversionProgress(doneParts: Int, doneChunks: Int) {
            withContext(Dispatchers.Main) {
                if (runIdGen.get() == currentRunId && runWork) {
                    processedPartsCount = doneParts
                    postprocessedChunksCount = doneChunks
                    statStr = "$doneParts/$totalPartsCount"
                    chunksStatStr = "$doneChunks/$totalChunksCount"
                    val composite = computeCompositeProgress(
                        doneParts = doneParts,
                        totalParts = totalPartsCount,
                        doneChunks = doneChunks,
                        totalChunks = totalChunksCount
                    )
                    progressBarMax = 1000
                    progressBarValue = (composite * 1000f).roundToInt().coerceIn(0, 1000)
                    val pct = (composite * 100f).roundToInt().coerceIn(0, 100)
                    val statusLabel = tr(if (workPaused) "KEY_PAUSE" else "KEY_PROCESSED")
                    ForegroundWorkService.updateProgress(
                        context = getApplication(),
                        text = "$statusLabel $statStr • $chunksStatStr ($pct%)",
                        progress = progressBarValue,
                        max = progressBarMax,
                        title = notifTitle
                    )
                }
            }
        }

        conversionMasterJob = viewModelScope.launch(Dispatchers.IO) {
            val childJobs = mutableListOf<Job>()

            while (isActive && launchedAtomic.get() < total) {
                if (workPaused) {
                    delay(100)
                    continue
                }
                val savedCount = savedPartsAtomic.get()
                val maxWindow = savedCount + settings.maxThreads
                val nextIdx = launchedAtomic.get()
                if (nextIdx < maxWindow && nextIdx < total) {
                    launchedAtomic.incrementAndGet()
                    val spec = specs[nextIdx]
                    val job = launch {
                        val offlineVoice = if (repo.isVoiceOffline(spec.voice)) {
                            spec.voice
                        } else {
                            resolveOfflineFallbackVoice()
                        }
                        val hasOfflineFallback = !offlineVoice.isNullOrBlank()
                        val wantOnline = settings.useOnlineVoices && !repo.isVoiceOffline(spec.voice)
                        val shouldUseOnline = wantOnline && isNetworkAvailable()
                        var mp3Data: ByteArray? = null
                        if (shouldUseOnline) {
                            mp3Data = SocketEdgeTtsWorker.synthesizePart(
                                voice = spec.voice,
                                pitch = spec.pitch,
                                rate = spec.rate,
                                text = spec.text,
                                lowBitrate = runLowBitrate,
                                timeoutMsec = timeoutMsec,
                                isPreview = false,
                                fallbackToOfflineOnError = hasOfflineFallback,
                                isPaused = { workPaused },
                                isCancelled = { !isActive || runIdGen.get() != currentRunId },
                                onStatusUpdate = { statusKey ->
                                    updateStatusBox(spec.index, statusKey, currentRunId)
                                }
                            )
                        }
                        if ((mp3Data == null || mp3Data.isEmpty()) && isActive && runIdGen.get() == currentRunId) {
                            if (!offlineVoice.isNullOrBlank()) {
                                val voiceDir = File(File(repo.programDir, "Voices"), offlineVoice)
                                if (OfflineTtsEngine.voiceInstalled(voiceDir)) {
                                    if (wantOnline) {
                                        OfflineTtsEngine.showFallbackToast(
                                            getApplication(),
                                            tr("KEY_OFFLINE_FALLBACK_TOAST")
                                        )
                                    }
                                    mp3Data = OfflineTtsEngine.synthesizePart(
                                        context = getApplication(),
                                        voiceDir = voiceDir,
                                        text = spec.text,
                                        rate = spec.rate,
                                        pitch = spec.pitch,
                                        lowBitrate = runLowBitrate,
                                        isPreview = false,
                                        isPaused = { workPaused },
                                        isCancelled = { !isActive || runIdGen.get() != currentRunId },
                                        onStatusUpdate = { statusKey ->
                                            updateStatusBox(spec.index, statusKey, currentRunId)
                                        }
                                    )
                                }
                            }
                        }

                        if (!isActive || runIdGen.get() != currentRunId || mp3Data == null || mp3Data.isEmpty()) {
                            return@launch
                        }

                        val savedOk = savePartMp3File(
                            data = mp3Data,
                            saveDirBase = runSaveDir,
                            fileName = spec.fileName,
                            glava = spec.glava,
                            fileNum = spec.fileNum,
                            ext = "mp3"
                        )
                        if (!savedOk) {
                            return@launch
                        }
                        updateStatusBox(spec.index, "KEY_SAVED", currentRunId)

                        val doneParts = savedPartsAtomic.incrementAndGet()
                        publishConversionProgress(doneParts, postprocessedChunksAtomic.get())

                        val chunkSpec = partToChunkMap.getOrNull(spec.index)
                        if (chunkSpec != null && chunkSpec.remainingParts.decrementAndGet() == 0) {
                            val chunkAssembled = mergeMutex.withLock {
                                assembleChunk(
                                    chunkSpec = chunkSpec,
                                    saveDirBase = runSaveDir,
                                    inBookDir = runBookDirCreate,
                                    multipleBooks = multipleBooks,
                                    soundGlavaBytes = soundGlavaBytes,
                                    opusOutput = runOpusOutput,
                                    embedMetadata = runEmbedMetadata,
                                    rid = currentRunId
                                )
                            }
                            if (chunkAssembled && runIdGen.get() == currentRunId) {
                                val doneChunks = postprocessedChunksAtomic.incrementAndGet()
                                publishConversionProgress(savedPartsAtomic.get(), doneChunks)
                            }
                        }
                    }
                    childJobs.add(job)
                    delay(100)
                } else {
                    delay(100)
                }
            }
            childJobs.forEach { it.join() }
            if (isActive && runIdGen.get() == currentRunId && savedPartsAtomic.get() >= total) {
                finalizeConversion(
                    rid = currentRunId,
                    saveDirBase = runSaveDir,
                    bookFolderNames = specsByBook.keys.toList()
                )
            }
        }
    }

    private data class ChunkJobSpec(
        val bookFolderName: String,
        val glava: String,
        val chunkIdx: Int,
        val totalChunksForGlava: Int,
        val totalChunksForBook: Int,
        val bookChunkNumber: Int,
        val targetBaseName: String,
        val includeGlavaIntro: Boolean,
        val fileNums: List<String>,
        val remainingParts: AtomicInteger,
        val bookMetadata: BookAudioMetadata,
        val voice: String
    )

    private fun updateStatusBox(index: Int, statusKey: String, expectedRunId: Int) {
        val status = PartStatus.fromKey(statusKey)
        viewModelScope.launch(Dispatchers.Main) {
            if (runIdGen.get() == expectedRunId && runWork && index in partStatuses.indices) {
                if (partStatuses[index] != status) {
                    partStatuses[index] = status
                }
            }
        }
    }

    private fun rawPartFileName(glava: String, fileNum: String, ext: String): String {
        val cleanGlava = glava.trim()
        return if (cleanGlava.isEmpty()) {
            ".part_c${fileNum}.$ext"
        } else {
            ".part_${cleanGlava}_c${fileNum}.$ext"
        }
    }

    private fun savePartMp3File(
        data: ByteArray,
        saveDirBase: String,
        fileName: String,
        glava: String,
        fileNum: String,
        ext: String = "mp3"
    ): Boolean {
        val bookFolderName = File(bookStem(fileName)).name.trim().ifEmpty { "Book" }
        val bookDir = File(saveDirBase, bookFolderName)
        if (bookDir.exists() || bookDir.mkdirs()) {
            val outFile = File(bookDir, rawPartFileName(glava, fileNum, ext))
            try {
                FileOutputStream(outFile).use { it.write(data) }
                return true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return false
    }

    private fun assembleChunk(
        chunkSpec: ChunkJobSpec,
        saveDirBase: String,
        inBookDir: Boolean,
        multipleBooks: Boolean,
        soundGlavaBytes: ByteArray,
        opusOutput: Boolean,
        embedMetadata: Boolean,
        rid: Int
    ): Boolean {
        if (runIdGen.get() != rid) return false
        val bookDir = File(saveDirBase, chunkSpec.bookFolderName)
        val outDir = if (chunkSpec.totalChunksForBook == 1 || (!inBookDir && !multipleBooks)) {
            File(saveDirBase)
        } else {
            bookDir
        }
        outDir.mkdirs()
        val finalBaseName = if (chunkSpec.totalChunksForBook == 1) {
            chunkSpec.bookFolderName
        } else {
            chunkSpec.targetBaseName
        }

        if (!embedMetadata && chunkSpec.fileNums.size == 1 && !chunkSpec.includeGlavaIntro && !opusOutput) {
            val rawFile = File(bookDir, rawPartFileName(chunkSpec.glava, chunkSpec.fileNums[0], "mp3"))
            val outFile = File(outDir, "$finalBaseName.mp3")
            if (rawFile.exists()) {
                moveFileOverwrite(rawFile, outFile)
                return outFile.exists()
            }
            return false
        }

        val mergeBuffer = ByteArrayOutputStream()
        if (chunkSpec.includeGlavaIntro && soundGlavaBytes.isNotEmpty()) {
            mergeBuffer.write(soundGlavaBytes)
        }
        for (num in chunkSpec.fileNums) {
            if (runIdGen.get() != rid) return false
            val rawFile = File(bookDir, rawPartFileName(chunkSpec.glava, num, "mp3"))
            if (rawFile.exists()) {
                try {
                    mergeBuffer.write(rawFile.readBytes())
                } catch (_: Exception) {
                }
                rawFile.delete()
            }
        }
        val assembledMp3 = mergeBuffer.toByteArray()
        if (assembledMp3.isEmpty() || runIdGen.get() != rid) return false

        val albumTitle = chunkSpec.bookMetadata.title.ifBlank { chunkSpec.bookFolderName }
        val trackTitle = when {
            chunkSpec.totalChunksForBook == 1 -> albumTitle
            chunkSpec.glava.isNotBlank() -> {
                val cleanChapter = extractDisplayChapterTitle(chunkSpec.glava).ifBlank { chunkSpec.glava.trim() }
                if (chunkSpec.totalChunksForGlava > 1) {
                    "$cleanChapter (${chunkSpec.chunkIdx + 1}/${chunkSpec.totalChunksForGlava})"
                } else {
                    cleanChapter
                }
            }
            else -> "$albumTitle - ${"%04d".format(chunkSpec.bookChunkNumber)}"
        }

        return if (opusOutput) {
            val opusBytes = OpusConverter.convertMp3ToOpus(assembledMp3)
            if (runIdGen.get() != rid) return false
            if (opusBytes != null && opusBytes.isNotEmpty()) {
                val finalOpus = if (embedMetadata) {
                    AudioMetadataTagger.tagOpus(
                        opusBytes = opusBytes,
                        bookMeta = chunkSpec.bookMetadata,
                        trackTitle = trackTitle,
                        albumTitle = albumTitle,
                        trackNumber = chunkSpec.bookChunkNumber,
                        totalTracks = chunkSpec.totalChunksForBook,
                        voiceName = chunkSpec.voice
                    )
                } else {
                    opusBytes
                }
                val outFile = File(outDir, "$finalBaseName.opus")
                try {
                    FileOutputStream(outFile).use { it.write(finalOpus) }
                    true
                } catch (_: Exception) {
                    false
                }
            } else {
                val finalMp3 = if (embedMetadata) {
                    AudioMetadataTagger.tagMp3(
                        mp3Bytes = assembledMp3,
                        bookMeta = chunkSpec.bookMetadata,
                        trackTitle = trackTitle,
                        albumTitle = albumTitle,
                        trackNumber = chunkSpec.bookChunkNumber,
                        totalTracks = chunkSpec.totalChunksForBook,
                        voiceName = chunkSpec.voice
                    )
                } else {
                    assembledMp3
                }
                val outFile = File(outDir, "$finalBaseName.mp3")
                try {
                    FileOutputStream(outFile).use { it.write(finalMp3) }
                    true
                } catch (_: Exception) {
                    false
                }
            }
        } else {
            val finalMp3 = if (embedMetadata) {
                AudioMetadataTagger.tagMp3(
                    mp3Bytes = assembledMp3,
                    bookMeta = chunkSpec.bookMetadata,
                    trackTitle = trackTitle,
                    albumTitle = albumTitle,
                    trackNumber = chunkSpec.bookChunkNumber,
                    totalTracks = chunkSpec.totalChunksForBook,
                    voiceName = chunkSpec.voice
                )
            } else {
                assembledMp3
            }
            val outFile = File(outDir, "$finalBaseName.mp3")
            try {
                FileOutputStream(outFile).use { it.write(finalMp3) }
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun finalizeConversion(
        rid: Int,
        saveDirBase: String,
        bookFolderNames: List<String>
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            for (bookFolderName in bookFolderNames) {
                val bookDir = File(saveDirBase, bookFolderName)
                if (bookDir.exists() && bookDir.listFiles()?.isEmpty() == true) {
                    bookDir.delete()
                }
            }
            if (runIdGen.get() == rid) {
                moveToRecycleBin()
            }
            withContext(Dispatchers.Main) {
                refreshLoadDirFiles()
                if (runIdGen.get() == rid && runWork) {
                    runWork = false
                    ForegroundWorkService.setActive(getApplication(), false)
                    if (workPaused && pauseIdleSince > 0L) {
                        workStartMsec += System.currentTimeMillis() - pauseIdleSince
                    }
                    workPaused = false
                    pauseIdleSince = 0L
                    workFinished = true
                    updateElapsedLabel()
                    progressBarVisible = false
                    progressBarValue = 0
                    val completedLabel = tr("KEY_WORK_COMPLETED")
                    val bookTitle = book?.fileNames?.map { it.fileName }?.distinct()?.joinToString(", ").orEmpty()
                    val processedSummary = "${tr("KEY_PROCESSED")}: $totalPartsCount ${tr("KEY_PARTS_UNIT")} • $totalChunksCount ${tr("KEY_CHAPTERS_UNIT")}"
                    val msg = "$completedLabel. $processedSummary"
                    ForegroundWorkService.showCompletionNotification(
                        context = getApplication(),
                        bookTitle = bookTitle,
                        completedLabel = completedLabel,
                        partsProcessed = processedSummary,
                        elapsedTime = elapsedTimeText
                    )
                    statInfo = completedLabel
                    statInfoDoneHighlight = true
                    ttsSay(msg)
                }
            }
        }
    }

    private fun moveFileOverwrite(src: File, dst: File) {
        if (src.absolutePath == dst.absolutePath) return
        dst.parentFile?.mkdirs()
        if (dst.exists()) {
            dst.delete()
        }
        if (!src.renameTo(dst)) {
            try {
                src.copyTo(dst, overwrite = true)
                src.delete()
            } catch (_: Exception) {
            }
        }
    }

    private fun moveToRecycleBin() {
        if (settings.recycleBinUse && filesList.isNotEmpty()) {
            val binDir = File(repo.programDir, "Bin")
            if (binDir.exists() || binDir.mkdirs()) {
                for (bookPath in filesList) {
                    val src = File(bookPath)
                    if (src.exists()) {
                        val dst = File(binDir, src.name)
                        if (!src.renameTo(dst)) {
                            try {
                                src.copyTo(dst, overwrite = true)
                                src.delete()
                            } catch (_: Exception) {
                            }
                        }
                    }
                }
            }
        }
    }

    fun stopConversion(announce: Boolean = true) {
        if (announce) {
            ttsSay(tr("KEY_WORK_OFF"))
        }
        runIdGen.incrementAndGet()
        conversionMasterJob?.cancel(CancellationException("Stopped by user"))
        conversionMasterJob = null
        runWork = false
        workPaused = false
        pauseIdleSince = 0L
        ForegroundWorkService.setActive(getApplication(), false)

        for (i in partStatuses.indices) {
            partStatuses[i] = PartStatus.OPENED
        }
        processedPartsCount = 0
        postprocessedChunksCount = 0
        totalChunksCount = computeTotalChunks()
        progressBarVisible = false
        progressBarValue = 0
        statInfoDoneHighlight = false
        statInfo = ""
        statStr = "0/$totalPartsCount"
        chunksStatStr = "0/$totalChunksCount"
        resetElapsedLabel()
    }

    private fun startElapsedLoop() {
        if (elapsedJob?.isActive == true) return
        elapsedJob = viewModelScope.launch {
            while (!workFinished) {
                updateElapsedLabel()
                delay(1000)
            }
            updateElapsedLabel()
        }
    }

    private fun updateElapsedLabel() {
        val now = if (workPaused && pauseIdleSince > 0L) pauseIdleSince else System.currentTimeMillis()
        val totalSec = ((now - workStartMsec).coerceAtLeast(0L)) / 1000L
        val h = totalSec / 3600L
        val m = (totalSec / 60L) % 60L
        val s = totalSec % 60L
        elapsedTimeText = if (h > 0L) {
            "%d:%02d:%02d".format(h, m, s)
        } else {
            "%d:%02d".format(m, s)
        }
    }

    private fun resetElapsedLabel() {
        workFinished = true
        elapsedVisible = false
        elapsedTimeText = ""
    }

    private fun bookStem(name: String): String {
        val f = File(name)
        val ext = f.extension.lowercase()
        return if (AppSettings.BOOK_EXTENSIONS.contains(ext)) f.nameWithoutExtension else name
    }

    private fun stripBookName(inBookDir: Boolean, dir: String, name: String, glava: String): Boolean {
        val candidate = "$dir/${mp3BaseName(false, name, glava, "0000", merged = true)}.mp3"
        return inBookDir && candidate.length > MAX_SAVE_PATH_LEN
    }

    private fun mp3BaseName(
        stripName: Boolean,
        name: String,
        glava: String,
        num: String = "",
        merged: Boolean = false
    ): String {
        var stem = if (!stripName) File(bookStem(name)).name else ""
        stem = (stem + glava).trim()
        if (stem.isEmpty()) {
            if (num.isEmpty()) return "${MERGED_PREFIX}0001"
            return if (merged) "$MERGED_PREFIX$num" else num
        }
        if (num.isEmpty()) return stem
        return stem + (if (merged) " - " else " ") + num
    }

    fun formatSignedValue(value: Int): String {
        val sign = if (value >= 0) "+" else "-"
        return "$sign${kotlin.math.abs(value)}"
    }

    override fun onCleared() {
        super.onCleared()
        PlayerNotificationService.actionListener = null
        voicePreviewStop()
        stopPlayerPlayback(clearCache = true)
        PlayerNotificationService.dismiss(getApplication())
        ForegroundWorkService.setActive(getApplication(), false)
        screenReader.shutdown()
    }

    companion object {
        private const val MERGED_PREFIX = "F"
        private const val MAX_SAVE_PATH_LEN = 220
    }
}
