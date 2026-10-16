package dm1sh.hedgehog.tts

import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.TlsVersion
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.net.ssl.HandshakeCompletedListener
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
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
    private val secureRandom = SecureRandom()

    val systemTrustManager: X509TrustManager by lazy {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /**
     * SSLSocketFactory that creates a fresh TLSv1.2 SSLContext per socket,
     * disables TLS session tickets (which cause speech.platform.bing.com / Azure Front Door
     * to return CN=*.azureedge.net on resumed sessions), and preserves SNI.
     */
    val tls12NoSessionReuseSocketFactory: SSLSocketFactory by lazy {
        object : SSLSocketFactory() {
            private val baseFactory: SSLSocketFactory by lazy {
                val ctx = SSLContext.getInstance("TLSv1.2")
                ctx.init(null, arrayOf<TrustManager>(systemTrustManager), secureRandom)
                ctx.socketFactory
            }

            override fun getDefaultCipherSuites(): Array<String> = baseFactory.defaultCipherSuites
            override fun getSupportedCipherSuites(): Array<String> = baseFactory.supportedCipherSuites

            private fun newFactory(): SSLSocketFactory {
                val ctx = SSLContext.getInstance("TLSv1.2")
                ctx.init(null, arrayOf<TrustManager>(systemTrustManager), secureRandom)
                return ctx.socketFactory
            }

            private fun configure(ssl: SSLSocket, host: String?): SSLSocket {
                ssl.enabledProtocols = arrayOf("TLSv1.2")
                if (!host.isNullOrEmpty()) {
                    try {
                        val m = ssl.javaClass.getMethod("setUseSessionTickets", Boolean::class.javaPrimitiveType)
                        m.invoke(ssl, false)
                    } catch (_: Throwable) {
                    }
                    try {
                        val m = ssl.javaClass.getMethod("setHostname", String::class.java)
                        m.invoke(ssl, host)
                    } catch (_: Throwable) {
                    }
                    try {
                        val params = ssl.sslParameters
                        params.serverNames = listOf(SNIHostName(host))
                        ssl.sslParameters = params
                    } catch (_: Throwable) {
                    }
                }
                return object : SSLSocket() {
                    override fun setSSLParameters(params: SSLParameters?) {
                        if (params != null && !host.isNullOrEmpty()) {
                            try {
                                params.serverNames = listOf(SNIHostName(host))
                            } catch (_: Throwable) {
                            }
                        }
                        ssl.sslParameters = params
                    }
                    override fun getSSLParameters(): SSLParameters = ssl.sslParameters
                    override fun setEnabledProtocols(protocols: Array<out String>?) {
                        ssl.enabledProtocols = arrayOf("TLSv1.2")
                    }
                    override fun getEnabledProtocols(): Array<String> = ssl.enabledProtocols
                    override fun getSupportedProtocols(): Array<String> = ssl.supportedProtocols
                    override fun getSupportedCipherSuites(): Array<String> = ssl.supportedCipherSuites
                    override fun getEnabledCipherSuites(): Array<String> = ssl.enabledCipherSuites
                    override fun setEnabledCipherSuites(suites: Array<out String>?) {
                        ssl.enabledCipherSuites = suites
                    }
                    override fun getSession(): SSLSession = ssl.session
                    override fun getHandshakeSession(): SSLSession? = ssl.handshakeSession
                    override fun addHandshakeCompletedListener(l: HandshakeCompletedListener?) =
                        ssl.addHandshakeCompletedListener(l)
                    override fun removeHandshakeCompletedListener(l: HandshakeCompletedListener?) =
                        ssl.removeHandshakeCompletedListener(l)
                    override fun startHandshake() = ssl.startHandshake()
                    override fun setUseClientMode(mode: Boolean) {
                        ssl.useClientMode = mode
                    }
                    override fun getUseClientMode(): Boolean = ssl.useClientMode
                    override fun setNeedClientAuth(need: Boolean) {
                        ssl.needClientAuth = need
                    }
                    override fun getNeedClientAuth(): Boolean = ssl.needClientAuth
                    override fun setWantClientAuth(want: Boolean) {
                        ssl.wantClientAuth = want
                    }
                    override fun getWantClientAuth(): Boolean = ssl.wantClientAuth
                    override fun setEnableSessionCreation(flag: Boolean) {
                        ssl.enableSessionCreation = flag
                    }
                    override fun getEnableSessionCreation(): Boolean = ssl.enableSessionCreation
                    override fun getApplicationProtocol(): String? = ssl.applicationProtocol
                    override fun getHandshakeApplicationProtocol(): String? = ssl.handshakeApplicationProtocol
                    override fun getInputStream() = ssl.inputStream
                    override fun getOutputStream() = ssl.outputStream
                    override fun close() = ssl.close()
                    override fun isClosed(): Boolean = ssl.isClosed
                    override fun isConnected(): Boolean = ssl.isConnected
                    override fun isBound(): Boolean = ssl.isBound
                    override fun isInputShutdown(): Boolean = ssl.isInputShutdown
                    override fun isOutputShutdown(): Boolean = ssl.isOutputShutdown
                    override fun setSoTimeout(timeout: Int) {
                        ssl.soTimeout = timeout
                    }
                    override fun getSoTimeout(): Int = ssl.soTimeout
                    override fun setTcpNoDelay(on: Boolean) {
                        ssl.tcpNoDelay = on
                    }
                    override fun getTcpNoDelay(): Boolean = ssl.tcpNoDelay
                    override fun setKeepAlive(on: Boolean) {
                        ssl.keepAlive = on
                    }
                    override fun getKeepAlive(): Boolean = ssl.keepAlive
                    override fun getInetAddress(): InetAddress? = ssl.inetAddress
                    override fun getLocalAddress(): InetAddress? = ssl.localAddress
                    override fun getPort(): Int = ssl.port
                    override fun getLocalPort(): Int = ssl.localPort
                    override fun getRemoteSocketAddress() = ssl.remoteSocketAddress
                    override fun getLocalSocketAddress() = ssl.localSocketAddress
                }
            }

            override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket {
                val ssl = newFactory().createSocket(s, host, port, autoClose) as SSLSocket
                return configure(ssl, host)
            }

            override fun createSocket(host: String?, port: Int): Socket {
                val ssl = newFactory().createSocket(host, port) as SSLSocket
                return configure(ssl, host)
            }

            override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket {
                val ssl = newFactory().createSocket(host, port, localHost, localPort) as SSLSocket
                return configure(ssl, host)
            }

            override fun createSocket(host: InetAddress?, port: Int): Socket {
                val ssl = newFactory().createSocket(host, port) as SSLSocket
                return configure(ssl, host?.hostName)
            }

            override fun createSocket(
                address: InetAddress?,
                port: Int,
                localAddress: InetAddress?,
                localPort: Int
            ): Socket {
                val ssl = newFactory().createSocket(address, port, localAddress, localPort) as SSLSocket
                return configure(ssl, address?.hostName)
            }
        }
    }

    fun createBingHttpClient(connectTimeoutSec: Long = 15, readTimeoutSec: Long = 15): OkHttpClient {
        val tls12Spec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .tlsVersions(TlsVersion.TLS_1_2)
            .build()
        return OkHttpClient.Builder()
            .sslSocketFactory(tls12NoSessionReuseSocketFactory, systemTrustManager)
            .connectionSpecs(listOf(tls12Spec))
            .connectTimeout(connectTimeoutSec, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
            .build()
    }

    private val syncHttpClient: OkHttpClient by lazy {
        createBingHttpClient(10, 10)
    }

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
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US)
        val daysOfWeek = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        val months = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        val dayOfWeek = daysOfWeek[cal.get(Calendar.DAY_OF_WEEK) - 1]
        val month = months[cal.get(Calendar.MONTH)]
        val day = cal.get(Calendar.DAY_OF_MONTH).toString()
        val year = cal.get(Calendar.YEAR).toString()
        val hour24 = cal.get(Calendar.HOUR_OF_DAY)
        var hour = hour24 % 12
        if (hour == 0) hour = 12
        val minute = String.format(Locale.US, "%02d", cal.get(Calendar.MINUTE))
        val second = String.format(Locale.US, "%02d", cal.get(Calendar.SECOND))
        val period = if (hour24 < 12) "AM" else "PM"
        return "$dayOfWeek, $month $day, $year, $hour:$minute:$second $period"
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
