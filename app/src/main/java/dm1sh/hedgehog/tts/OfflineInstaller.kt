package dm1sh.hedgehog.tts

import dm1sh.hedgehog.localization.LocalizationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

object OfflineInstaller {

    private const val SITE = "https://github.com/dm1sh/hedgehog/releases/download/offline-voices/"
    private val DEFAULT_VOICES = mapOf(
        "Dmitry" to listOf("ru"),
        "Svetlana" to listOf("ru"),
        "Dariya" to listOf("ru"),
        "Ryan" to emptyList()
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun install(
        programDir: File,
        currentLocale: String,
        onRegisterVoice: (String) -> Unit,
        onProgress: (fileName: String, downloaded: Long, total: Long) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val lang = LocalizationManager.localelocaleLanguage(currentLocale)
        val catalog = fetchCatalog(programDir, onProgress)
        var allOk = true
        for ((voice, langs) in catalog) {
            if (langs.isEmpty() || langs.contains(lang)) {
                val ok = installVoice(programDir, voice, onRegisterVoice, onProgress)
                allOk = ok && allOk
            }
        }
        allOk
    }

    private fun fetchCatalog(
        programDir: File,
        onProgress: (String, Long, Long) -> Unit
    ): Map<String, List<String>> {
        val tempDir = File(programDir, "Temp").apply { mkdirs() }
        val jsonFile = File(tempDir, "Voices.json")
        if (downloadFile("Voices.json", SITE + "Voices.json", jsonFile, onProgress)) {
            try {
                val obj = JSONObject(jsonFile.readText(Charsets.UTF_8))
                jsonFile.delete()
                val result = linkedMapOf<String, List<String>>()
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val voice = keys.next()
                    if (voice.isNotBlank() && !voice.startsWith(".") && !voice.contains("/")) {
                        val arr = obj.optJSONArray(voice)
                        val langs = mutableListOf<String>()
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                langs.add(arr.getString(i))
                            }
                        }
                        result[voice] = langs
                    }
                }
                if (result.isNotEmpty()) return result
            } catch (_: Exception) {
                jsonFile.delete()
            }
        }
        return DEFAULT_VOICES
    }

    private fun installVoice(
        programDir: File,
        voice: String,
        onRegisterVoice: (String) -> Unit,
        onProgress: (String, Long, Long) -> Unit
    ): Boolean {
        val voicesDir = File(programDir, "Voices")
        val voiceDir = File(voicesDir, voice)
        if (!OfflineTtsEngine.voiceInstalled(voiceDir)) {
            if (!voicesDir.exists() && !voicesDir.mkdirs()) return false
            val archive = File(voicesDir, "$voice.zip")
            if (!downloadFile("$voice.zip", "$SITE$voice.zip", archive, onProgress)) {
                return false
            }
            val staging = File(voicesDir, "$voice.part")
            staging.deleteRecursively()
            val extractedOk = extractZip(archive, staging)
            archive.delete()
            var ok = false
            if (extractedOk) {
                voiceDir.deleteRecursively()
                ok = staging.renameTo(voiceDir) && OfflineTtsEngine.voiceInstalled(voiceDir)
            }
            if (!ok) {
                staging.deleteRecursively()
                voiceDir.deleteRecursively()
                return false
            }
        }
        onRegisterVoice(voice)
        return true
    }

    private fun downloadFile(
        label: String,
        url: String,
        dest: File,
        onProgress: (String, Long, Long) -> Unit
    ): Boolean {
        onProgress(label, 0L, 0L)
        val partFile = File(dest.parentFile, "${dest.name}.part")
        partFile.delete()
        return try {
            val req = Request.Builder().url(url).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val body = resp.body ?: return false
                val total = body.contentLength()
                var downloaded = 0L
                var lastReport = 0L
                body.byteStream().use { input ->
                    FileOutputStream(partFile).use { output ->
                        val buf = ByteArray(16384)
                        var read: Int
                        while (input.read(buf).also { read = it } != -1) {
                            output.write(buf, 0, read)
                            downloaded += read
                            val now = System.currentTimeMillis()
                            if (now - lastReport >= 200) {
                                lastReport = now
                                onProgress(label, downloaded, total)
                            }
                        }
                    }
                }
                onProgress(label, downloaded, total)
            }
            dest.delete()
            partFile.renameTo(dest)
        } catch (_: Exception) {
            partFile.delete()
            false
        }
    }

    private fun extractZip(archive: File, dest: File): Boolean {
        return try {
            if (!dest.exists() && !dest.mkdirs()) return false
            ZipFile(archive).use { zip ->
                val entries = zip.entries().asSequence().toList()
                if (entries.isEmpty()) return false
                var commonRoot = entries.first().name.substringBefore("/", "") + "/"
                for (entry in entries) {
                    if (!entry.name.startsWith(commonRoot)) {
                        commonRoot = ""
                        break
                    }
                }
                for (entry in entries) {
                    val rel = if (commonRoot.isNotEmpty()) entry.name.removePrefix(commonRoot) else entry.name
                    if (rel.isEmpty() || rel.endsWith("/")) continue
                    val outFile = File(dest, rel)
                    outFile.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(outFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}
