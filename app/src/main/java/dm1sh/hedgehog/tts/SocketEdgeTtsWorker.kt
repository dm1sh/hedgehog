package dm1sh.hedgehog.tts

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import okhttp3.ConnectionSpec
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.TlsVersion
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

/**
 * Port of SocketEdgeTTS.gd:
 * Synthesizes a single text part via Microsoft Edge ReadAloud WebSocket endpoint
 * (wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1).
 * Automatically retries on transient failure or inactivity timeout while respecting pause/stop state.
 */
object SocketEdgeTtsWorker {
    private const val PREVIEW_TIMEOUT_MSEC = 30_000L
    private const val DEFAULT_STALL_TIMEOUT_MSEC = 60_000L

    /* Retry backoff: 4 s, 8 s, 16 s, capped at 30 s, ±20 % jitter. */
    private const val STREAM_RESTART_BASE_DELAY_MSEC = 4000L
    private const val STREAM_RESTART_MAX_DELAY_MSEC = 30_000L
    private const val PREVIEW_RESTART_DELAY_MSEC = 300L
    private const val BING_HOST = "speech.platform.bing.com"
    private const val BING_PORT = 443
    private val AUDIO_SEP_BYTES = "Path:audio\r\n".toByteArray(Charsets.UTF_8)

    private val probeExecutor = Executors.newCachedThreadPool()
    private val lastReachableCheckMsec = AtomicLong(0L)
    private val lastReachableResult = AtomicBoolean(true)

