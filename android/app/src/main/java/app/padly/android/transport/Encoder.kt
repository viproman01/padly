package app.padly.android.transport

import app.padly.android.proto.PadlyMessage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.concurrent.atomic.AtomicLong

/**
 * Encodes [PadlyMessage] instances into framed wire bytes:
 *   ver(1) | seq(4 LE) | msgpack(payload) | hmac(32)
 */
class Encoder(initialSeq: Long = 1L) {
    private val seq = AtomicLong(initialSeq)
    private val version: Byte = 0x01

    fun encode(msg: PadlyMessage, hmacKey: ByteArray): ByteArray {
        val payload = MessagePackCodec.encode(toMsgPack(msg))
        val s = seq.getAndIncrement().toInt()
        val seqBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(s).array()
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(hmacKey, "HmacSHA256"))
        mac.update(byteArrayOf(version))
        mac.update(seqBytes)
        mac.update(payload)
        val hmac = mac.doFinal()
        return ByteArray(1 + 4 + payload.size + 32).also { out ->
            out[0] = version
            System.arraycopy(seqBytes, 0, out, 1, 4)
            System.arraycopy(payload, 0, out, 5, payload.size)
            System.arraycopy(hmac, 0, out, 5 + payload.size, 32)
        }
    }

    /** Parse server frame: verify HMAC, unpack msgpack, hand back the root MsgPack. */
    fun decode(frame: ByteArray, hmacKey: ByteArray): MsgPack {
        require(frame.size > 1 + 4 + 32) { "frame too short" }
        require(frame[0] == version) { "bad version" }
        val seqBytes = frame.copyOfRange(1, 5)
        val payload = frame.copyOfRange(5, frame.size - 32)
        val hmac = frame.copyOfRange(frame.size - 32, frame.size)

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(hmacKey, "HmacSHA256"))
        mac.update(byteArrayOf(version))
        mac.update(seqBytes)
        mac.update(payload)
        require(mac.doFinal().contentEqualsConstantTime(hmac)) { "bad hmac" }
        return MessagePackCodec.decode(payload)
    }

    private fun toMsgPack(msg: PadlyMessage): MsgPack {
        fun map(t: String, vararg pairs: Pair<String, MsgPack>): MsgPack = MsgPack.Map(
            listOf(
                MsgPack.Str("t") to MsgPack.Str(t),
                MsgPack.Str("d") to MsgPack.Map(pairs.map { (k, v) -> MsgPack.Str(k) to v }),
            ),
        )

        return when (msg) {
            is PadlyMessage.Hello -> map(
                "hello",
                "deviceId" to MsgPack.Str(msg.deviceId),
                "deviceName" to MsgPack.Str(msg.deviceName),
                "appVer" to MsgPack.Str(msg.appVer),
                "caps" to MsgPack.Arr(msg.capabilities.map { MsgPack.Str(it) }),
            )
            is PadlyMessage.PinAttempt -> map(
                "pin",
                "hash" to MsgPack.Bin(msg.pinHash),
                "salt" to MsgPack.Bin(msg.salt),
            )
            is PadlyMessage.Ping -> map("ping", "ts" to MsgPack.UInt64(msg.ts))
            is PadlyMessage.CursorMove -> map(
                "cursorMove",
                "dx" to MsgPack.Float32(msg.dx),
                "dy" to MsgPack.Float32(msg.dy),
                "ts" to MsgPack.UInt64(msg.ts),
            )
            is PadlyMessage.CursorClick -> map(
                "cursorClick",
                "btn" to MsgPack.UInt64(msg.button.code.toULong()),
                "down" to MsgPack.Bool(msg.down),
                "ts" to MsgPack.UInt64(msg.ts),
            )
            is PadlyMessage.Scroll -> map(
                "scroll",
                "dx" to MsgPack.Float32(msg.dx),
                "dy" to MsgPack.Float32(msg.dy),
                "momentum" to MsgPack.Bool(msg.momentum),
                "ts" to MsgPack.UInt64(msg.ts),
            )
            is PadlyMessage.GestureMsg -> map(
                "gesture",
                "kind" to MsgPack.UInt64(msg.kind.code.toULong()),
                "phase" to MsgPack.UInt64(msg.phase.code.toULong()),
                "data" to MsgPack.Bin(msg.data),
                "ts" to MsgPack.UInt64(msg.ts),
            )
            is PadlyMessage.Key -> map(
                "key",
                "code" to MsgPack.UInt64(msg.code.toULong()),
                "mods" to MsgPack.UInt64(msg.modifiers.toULong()),
                "down" to MsgPack.Bool(msg.down),
                "ts" to MsgPack.UInt64(msg.ts),
            )
            is PadlyMessage.Presenter -> {
                val pairs = mutableListOf(
                    "kind" to MsgPack.UInt64(msg.kind.code.toULong()),
                    "ts" to MsgPack.UInt64(msg.ts),
                )
                if (msg.x != null) pairs += "x" to MsgPack.Float32(msg.x)
                if (msg.y != null) pairs += "y" to MsgPack.Float32(msg.y)
                map("presenter", *pairs.toTypedArray())
            }
            is PadlyMessage.Bye -> map("bye", "reason" to MsgPack.Str(msg.reason))
        }
    }
}

private fun ByteArray.contentEqualsConstantTime(other: ByteArray): Boolean {
    if (size != other.size) return false
    var diff = 0
    for (i in indices) diff = diff or (this[i].toInt() xor other[i].toInt())
    return diff == 0
}
