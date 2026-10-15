package app.padly.android.proto

import kotlinx.serialization.Serializable

/**
 * Wire-protocol message shapes. Each is a sealed branch of [PadlyMessage].
 * The actual MessagePack encoding lives in [app.padly.android.transport.Encoder];
 * these classes are just the in-process representation.
 */
sealed interface PadlyMessage {
    val ts: ULong

    /** Encrypted pin proof: HMAC-SHA256(sessionKey, "padly-pin-v2" || pin). */
    data class PinProof(
        val proof: ByteArray,
        override val ts: ULong = 0u,
    ) : PadlyMessage {
        override fun equals(other: Any?): Boolean = other is PinProof &&
            proof.contentEquals(other.proof) && ts == other.ts
        override fun hashCode(): Int = proof.contentHashCode()
    }

    data class Ping(override val ts: ULong) : PadlyMessage

    data class CursorMove(val dx: Float, val dy: Float, override val ts: ULong) : PadlyMessage
    data class CursorClick(val button: MouseButton, val down: Boolean, override val ts: ULong) : PadlyMessage
    data class Scroll(val dx: Float, val dy: Float, val momentum: Boolean, override val ts: ULong) : PadlyMessage
    data class GestureMsg(val kind: GestureKind, val phase: GesturePhase, val data: ByteArray, override val ts: ULong) : PadlyMessage {
        override fun equals(other: Any?): Boolean = other is GestureMsg && kind == other.kind && phase == other.phase && data.contentEquals(other.data) && ts == other.ts
        override fun hashCode(): Int = kind.hashCode() * 31 + phase.hashCode()
    }
    data class Key(val code: UShort, val modifiers: UByte, val down: Boolean, override val ts: ULong) : PadlyMessage
    /** Literal text typed on the Mac as Unicode — any language, independent of the Mac's keyboard layout. */
    data class Text(val text: String, override val ts: ULong) : PadlyMessage
    data class Presenter(val kind: PresenterKind, val x: Float?, val y: Float?, override val ts: ULong) : PadlyMessage
    data class Bye(val reason: String, override val ts: ULong = 0u) : PadlyMessage
}

enum class MouseButton(val code: UByte) { Left(0u), Right(1u), Middle(2u) }

enum class GesturePhase(val code: UByte) { Begin(0u), Update(1u), End(2u) }

enum class GestureKind(val code: UByte) {
    PinchZoom(0u), Rotate(1u),
    Swipe3Up(2u), Swipe3Down(3u), Swipe3Left(4u), Swipe3Right(5u),
    Swipe4Up(6u), Swipe4Down(7u), Swipe4Left(8u), Swipe4Right(9u),
    Spread4(10u), Pinch4(11u),
}

enum class PresenterKind(val code: UByte) {
    Next(0u), Prev(1u), Black(2u), White(3u), Pointer(4u), Stop(5u),
}

@Serializable
data class PairingPayload(
    val ip: String,
    val port: Int,
    val serverId: String,
    val name: String = "Mac",
)

/** Inbound messages the Mac sends to the phone. */
sealed interface ServerMessage {
    /** Plaintext handshake reply: Mac identity + its ephemeral public key. */
    data class WelcomeE(
        val serverId: String,
        val name: String,
        val serverVer: String,
        val publicKey: ByteArray,
        val known: Boolean,
    ) : ServerMessage {
        override fun equals(other: Any?): Boolean = other is WelcomeE &&
            serverId == other.serverId && publicKey.contentEquals(other.publicKey)
        override fun hashCode(): Int = serverId.hashCode()
    }

    data class Welcome(val accepted: Boolean, val requirePin: Boolean) : ServerMessage
    data class PairOk(val ltk: ByteArray) : ServerMessage {
        override fun equals(other: Any?): Boolean = other is PairOk && ltk.contentEquals(other.ltk)
        override fun hashCode(): Int = ltk.contentHashCode()
    }
    data class PairFail(val reason: String, val attemptsLeft: Long) : ServerMessage
    data class Pong(val ts: ULong, val serverTs: ULong) : ServerMessage
    data class Bye(val reason: String) : ServerMessage
}

object ModifierBits {
    const val CMD: UByte = 1u
    const val OPT: UByte = 2u
    const val CTRL: UByte = 4u
    const val SHIFT: UByte = 8u
    const val FN: UByte = 16u
}
