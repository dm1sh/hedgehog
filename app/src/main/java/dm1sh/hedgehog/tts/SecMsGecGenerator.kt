package dm1sh.hedgehog.tts

import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.math.floor

object SecMsGecGenerator {
    const val WIN_EPOCH = 11644473600.0
    const val S_TO_NS = 1000000000.0
    const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    const val CHROMIUM_FULL_VERSION = "143.0.3650.75"
    const val SEC_MS_GEC_VERSION = "2-$CHROMIUM_FULL_VERSION"

    private const val U32_MASK = 0xFFFFFFFFL

    private val SHA_INITIAL = longArrayOf(
        1779033703L, 3144134277L, 1013904242L, 2773480762L,
        1359893119L, 2600822924L, 528734635L, 1541459225L
    )
    private val SHA_STATE = longArrayOf(
        4255104674L, 2435071207L, 3192492180L, 3442351626L,
        1811967817L, 79508907L, 4272990180L, 66059218L
    )
    private val SHA_SCHEDULE = longArrayOf(
        3945054110L, 358115615L, 2696978146L, 217723578L,
        2787307148L, 3520959633L, 3235718560L, 4192668342L,
        4039316851L, 1884868174L, 1904797190L, 15051571L,
        3529027680L, 1920361828L
    )
    private val SHA_K = longArrayOf(
        1116352408L, 1899447441L, 3049323471L, 3921009573L, 961987163L,
        1508970993L, 2453635748L, 2870763221L, 3624381080L, 310598401L, 607225278L,
        1426881987L, 1925078388L, 2162078206L, 2614888103L, 3248222580L, 3835390401L,
        4022224774L, 264347078L, 604807628L, 770255983L, 1249150122L, 1555081692L,
        1996064986L, 2554220882L, 2821834349L, 2952996808L, 3210313671L, 3336571891L,
        3584528711L, 113926993L, 338241895L, 666307205L, 773529912L, 1294757372L,
        1396182291L, 1695183700L, 1986661051L, 2177026350L, 2456956037L, 2730485921L,
        2820302411L, 3259730800L, 3345764771L, 3516065817L, 3600352804L, 4094571909L,
        275423344L, 430227734L, 506948616L, 659060556L, 883997877L, 958139571L,
        1322822218L, 1537002063L, 1747873779L, 1955562222L, 2024104815L, 2227730452L,
        2361852424L, 2428436474L, 2756734187L, 3204031479L, 3329325298L
    )
    private const val SHA_K14_ADJ = 3386640039L
    private const val SHA_SIGMA0_ADJ = 94112640L
    private const val SHA_SIGMA1_ADJ = 758464L

    private val CS64_COEF = longArrayOf(
        3895366065L, 1155409094L, 1005282748L, 3365689651L, 2367603217L,
        827258325L, 3911487921L, 4037392853L, 101076211L, 3665427040L, 3201434976L,
        2314848760L, 905590982L, 3410617286L
    )
    private val CS64_OFFSET = longArrayOf(
        3005677776L, 2296378653L, 693190712L, 3005795295L, 3557143765L,
        652566332L, 1618477510L, 2192753147L, 4199654362L, 545047652L, 1010595059L,
        3536931681L, 3137643201L, 3311559015L
    )
    private const val CS64_FACTOR = 2190031155L
    private const val CS64_ADJ0 = 4076407578L
    private const val CS64_ADJ1 = 186253312L

    private val gecLock = Any()
    private var gecErrorCount = 0
    private var gecClockSkewSeconds = 0.0
    private var gecSyncInProgress = false
    private const val GEC_ERRORS_BEFORE_SYNC = 3

