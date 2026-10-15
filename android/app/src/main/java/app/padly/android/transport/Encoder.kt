package app.padly.android.transport

import app.padly.android.proto.PadlyMessage
import app.padly.android.proto.ServerMessage

/**
 * Converts [PadlyMessage] instances to MessagePack payload bytes and parses
 * the Mac's replies. Framing/encryption live in [SecureChannel]; the plaintext
 * handshake messages (helloE / welcomeE) are raw msgpack with no frame header.
 */
object Encoder {
    // MARK: outbound

    /** Plaintext handshake hello: identity + ephemeral public key. */
    fun helloE(
        deviceId: String,
        deviceName: String,
        appVer: String,
        capabilities: List<String>,
        publicKey: ByteArray,
    ): ByteArray = MessagePackCodec.encode(
        MsgPack.Map(
            listOf(
                MsgPack.Str("t") to MsgPack.Str("helloE"),
                MsgPack.Str("d") to MsgPack.Map(
                    listOf(
                        MsgPack.Str("deviceId") to MsgPack.Str(deviceId),
                        MsgPack.Str("deviceName") to MsgPack.Str(deviceName),
                        MsgPack.Str("appVer") to MsgPack.Str(appVer),
                        MsgPack.Str("caps") to MsgPack.Arr(capabilities.map { MsgPack.Str(it) }),
                        MsgPack.Str("pub") to MsgPack.Bin(publicKey),
                    ),
                ),
            ),
        ),
    )

    /** Post-handshake message → msgpack payload (caller seals it). */
    fun payload(msg: PadlyMessage): ByteArray = MessagePackCodec.encode(toMsgPack(msg))

    private fun toMsgPack(msg: PadlyMessage): MsgPack {
        fun map(t: String, vararg pairs: Pair<String, MsgPack>): MsgPack = MsgPack.Map(
            listOf(
                MsgPack.Str("t") to MsgPack.Str(t),
                MsgPack.Str("d") to MsgPack.Map(pairs.map { (k, v) -> MsgPack.Str(k) to v }),
            ),
        )

        return when (msg) {
            is PadlyMessage.PinProof -> map(
                "pin",
                "proof" to MsgPack.Bin(msg.proof),
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
            is PadlyMessage.Text -> map(
                "text",
                "s" to MsgPack.Str(msg.text),
                "ts" to MsgPack.UInt64(msg.ts),
            )
            is PadlyMessage.Presenter -> {
                val pairs = mutableListOf<Pair<String, MsgPack>>(
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

    // MARK: inbound

    /** Parse any server message (plaintext welcomeE or a decrypted payload). */
    fun parseServer(bytes: ByteArray): ServerMessage? {
        val root = runCatching { MessagePackCodec.decode(bytes) }.getOrNull() ?: return null
        val pairs = (root as? MsgPack.Map)?.pairs ?: return null
        val t = str(pairs, "t") ?: return null
        val d = (value(pairs, "d") as? MsgPack.Map)?.pairs ?: emptyList()

        return when (t) {
            "welcomeE" -> {
                val pub = bin(d, "pub") ?: return null
                ServerMessage.WelcomeE(
                    serverId = str(d, "serverId") ?: "",
                    name = str(d, "name") ?: "Mac",
                    serverVer = str(d, "serverVer") ?: "",
                    publicKey = pub,
                    known = bool(d, "known") ?: false,
                )
            }
            "welcome" -> ServerMessage.Welcome(
                accepted = bool(d, "accepted") ?: false,
                requirePin = bool(d, "requirePin") ?: false,
            )
            "pairOk" -> ServerMessage.PairOk(ltk = bin(d, "ltk") ?: return null)
            "pairFail" -> ServerMessage.PairFail(
                reason = str(d, "reason") ?: "unknown",
                attemptsLeft = uint(d, "left") ?: 0,
            )
            "pong" -> ServerMessage.Pong(
                ts = (uint(d, "ts") ?: 0).toULong(),
                serverTs = (uint(d, "serverTs") ?: 0).toULong(),
            )
            "bye" -> ServerMessage.Bye(reason = str(d, "reason") ?: "")
            else -> null
        }
    }

    private fun value(pairs: List<Pair<MsgPack, MsgPack>>, key: String): MsgPack? =
        pairs.firstOrNull { (k, _) -> (k as? MsgPack.Str)?.v == key }?.second

    private fun str(pairs: List<Pair<MsgPack, MsgPack>>, key: String): String? =
        (value(pairs, key) as? MsgPack.Str)?.v

    private fun bool(pairs: List<Pair<MsgPack, MsgPack>>, key: String): Boolean? =
        (value(pairs, key) as? MsgPack.Bool)?.v

    private fun bin(pairs: List<Pair<MsgPack, MsgPack>>, key: String): ByteArray? =
        (value(pairs, key) as? MsgPack.Bin)?.v

    private fun uint(pairs: List<Pair<MsgPack, MsgPack>>, key: String): Long? =
        when (val v = value(pairs, key)) {
            is MsgPack.UInt64 -> v.v.toLong()
            is MsgPack.Int64 -> v.v
            else -> null
        }
}
