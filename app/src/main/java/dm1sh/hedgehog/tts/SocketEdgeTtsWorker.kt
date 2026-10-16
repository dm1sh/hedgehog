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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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
    private val AUDIO_SEP_BYTES = "Path:audio\r\n".toByteArray(Charsets.UTF_8)

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
            .connectTimeout(20, TimeUnit.SECONDS)
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
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>\n" +
            "<voice name='Microsoft Server Speech Text to Speech Voice ($voice)'>" +
            "<prosody pitch='$pitch' rate='$rate' volume='$volume'>\n" +
            "$text</prosody></voice></speak>"
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
        isPaused: () -> Boolean,
        isCancelled: () -> Boolean,
        onStatusUpdate: (String) -> Unit
    ): ByteArray? {
        if (text.trim().isEmpty()) {
            return ByteArray(0)
        }

        val audioFormat = if (lowBitrate) {
            "audio-24khz-48kbitrate-mono-mp3"
        } else {
            "audio-24khz-96kbitrate-mono-mp3"
        }

        while (coroutineContext.isActive && !isCancelled()) {
            if (!isPreview && isPaused()) {
                onStatusUpdate("KEY_PAUSE")
                while (isPaused() && coroutineContext.isActive && !isCancelled()) {
                    delay(100)
                }
                continue
            }

            val effectiveTimeout = when {
                isPreview -> PREVIEW_TIMEOUT_MSEC
                timeoutMsec <= 0L || timeoutMsec >= 10_000_000L -> DEFAULT_STALL_TIMEOUT_MSEC
                else -> timeoutMsec
            }

            val result = attemptSingleWebSocketSynthesis(
                voice = voice,
                pitch = pitch,
                rate = rate,
                text = text,
                audioFormat = audioFormat,
                effectiveTimeout = effectiveTimeout,
                isPreview = isPreview,
                isPaused = isPaused,
                isCancelled = isCancelled,
                onStatusUpdate = onStatusUpdate
            )

            if (result != null && result.isNotEmpty()) {
                SecMsGecGenerator.reportSuccess()
                return result
            }

            if (isCancelled() || !coroutineContext.isActive) {
                return null
            }

            if (!isPreview && isPaused()) {
                continue
            }

            SecMsGecGenerator.reportError()

            if (isPreview) {
                return null
            }

            onStatusUpdate("KEY_ERROR_RERUN")
            // Wait 4 seconds before retrying (matching SocketEdgeTTS.gd OS.delay_msec(4000))
            var waited = 0L
            while (waited < 4000L && coroutineContext.isActive && !isCancelled()) {
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
        isPaused: () -> Boolean,
        isCancelled: () -> Boolean,
        onStatusUpdate: (String) -> Unit
    ): ByteArray? {
        val resultDeferred = CompletableDeferred<ByteArray?>()
        val audioBuffer = ByteArrayOutputStream()
        val receivedFirstChunk = AtomicBoolean(false)
        val lastActivityTime = AtomicLong(System.currentTimeMillis())

        val secMsGec = SecMsGecGenerator.generateSecMsGec()
        val muid = SecMsGecGenerator.generateMuid()
        val connectId = SecMsGecGenerator.generateConnectId()

        val url = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1?" +
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
                val arr = bytes.toByteArray()
                val idx = findSubArray(arr, AUDIO_SEP_BYTES)
                if (idx >= 0) {
                    val payloadStart = idx + AUDIO_SEP_BYTES.size
                    if (payloadStart <= arr.size) {
                        if (receivedFirstChunk.compareAndSet(false, true) && !isPreview) {
                            onStatusUpdate("KEY_PROCESSING")
                        }
                        synchronized(audioBuffer) {
                            audioBuffer.write(arr, payloadStart, arr.size - payloadStart)
                        }
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
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
