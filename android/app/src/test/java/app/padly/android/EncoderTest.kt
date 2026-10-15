package app.padly.android

import app.padly.android.proto.MouseButton
import app.padly.android.proto.PadlyMessage
import app.padly.android.proto.ServerMessage
import app.padly.android.transport.CryptoV2
import app.padly.android.transport.Encoder
import app.padly.android.transport.MessagePackCodec
import app.padly.android.transport.MsgPack
import app.padly.android.transport.SecureChannel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EncoderTest {

    @Test
    fun msgPackRoundTripMap() {
        val v: MsgPack = MsgPack.Map(
            listOf(
                MsgPack.Str("t") to MsgPack.Str("hello"),
                MsgPack.Str("ts") to MsgPack.UInt64(42u),
            ),
        )
        val bytes = MessagePackCodec.encode(v)
        val parsed = MessagePackCodec.decode(bytes)
        assertEquals(v, parsed)
    }

    @Test
    fun payloadEncodesCursorMove() {
        val payload = Encoder.payload(PadlyMessage.CursorMove(1.5f, -2.25f, 100u))
        val root = MessagePackCodec.decode(payload) as MsgPack.Map
        val t = root.pairs.first { (k, _) -> (k as MsgPack.Str).v == "t" }.second
        assertEquals("cursorMove", (t as MsgPack.Str).v)
    }

    @Test
    fun helloEIncludesPublicKey() {
        val pub = ByteArray(65) { 4 }
        val bytes = Encoder.helloE("dev-1", "Pixel", "0.1.0", listOf("gesture"), pub)
        val root = MessagePackCodec.decode(bytes) as MsgPack.Map
        val d = root.pairs.first { (k, _) -> (k as MsgPack.Str).v == "d" }.second as MsgPack.Map
        val sent = d.pairs.first { (k, _) -> (k as MsgPack.Str).v == "pub" }.second as MsgPack.Bin
        assertArrayEquals(pub, sent.v)
    }

    @Test
    fun parseServerPairFail() {
        val bytes = MessagePackCodec.encode(
            MsgPack.Map(
                listOf(
                    MsgPack.Str("t") to MsgPack.Str("pairFail"),
                    MsgPack.Str("d") to MsgPack.Map(
                        listOf(
                            MsgPack.Str("reason") to MsgPack.Str("bad-pin"),
                            MsgPack.Str("left") to MsgPack.UInt64(3u),
                        ),
                    ),
                ),
            ),
        )
        val msg = Encoder.parseServer(bytes) as ServerMessage.PairFail
        assertEquals("bad-pin", msg.reason)
        assertEquals(3L, msg.attemptsLeft)
    }

    // MARK: crypto

    @Test
    fun ecdhBothSidesDeriveSameKey() {
        val phone = CryptoV2.generateKeyPair()
        val mac = CryptoV2.generateKeyPair()
        val phonePub = CryptoV2.encodePublicKey(phone.public)
        val macPub = CryptoV2.encodePublicKey(mac.public)

        val a = CryptoV2.deriveSessionKey(phone.private, macPub, phonePub, macPub, ltk = null)
        val b = CryptoV2.deriveSessionKey(mac.private, phonePub, phonePub, macPub, ltk = null)
        assertArrayEquals(a, b)
        assertEquals(32, a.size)
    }

    @Test
    fun ltkChangesDerivedKey() {
        val phone = CryptoV2.generateKeyPair()
        val mac = CryptoV2.generateKeyPair()
        val phonePub = CryptoV2.encodePublicKey(phone.public)
        val macPub = CryptoV2.encodePublicKey(mac.public)

        val plain = CryptoV2.deriveSessionKey(phone.private, macPub, phonePub, macPub, ltk = null)
        val resumed = CryptoV2.deriveSessionKey(phone.private, macPub, phonePub, macPub, ltk = ByteArray(32) { 7 })
        assertNotEquals(plain.toList(), resumed.toList())
    }

    @Test
    fun sealedRoundTrip() {
        val key = ByteArray(32) { it.toByte() }
        val phone = SecureChannel(key, sendDirection = 0x01)
        val mac = SecureChannel(key, sendDirection = 0x02)

        val payload = "cursor dx=1.5".toByteArray()
        val frame = phone.seal(payload)
        // the mac-side channel that RECEIVES phone frames seals with 0x02 but
        // opens 0x01 frames — open() only checks version + seq, nonce comes
        // from the frame itself.
        val opened = mac.open(frame)
        assertArrayEquals(payload, opened)
    }

    @Test
    fun tamperedFrameRejected() {
        val key = ByteArray(32) { it.toByte() }
        val sender = SecureChannel(key, sendDirection = 0x01)
        val receiver = SecureChannel(key, sendDirection = 0x02)
        val frame = sender.seal("hello".toByteArray())
        frame[frame.size - 1] = (frame[frame.size - 1].toInt() xor 0xFF).toByte()
        var threw = false
        try {
            receiver.open(frame)
        } catch (_: Throwable) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun replayRejected() {
        val key = ByteArray(32) { it.toByte() }
        val sender = SecureChannel(key, sendDirection = 0x01)
        val receiver = SecureChannel(key, sendDirection = 0x02)
        val frame = sender.seal("once".toByteArray())
        receiver.open(frame)
        var threw = false
        try {
            receiver.open(frame)
        } catch (_: Throwable) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun pinProofDeterministic() {
        val key = ByteArray(32) { 3 }
        val a = CryptoV2.pinProof(key, "123456")
        val b = CryptoV2.pinProof(key, "123456")
        val c = CryptoV2.pinProof(key, "654321")
        assertArrayEquals(a, b)
        assertNotEquals(a.toList(), c.toList())
        assertEquals(32, a.size)
    }

    @Test
    fun publicKeyEncodeDecodeRoundTrip() {
        val kp = CryptoV2.generateKeyPair()
        val encoded = CryptoV2.encodePublicKey(kp.public)
        assertEquals(65, encoded.size)
        assertEquals(0x04.toByte(), encoded[0])
        val decoded = CryptoV2.decodePublicKey(encoded)
        assertArrayEquals(encoded, CryptoV2.encodePublicKey(decoded))
    }

    @Test
    fun clickPayloadEncodes() {
        val payload = Encoder.payload(PadlyMessage.CursorClick(MouseButton.Right, true, 7u))
        assertTrue(payload.isNotEmpty())
    }
}
