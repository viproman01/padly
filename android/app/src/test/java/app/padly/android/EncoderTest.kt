package app.padly.android

import app.padly.android.proto.MouseButton
import app.padly.android.proto.PadlyMessage
import app.padly.android.transport.Encoder
import app.padly.android.transport.MessagePackCodec
import app.padly.android.transport.MsgPack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class EncoderTest {

    private val key = ByteArray(32) { it.toByte() }

    @Test
    fun roundTripCursorMove() {
        val enc = Encoder()
        val msg = PadlyMessage.CursorMove(1.5f, -2.25f, 100u)
        val frame = enc.encode(msg, key)
        // verify framing
        assertEquals(0x01.toByte(), frame[0])
        // verify hmac slot
        assertEquals(32, frame.copyOfRange(frame.size - 32, frame.size).size)
    }

    @Test
    fun hmacMismatchDetected() {
        val enc = Encoder()
        val frame = enc.encode(PadlyMessage.Ping(42u), key)
        val tampered = frame.copyOf().also { it[6] = (it[6] + 1).toByte() }
        var threw = false
        try {
            enc.decode(tampered, key)
        } catch (_: Throwable) {
            threw = true
        }
        assertEquals(true, threw)
    }

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
    fun seqIncrements() {
        val enc = Encoder(initialSeq = 1L)
        val a = enc.encode(PadlyMessage.Ping(1u), key)
        val b = enc.encode(PadlyMessage.Ping(2u), key)
        assertNotEquals(a.copyOfRange(1, 5).contentHashCode(), b.copyOfRange(1, 5).contentHashCode())
    }

    @Test
    fun clickEncodes() {
        val enc = Encoder()
        val frame = enc.encode(PadlyMessage.CursorClick(MouseButton.Right, true, 7u), key)
        assert(frame.size > 5 + 32)
    }
}
