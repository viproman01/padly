package app.padly.android.transport

import android.util.Log
import app.padly.android.proto.PadlyMessage
import app.padly.android.proto.ServerMessage
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.security.KeyPair
import java.util.concurrent.TimeUnit

/**
 * WebSocket client speaking protocol v2:
 *   1. open plain ws:// and send helloE (identity + ephemeral P-256 key)
 *   2. receive welcomeE, run ECDH → session key
 *      - known device: channel is live, wait for sealed welcome{accepted}
 *      - new device:   emit NeedPin, caller supplies the PIN from the Mac's
 *        screen via [submitPin]; on pairOk persist the ltk via [onPaired]
 *   3. all subsequent traffic is AES-GCM sealed frames
 */
class WSClient(
    private val host: String,
    private val port: Int,
    private val deviceId: String,
    private val deviceName: String,
    private val appVer: String = "0.1.0",
    /** Stored long-term key when this Mac was paired before, else null. */
    private val ltk: ByteArray?,
    /** Called on successful pairing so the caller can persist the new ltk. */
    private val onPaired: ((serverId: String, name: String, ltk: ByteArray) -> Unit)? = null,
) {
    // An exception in a connect attempt must never take the whole app down.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t -> Log.w(TAG, "ws job failed: $t") },
    )
    private var connection: WebSocket? = null
    private var reconnectJob: Job? = null
    private var shouldReconnect = true
    private var reconnectAttempt = 0

    // 3 s WebSocket pings: a dead Mac/WiFi is noticed quickly, while a
    // power-saving phone radio still has time to answer (1 s was too tight).
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .pingInterval(3, TimeUnit.SECONDS)
            .connectTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    private var keyPair: KeyPair? = null
    private var sessionKey: ByteArray? = null
    private var sendChannel: SecureChannel? = null
    private var recvChannel: SecureChannel? = null
    private var serverId: String = ""
    private var serverName: String = "Mac"

    sealed interface State {
        data object Idle : State
        data object Connecting : State
        data object Handshaking : State
        /** New Mac — the user must type the PIN shown on its screen. */
        data class NeedPin(val macName: String) : State
        data class PinRejected(val attemptsLeft: Long) : State
        data class Ready(val macName: String) : State
        data class Failed(val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    fun connect() {
        shouldReconnect = true
        scope.launch { connectOnce() }
    }

    private fun connectOnce() {
        _state.value = State.Connecting
        sessionKey = null
        sendChannel = null
        recvChannel = null

        val req = try {
            Request.Builder().url("ws://$host:$port").build()
        } catch (e: IllegalArgumentException) {
            shouldReconnect = false
            _state.value = State.Failed("bad address $host:$port")
            return
        }
        connection = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _state.value = State.Handshaking
                val kp = CryptoV2.generateKeyPair()
                keyPair = kp
                val hello = Encoder.helloE(
                    deviceId = deviceId,
                    deviceName = deviceName,
                    appVer = appVer,
                    capabilities = listOf("gesture", "key", "presenter"),
                    publicKey = CryptoV2.encodePublicKey(kp.public),
                )
                webSocket.send(hello.toByteString(0, hello.size))
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleInbound(bytes.toByteArray())
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "closed: $reason")
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "ws failure: $t")
                _state.value = State.Failed(t.message ?: "connection failed")
                scheduleReconnect()
            }
        })
    }

    private fun handleInbound(bytes: ByteArray) {
        val recv = recvChannel
        if (recv == null) {
            handleWelcomeE(bytes)
            return
        }
        val plain = try {
            recv.open(bytes)
        } catch (t: Throwable) {
            Log.w(TAG, "frame rejected: $t")
            return
        }
        when (val msg = Encoder.parseServer(plain)) {
            is ServerMessage.Welcome -> {
                if (msg.accepted && !msg.requirePin) {
                    reconnectAttempt = 0
                    _state.value = State.Ready(serverName)
                } else if (msg.requirePin) {
                    _state.value = State.NeedPin(serverName)
                } else {
                    _state.value = State.Failed("rejected by Mac")
                    shouldReconnect = false
                    connection?.close(1000, "rejected")
                }
            }
            is ServerMessage.PairOk -> {
                onPaired?.invoke(serverId, serverName, msg.ltk)
                reconnectAttempt = 0
                _state.value = State.Ready(serverName)
            }
            is ServerMessage.PairFail -> {
                if (msg.attemptsLeft > 0) {
                    _state.value = State.PinRejected(msg.attemptsLeft)
                } else {
                    _state.value = State.Failed("pairing failed: ${msg.reason}")
                    shouldReconnect = false
                }
            }
            is ServerMessage.Pong -> { /* latency bookkeeping hook */ }
            is ServerMessage.Bye -> {
                _state.value = State.Failed("Mac closed: ${msg.reason}")
                connection?.close(1000, "bye")
            }
            is ServerMessage.WelcomeE, null -> {
                Log.w(TAG, "unexpected message on sealed channel")
            }
        }
    }

    private fun handleWelcomeE(bytes: ByteArray) {
        val kp = keyPair ?: return
        val welcome = Encoder.parseServer(bytes) as? ServerMessage.WelcomeE
        if (welcome == null) {
            Log.w(TAG, "handshake: bad welcomeE")
            _state.value = State.Failed("bad handshake reply")
            connection?.close(1002, "bad-welcomeE")
            return
        }
        serverId = welcome.serverId
        serverName = welcome.name

        val myPub = CryptoV2.encodePublicKey(kp.public)
        // Mac derives with our stored ltk only when it knows this deviceId.
        val useLtk = if (welcome.known) ltk else null
        val key = try {
            CryptoV2.deriveSessionKey(
                privateKey = kp.private,
                peerPublicKey = welcome.publicKey,
                clientPub = myPub,
                serverPub = welcome.publicKey,
                ltk = useLtk,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "handshake: derive failed: $t")
            _state.value = State.Failed("key derivation failed")
            connection?.close(1002, "derive-failed")
            return
        }
        sessionKey = key
        sendChannel = SecureChannel(key, sendDirection = 0x01)
        recvChannel = SecureChannel(key, sendDirection = 0x02)
        // Next inbound frame: sealed welcome (resume) or welcome{requirePin} (pairing).
    }

    /** Called by the pairing UI with the 6-digit PIN from the Mac's screen. */
    fun submitPin(pin: String) {
        val key = sessionKey ?: return
        send(PadlyMessage.PinProof(CryptoV2.pinProof(key, pin)))
    }

    fun send(msg: PadlyMessage): Boolean {
        val channel = sendChannel ?: return false
        val conn = connection ?: return false
        val frame = try {
            channel.seal(Encoder.payload(msg))
        } catch (t: Throwable) {
            Log.w(TAG, "seal failed: $t")
            return false
        }
        return conn.send(frame.toByteString(0, frame.size))
    }

    /** Retry forever with backoff 0.2 s → 1 s → 3 s (capped); reset once Ready. */
    private fun scheduleReconnect() {
        if (!shouldReconnect) return
        reconnectJob?.cancel()
        val backoff = listOf(200L, 1000L, 3000L)[reconnectAttempt.coerceAtMost(2)]
        reconnectAttempt++
        reconnectJob = scope.launch {
            delay(backoff)
            if (shouldReconnect) connectOnce()
        }
    }

    fun close() {
        shouldReconnect = false
        reconnectJob?.cancel()
        connection?.close(1000, "client-close")
        connection = null
        _state.value = State.Idle
    }

    private companion object {
        const val TAG = "Padly/WS"
    }
}
