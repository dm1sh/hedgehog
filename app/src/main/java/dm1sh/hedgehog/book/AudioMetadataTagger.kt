package dm1sh.hedgehog.book

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

data class BookAudioMetadata(
    val title: String = "",
    val author: String = "",
    val date: String = "",
    val genre: String = "",
    val description: String = "",
    val language: String = "",
    val publisher: String = "",
    val series: String = "",
    val seriesIndex: String = "",
    val coverBytes: ByteArray? = null,
    val coverMimeType: String = "",
    val coverWidth: Int = 0,
    val coverHeight: Int = 0
) {
    @Transient
    @Volatile
    internal var cachedId3ApicFrame: ByteArray? = null

    @Transient
    @Volatile
    internal var cachedOpusPictureTag: String? = null
}

object AudioMetadataTagger {

    data class ImageInfo(
        val mimeType: String,
        val width: Int,
        val height: Int
    )

    private val OGG_CRC_TABLE = IntArray(256).apply {
        for (i in 0 until 256) {
            var r = i shl 24
            for (bit in 0 until 8) {
                r = if ((r and -0x80000000) != 0) {
                    (r shl 1) xor 0x04C11DB7
                } else {
                    r shl 1
                }
            }
            this[i] = r
        }
    }

    fun inspectImage(bytes: ByteArray, declaredMime: String = ""): ImageInfo {
        val cleanDeclared = declaredMime.trim().lowercase(Locale.ROOT)
        if (bytes.size >= 24 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte()
        ) {
            val width = readBigEndianInt(bytes, 16).coerceAtLeast(0)
            val height = readBigEndianInt(bytes, 20).coerceAtLeast(0)
            return ImageInfo("image/png", width, height)
        }
        if (bytes.size >= 4 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte()
        ) {
            val (w, h) = parseJpegDimensions(bytes)
            return ImageInfo("image/jpeg", w, h)
        }
        if (bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() &&
            bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() &&
            bytes[11] == 'P'.code.toByte()
        ) {
            return ImageInfo("image/webp", 0, 0)
        }
        if (bytes.size >= 10 &&
            bytes[0] == 'G'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte()
        ) {
            val w = ((bytes[6].toInt() and 0xFF) or ((bytes[7].toInt() and 0xFF) shl 8)).coerceAtLeast(0)
            val h = ((bytes[8].toInt() and 0xFF) or ((bytes[9].toInt() and 0xFF) shl 8)).coerceAtLeast(0)
            return ImageInfo("image/gif", w, h)
        }
        val fallback = when {
            cleanDeclared.startsWith("image/") -> cleanDeclared
            else -> "image/jpeg"
        }
        return ImageInfo(fallback, 0, 0)
    }

    private fun parseJpegDimensions(bytes: ByteArray): Pair<Int, Int> {
        var i = 2
        val len = bytes.size
        while (i + 8 < len) {
            if (bytes[i] != 0xFF.toByte()) {
                i++
                continue
            }
            while (i < len && bytes[i] == 0xFF.toByte()) {
                i++
            }
            if (i >= len) break
            val marker = bytes[i].toInt() and 0xFF
            i++
            if (marker == 0xD8 || marker == 0xD9 || marker in 0xD0..0xD7 || marker == 0x01) {
                continue
            }
            if (i + 1 >= len) break
            val segLen = ((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)
            if (segLen < 2 || i + segLen > len) break
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                if (segLen >= 7 && i + 6 < len) {
                    val height = ((bytes[i + 3].toInt() and 0xFF) shl 8) or (bytes[i + 4].toInt() and 0xFF)
                    val width = ((bytes[i + 5].toInt() and 0xFF) shl 8) or (bytes[i + 6].toInt() and 0xFF)
                    return width.coerceAtLeast(0) to height.coerceAtLeast(0)
                }
                break
            }
            i += segLen
        }
        return 0 to 0
    }