    /**
     * Verifies real TCP/DNS reachability to speech.platform.bing.com:443 with a hard timeout.
     * Handles the case where Internet is off while a VPN TUN interface is active and
     * Android's ConnectivityManager still reports NET_CAPABILITY_INTERNET = true.
     */
    fun isOnlineReachable(timeoutMs: Long = 2500L, forceRefresh: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        val elapsed = now - lastReachableCheckMsec.get()
        val cached = lastReachableResult.get()
        val ttl = if (cached) 8_000L else 4_000L
        if (!forceRefresh && lastReachableCheckMsec.get() > 0L && elapsed in 0..ttl) {
            return cached
        }
        val future = probeExecutor.submit<Boolean> {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(BING_HOST, BING_PORT), timeoutMs.toInt())
                    true
                }
            } catch (_: Throwable) {
                false
            }
        }
        val ok = try {
            future.get(timeoutMs + 200L, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
            future.cancel(true)
            false
        }
        lastReachableResult.set(ok)
        lastReachableCheckMsec.set(System.currentTimeMillis())
        return ok
    }

    /**
     * Idle (stall) timeout a stream is actually given for the configured [timeoutMsec]:
     * previews use 30 s, "no timeout" (<= 0) and the maximum setting (>= 10000 s) fall back
     * to 60 s. Both frontends share this so the CLI can report the value the app would use.
     */
    fun effectiveStallTimeoutMsec(timeoutMsec: Long, isPreview: Boolean = false): Long = when {
        isPreview -> PREVIEW_TIMEOUT_MSEC
        timeoutMsec <= 0L || timeoutMsec >= 10_000_000L -> DEFAULT_STALL_TIMEOUT_MSEC
        else -> timeoutMsec
    }

    /**
     * Exponential backoff with jitter for the next stream attempt.
     *
     * Dozens of parallel streams tend to fail together when the endpoint starts throttling
     * (the usual cause of mid-stream "Connection reset"). With a fixed delay they would all
     * reconnect in lockstep 4 s later and get reset again, so the delay grows and is spread out.
     */
    fun restartDelayMsec(failedAttempts: Int): Long {
        val exponent = (failedAttempts - 1).coerceIn(0, 3)
        val base = (STREAM_RESTART_BASE_DELAY_MSEC shl exponent).coerceAtMost(STREAM_RESTART_MAX_DELAY_MSEC)
        val jittered = (base * (0.8 + ThreadLocalRandom.current().nextDouble() * 0.4)).toLong()
        return jittered.coerceIn(1_000L, STREAM_RESTART_MAX_DELAY_MSEC)
    }

    private val wsClient: OkHttpClient by lazy {
        val tls12Spec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .tlsVersions(TlsVersion.TLS_1_2)
            .build()
        val dispatcher = Dispatcher().apply {
            maxRequests = 256
            maxRequestsPerHost = 256
        }
        OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .sslSocketFactory(
                SecMsGecGenerator.tls12NoSessionReuseSocketFactory,
                SecMsGecGenerator.systemTrustManager
            )
            .connectionSpecs(listOf(tls12Spec))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    fun mkssml(
        text: String,
        voice: String,
        rate: String,
        pitch: String,
        volume: String = "+0%"
    ): String {
        val escaped = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>\n" +
            "<voice name='Microsoft Server Speech Text to Speech Voice ($voice)'>" +
            "<prosody pitch='$pitch' rate='$rate' volume='$volume'>\n" +
            "$escaped</prosody></voice></speak>"
    }

    fun ssmlHeadersPlusData(requestId: String, timestamp: String, ssml: String): String {
        return "X-RequestId:$requestId\r\n" +
            "Content-Type:application/ssml+xml\r\n" +
            "X-Timestamp:${timestamp}Z\r\n" +
            "Path:ssml\r\n\r\n" +
            ssml
    }

    suspend fun synthesizePart(
        voice: String,
        pitch: String,
        rate: String,
        text: String,
        lowBitrate: Boolean,
        timeoutMsec: Long,
        isPreview: Boolean = false,
        fallbackToOfflineOnError: Boolean = false,
        isPaused: () -> Boolean,
        isCancelled: () -> Boolean,
        onStatusUpdate: (String) -> Unit,
        onDiagnostic: (String) -> Unit = {}
    ): ByteArray? {
        if (text.trim().isEmpty()) {
            return ByteArray(0)
        }

        val audioFormat = if (lowBitrate) {
            "audio-24khz-48kbitrate-mono-mp3"
        } else {
            "audio-24khz-96kbitrate-mono-mp3"
        }

        var failedAttempts = 0
        /* Last attempt's failure reason, reported once together with the retry decision. */
        val lastError = AtomicReference<String?>(null)

        while (coroutineContext.isActive && !isCancelled()) {
            if (!isPreview && isPaused()) {
                onStatusUpdate("KEY_PAUSE")
                while (isPaused() && coroutineContext.isActive && !isCancelled()) {
                    delay(100)
                }
                continue
            }

            val effectiveTimeout = effectiveStallTimeoutMsec(timeoutMsec, isPreview)

            val wsOpened = AtomicBoolean(false)
            val result = attemptSingleWebSocketSynthesis(
                voice = voice,
                pitch = pitch,
                rate = rate,
                text = text,
                audioFormat = audioFormat,
                effectiveTimeout = effectiveTimeout,
                isPreview = isPreview,
                wsOpened = wsOpened,
                isPaused = isPaused,
                isCancelled = isCancelled,
                onStatusUpdate = onStatusUpdate,
                lastError = lastError
            )

            if (result != null && result.isNotEmpty()) {
                lastReachableResult.set(true)
                lastReachableCheckMsec.set(System.currentTimeMillis())
                SecMsGecGenerator.reportSuccess()
                return result
            }

            if (isCancelled() || !coroutineContext.isActive) {
                return null
            }

            if (!isPreview && isPaused()) {
                continue
            }

            failedAttempts++
            SecMsGecGenerator.reportError(syncBlocking = failedAttempts == 1)

            val reason = lastError.getAndSet(null) ?: "stream ended without any audio"

            if (isPreview) {
                if (failedAttempts >= 2) {
                    onDiagnostic(reason)
                    return null
                }
                delay(PREVIEW_RESTART_DELAY_MSEC)
                continue
            }

            // If an offline fallback voice is available and the WebSocket could not connect
            // (e.g. internet is off while VPN is on), return null so caller falls back to offline TTS.
            if (fallbackToOfflineOnError && !wsOpened.get() && failedAttempts >= 2) {
                onDiagnostic("$reason; could not open $BING_HOST $failedAttempts time(s), switching to offline voice")
                return null
            }

            onStatusUpdate("KEY_ERROR_RERUN")
            val restartDelayMs = restartDelayMsec(failedAttempts)
            onDiagnostic(
                "$reason; retrying in " +
                    "${"%.1f".format(Locale.US, restartDelayMs / 1000.0)} s (attempt ${failedAttempts + 1})"
            )
            var waited = 0L
            while (waited < restartDelayMs && coroutineContext.isActive && !isCancelled()) {
                if (isPaused()) {
                    break
                }
                delay(200)
                waited += 200L
            }
        }
        return null
    }

    private suspend fun attemptSingleWebSocketSynthesis(
        voice: String,
        pitch: String,
        rate: String,
        text: String,
        audioFormat: String,
        effectiveTimeout: Long,
        isPreview: Boolean,
        wsOpened: AtomicBoolean,
        isPaused: () -> Boolean,
        isCancelled: () -> Boolean,
        onStatusUpdate: (String) -> Unit,
        lastError: AtomicReference<String?>
    ): ByteArray? {
        lastError.set(null)
        val resultDeferred = CompletableDeferred<ByteArray?>()
        val audioBuffer = ByteArrayOutputStream()
        val receivedFirstChunk = AtomicBoolean(false)
        val lastActivityTime = AtomicLong(System.currentTimeMillis())

        val secMsGec = SecMsGecGenerator.generateSecMsGec()
        val muid = SecMsGecGenerator.generateMuid()
        val connectId = SecMsGecGenerator.generateConnectId()

        val url = "wss://$BING_HOST/consumer/speech/synthesize/readaloud/edge/v1?" +
            "TrustedClientToken=${SecMsGecGenerator.TRUSTED_CLIENT_TOKEN}" +
            "&Sec-MS-GEC=$secMsGec" +
            "&Sec-MS-GEC-Version=${SecMsGecGenerator.SEC_MS_GEC_VERSION}" +
            "&ConnectionId=$connectId"

        val request = Request.Builder()
            .url(url)
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0"
            )
            .header("Accept-Encoding", "gzip, deflate, br")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cookie", "muid=$muid;")
            .build()

        val ws = wsClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                wsOpened.set(true)
                lastActivityTime.set(System.currentTimeMillis())
                if (isCancelled() || (!isPreview && isPaused())) {
                    webSocket.cancel()
                    resultDeferred.complete(null)
                    return
                }
                if (!isPreview) {
                    onStatusUpdate("KEY_STARTED")
                }
                val myDate = SecMsGecGenerator.dateToString()
                val configMsg = "X-Timestamp:$myDate\r\n" +
                    "Content-Type:application/json; charset=utf-8\r\n" +
                    "Path:speech.config\r\n\r\n" +
                    "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{" +
                    "\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"true\"}," +
                    "\"outputFormat\":\"$audioFormat\"" +
                    "}}}}\r\n"
                webSocket.send(configMsg)

                val ssml = mkssml(text = text, voice = voice, rate = rate, pitch = pitch, volume = "+0%")
                val ssmlMsg = ssmlHeadersPlusData(
                    requestId = SecMsGecGenerator.generateConnectId(),
                    timestamp = myDate,
                    ssml = ssml
                )
                webSocket.send(ssmlMsg)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                lastActivityTime.set(System.currentTimeMillis())
                if (isCancelled() || (!isPreview && isPaused())) {
                    webSocket.cancel()
                    resultDeferred.complete(null)
                    return
                }
                if (text.contains("Path:turn.end")) {
                    val bytes = synchronized(audioBuffer) { audioBuffer.toByteArray() }
                    if (bytes.isNotEmpty()) {
                        resultDeferred.complete(bytes)
                    } else {
                        lastError.set("server finished the stream without any audio")
                        resultDeferred.complete(null)
                    }
                    webSocket.close(1000, "Normal closure")
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                lastActivityTime.set(System.currentTimeMillis())
                if (isCancelled() || (!isPreview && isPaused())) {
                    webSocket.cancel()
                    resultDeferred.complete(null)
                    return
                }
                val idx = bytes.indexOf(AUDIO_SEP_BYTES)
                if (idx >= 0) {
                    val payloadStart = idx + AUDIO_SEP_BYTES.size
                    if (payloadStart <= bytes.size) {
                        if (receivedFirstChunk.compareAndSet(false, true) && !isPreview) {
                            onStatusUpdate("KEY_PROCESSING")
                        }
                        if (payloadStart < bytes.size) {
                            val payload = bytes.substring(payloadStart)
                            synchronized(audioBuffer) {
                                payload.write(audioBuffer)
                            }
                        }
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (code != 1000 && !isCancelled()) {
                    lastError.set("stream closed by server: code=$code${if (reason.isNotBlank()) " reason=$reason" else ""}")
                }
                webSocket.close(1000, null)
                if (!resultDeferred.isCompleted) {
                    resultDeferred.complete(null)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!resultDeferred.isCompleted) {
                    resultDeferred.complete(null)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!resultDeferred.isCompleted && !isCancelled()) {
                    val http = response?.let { " (HTTP ${it.code}${if (it.message.isNotBlank()) " ${it.message}" else ""})" }.orEmpty()
                    val msg = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
                    val kind = if (wsOpened.get()) "network error: stream failed" else "network error: connection failed"
                    lastError.set("$kind: $msg$http")
                }
                if (!resultDeferred.isCompleted) {
                    resultDeferred.complete(null)
                }
            }
        })

        try {
            while (!resultDeferred.isCompleted) {
                if (isCancelled() || !coroutineContext.isActive || (!isPreview && isPaused())) {
                    ws.cancel()
                    return null
                }
                val idleMs = System.currentTimeMillis() - lastActivityTime.get()
                if (idleMs > effectiveTimeout) {
                    lastError.set(
                        "timeout: no data from server for " +
                            "${"%.1f".format(Locale.US, idleMs / 1000.0)} s " +
                            "(limit ${"%.1f".format(Locale.US, effectiveTimeout / 1000.0)} s)"
                    )
                    ws.cancel()
                    return null
                }
                delay(100)
            }
            return resultDeferred.await()
        } finally {
            if (!resultDeferred.isCompleted) {
                ws.cancel()
            }
        }
    }

    private fun findSubArray(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        val limit = haystack.size - needle.size
        outer@ for (i in 0..limit) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
