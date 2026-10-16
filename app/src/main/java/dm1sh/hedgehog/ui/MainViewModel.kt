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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dm1sh.hedgehog.accessibility.ScreenReaderTts
import dm1sh.hedgehog.book.ProcessingFile
import dm1sh.hedgehog.book.TextsConverter
import dm1sh.hedgehog.data.SettingsRepository
import dm1sh.hedgehog.localization.LocalizationManager
import dm1sh.hedgehog.model.AppSettings
import dm1sh.hedgehog.model.PartStatus
import dm1sh.hedgehog.service.ForegroundWorkService
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

enum class MainTab {
    BOOK, PARTS, SETTINGS
}

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
    var statStr by mutableStateOf("0 / 0")
    var totalPartsCount by mutableIntStateOf(0)
    var elapsedTimeText by mutableStateOf("")
    var elapsedVisible by mutableStateOf(false)
    var workFinished by mutableStateOf(true)

    var progressBarVisible by mutableStateOf(false)
    var progressBarValue by mutableIntStateOf(0)
    var progressBarMax by mutableIntStateOf(1)

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
    var editorPartIndex by mutableIntStateOf(0)
    var editorPartFieldText by mutableStateOf("0")
    var partPreviewActive by mutableStateOf(false)
        private set

    private var editorSnapshot: List<String> = emptyList()
    private var editorSnapshotIndex = 0
    private var editorSnapshotBook: ProcessingFile? = null

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
    private val mergeList = mutableMapOf<String, MutableMap<String, MutableMap<String, MutableList<String>>>>()

    private val httpClient = SecMsGecGenerator.createBingHttpClient(20, 20)

    // 10-fast-click screen reader toggle
    private var lastTapMsec = 0L
    private var fastTapCount = 0

    init {
        OfflineTtsEngine.setMaxJobs(settings.maxThreads)
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
            ttsSay("${tr("KEY_SPEED")} ${formatSignedValue(clamped)}%")
        }
    }

    fun setPitch(value: Int) {
        val clamped = value.coerceIn(-50, 50)
        if (clamped != settings.pitch) {
            updateSettings { it.copy(pitch = clamped) }
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

    fun loadLexx() {
        val path = settings.lexxPath
        if (path.isNotBlank()) {
            val file = File(path)
            lexxRules = if (file.exists() && file.canRead()) {
                try {
                    TextsConverter.readTextWithFallback(file.readBytes()).split("\n")
                } catch (_: Exception) {
                    emptyList()
                }
            } else {
                emptyList()
            }
        } else {
            lexxRules = emptyList()
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
        isBookLoading = false
        bookLoaded = false
        loadedBooksCount = 0
        bookTitles.clear()
        partStatuses.clear()
        textAreaText = ""
        statStr = "0 / 0"
        totalPartsCount = 0
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

    fun onOpenBooks(paths: List<String>) {
        if (paths.isEmpty()) {
            isBookLoading = false
            return
        }
        stopConversion(announce = false)
        clearEditorSnapshot()
        isBookLoading = true
        bookLoaded = false
        statInfoDoneHighlight = false
        statInfo = tr("KEY_LOAD")
        partStatuses.clear()
        textAreaText = ""
        statStr = "0 / 0"
        totalPartsCount = 0
        resetElapsedLabel()
        filesList.clear()
        bookTitles.clear()
        book = null
        editorPartIndex = 0
        editorRefreshFields()

        viewModelScope.launch(Dispatchers.IO) {
            var currentBook: ProcessingFile? = null
            val activeLexx = if (settings.lexxUse) lexxRules else emptyList()

            fun appendTextEntry(saveName: String, content: String, voice: String, rate: String, pitch: String) {
                if (content.isEmpty()) return
                val b = currentBook
                if (b == null) {
                    currentBook = ProcessingFile(
                        fileName = saveName,
                        text = content,
                        langLexx = activeLexx,
                        lexxRegister = settings.lexxRegister,
                        bookChapter = settings.bookChapter,
                        glavTextUse = settings.glavTextUse,
                        regexGlavaPattern = settings.regexGlavaCompile,
                        voice = voice,
                        rate = rate,
                        pitch = pitch
                    )
                } else {
                    b.addNewText(saveName, content, voice, rate, pitch)
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
                            appendTextEntry(baseName, text, "", "", "")
                        }
                        "fb2" -> {
                            val raw = TextsConverter.readTextWithFallback(file.readBytes())
                            val bookName = TextsConverter.extractFb2BookName(raw, baseName)
                            appendTextEntry(
                                bookName,
                                TextsConverter.convertFb2ToTxt(raw, inlineNotes = settings.inlineNotes),
                                "",
                                "",
                                ""
                            )
                        }
                        "epub" -> {
                            val bookName = TextsConverter.extractEpubBookName(file.absolutePath, baseName)
                            val text = TextsConverter.convertEpubToTxt(
                                file.absolutePath,
                                inlineNotes = settings.inlineNotes
                            )
                            appendTextEntry(bookName, text, "", "", "")
                        }
                        "zip" -> {
                            TextsConverter.extractZipEntries(
                                file.absolutePath,
                                inlineNotes = settings.inlineNotes
                            ) { saveName, text, v, r, p ->
                                appendTextEntry(saveName, text, v, r, p)
                            }
                        }
                        "csv" -> {
                            val raw = TextsConverter.readTextWithFallback(file.readBytes())
                            val lines = TextsConverter.parseCsv(raw)
                            lines.forEachIndexed { idx, row ->
                                TextsConverter.processCsvLine(row, baseName, idx + 1) { saveName, text, v, r, p ->
                                    appendTextEntry(saveName, text, v, r, p)
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

            withContext(Dispatchers.Main) {
                isBookLoading = false
                book = currentBook
                val count = currentBook?.allSentences?.size ?: 0
                syncBookTitlesFromBook()
                loadedBooksCount = bookTitles.size.coerceAtLeast(if (count > 0) 1 else 0)
                bookLoaded = count > 0
                totalPartsCount = count
                partStatuses.clear()
                if (count > 0) {
                    partStatuses.addAll(java.util.Collections.nCopies(count, PartStatus.OPENED))
                }
                statStr = "0 / $count"
                statInfo = ""
                editorPartIndex = 0
                if (settings.showTextAreaPressed) {
                    editorLoadCurrentPart()
                } else {
                    editorRefreshFields()
                }
                ttsSay("${bookLoadedCaption()}. $statStr")
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
                langLexx = if (settings.lexxUse) lexxRules else emptyList(),
                lexxRegister = settings.lexxRegister,
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
        } else {
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
        totalPartsCount = finalCount
        statStr = "0 / $finalCount"
        partStatuses.clear()
        if (finalCount > 0) {
            partStatuses.addAll(java.util.Collections.nCopies(finalCount, PartStatus.OPENED))
        }
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
            } else {
                editorPartIndex = editorPartIndex.coerceIn(0, b.allSentences.size - 1)
                textAreaText = b.allSentences[editorPartIndex]
            }
        }
        editorRefreshFields()
    }

    private fun editorRefreshFields() {
        val b = book
        val count = b?.allSentences?.size ?: 0
        totalPartsCount = count
        editorPartFieldText = if (count == 0) "0" else (editorPartIndex + 1).toString()
    }

    private fun takeEditorSnapshot() {
        clearEditorSnapshot()
        val b = book ?: return
        if (runWork) return
        editorSnapshot = b.allSentences.toList()
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
        editorPartIndex = editorSnapshotIndex.coerceIn(0, (currentBook.allSentences.size - 1).coerceAtLeast(0))
        clearEditorSnapshot()
    }

    private fun clearEditorSnapshot() {
        editorSnapshot = emptyList()
        editorSnapshotIndex = 0
        editorSnapshotBook = null
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
        val wantOnline = (forceOnline ?: settings.useOnlineVoices) && isNetworkAvailable()
        val chosenVoice = when {
            forceVoice != null && (wantOnline || !forceOnline!!) -> forceVoice
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
            val fallbackOffline = if (repo.isVoiceOffline(chosenVoice)) chosenVoice else resolveOfflineFallbackVoice()
            val hasOfflineFallback = !fallbackOffline.isNullOrBlank()
            val useOnlineNow = wantOnline && !repo.isVoiceOffline(chosenVoice) &&
                (!hasOfflineFallback || SocketEdgeTtsWorker.isOnlineReachable())
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
            if (mp3Bytes == null || mp3Bytes.isEmpty()) {
                if (!fallbackOffline.isNullOrBlank()) {
                    val voiceDir = File(File(repo.programDir, "Voices"), fallbackOffline)
                    if (OfflineTtsEngine.voiceInstalled(voiceDir)) {
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
        } else {
            startConversion()
        }
    }

    fun onPauseOrResumeButtonPressed() {
        if (!runWork) return
        workPaused = !workPaused
        val doneCount = (progressBarValue - 1).coerceAtLeast(0)
        val pct = if (totalPartsCount > 0) (doneCount * 100) / totalPartsCount else 0
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
            text = "$statInfo $doneCount / $totalPartsCount ($pct%)",
            progress = doneCount,
            max = totalPartsCount,
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
        voicePreviewStop()

        if (book == null) {
            numTextCounter++
            val seedText = textAreaText
            val defaultTitle = bookTitles.firstOrNull()?.takeIf { it.isNotBlank() }
                ?: "Text ${"%04d".format(numTextCounter)}"
            val created = ProcessingFile(
                fileName = defaultTitle,
                text = seedText,
                langLexx = if (settings.lexxUse) lexxRules else emptyList(),
                lexxRegister = settings.lexxRegister,
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
        totalPartsCount = total
        partStatuses.clear()
        if (total > 0) {
            partStatuses.addAll(java.util.Collections.nCopies(total, PartStatus.OPENED))
        }

        workPaused = false
        pauseIdleSince = 0L
        runWork = true
        val currentRunId = runIdGen.incrementAndGet()
        val notifTitle = activeBook.fileNames.joinToString(", ") { it.fileName }
        ForegroundWorkService.setActive(
            context = getApplication(),
            active = true,
            text = "${tr("KEY_PROCESSED")} 0 / $total (0%)",
            progress = 0,
            max = total,
            title = notifTitle
        )

        statInfoDoneHighlight = false
        statInfo = tr("KEY_PROCESSED")
        statStr = "0 / $total"
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
        val runSaveDir = settings.saveDir.ifEmpty { repo.defaultDownloadsDir }
        val timeoutMsec = if (settings.restartDelay < 0) Long.MAX_VALUE else settings.restartDelay * 1000L

        progressBarMax = total + 1
        progressBarValue = 1
        progressBarVisible = true

        if (total == 0) {
            finalizeConversion(currentRunId, runBookDirCreate, runMergeFiles, runLowBitrate, runOpusOutput)
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
            val text: String
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
                    text = activeBook.allSentences[idx]
                )
            )
        }

        val savedPartsAtomic = AtomicInteger(0)
        val launchedAtomic = AtomicInteger(0)

        conversionMasterJob = viewModelScope.launch(Dispatchers.IO) {
            mergeMutex.withLock { mergeList.clear() }
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
                        val shouldUseOnline = settings.useOnlineVoices &&
                            !repo.isVoiceOffline(spec.voice) &&
                            isNetworkAvailable() &&
                            (!hasOfflineFallback || SocketEdgeTtsWorker.isOnlineReachable())
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

                        val finalAudioData: ByteArray
                        val finalExt: String
                        if (runOpusOutput) {
                            val opusData = OpusConverter.convertMp3ToOpus(mp3Data)
                            if (opusData != null && opusData.isNotEmpty()) {
                                finalAudioData = opusData
                                finalExt = "opus"
                            } else {
                                finalAudioData = mp3Data
                                finalExt = "mp3"
                            }
                        } else {
                            finalAudioData = mp3Data
                            finalExt = "mp3"
                        }

                        savePartMp3File(
                            data = finalAudioData,
                            saveDirBase = runSaveDir,
                            inBookDir = runBookDirCreate,
                            fileName = spec.fileName,
                            glava = spec.glava,
                            fileNum = spec.fileNum,
                            ext = finalExt
                        )
                        updateStatusBox(spec.index, "KEY_SAVED", currentRunId)

                        val doneCount = savedPartsAtomic.incrementAndGet()
                        withContext(Dispatchers.Main) {
                            if (runIdGen.get() == currentRunId && runWork) {
                                progressBarValue = doneCount + 1
                                statStr = "$doneCount / $totalPartsCount"
                                val pct = if (totalPartsCount > 0) (doneCount * 100) / totalPartsCount else 0
                                val statusLabel = tr(if (workPaused) "KEY_PAUSE" else "KEY_PROCESSED")
                                ForegroundWorkService.updateProgress(
                                    context = getApplication(),
                                    text = "$statusLabel $doneCount / $totalPartsCount ($pct%)",
                                    progress = doneCount,
                                    max = totalPartsCount,
                                    title = notifTitle
                                )
                            }
                        }

                        if (doneCount >= total && runIdGen.get() == currentRunId) {
                            finalizeConversion(currentRunId, runBookDirCreate, runMergeFiles, runLowBitrate, runOpusOutput)
                        }
                    }
                    childJobs.add(job)
                    delay(100)
                } else {
                    delay(100)
                }
            }
            childJobs.forEach { it.join() }
        }
    }

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

    private suspend fun savePartMp3File(
        data: ByteArray,
        saveDirBase: String,
        inBookDir: Boolean,
        fileName: String,
        glava: String,
        fileNum: String,
        ext: String = "mp3"
    ) {
        val bookFolderName = File(bookStem(fileName)).name.trim().ifEmpty { "Book" }
        val bookDir = File(saveDirBase, bookFolderName)
        if (bookDir.exists() || bookDir.mkdirs()) {
            val outFile = File(bookDir, rawPartFileName(glava, fileNum, ext))
            try {
                FileOutputStream(outFile).use { it.write(data) }
                mergeMutex.withLock {
                    val byBook = mergeList.getOrPut(saveDirBase) { LinkedHashMap() }
                    val byGlava = byBook.getOrPut(bookFolderName) { LinkedHashMap() }
                    val nums = byGlava.getOrPut(glava.trim()) { mutableListOf() }
                    nums.add(fileNum)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun finalizeConversion(
        rid: Int,
        inBookDir: Boolean,
        perFile: Int,
        lowBitrate: Boolean,
        opusOutput: Boolean = false
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val snapshot = mergeMutex.withLock {
                val copy = mergeList.mapValues { (_, byBook) ->
                    byBook.mapValues { (_, byGlava) ->
                        byGlava.mapValues { (_, nums) -> nums.toMutableList() }.toMutableMap()
                    }.toMutableMap()
                }.toMutableMap()
                mergeList.clear()
                copy
            }

            finalizeBookOutputs(snapshot, inBookDir, perFile, lowBitrate, opusOutput, rid)
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
                    val msg = "${tr("KEY_WORK_COMPLETED")}. $statInfo $statStr"
                    val completedLabel = tr("KEY_WORK_COMPLETED")
                    val bookTitle = book?.fileNames?.joinToString(", ") { it.fileName }.orEmpty()
                    val processedSummary = "${tr("KEY_PROCESSED")}: $statStr"
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

    private fun finalizeBookOutputs(
        ml: Map<String, Map<String, Map<String, MutableList<String>>>>,
        inBookDir: Boolean,
        perFile: Int,
        lowBitrate: Boolean,
        opusOutput: Boolean,
        rid: Int
    ) {
        val ext = if (opusOutput) "opus" else "mp3"
        val addGlavMp3 = settings.bookChapter && settings.glavMp3Use
        var soundGlavaBytes = ByteArray(0)
        var glavaTempOpusFile: File? = null
        if (addGlavMp3) {
            soundGlavaBytes = try {
                if (settings.glavMp3Path.isBlank()) {
                    val assetName = if (lowBitrate) "Silence_48.mp3" else "Silence_96.mp3"
                    getApplication<Application>().assets.open(assetName).use { it.readBytes() }
                } else {
                    val f = File(settings.glavMp3Path)
                    if (f.exists()) f.readBytes() else ByteArray(0)
                }
            } catch (_: Exception) {
                ByteArray(0)
            }
            if (opusOutput && soundGlavaBytes.isNotEmpty()) {
                val glavaOpus = OpusConverter.convertMp3ToOpus(soundGlavaBytes)
                if (glavaOpus != null && glavaOpus.isNotEmpty()) {
                    try {
                        val tmp = File.createTempFile("glava_intro_", ".opus", getApplication<Application>().cacheDir)
                        tmp.writeBytes(glavaOpus)
                        glavaTempOpusFile = tmp
                    } catch (_: Exception) {
                    }
                }
            }
        }

        try {
            val totalLoadedBooks = loadedBooksCount.coerceAtLeast(1)
            for ((saveDirBase, byBook) in ml) {
                val multipleBooks = byBook.size > 1 || totalLoadedBooks > 1
                for ((bookFolderName, byGlava) in byBook) {
                    val bookDir = File(saveDirBase, bookFolderName)
                    val producedFiles = mutableListOf<File>()

                    for ((rawGlava, nums) in byGlava) {
                        if (runIdGen.get() != rid) return
                        val glava = rawGlava.trim()
                        nums.sort()
                        if (nums.isEmpty()) continue

                        val chunkSize = when {
                            perFile <= 1 -> 1
                            perFile >= 100 -> nums.size
                            else -> perFile
                        }
                        val chunks = nums.chunked(chunkSize.coerceAtLeast(1))
                        val totalChunksForGlava = chunks.size

                        for ((chunkIdx, chunkNums) in chunks.withIndex()) {
                            if (runIdGen.get() != rid) return
                            val partNumberStr = "%04d".format(chunkIdx + 1)
                            val targetName = if (glava.isEmpty()) {
                                "c$partNumberStr.$ext"
                            } else if (totalChunksForGlava == 1) {
                                "$glava.$ext"
                            } else {
                                "${glava}_c$partNumberStr.$ext"
                            }
                            val outFile = File(bookDir, targetName)
                            val includeGlavaIntro = addGlavMp3 && chunkIdx == 0

                            if (chunkNums.size == 1 && !includeGlavaIntro) {
                                val rawFile = File(bookDir, rawPartFileName(glava, chunkNums[0], ext))
                                if (rawFile.exists()) {
                                    moveFileOverwrite(rawFile, outFile)
                                    producedFiles.add(outFile)
                                }
                            } else if (!opusOutput) {
                                val mergeBuffer = ByteArrayOutputStream()
                                if (includeGlavaIntro && soundGlavaBytes.isNotEmpty()) {
                                    mergeBuffer.write(soundGlavaBytes)
                                }
                                for (num in chunkNums) {
                                    val rawFile = File(bookDir, rawPartFileName(glava, num, ext))
                                    if (rawFile.exists()) {
                                        try {
                                            mergeBuffer.write(rawFile.readBytes())
                                        } catch (_: Exception) {
                                        }
                                        rawFile.delete()
                                    }
                                }
                                try {
                                    FileOutputStream(outFile).use { it.write(mergeBuffer.toByteArray()) }
                                    producedFiles.add(outFile)
                                } catch (_: Exception) {
                                }
                            } else {
                                val batchFiles = mutableListOf<File>()
                                val batchInputPaths = mutableListOf<String>()
                                if (includeGlavaIntro) {
                                    glavaTempOpusFile?.let { batchInputPaths.add(it.absolutePath) }
                                }
                                for (num in chunkNums) {
                                    val rawFile = File(bookDir, rawPartFileName(glava, num, ext))
                                    if (rawFile.exists()) {
                                        batchFiles.add(rawFile)
                                        batchInputPaths.add(rawFile.absolutePath)
                                    }
                                }
                                if (batchInputPaths.isNotEmpty()) {
                                    val tmpOutFile = File(bookDir, "${outFile.name}.merging.opus")
                                    val mergedOk = OpusConverter.mergeOpusFiles(batchInputPaths, tmpOutFile.absolutePath)
                                    batchFiles.forEach { it.delete() }
                                    if (mergedOk && tmpOutFile.exists()) {
                                        moveFileOverwrite(tmpOutFile, outFile)
                                        producedFiles.add(outFile)
                                    } else {
                                        tmpOutFile.delete()
                                    }
                                }
                            }
                        }
                    }

                    // Clever saving rules:
                    // 1. If only a single file is produced for the book, save it at the root of saveDirBase
                    //    even if "Save to a separate folder" (inBookDir) is on.
                    // 2. If multiple files are produced, keep them inside bookDir when inBookDir is on
                    //    OR when multiple books were loaded; otherwise move them to saveDirBase.
                    if (producedFiles.size == 1) {
                        val singleOut = File(saveDirBase, "$bookFolderName.$ext")
                        moveFileOverwrite(producedFiles[0], singleOut)
                        if (bookDir.exists() && bookDir.listFiles()?.isEmpty() == true) {
                            bookDir.delete()
                        }
                    } else if (producedFiles.size > 1 && !inBookDir && !multipleBooks) {
                        for (f in producedFiles) {
                            moveFileOverwrite(f, File(saveDirBase, f.name))
                        }
                        if (bookDir.exists() && bookDir.listFiles()?.isEmpty() == true) {
                            bookDir.delete()
                        }
                    }
                }
            }
        } finally {
            glavaTempOpusFile?.delete()
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
        progressBarVisible = false
        progressBarValue = 0
        statInfoDoneHighlight = false
        statInfo = ""
        statStr = "0 / $totalPartsCount"
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
        voicePreviewStop()
        ForegroundWorkService.setActive(getApplication(), false)
        screenReader.shutdown()
    }

    companion object {
        private const val MERGED_PREFIX = "F"
        private const val MAX_SAVE_PATH_LEN = 220
    }
}