    /**
     * Embeds ID3v2.3 metadata and front cover art (APIC) into an MP3 byte array.
     */
    fun tagMp3(
        mp3Bytes: ByteArray,
        bookMeta: BookAudioMetadata,
        trackTitle: String,
        albumTitle: String,
        trackNumber: Int,
        totalTracks: Int,
        voiceName: String = ""
    ): ByteArray {
        if (mp3Bytes.isEmpty()) return mp3Bytes
        return try {
            val audioStart = findMp3AudioStartAfterId3v2(mp3Bytes)
            val framesOut = ByteArrayOutputStream(
                512 + (bookMeta.coverBytes?.size ?: 0)
            )

            val resolvedTitle = trackTitle.trim().ifEmpty { bookMeta.title.trim() }.ifEmpty { albumTitle.trim() }
            val resolvedAlbum = albumTitle.trim().ifEmpty { bookMeta.title.trim() }.ifEmpty { resolvedTitle }
            val resolvedArtist = bookMeta.author.trim()
            val resolvedGenre = bookMeta.genre.trim().ifEmpty { "Audiobook" }
            val resolvedDate = bookMeta.date.trim()
            val resolvedYear = Regex("\\b(1\\d{3}|20\\d{2})\\b").find(resolvedDate)?.value.orEmpty()
            val resolvedTrack = when {
                trackNumber > 0 && totalTracks > 0 -> "$trackNumber/$totalTracks"
                trackNumber > 0 -> "$trackNumber"
                else -> ""
            }
            val resolvedSeries = if (bookMeta.series.isNotBlank()) {
                if (bookMeta.seriesIndex.isNotBlank()) {
                    "${bookMeta.series.trim()} #${bookMeta.seriesIndex.trim()}"
                } else {
                    bookMeta.series.trim()
                }
            } else {
                ""
            }
            val resolvedEncoder = if (voiceName.isNotBlank()) {
                "Hedgehog TTS (${voiceName.trim()})"
            } else {
                "Hedgehog TTS"
            }

            writeId3TextFrame(framesOut, "TIT2", resolvedTitle)
            writeId3TextFrame(framesOut, "TALB", resolvedAlbum)
            writeId3TextFrame(framesOut, "TPE1", resolvedArtist)
            writeId3TextFrame(framesOut, "TPE2", resolvedArtist)
            writeId3TextFrame(framesOut, "TCOM", resolvedArtist)
            writeId3TextFrame(framesOut, "TRCK", resolvedTrack)
            writeId3TextFrame(framesOut, "TYER", resolvedYear)
            writeId3TextFrame(framesOut, "TDRC", resolvedDate)
            writeId3TextFrame(framesOut, "TCON", resolvedGenre)
            writeId3TextFrame(framesOut, "TPUB", bookMeta.publisher.trim())
            writeId3TextFrame(framesOut, "TLAN", bookMeta.language.trim())
            writeId3TextFrame(framesOut, "TIT1", resolvedSeries)
            writeId3TextFrame(framesOut, "TENC", resolvedEncoder)

            if (bookMeta.description.isNotBlank()) {
                writeId3CommentFrame(
                    out = framesOut,
                    comment = bookMeta.description.trim(),
                    lang = normalizeId3Lang(bookMeta.language)
                )
            }

            val cover = bookMeta.coverBytes
            if (cover != null && cover.isNotEmpty()) {
                val apicFrame = bookMeta.cachedId3ApicFrame ?: synchronized(bookMeta) {
                    bookMeta.cachedId3ApicFrame ?: run {
                        val info = inspectImage(cover, bookMeta.coverMimeType)
                        val apicOut = ByteArrayOutputStream(32 + cover.size)
                        writeId3ApicFrame(apicOut, cover, info.mimeType)
                        apicOut.toByteArray().also { bookMeta.cachedId3ApicFrame = it }
                    }
                }
                framesOut.write(apicFrame)
            }

            val framesBytes = framesOut.toByteArray()
            if (framesBytes.isEmpty()) return mp3Bytes

            val audioLen = (mp3Bytes.size - audioStart).coerceAtLeast(0)
            val result = ByteArray(10 + framesBytes.size + audioLen)
            // ID3v2.3.0 header
            result[0] = 'I'.code.toByte()
            result[1] = 'D'.code.toByte()
            result[2] = '3'.code.toByte()
            result[3] = 3
            result[4] = 0
            result[5] = 0
            val tagSize = framesBytes.size
            result[6] = ((tagSize ushr 21) and 0x7F).toByte()
            result[7] = ((tagSize ushr 14) and 0x7F).toByte()
            result[8] = ((tagSize ushr 7) and 0x7F).toByte()
            result[9] = (tagSize and 0x7F).toByte()
            System.arraycopy(framesBytes, 0, result, 10, framesBytes.size)
            if (audioLen > 0) {
                System.arraycopy(mp3Bytes, audioStart, result, 10 + framesBytes.size, audioLen)
            }
            result
        } catch (_: Throwable) {
            mp3Bytes
        }
    }

