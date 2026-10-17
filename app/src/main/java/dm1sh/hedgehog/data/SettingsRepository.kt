package dm1sh.hedgehog.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import dm1sh.hedgehog.localization.LocalizationManager
import dm1sh.hedgehog.model.AppSettings
import dm1sh.hedgehog.model.DefaultVoices
import dm1sh.hedgehog.tts.OfflineTtsEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class SettingsRepository(private val context: Context) {

    val programDir: File
        get() {
            val docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val edgeDir = File(docs, "EdgeTTS")
            if (hasAllFilesAccess() && (edgeDir.exists() || edgeDir.mkdirs())) {
                return edgeDir
            }
            val fallback = File(context.getExternalFilesDir(null) ?: context.filesDir, "EdgeTTS")
            fallback.mkdirs()
            return fallback
        }

    val defaultDownloadsDir: String
        get() = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath

    private val settingsFile: File
        get() = File(context.filesDir, "settings.json")

    private val voicesJsonFile: File
        get() = File(File(programDir, "Data"), "Voices.json")

    var onlineVoices: List<String> = DefaultVoices.ONLINE_VOICES
        private set

    var offlineVoices: MutableList<String> = mutableListOf()
        private set

    fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    fun requestAllFilesAccess(activityContext: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            openStorageSettings(activityContext)
        }
    }

    fun openStorageSettings(activityContext: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent("android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION").apply {
                    data = Uri.parse("package:${activityContext.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activityContext.startActivity(intent)
            } catch (e: Exception) {
                try {
                    val fallback = Intent("android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    activityContext.startActivity(fallback)
                } catch (_: Exception) {
                }
            }
        }
    }

    fun loadSettings(): AppSettings {
        loadCustomVoices()
        val defaultDir = defaultDownloadsDir
        if (!settingsFile.exists()) {
            val initial = AppSettings(saveDir = defaultDir, loadDir = defaultDir)
            saveSettings(initial)
            return initial
        }
        return try {
            val root = JSONObject(settingsFile.readText(Charsets.UTF_8))
            val s = root.optJSONObject("settings") ?: JSONObject()
            val hasAppVersion = s.has("app_version")
            val saveDir = s.optString("save_dir", "").ifEmpty { defaultDir }
            val loadDir = s.optString("load_dir", "").ifEmpty { defaultDir }
            val regexGlava = s.optString("regex_glava_compile", AppSettings.DEFAULT_REGEX_GLAVA)
                .ifEmpty { AppSettings.DEFAULT_REGEX_GLAVA }

            AppSettings(
                voicesSelect = s.optInt("voices_select", 0),
                voicesName = s.optString("voices_name", "ru-RU, DmitryNeural"),
                offlineVoicesSelect = s.optInt("offline_voices_select", 0),
                offlineVoicesName = s.optString("offline_voices_name", ""),
                useOnlineVoices = s.optBoolean("use_online_voices", true),
                rate = s.optInt("rate", 100),
                pitch = s.optInt("pitch", 0),
                maxThreads = if (hasAppVersion) s.optInt("max_threads", AppSettings.DEFAULT_MAX_THREADS) else AppSettings.DEFAULT_MAX_THREADS,
                restartDelay = if (hasAppVersion) s.optInt("restart_delay", AppSettings.DEFAULT_RESTART_DELAY) else AppSettings.DEFAULT_RESTART_DELAY,
                mergeFiles = s.optInt("merge_files", 50),
                loadDir = loadDir,
                saveDir = saveDir,
                lexxPath = s.optString("lexx_path", ""),
                regexGlavaCompile = regexGlava,
                lexxUse = s.optBoolean("lexx_use", false),
                lexxRegister = s.optBoolean("lexx_register", true),
                bookDirCreate = s.optBoolean("book_dir_create", true),
                lowBitrate = s.optBoolean("low_bitrate", false),
                opusOutput = s.optBoolean("opus_output", false),
                inlineNotes = s.optBoolean("inline_notes", false),
                voicePreview = s.optBoolean("voice_preview", false),
                bookChapter = s.optBoolean("book_chapter", false),
                dopSettingsPressed = s.optBoolean("dop_settings_pressed", false),
                showTextAreaPressed = s.optBoolean("show_text_area_pressed", false),
                group4Visible = s.optBoolean("group_4_visible", false),
                loadDirUse = s.optBoolean("load_dir_use", false),
                recycleBinUse = s.optBoolean("recycle_bin_use", false),
                zoomUi = s.optDouble("zoom_ui", 1.0).toFloat(),
                fontSize = s.optInt("font_size", 16),
                glavMp3Path = s.optString("glav_mp3_path", ""),
                glavMp3Use = s.optBoolean("glav_mp3_use", false),
                glavTextUse = s.optBoolean("glav_text_use", false),
                languagesSelect = s.optInt("languages_select", 0),
                group1Visible = s.optBoolean("group_1_visible", true),
                group2Visible = s.optBoolean("group_2_visible", true),
                group6Visible = s.optBoolean("group_6_visible", false),
                group7Visible = s.optBoolean("group_7_visible", false),
                group8Visible = s.optBoolean("group_8_visible", false),
                languagesUiTtsSelect = s.optInt("languages_ui_tts_select", 0),
                appVersion = s.optString("app_version", "1.0.1.24")
            )
        } catch (e: Exception) {
            val fallback = AppSettings(saveDir = defaultDir, loadDir = defaultDir)
            saveSettings(fallback)
            fallback
        }
    }

    fun saveSettings(settings: AppSettings) {
        try {
            val s = JSONObject().apply {
                put("voices_select", settings.voicesSelect)
                put("voices_name", settings.voicesName)
                put("offline_voices_select", settings.offlineVoicesSelect)
                put("offline_voices_name", settings.offlineVoicesName)
                put("use_online_voices", settings.useOnlineVoices)
                put("rate", settings.rate)
                put("pitch", settings.pitch)
                put("max_threads", settings.maxThreads)
                put("restart_delay", settings.restartDelay)
                put("merge_files", settings.mergeFiles)
                put("load_dir", settings.loadDir)
                put("save_dir", settings.saveDir)
                put("lexx_path", settings.lexxPath)
                put("regex_glava_compile", settings.regexGlavaCompile)
                put("lexx_use", settings.lexxUse)
                put("lexx_register", settings.lexxRegister)
                put("book_dir_create", settings.bookDirCreate)
                put("low_bitrate", settings.lowBitrate)
                put("opus_output", settings.opusOutput)
                put("inline_notes", settings.inlineNotes)
                put("voice_preview", settings.voicePreview)
                put("book_chapter", settings.bookChapter)
                put("dop_settings_pressed", settings.dopSettingsPressed)
                put("show_text_area_pressed", settings.showTextAreaPressed)
                put("group_4_visible", settings.group4Visible)
                put("load_dir_use", settings.loadDirUse)
                put("recycle_bin_use", settings.recycleBinUse)
                put("zoom_ui", settings.zoomUi.toDouble())
                put("font_size", settings.fontSize)
                put("glav_mp3_path", settings.glavMp3Path)
                put("glav_mp3_use", settings.glavMp3Use)
                put("glav_text_use", settings.glavTextUse)
                put("languages_select", settings.languagesSelect)
                put("group_1_visible", settings.group1Visible)
                put("group_2_visible", settings.group2Visible)
                put("group_6_visible", settings.group6Visible)
                put("group_7_visible", settings.group7Visible)
                put("group_8_visible", settings.group8Visible)
                put("languages_ui_tts_select", settings.languagesUiTtsSelect)
                put("app_version", settings.appVersion)
            }
            val root = JSONObject().apply {
                put("settings", s)
                put("voices", buildVoicesJsonObject())
            }
            settingsFile.writeText(root.toString(), Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun saveProfile(slot: Int, settings: AppSettings) {
        saveSettings(settings)
        val profileFile = File(context.filesDir, "profile_f$slot.json")
        if (settingsFile.exists()) {
            settingsFile.copyTo(profileFile, overwrite = true)
        }
    }

    fun loadProfile(slot: Int): AppSettings? {
        val profileFile = File(context.filesDir, "profile_f$slot.json")
        if (!profileFile.exists()) return null
        profileFile.copyTo(settingsFile, overwrite = true)
        return loadSettings()
    }

    fun loadCustomVoices() {
        val file = voicesJsonFile
        if (!file.exists()) {
            saveVoicesJson()
            return
        }
        try {
            val obj = JSONObject(file.readText(Charsets.UTF_8))
            val valArr = obj.optJSONArray("value")
            if (valArr != null && valArr.length() > 0) {
                val list = mutableListOf<String>()
                for (i in 0 until valArr.length()) {
                    list.add(valArr.getString(i))
                }
                onlineVoices = list
            } else {
                onlineVoices = DefaultVoices.ONLINE_VOICES
            }
            val offArr = obj.optJSONArray("value_offline") ?: obj.optJSONArray("values_offline")
            val offList = mutableListOf<String>()
            if (offArr != null) {
                for (i in 0 until offArr.length()) {
                    offList.add(offArr.getString(i))
                }
            }
            offlineVoices = offList
        } catch (e: Exception) {
            onlineVoices = DefaultVoices.ONLINE_VOICES
        }
    }

    fun saveVoicesJson() {
        try {
            val dataDir = File(programDir, "Data")
            if (!dataDir.exists() && !dataDir.mkdirs()) return
            voicesJsonFile.writeText(buildVoicesJsonObject().toString(4), Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun updateOnlineVoices(sortedVoices: List<String>) {
        onlineVoices = sortedVoices
        saveVoicesJson()
    }

    fun registerOfflineVoice(voice: String) {
        if (!offlineVoices.contains(voice)) {
            offlineVoices.add(voice)
            saveVoicesJson()
        }
    }

    fun readyOfflineVoices(): List<String> {
        val voicesRoot = File(programDir, "Voices")
        if (voicesRoot.exists() && voicesRoot.isDirectory) {
            var changed = false
            voicesRoot.listFiles()?.forEach { dir ->
                if (dir.isDirectory && OfflineTtsEngine.voiceInstalled(dir) && !offlineVoices.contains(dir.name)) {
                    offlineVoices.add(dir.name)
                    changed = true
                }
            }
            if (changed) {
                saveVoicesJson()
            }
        }
        return offlineVoices.filter { voice ->
            OfflineTtsEngine.voiceInstalled(File(voicesRoot, voice))
        }
    }

    fun isVoiceOffline(voice: String): Boolean {
        if (voice.isEmpty()) return false
        if (offlineVoices.contains(voice)) return true
        val voiceDir = File(File(programDir, "Voices"), voice)
        return OfflineTtsEngine.voiceInstalled(voiceDir)
    }

    fun combinedVoicesList(): List<String> {
        return readyOfflineVoices() + onlineVoices
    }

    fun sortVoices(names: List<String>, currentLocale: String): List<String> {
        val lang = LocalizationManager.localelocaleLanguage(currentLocale)
        val native = mutableListOf<String>()
        val multi = mutableListOf<String>()
        val rest = mutableListOf<String>()
        for (voice in names) {
            if (LocalizationManager.localelocaleLanguage(voice) == lang) {
                native.add(voice)
            } else if (voice.lowercase().contains("multilingual")) {
                multi.add(voice)
            } else {
                rest.add(voice)
            }
        }
        return native + multi + rest
    }

    private fun buildVoicesJsonObject(): JSONObject {
        return JSONObject().apply {
            put("value", JSONArray(onlineVoices))
            put("value_offline", JSONArray(offlineVoices))
        }
    }

    /**
     * Resolves an Android directory tree content:// URI (from OpenDocumentTree) to an absolute filesystem path.
     */
    fun resolveAndroidTreeUriToPath(uri: Uri): String {
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: Exception) {
        }

        val rawStr = uri.toString()
        if (!rawStr.startsWith("content://")) {
            return uri.path ?: defaultDownloadsDir
        }

        try {
            val docId = try {
                DocumentsContract.getTreeDocumentId(uri)
            } catch (_: Exception) {
                val decoded = Uri.decode(rawStr)
                val marker = "/tree/"
                val idx = decoded.indexOf(marker)
                if (idx != -1) decoded.substring(idx + marker.length).substringBefore("/document/") else ""
            }

            if (docId.isNotEmpty()) {
                if (docId.startsWith("raw:")) {
                    return docId.removePrefix("raw:")
                }
                if (docId.equals("downloads", ignoreCase = true)) {
                    return defaultDownloadsDir
                }
                if (docId.startsWith("home:", ignoreCase = true)) {
                    val rel = docId.substringAfter(':')
                    val docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).absolutePath
                    return if (rel.isNotEmpty()) "$docs/$rel" else docs
                }
                val colon = docId.indexOf(':')
                if (colon >= 0) {
                    val volume = docId.substring(0, colon)
                    val rel = docId.substring(colon + 1).trim('/')
                    val base = if (volume.equals("primary", ignoreCase = true)) {
                        Environment.getExternalStorageDirectory().absolutePath
                    } else {
                        "/storage/$volume"
                    }
                    val real = if (rel.isNotEmpty()) "$base/$rel" else base
                    try {
                        File(real).mkdirs()
                    } catch (_: Exception) {
                    }
                    return real
                }
            }
        } catch (_: Exception) {
        }
        return defaultDownloadsDir
    }

    /**
     * Resolves an Android content:// URI to an absolute filesystem path (matching Globals.resolve_android_path),
     * or copies it into cacheDir if direct path access is unavailable.
     */
    fun resolveAndroidUriToPath(uri: Uri): String {
        val rawStr = uri.toString()
        if (!rawStr.startsWith("content://")) {
            return uri.path ?: rawStr
        }
        try {
            val decoded = Uri.decode(rawStr)
            var marker = "/document/"
            var idx = decoded.indexOf(marker)
            if (idx == -1) {
                marker = "/tree/"
                idx = decoded.indexOf(marker)
            }
            if (idx != -1) {
                var docId = decoded.substring(idx + marker.length)
                val childrenMarker = "/children"
                if (docId.endsWith(childrenMarker)) {
                    docId = docId.removeSuffix(childrenMarker)
                }
                if (docId.contains("/document/")) {
                    docId = docId.substringAfterLast("/document/")
                }
                if (docId.startsWith("raw:")) {
                    val realRaw = docId.substring(4)
                    if (File(realRaw).exists()) return realRaw
                }
                val colon = docId.indexOf(':')
                if (colon > 0) {
                    val volume = docId.substring(0, colon)
                    val rel = docId.substring(colon + 1)
                    val base = if (volume == "primary") {
                        Environment.getExternalStorageDirectory().absolutePath
                    } else {
                        "/storage/$volume"
                    }
                    val real = if (rel.isNotEmpty()) "$base/$rel" else base
                    if (File(real).exists()) return real
                }
            }
        } catch (_: Exception) {
        }

        // Fallback: copy content stream to a temp file in cacheDir with original display name
        return try {
            var fileName = "picked_${System.currentTimeMillis()}"
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0) {
                        fileName = cursor.getString(nameIdx) ?: fileName
                    }
                }
            }
            val destDir = File(context.cacheDir, "picked_files").apply { mkdirs() }
            val destFile = File(destDir, fileName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            destFile.absolutePath
        } catch (e: Exception) {
            rawStr
        }
    }
}