    private val syncHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    fun generateSecMsGec(): String {
        val skew = synchronized(gecLock) { gecClockSkewSeconds }
        val nowUnix = System.currentTimeMillis() / 1000.0
        var ticks = nowUnix + skew + WIN_EPOCH
        ticks = floor(ticks / 300.0) * 300.0
        ticks *= S_TO_NS / 100.0

        val strToHash = String.format(Locale.US, "%.0f%s", ticks, TRUSTED_CLIENT_TOKEN)
            .toByteArray(Charsets.US_ASCII)

        val digest = shaDigest(strToHash)
        val cs64 = cs64Digest(strToHash, digest)
        val hexDigest = digest.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
        return u32LeHex(cs64[0]) + u32LeHex(cs64[1]) + "-" + hexDigest
    }

    fun generateMuid(): String {
        val chars = "0123456789ABCDEF"
        return buildString(32) {
            repeat(32) {
                append(chars.random())
            }
        }
    }

    fun generateConnectId(): String {
        val template = "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx"
        return buildString(32) {
            for (c in template) {
                when (c) {
                    'x' -> append("%x".format((0..15).random()))
                    'y' -> append("%x".format(((0..15).random() and 3) or 8))
                    '-' -> {}
                    else -> append(c)
                }
            }
        }
    }