    private fun findMp3AudioStartAfterId3v2(mp3Bytes: ByteArray): Int {
        var offset = 0
        while (offset + 10 <= mp3Bytes.size &&
            mp3Bytes[offset] == 'I'.code.toByte() &&
            mp3Bytes[offset + 1] == 'D'.code.toByte() &&
            mp3Bytes[offset + 2] == '3'.code.toByte()
        ) {
            val b6 = mp3Bytes[offset + 6].toInt() and 0x7F
            val b7 = mp3Bytes[offset + 7].toInt() and 0x7F
            val b8 = mp3Bytes[offset + 8].toInt() and 0x7F
            val b9 = mp3Bytes[offset + 9].toInt() and 0x7F
            val size = (b6 shl 21) or (b7 shl 14) or (b8 shl 7) or b9
            val flags = mp3Bytes[offset + 5].toInt() and 0xFF
            val footer = if ((flags and 0x10) != 0) 10 else 0
            val next = offset + 10 + size + footer
            if (next <= offset || next >= mp3Bytes.size) break
            offset = next
        }
        return offset
    }

    private fun writeId3TextFrame(out: ByteArrayOutputStream, frameId: String, value: String) {
        if (value.isBlank() || frameId.length != 4) return
        val utf16Le = value.toByteArray(Charsets.UTF_16LE)
        val payloadSize = 1 + 2 + utf16Le.size // encoding(0x01) + BOM(FF FE) + UTF-16LE bytes
        writeId3FrameHeader(out, frameId, payloadSize)
        out.write(0x01) // UTF-16 with BOM
        out.write(0xFF)
        out.write(0xFE)
        out.write(utf16Le)
    }

    private fun writeId3CommentFrame(out: ByteArrayOutputStream, comment: String, lang: String) {
        if (comment.isBlank()) return
        val utf16Le = comment.toByteArray(Charsets.UTF_16LE)
        // encoding(1) + lang(3) + empty description BOM+null(4) + comment BOM(2) + utf16Le
        val payloadSize = 1 + 3 + 4 + 2 + utf16Le.size
        writeId3FrameHeader(out, "COMM", payloadSize)
        out.write(0x01) // UTF-16 with BOM
        val langBytes = lang.padEnd(3, ' ').take(3).toByteArray(Charsets.US_ASCII)
        out.write(langBytes)
        // Empty short description (UTF-16LE BOM + 0x0000 terminator)
        out.write(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x00))
        // Comment text (UTF-16LE BOM + text)
        out.write(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
        out.write(utf16Le)
    }

    private fun writeId3ApicFrame(out: ByteArrayOutputStream, coverBytes: ByteArray, mimeType: String) {
        val mimeBytes = mimeType.ifBlank { "image/jpeg" }.toByteArray(Charsets.ISO_8859_1)
        // encoding(1: 0x00 ISO-8859-1) + mime + 0x00 + picType(1: 0x03 Front Cover) + desc(1: 0x00) + coverBytes
        val payloadSize = 1 + mimeBytes.size + 1 + 1 + 1 + coverBytes.size
        writeId3FrameHeader(out, "APIC", payloadSize)
        out.write(0x00) // ISO-8859-1 for MIME & empty description
        out.write(mimeBytes)
        out.write(0x00)
        out.write(0x03) // Cover (front)
        out.write(0x00) // Empty description terminator
        out.write(coverBytes)
    }

    private fun writeId3FrameHeader(out: ByteArrayOutputStream, frameId: String, payloadSize: Int) {
        out.write(frameId.toByteArray(Charsets.US_ASCII))
        out.write((payloadSize ushr 24) and 0xFF)
        out.write((payloadSize ushr 16) and 0xFF)
        out.write((payloadSize ushr 8) and 0xFF)
        out.write(payloadSize and 0xFF)
        out.write(0x00)
        out.write(0x00)
    }

