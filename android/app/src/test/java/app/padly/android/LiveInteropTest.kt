package app.padly.android

import app.padly.android.proto.PadlyMessage
import app.padly.android.transport.WSClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

/**
 * Cross-language interop check: the real Kotlin WSClient (OkHttp + CryptoV2)
 * against a live Swift Padly server. Skipped unless PADLY_E2E_PORT is set.
 *
 *   PADLY_EPHEMERAL_KEYSTORE=1 mac/.build/debug/Padly --headless   # prints PIN
 *   PADLY_E2E_HOST=127.0.0.1 PADLY_E2E_PORT=47821 PADLY_E2E_PIN=<pin> \
 *     gradle :app:testDebugUnitTest --tests '*LiveInteropTest*' --rerun
 *
 * Only pings are sent — no cursor/key traffic that could touch the host UI.
 */
class LiveInteropTest {
    private val host = System.getenv("PADLY_E2E_HOST") ?: "127.0.0.1"
    private val port = System.getenv("PADLY_E2E_PORT")?.toIntOrNull()
    private val pin = System.getenv("PADLY_E2E_PIN")

    @Test
    fun pairThenResumeAgainstSwiftServer() = runBlocking {
        assumeTrue("PADLY_E2E_PORT not set — live test skipped", port != null && pin != null)
        val deviceId = "jvm-${UUID.randomUUID()}"
        var ltk: ByteArray? = null

        // 1. wrong PIN is rejected with attempts left, right PIN pairs
        val pairing = WSClient(host, port!!, deviceId, "JVM interop", ltk = null,
            onPaired = { _, _, key -> ltk = key })
        pairing.connect()
        withTimeout(10_000) { pairing.state.first { it is WSClient.State.NeedPin } }

        pairing.submitPin(if (pin == "000000") "111111" else "000000")
        val rejected = withTimeout(10_000) {
            pairing.state.first { it is WSClient.State.PinRejected } as WSClient.State.PinRejected
        }
        assertEquals(4L, rejected.attemptsLeft)

        pairing.submitPin(pin!!)
        withTimeout(10_000) { pairing.state.first { it is WSClient.State.Ready } }
        assertEquals(32, ltk?.size)
        assertTrue(pairing.send(PadlyMessage.Ping(1u)))
        pairing.close()

        // 2. reconnect with the stored ltk: no PIN, straight to Ready
        val resumed = WSClient(host, port, deviceId, "JVM interop", ltk = ltk)
        resumed.connect()
        withTimeout(10_000) { resumed.state.first { it is WSClient.State.Ready } }
        assertTrue(resumed.send(PadlyMessage.Ping(2u)))
        resumed.close()
    }
}
