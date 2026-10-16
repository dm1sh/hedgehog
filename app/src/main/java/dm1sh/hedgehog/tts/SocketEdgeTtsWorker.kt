package dm1sh.hedgehog.tts

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

object SocketEdgeTtsWorker {

    private val DATA_SEPARATOR = "Path:audio\r\n".toByteArray(Charsets.UTF_8)

    private val wsClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    suspend fun synthesizePart(
        voice: String,
        pitch: String,
        rate: String,
        volume: String = "+0%",
        text: String,
        lowBitrate: Boolean,
        timeoutMsec: Long,
        isPreview: Boolean = false,
        isPaused: () -> Boolean,
        isCancelled: () -> Boolean,
        onStatusUpdate: (String) -> Unit
    ): ByteArray? {
        while (!isCancelled()) {
            if (!isPreview && isPaused()) {
                onStatusUpdate("KEY_PAUSE")
                while (isPaused() && !isCancelled()) {
                    delay(100)
                }
                if (isCancelled()) return null
            }

            val audioBytes = attemptSingleWebSocketSynthesis(
                voice = voice,
                pitch = pitch,
                rate = rate,
                volume = volume,
                text = text,
                lowBitrate = lowBitrate,
                timeoutMsec = timeoutMsec,
                onStatusUpdate = onStatusUpdate
            )

            if (isCancelled()) return null

            if (audioBytes != null && audioBytes.isNotEmpty()) {
                SecMsGecGenerator.reportSuccess()
                return audioBytes
            }

            SecMsGecGenerator.reportError()
            onStatusUpdate("KEY_ERROR_RERUN")
            if (isPreview) {
                return null
            }
            delay(4000)
        }
        return null
    }

    private suspend fun attemptSingleWebSocketSynthesis(
        voice: String,
        pitch: String,
        rate: String,
        volume: String,
        text: String,
        lowBitrate: Boolean,
        timeoutMsec: Long,
        onStatusUpdate: (String) -> Unit
    ): ByteArray? {
        val secMsGec = SecMsGecGenerator.generateSecMsGec()
        val muid = SecMsGecGenerator.generateMuid()
        val connectId = SecMsGecGenerator.generateConnectId()
        val chromiumMajor = SecMsGecGenerator.CHROMIUM_FULL_VERSION.substringBefore(".")
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/$chromiumMajor.0.0.0 Safari/537.36 Edg/$chromiumMajor.0.0.0"

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
            .header("User-Agent", userAgent)
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cookie", "muid=$muid;")
            .build()

        val resultDeferred = CompletableDeferred<ByteArray?>()
        val audioStream = ByteArrayOutputStream()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onStatusUpdate("KEY_STARTED")
                val kbps = if (lowBitrate) "48" else "96"
                val myDate = SecMsGecGenerator.dateToString()

                val configMsg = "X-Timestamp:$myDate\r\n" +
                    "Content-Type:application/json; charset=utf-8\r\n" +
                    "Path:speech.config\r\n\r\n" +
                    "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{" +
                    "\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"true\"}," +
                    "\"outputFormat\":\"audio-24khz-${kbps}kbitrate-mono-mp3\"" +
                    "}}}}\r\n"
                webSocket.send(configMsg)

                val ssml = buildSsml(voice, pitch, rate, volume, text)
                val ssmlMsg = "X-RequestId:${SecMsGecGenerator.generateConnectId()}\r\n" +
                    "Content-Type:application/ssml+xml\r\n" +
                    "X-Timestamp:${myDate}Z\r\n" +
                    "Path:ssml\r\n\r\n" +
                    ssml
                webSocket.send(ssmlMsg)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("Path:turn.end")) {
                    val bytes = synchronized(audioStream) { audioStream.toByteArray() }
                    resultDeferred.complete(bytes.takeIf { it.isNotEmpty() })
                    webSocket.close(1000, "Normal closure")
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val arr = bytes.toByteArray()
                val idx = findSeparatorEndIndex(arr, DATA_SEPARATOR)
                if (idx != -1 && idx < arr.size) {
                    synchronized(audioStream) {
                        audioStream.write(arr, idx, arr.size - idx)
                    }
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
        }

        val ws = wsClient.newWebSocket(request, listener)
        return try {
            val effectiveTimeout = if (timeoutMsec <= 0L || timeoutMsec == Long.MAX_VALUE) {
                3600_000L
            } else {
                timeoutMsec
            }
            withTimeoutOrNull(effectiveTimeout) {
                resultDeferred.await()
            }.also {
                if (it == null) {
                    ws.cancel()
                }
            }
        } catch (e: CancellationException) {
            ws.cancel()
            throw e
        } catch (_: Exception) {
            ws.cancel()
            null
        }
    }

    private fun buildSsml(voice: String, pitch: String, rate: String, volume: String, text: String): String {
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>\n" +
            "<voice name='Microsoft Server Speech Text to Speech Voice ($voice)'>" +
            "<prosody pitch='$pitch' rate='$rate' volume='$volume'>\n" +
            "$text</prosody></voice></speak>"
    }

    private fun findSeparatorEndIndex(array: ByteArray, separator: ByteArray): Int {
        if (array.size < separator.size) return -1
        for (i in 0..(array.size - separator.size)) {
            var found = true
            for (j in separator.indices) {
                if (array[i + j] != separator[j]) {
                    found = false
                    break
                }
            }
            if (found) {
                return i + separator.size
            }
        }
        return -1
    }
}
