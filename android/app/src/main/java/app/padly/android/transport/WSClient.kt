package app.padly.android.transport

import android.util.Log
import app.padly.android.proto.PadlyMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * WebSocket client to the Mac. Handles connection lifecycle, exponential
 * backoff reconnect, and pin-verifies the server certificate against a
 * SHA-256 fingerprint stored at pairing time.
 */
class WSClient(
    private val host: String,
    private val port: Int,
    private val certPinSha256Hex: String,
    private val hmacKey: ByteArray,
    private val encoder: Encoder = Encoder(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connection: WebSocket? = null
    private var reconnectJob: Job? = null

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 64)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    sealed interface Event {
        data object Connecting : Event
        data object Connected : Event
        data class Closed(val reason: String) : Event
        data class Error(val cause: Throwable) : Event
        data class Frame(val payload: MsgPack) : Event
    }

    fun connect() {
        scope.launch {
            connectOnce()
        }
    }

    private suspend fun connectOnce() {
        _events.emit(Event.Connecting)
        try {
            val client = OkHttpClient.Builder()
                .pingInterval(1, TimeUnit.SECONDS)
                .sslSocketFactory(pinningSocketFactory(), pinningTrustManager())
                .hostnameVerifier { _, _ -> true } // self-signed cert; pin checks identity
                .build()

            val req = Request.Builder().url("wss://$host:$port").build()
            val ws = client.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    scope.launch { _events.emit(Event.Connected) }
                }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    try {
                        val mp = encoder.decode(bytes.toByteArray(), hmacKey)
                        scope.launch { _events.emit(Event.Frame(mp)) }
                    } catch (t: Throwable) {
                        Log.w(TAG, "decode failed: $t")
                    }
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    scope.launch {
                        _events.emit(Event.Closed(reason))
                        scheduleReconnect()
                    }
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.w(TAG, "ws failure: $t")
                    scope.launch {
                        _events.emit(Event.Error(t))
                        scheduleReconnect()
                    }
                }
            })
            connection = ws
        } catch (t: Throwable) {
            _events.emit(Event.Error(t))
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            for (attempt in listOf(200L, 1000L, 3000L)) {
                delay(attempt)
                try {
                    connectOnce()
                    return@launch
                } catch (_: Throwable) {
                    // keep iterating
                }
            }
        }
    }

    fun send(msg: PadlyMessage): Boolean {
        val frame = encoder.encode(msg, hmacKey)
        return connection?.send(frame.toByteString(0, frame.size)) ?: false
    }

    fun close() {
        reconnectJob?.cancel()
        connection?.close(1000, "client-close")
        connection = null
    }

    // MARK: TLS pinning

    private fun pinningTrustManager(): X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val first = chain?.firstOrNull() ?: error("no peer cert")
            val der = first.encoded
            val sha = java.security.MessageDigest.getInstance("SHA-256").digest(der)
            val hex = sha.joinToString("") { "%02x".format(it) }
            require(hex.equals(certPinSha256Hex, ignoreCase = true)) {
                "cert pin mismatch: $hex vs $certPinSha256Hex"
            }
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private fun pinningSocketFactory() = SSLContext.getInstance("TLSv1.3").apply {
        init(null, arrayOf(pinningTrustManager()), java.security.SecureRandom())
    }.socketFactory

    private companion object {
        const val TAG = "Padly/WS"
    }
}