    private fun normalizeId3Lang(rawLang: String): String {
        return when (val clean = rawLang.trim().lowercase(Locale.ROOT).take(3)) {
            "ru", "rus" -> "rus"
            "en", "eng" -> "eng"
            "de", "deu", "ger" -> "deu"
            "es", "spa" -> "spa"
            "fr", "fra", "fre" -> "fra"
            "it", "ita" -> "ita"
            "pt", "por" -> "por"
            "pl", "pol" -> "pol"
            "fi", "fin" -> "fin"
            "ja", "jpn" -> "jpn"
            "ko", "kor" -> "kor"
            "zh", "zho", "chi" -> "zho"
            else -> if (clean.length == 3 && clean.all { it in 'a'..'z' }) clean else "und"
        }
    }

    /**
     * Embeds Vorbis comments and METADATA_BLOCK_PICTURE cover art into an Ogg Opus (.opus) byte array
     * per RFC 7845 Section 5.2.
     */
    fun tagOpus(
        opusBytes: ByteArray,
        bookMeta: BookAudioMetadata,
        trackTitle: String,
        albumTitle: String,
        trackNumber: Int,
        totalTracks: Int,
        voiceName: String = ""
    ): ByteArray {
        if (opusBytes.size < 64) return opusBytes
        return try {
            val pages = parseOggPages(opusBytes)
            if (pages.size < 2) return opusBytes

            val headPage = pages[0]
            // Locate how many pages belong to the existing OpusTags packet (starts at page 1)
            var audioStartPageIdx = 1
            while (audioStartPageIdx < pages.size) {
                val pg = pages[audioStartPageIdx]
                audioStartPageIdx++
                // An Ogg packet ends on this page if its last segment size < 255
                if (pg.lastSegmentSize < 255) {
                    break
                }
            }
            if (audioStartPageIdx > pages.size) return opusBytes

            val resolvedTitle = trackTitle.trim().ifEmpty { bookMeta.title.trim() }.ifEmpty { albumTitle.trim() }
            val resolvedAlbum = albumTitle.trim().ifEmpty { bookMeta.title.trim() }.ifEmpty { resolvedTitle }
            val resolvedArtist = bookMeta.author.trim()
            val resolvedGenre = bookMeta.genre.trim().ifEmpty { "Audiobook" }
            val resolvedDate = bookMeta.date.trim()
            val resolvedEncoder = if (voiceName.isNotBlank()) {
                "Hedgehog TTS (${voiceName.trim()})"
            } else {
                "Hedgehog TTS"
            }

            val comments = mutableListOf<String>()
            fun addTag(key: String, value: String) {
                val trimmed = value.trim()
                if (trimmed.isNotEmpty()) {
                    comments.add("$key=$trimmed")
                }
            }

            addTag("TITLE", resolvedTitle)
            addTag("ALBUM", resolvedAlbum)
            addTag("ARTIST", resolvedArtist)
            addTag("ALBUMARTIST", resolvedArtist)
            addTag("AUTHOR", resolvedArtist)
            addTag("COMPOSER", resolvedArtist)
            if (trackNumber > 0) {
                addTag("TRACKNUMBER", trackNumber.toString())
            }
            if (totalTracks > 0) {
                addTag("TOTALTRACKS", totalTracks.toString())
            }
            addTag("DATE", resolvedDate)
            addTag("GENRE", resolvedGenre)
            addTag("DESCRIPTION", bookMeta.description)
            addTag("COMMENT", bookMeta.description)
            addTag("PUBLISHER", bookMeta.publisher)
            addTag("LANGUAGE", bookMeta.language)
            addTag("SERIES", bookMeta.series)
            addTag("PART", bookMeta.seriesIndex)
            addTag("ENCODER", resolvedEncoder)

            val cover = bookMeta.coverBytes
            if (cover != null && cover.isNotEmpty()) {
                val pictureTag = bookMeta.cachedOpusPictureTag ?: synchronized(bookMeta) {
                    bookMeta.cachedOpusPictureTag ?: run {
                        val info = inspectImage(cover, bookMeta.coverMimeType)
                        val w = if (bookMeta.coverWidth > 0) bookMeta.coverWidth else info.width
                        val h = if (bookMeta.coverHeight > 0) bookMeta.coverHeight else info.height
                        val picBase64 = buildFlacPictureBlockBase64(
                            coverBytes = cover,
                            mimeType = info.mimeType,
                            width = w,
                            height = h
                        )
                        "METADATA_BLOCK_PICTURE=$picBase64".also { bookMeta.cachedOpusPictureTag = it }
                    }
                }
                comments.add(pictureTag)
            }

            val opusTagsPacket = buildOpusTagsPacket("Hedgehog TTS", comments)
            val serialNumber = headPage.serialNumber

            // Paginate OpusTags packet into 1 or more Ogg pages starting at sequence number 1
            var seqNum = 1
            val tagsPages = buildOggPacketPages(
                packet = opusTagsPacket,
                serialNumber = serialNumber,
                startSeqNum = seqNum,
                endGranulePosition = 0L
            )
            var tagsPagesLen = 0
            for (tp in tagsPages) {
                tagsPagesLen += tp.size
                seqNum++
            }

            var audioTailLen = 0
            for (idx in audioStartPageIdx until pages.size) {
                audioTailLen += pages[idx].length
            }

            val result = ByteArray(headPage.length + tagsPagesLen + audioTailLen)
            var writePos = 0

            // Write Page 0 (OpusHead) untouched
            System.arraycopy(opusBytes, headPage.offset, result, writePos, headPage.length)
            writePos += headPage.length

            for (tp in tagsPages) {
                System.arraycopy(tp, 0, result, writePos, tp.size)
                writePos += tp.size
            }

            // Write remaining audio pages and update sequence numbers + Ogg CRC32 in-place
            for (idx in audioStartPageIdx until pages.size) {
                val pg = pages[idx]
                System.arraycopy(opusBytes, pg.offset, result, writePos, pg.length)
                writeLittleEndianInt(result, writePos + 18, seqNum)
                updateOggPageCrcSlice(result, writePos, pg.length)
                writePos += pg.length
                seqNum++
            }

            result
        } catch (_: Throwable) {
            opusBytes
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun buildFlacPictureBlockBase64(
        coverBytes: ByteArray,
        mimeType: String,
        width: Int,
        height: Int
    ): String {
        val mimeAscii = mimeType.ifBlank { "image/jpeg" }.toByteArray(Charsets.US_ASCII)
        val totalLen = 32 + mimeAscii.size + coverBytes.size
        val buf = ByteBuffer.allocate(totalLen).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(3) // Picture type 3: Cover (front)
        buf.putInt(mimeAscii.size)
        buf.put(mimeAscii)
        buf.putInt(0) // Description length = 0
        buf.putInt(width.coerceAtLeast(0))
        buf.putInt(height.coerceAtLeast(0))
        buf.putInt(24) // Color depth
        buf.putInt(0) // Indexed colors = 0
        buf.putInt(coverBytes.size)
        buf.put(coverBytes)
        return Base64.Default.encode(buf.array())
    }

    private fun buildOpusTagsPacket(vendor: String, comments: List<String>): ByteArray {
        val vendorBytes = vendor.toByteArray(Charsets.UTF_8)
        val commentBytesList = comments.map { it.toByteArray(Charsets.UTF_8) }
        val totalSize = 8 + 4 + vendorBytes.size + 4 + commentBytesList.sumOf { 4 + it.size }
        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("OpusTags".toByteArray(Charsets.US_ASCII))
        buf.putInt(vendorBytes.size)
        buf.put(vendorBytes)
        buf.putInt(commentBytesList.size)
        for (cb in commentBytesList) {
            buf.putInt(cb.size)
            buf.put(cb)
        }
        return buf.array()
    }

    private fun buildOggPacketPages(
        packet: ByteArray,
        serialNumber: Int,
        startSeqNum: Int,
        endGranulePosition: Long
    ): List<ByteArray> {
        // Split packet into Ogg lacing segments (each 255 bytes, terminated by a segment of 0..254 bytes)
        val segmentSizes = ArrayList<Int>(packet.size / 255 + 2)
        var remaining = packet.size
        while (remaining >= 255) {
            segmentSizes.add(255)
            remaining -= 255
        }
        segmentSizes.add(remaining) // Always ends with a segment < 255 (can be 0)

        val pages = mutableListOf<ByteArray>()
        var segIdx = 0
        var packetOffset = 0
        var pageSeq = startSeqNum

        while (segIdx < segmentSizes.size) {
            val segCount = minOf(255, segmentSizes.size - segIdx)
            val isFirstPageOfPacket = (segIdx == 0)
            val isLastPageOfPacket = (segIdx + segCount == segmentSizes.size)

            var bodyLen = 0
            for (k in 0 until segCount) {
                bodyLen += segmentSizes[segIdx + k]
            }

            val headerLen = 27 + segCount
            val pageBytes = ByteArray(headerLen + bodyLen)
            pageBytes[0] = 'O'.code.toByte()
            pageBytes[1] = 'g'.code.toByte()
            pageBytes[2] = 'g'.code.toByte()
            pageBytes[3] = 'S'.code.toByte()
            pageBytes[4] = 0 // version
            pageBytes[5] = if (isFirstPageOfPacket) 0x00 else 0x01 // continuation flag
            val granule = if (isLastPageOfPacket) endGranulePosition else -1L
            writeLittleEndianLong(pageBytes, 6, granule)
            writeLittleEndianInt(pageBytes, 14, serialNumber)
            writeLittleEndianInt(pageBytes, 18, pageSeq)
            writeLittleEndianInt(pageBytes, 22, 0) // CRC placeholder
            pageBytes[26] = segCount.toByte()
            for (k in 0 until segCount) {
                pageBytes[27 + k] = segmentSizes[segIdx + k].toByte()
            }
            if (bodyLen > 0) {
                System.arraycopy(packet, packetOffset, pageBytes, headerLen, bodyLen)
                packetOffset += bodyLen
            }
            updateOggPageCrc(pageBytes)
            pages.add(pageBytes)

            segIdx += segCount
            pageSeq++
        }
        return pages
    }

    private data class OggPageSlice(
        val offset: Int,
        val length: Int,
        val serialNumber: Int,
        val lastSegmentSize: Int
    )

    private fun parseOggPages(data: ByteArray): List<OggPageSlice> {
        val pages = mutableListOf<OggPageSlice>()
        var pos = 0
        val len = data.size
        while (pos + 27 <= len) {
            if (data[pos] != 'O'.code.toByte() ||
                data[pos + 1] != 'g'.code.toByte() ||
                data[pos + 2] != 'g'.code.toByte() ||
                data[pos + 3] != 'S'.code.toByte()
            ) {
                break
            }
            val numSegments = data[pos + 26].toInt() and 0xFF
            val headerLen = 27 + numSegments
            if (pos + headerLen > len) break
            var bodyLen = 0
            var lastSeg = 0
            for (i in 0 until numSegments) {
                val s = data[pos + 27 + i].toInt() and 0xFF
                bodyLen += s
                lastSeg = s
            }
            val pageLen = headerLen + bodyLen
            if (pos + pageLen > len) break
            val serial = readLittleEndianInt(data, pos + 14)
            pages.add(OggPageSlice(pos, pageLen, serial, lastSeg))
            pos += pageLen
        }
        return pages
    }

    private fun updateOggPageCrc(page: ByteArray) {
        updateOggPageCrcSlice(page, 0, page.size)
    }

    private fun updateOggPageCrcSlice(data: ByteArray, offset: Int, length: Int) {
        data[offset + 22] = 0
        data[offset + 23] = 0
        data[offset + 24] = 0
        data[offset + 25] = 0
        var crc = 0
        val end = offset + length
        for (i in offset until end) {
            val idx = ((crc ushr 24) xor (data[i].toInt() and 0xFF)) and 0xFF
            crc = (crc shl 8) xor OGG_CRC_TABLE[idx]
        }
        writeLittleEndianInt(data, offset + 22, crc)
    }

    private fun readBigEndianInt(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
    }

    private fun readLittleEndianInt(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun writeLittleEndianInt(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        data[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        data[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun writeLittleEndianLong(data: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) {
            data[offset + i] = ((value ushr (8 * i)) and 0xFFL).toByte()
        }
    }
}