    fun dateToString(): String {
        val sdf = SimpleDateFormat("EEE, MMM d, yyyy, h:mm:ss a", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }

    fun reportSuccess() {
        synchronized(gecLock) {
            gecErrorCount = 0
        }
    }

    fun reportError() {
        var doSync = false
        synchronized(gecLock) {
            gecErrorCount++
            if (gecErrorCount >= GEC_ERRORS_BEFORE_SYNC && !gecSyncInProgress) {
                gecSyncInProgress = true
                gecErrorCount = 0
                doSync = true
            }
        }
        if (doSync) {
            Thread {
                val skew = fetchServerSkew()
                synchronized(gecLock) {
                    if (!skew.isNaN()) {
                        gecClockSkewSeconds = skew
                    }
                    gecSyncInProgress = false
                }
            }.start()
        }
    }

    private fun fetchServerSkew(): Double {
        return try {
            val req = Request.Builder()
                .url("https://speech.platform.bing.com/")
                .head()
                .build()
            syncHttpClient.newCall(req).execute().use { resp ->
                val dateHeader = resp.header("Date") ?: return Double.NaN
                val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
                val parsed = sdf.parse(dateHeader) ?: return Double.NaN
                (parsed.time / 1000.0) - (System.currentTimeMillis() / 1000.0)
            }
        } catch (_: Exception) {
            Double.NaN
        }
    }

    private fun rotr(value: Long, n: Int): Long {
        val v = value and U32_MASK
        return ((v ushr n) or (v shl (32 - n))) and U32_MASK
    }

    private fun rol16(value: Long): Long {
        val v = value and U32_MASK
        return ((v shl 16) or (v ushr 16)) and U32_MASK
    }

    private fun sigma0(value: Long): Long {
        val v = value and U32_MASK
        return rotr(v, 7) xor rotr(v, 18) xor (v ushr 3)
    }

    private fun sigma1(value: Long): Long {
        val v = value and U32_MASK
        return rotr(v, 17) xor rotr(v, 19) xor (v ushr 10)
    }

    private fun shaRounds(state: LongArray, words: LongArray, firstRound: Int): LongArray {
        var a = state[0]; var b = state[1]; var c = state[2]; var d = state[3]
        var e = state[4]; var f = state[5]; var g = state[6]; var h = state[7]
        for (i in firstRound until 64) {
            val k = if (firstRound == 14 && i == 14) SHA_K14_ADJ else SHA_K[i]
            val bigSig1 = rotr(e, 6) xor rotr(e, 11) xor rotr(e, 25)
            val t1 = (h + bigSig1 + (g xor (e and (f xor g))) + k + words[i]) and U32_MASK
            val bigSig0 = rotr(a, 2) xor rotr(a, 13) xor rotr(a, 22)
            val t2 = (bigSig0 + ((a and b) or (c and (a or b)))) and U32_MASK
            h = g; g = f; f = e; e = (d + t1) and U32_MASK
            d = c; c = b; b = a; a = (t1 + t2) and U32_MASK
        }
        return longArrayOf(a, b, c, d, e, f, g, h)
    }

    private fun shaExpand(words: LongArray, firstWord: Int) {
        for (i in firstWord until 64) {
            words[i] = (sigma1(words[i - 2]) + words[i - 7] + sigma0(words[i - 15]) + words[i - 16]) and U32_MASK
        }
    }

    private fun shaInit(data: ByteArray): LongArray {
        val words = LongArray(64)
        val d0 = data[0].toLong() and 0xFF
        val d1 = data[1].toLong() and 0xFF
        val d2 = data[2].toLong() and 0xFF
        val d3 = data[3].toLong() and 0xFF
        val d4 = data[4].toLong() and 0xFF
        val d5 = data[5].toLong() and 0xFF
        val d6 = data[6].toLong() and 0xFF

        words[14] = (d0 shl 16) or (d1 shl 8) or d2
        words[15] = (d3 shl 24) or (d4 shl 16) or (d5 shl 8) or d6

        for (i in 16 until 30) {
            var v = SHA_SCHEDULE[i - 16]
            if (i - 2 >= 14) {
                v += if (i == 16) (sigma1(words[i - 2]) xor SHA_SIGMA1_ADJ) else sigma1(words[i - 2])
            }
            if (i - 7 >= 14) {
                v += words[i - 7]
            }
            if (i - 15 >= 14) {
                v += if (i == 29) (sigma0(words[i - 15]) xor SHA_SIGMA0_ADJ) else sigma0(words[i - 15])
            }
            words[i] = v and U32_MASK
        }
        words[30] = (sigma1(words[28]) + words[23] + sigma0(words[15]) + words[14] + SHA_K14_ADJ - SHA_K[14]) and U32_MASK
        shaExpand(words, 31)
        val working = shaRounds(SHA_STATE, words, 14)
        return LongArray(8) { i -> (SHA_INITIAL[i] + working[i]) and U32_MASK }
    }

    private fun shaDigest(data: ByteArray): ByteArray {
        val length = 57 + data.size
        val sliceAfter7 = data.copyOfRange(7, data.size)
        val padZeroCount = ((55 - length) % 64 + 64) % 64
        val padded = ByteArray(sliceAfter7.size + 1 + padZeroCount + 8)
        System.arraycopy(sliceAfter7, 0, padded, 0, sliceAfter7.size)
        padded[sliceAfter7.size] = 0x80.toByte()
        val bitLen = length.toLong() * 8L
        val lenOffset = sliceAfter7.size + 1 + padZeroCount
        for (i in 0 until 8) {
            padded[lenOffset + i] = ((bitLen ushr (56 - i * 8)) and 0xFFL).toByte()
        }

        val state = shaInit(data.copyOfRange(0, 7))
        var offset = 0
        while (offset < padded.size) {
            val words = LongArray(64)
            for (w in 0 until 16) {
                val p = offset + w * 4
                val b0 = padded[p].toLong() and 0xFF
                val b1 = padded[p + 1].toLong() and 0xFF
                val b2 = padded[p + 2].toLong() and 0xFF
                val b3 = padded[p + 3].toLong() and 0xFF
                words[w] = (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
            }
            shaExpand(words, 16)
            val working = shaRounds(state, words, 0)
            for (i in 0 until 8) {
                state[i] = (state[i] + working[i]) and U32_MASK
            }
            offset += 64
        }

        val out = ByteArray(32)
        for (i in 0 until 8) {
            out[i * 4] = ((state[i] ushr 24) and 0xFF).toByte()
            out[i * 4 + 1] = ((state[i] ushr 16) and 0xFF).toByte()
            out[i * 4 + 2] = ((state[i] ushr 8) and 0xFF).toByte()
            out[i * 4 + 3] = (state[i] and 0xFF).toByte()
        }
        return out
    }

    private fun wordsLe4Even(data: ByteArray): LongArray {
        val words = ArrayList<Long>()
        var i = 0
        while (i < data.size) {
            var w = 0L
            for (j in 0 until 4) {
                if (i + j < data.size) {
                    w = w or ((data[i + j].toLong() and 0xFF) shl (j * 8))
                }
            }
            words.add(w and U32_MASK)
            i += 4
        }
        if (words.size % 2 != 0) {
            words.add(0L)
        }
        return words.toLongArray()
    }

    private fun cs64Digest(data: ByteArray, digest: ByteArray): LongArray {
        val d0 = digest[0].toLong() and 0xFF
        val d1 = digest[1].toLong() and 0xFF
        val d2 = digest[2].toLong() and 0xFF
        val d3 = digest[3].toLong() and 0xFF
        val d4 = digest[4].toLong() and 0xFF
        val d5 = digest[5].toLong() and 0xFF
        val d6 = digest[6].toLong() and 0xFF
        val d7 = digest[7].toLong() and 0xFF

        val keyA = (d0 or (d1 shl 8) or (d2 shl 16) or (d3 shl 24)) or 1L
        val keyB = (d4 or (d5 shl 8) or (d6 shl 16) or (d7 shl 24)) or 1L
        val factors = longArrayOf(
            (keyA * CS64_FACTOR) and U32_MASK,
            (keyB * CS64_FACTOR) and U32_MASK
        )
        val terms = ArrayList<Long>()
        val offsets = ArrayList<Long>()
        for (i in CS64_COEF.indices) {
            terms.add((CS64_COEF[i] * factors[i % 2]) and U32_MASK)
            offsets.add(CS64_OFFSET[i])
        }
        val salted = ByteArray(1 + data.size)
        System.arraycopy(data, 0, salted, 1, data.size)
        val words = wordsLe4Even(salted)
        for (i in words.indices) {
            val word = words[i]
            var term = word * (if (i % 2 == 0) keyA else keyB)
            var offset = rol16(word) * (if (i % 2 == 0) 4010109435L else 3273069531L)
            if (i == 0) {
                term += CS64_ADJ0 * factors[0]
                offset += CS64_ADJ1
            }
            terms.add(term and U32_MASK)
            offsets.add(offset and U32_MASK)
        }
        var swapAcc = 0L
        var swapLast = 0L
        var state = 0L
        var acc = 0L
        var i = 0
        while (i < terms.size) {
            val x = terms[i]
            val y = terms[i + 1]
            var t = rol16((offsets[i] + x) and U32_MASK)
            var q = (t * 1995771700L) and U32_MASK
            swapAcc += (rol16(q) * 3287280279L + q) and U32_MASK
            t = rol16((offsets[i + 1] + y) and U32_MASK)
            q = (t * 411160124L) and U32_MASK
            swapLast = (rol16(q) * 901586633L + q) and U32_MASK
            swapAcc = (swapAcc + swapLast) and U32_MASK
            state = (state * keyA + x) and U32_MASK
            state = (rol16(state) * 3482890513L) and U32_MASK
            state = (rol16(state) * 2265471903L) and U32_MASK
            state = (rol16(state) * 315537773L) and U32_MASK
            state = (rol16(state) * 629022083L) and U32_MASK
            acc += state
            t = (state * keyB + y) and U32_MASK
            t = (rol16(t) * 2725517045L) and U32_MASK
            t = (rol16(t) * 3548616447L) and U32_MASK
            state = (rol16((rol16(t) * 2090019721L) and U32_MASK) * 3215236969L) and U32_MASK
            acc = (acc + state) and U32_MASK
            i += 2
        }
        return longArrayOf((swapLast xor state) and U32_MASK, (swapAcc xor acc) and U32_MASK)
    }

    private fun u32LeHex(value: Long): String {
        val v = value and U32_MASK
        return "%02X%02X%02X%02X".format(
            (v and 0xFF).toInt(),
            ((v ushr 8) and 0xFF).toInt(),
            ((v ushr 16) and 0xFF).toInt(),
            ((v ushr 24) and 0xFF).toInt()
        )
    }
}
