package dm1sh.hedgehog.book

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class BookConversionIndex private constructor(
    val indexFile: File
) {
    data class PartRecord(
        val textHash: String,
        val fileName: String,
        val ext: String
    )

    data class ChapterRecord(
        val textHash: String,
        val fileName: String
    )

    private val saveLock = Any()
    private val parts = ConcurrentHashMap<String, PartRecord>()
    private val chapters = ConcurrentHashMap<String, ChapterRecord>()

    fun getReusablePart(partKey: String, text: String, partDir: File): File? {
        val rec = parts[partKey] ?: return null
        val expectedHash = sha256Hex(text)
        if (rec.textHash != expectedHash) {
            return null
        }
        val f = File(partDir, rec.fileName)
        return if (f.exists() && f.isFile && f.length() > 0L) f else null
    }

    fun recordPart(partKey: String, text: String, fileName: String, ext: String) {
        parts[partKey] = PartRecord(
            textHash = sha256Hex(text),
            fileName = fileName,
            ext = ext
        )
        save()
    }

    fun removeParts(partKeys: Collection<String>, saveImmediately: Boolean = true) {
        var changed = false
        for (k in partKeys) {
            if (parts.remove(k) != null) {
                changed = true
            }
        }
        if (changed && saveImmediately) {
            save()
        }
    }

    fun isChapterReusable(chapterKey: String, partTexts: List<String>, targetFile: File): Boolean {
        if (!targetFile.exists() || !targetFile.isFile || targetFile.length() <= 0L) {
            return false
        }
        val rec = chapters[chapterKey] ?: return false
        if (rec.fileName != targetFile.name) {
            return false
        }
        val expectedHash = computeChapterTextHash(partTexts)
        return rec.textHash == expectedHash
    }

    fun recordChapterCompleted(
        chapterKey: String,
        partTexts: List<String>,
        targetFileName: String,
        mergedPartKeys: Collection<String>
    ) {
        for (k in mergedPartKeys) {
            parts.remove(k)
        }
        chapters[chapterKey] = ChapterRecord(
            textHash = computeChapterTextHash(partTexts),
            fileName = targetFileName
        )
        save()
    }

    fun save() {
        synchronized(saveLock) {
            try {
                val root = JSONObject()
                root.put("version", 1)

                val partsObj = JSONObject()
                for ((k, v) in parts) {
                    val obj = JSONObject()
                    obj.put("textHash", v.textHash)
                    obj.put("fileName", v.fileName)
                    obj.put("ext", v.ext)
                    partsObj.put(k, obj)
                }
                root.put("parts", partsObj)

                val chaptersObj = JSONObject()
                for ((k, v) in chapters) {
                    val obj = JSONObject()
                    obj.put("textHash", v.textHash)
                    obj.put("fileName", v.fileName)
                    chaptersObj.put(k, obj)
                }
                root.put("chapters", chaptersObj)

                val bytes = root.toString(2).toByteArray(Charsets.UTF_8)
                writeBytesAtomically(indexFile, bytes)
            } catch (_: Throwable) {
            }
        }
    }

    fun deleteIndexFile() {
        synchronized(saveLock) {
            try {
                if (indexFile.exists()) {
                    indexFile.delete()
                }
            } catch (_: Throwable) {
            }
        }
    }

    companion object {
        const val INDEX_FILE_NAME = ".hedgehog_index.json"

        fun load(dir: File, indexFileName: String = INDEX_FILE_NAME): BookConversionIndex {
            dir.mkdirs()
            val indexFile = File(dir, indexFileName)
            val idx = BookConversionIndex(indexFile)
            if (indexFile.exists() && indexFile.isFile) {
                try {
                    val raw = indexFile.readText(Charsets.UTF_8)
                    val root = JSONObject(raw)
                    val partsObj = root.optJSONObject("parts")
                    if (partsObj != null) {
                        val keys = partsObj.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            val item = partsObj.optJSONObject(k) ?: continue
                            val hash = item.optString("textHash", "")
                            val fileName = item.optString("fileName", "")
                            val ext = item.optString("ext", "")
                            if (hash.isNotEmpty() && fileName.isNotEmpty()) {
                                idx.parts[k] = PartRecord(hash, fileName, ext)
                            }
                        }
                    }
                    val chaptersObj = root.optJSONObject("chapters")
                    if (chaptersObj != null) {
                        val keys = chaptersObj.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            val item = chaptersObj.optJSONObject(k) ?: continue
                            val hash = item.optString("textHash", "")
                            val fileName = item.optString("fileName", "")
                            if (hash.isNotEmpty() && fileName.isNotEmpty()) {
                                idx.chapters[k] = ChapterRecord(hash, fileName)
                            }
                        }
                    }
                } catch (_: Throwable) {
                }
            }
            return idx
        }

        fun partKey(glava: String, fileNum: String): String {
            val cleanGlava = glava.trim()
            return if (cleanGlava.isEmpty()) "c$fileNum" else "${cleanGlava}_c$fileNum"
        }

        fun computeChapterTextHash(partTexts: List<String>): String {
            val md = MessageDigest.getInstance("SHA-256")
            for (t in partTexts) {
                md.update(t.toByteArray(Charsets.UTF_8))
                md.update(0.toByte())
            }
            return bytesToHex(md.digest())
        }

        fun sha256Hex(text: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(text.toByteArray(Charsets.UTF_8))
            return bytesToHex(digest)
        }

        private fun bytesToHex(bytes: ByteArray): String {
            val hexChars = "0123456789abcdef"
            val out = CharArray(bytes.size * 2)
            for (i in bytes.indices) {
                val v = bytes[i].toInt() and 0xFF
                out[i * 2] = hexChars[v ushr 4]
                out[i * 2 + 1] = hexChars[v and 0x0F]
            }
            return String(out)
        }

        fun writeBytesAtomically(
            targetFile: File,
            data: ByteArray,
            shouldAbort: () -> Boolean = { false }
        ): Boolean {
            if (shouldAbort()) return false
            val parent = targetFile.parentFile ?: File(".")
            parent.mkdirs()
            val tmpFile = File(parent, ".${targetFile.name}.${System.nanoTime()}.tmp")
            return try {
                FileOutputStream(tmpFile).use { it.write(data) }
                if (shouldAbort()) {
                    tmpFile.delete()
                    false
                } else {
                    try {
                        Files.move(
                            tmpFile.toPath(),
                            targetFile.toPath(),
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE
                        )
                    } catch (_: Throwable) {
                        Files.move(
                            tmpFile.toPath(),
                            targetFile.toPath(),
                            StandardCopyOption.REPLACE_EXISTING
                        )
                    }
                    true
                }
            } catch (_: Throwable) {
                tmpFile.delete()
                false
            }
        }
    }
}
