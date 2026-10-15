package app.padly.android.transport

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Protocol v2 crypto — mirror of the Mac's CryptoLayer.swift:
 * - ephemeral P-256 ECDH, HKDF-SHA256 key derivation
 *   (salt "padly-pair-v2" when pairing, "padly-resume-v2" + ltk on reconnect)
 * - AES-256-GCM frames: ver(0x02) | seq(4 LE) | nonce(12) | ciphertext+tag
 *   AAD = ver||seq; strictly increasing seq per direction (replay guard).
 */
object CryptoV2 {
    const val VERSION: Byte = 0x02
    val PAIR_SALT = "padly-pair-v2".toByteArray()
    val RESUME_SALT = "padly-resume-v2".toByteArray()
    val PIN_CONTEXT = "padly-pin-v2".toByteArray()

    fun generateKeyPair(): KeyPair {
        val gen = KeyPairGenerator.getInstance("EC")
        gen.initialize(ECGenParameterSpec("secp256r1"))
        return gen.generateKeyPair()
    }

    /** Uncompressed X9.63 point: 0x04 || X(32) || Y(32) — 65 bytes. */
    fun encodePublicKey(pub: java.security.PublicKey): ByteArray {
        val point = (pub as ECPublicKey).w
        val x = point.affineX.toFixed32()
        val y = point.affineY.toFixed32()
        return byteArrayOf(0x04) + x + y
    }

    fun decodePublicKey(bytes: ByteArray): java.security.PublicKey {
        require(bytes.size == 65 && bytes[0] == 0x04.toByte()) { "bad public key" }
        val x = BigInteger(1, bytes.copyOfRange(1, 33))
        val y = BigInteger(1, bytes.copyOfRange(33, 65))
        val params = AlgorithmParameters.getInstance("EC").apply {
            init(ECGenParameterSpec("secp256r1"))
        }.getParameterSpec(ECParameterSpec::class.java)
        val spec = ECPublicKeySpec(ECPoint(x, y), params)
        return KeyFactory.getInstance("EC").generatePublic(spec)
    }

    /**
     * sessionKey = HKDF(ikm: ECDH(priv, peerPub), salt, info: clientPub || serverPub [|| ltk]).
     * The phone is always `clientPub`, the Mac `serverPub` — same order both sides.
     */
    fun deriveSessionKey(
        privateKey: java.security.PrivateKey,
        peerPublicKey: ByteArray,
        clientPub: ByteArray,
        serverPub: ByteArray,
        ltk: ByteArray?,
    ): ByteArray {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(decodePublicKey(peerPublicKey), true)
        val shared = agreement.generateSecret()

        val info = clientPub + serverPub + (ltk ?: ByteArray(0))
        val salt = if (ltk != null) RESUME_SALT else PAIR_SALT
        return hkdfSha256(ikm = shared, salt = salt, info = info, length = 32)
    }

    /** proof = HMAC-SHA256(sessionKey, "padly-pin-v2" || pin-utf8) */
    fun pinProof(sessionKey: ByteArray, pin: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(sessionKey, "HmacSHA256"))
        mac.update(PIN_CONTEXT)
        mac.update(pin.toByteArray())
        return mac.doFinal()
    }

    /** RFC 5869 HKDF-SHA256 (extract + expand). */
    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val extract = Mac.getInstance("HmacSHA256")
        extract.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = extract.doFinal(ikm)

        val out = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            val expand = Mac.getInstance("HmacSHA256")
            expand.init(SecretKeySpec(prk, "HmacSHA256"))
            expand.update(t)
            expand.update(info)
            expand.update(counter.toByte())
            t = expand.doFinal()
            val n = minOf(t.size, length - offset)
            System.arraycopy(t, 0, out, offset, n)
            offset += n
            counter++
        }
        return out
    }

    private fun BigInteger.toFixed32(): ByteArray {
        val raw = toByteArray()
        val out = ByteArray(32)
        when {
            raw.size == 32 -> return raw
            raw.size > 32 -> System.arraycopy(raw, raw.size - 32, out, 0, 32) // strip sign byte
            else -> System.arraycopy(raw, 0, out, 32 - raw.size, raw.size)
        }
        return out
    }
}

class CryptoException(message: String) : Exception(message)

/**
 * Sealed transport channel. The phone seals with direction 0x01 and opens
 * frames the Mac sealed with 0x02.
 */
class SecureChannel(private val key: ByteArray, private val sendDirection: Byte) {
    private var sendSeq: Long = 1
    private var lastRecvSeq: Long = 0

    fun seal(payload: ByteArray): ByteArray {
        val seq = sendSeq
        sendSeq += 1

        val seqBytes = byteArrayOf(
            (seq and 0xFF).toByte(),
            ((seq shr 8) and 0xFF).toByte(),
            ((seq shr 16) and 0xFF).toByte(),
            ((seq shr 24) and 0xFF).toByte(),
        )
        // Deterministic nonce: direction(1) || zero(3) || seq(4 LE) || zero(4).
        val nonce = ByteArray(12)
        nonce[0] = sendDirection
        System.arraycopy(seqBytes, 0, nonce, 4, 4)

        val aad = byteArrayOf(CryptoV2.VERSION) + seqBytes

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        val ciphertextAndTag = cipher.doFinal(payload)

        return byteArrayOf(CryptoV2.VERSION) + seqBytes + nonce + ciphertextAndTag
    }

    fun open(frame: ByteArray): ByteArray {
        // ver(1) + seq(4) + nonce(12) + tag(16) = 33 minimum.
        if (frame.size < 33) throw CryptoException("frame too short")
        if (frame[0] != CryptoV2.VERSION) throw CryptoException("bad version ${frame[0]}")

        val seqBytes = frame.copyOfRange(1, 5)
        val seq = (seqBytes[0].toLong() and 0xFF) or
            ((seqBytes[1].toLong() and 0xFF) shl 8) or
            ((seqBytes[2].toLong() and 0xFF) shl 16) or
            ((seqBytes[3].toLong() and 0xFF) shl 24)
        if (seq <= lastRecvSeq) throw CryptoException("replay: $seq after $lastRecvSeq")

        val nonce = frame.copyOfRange(5, 17)
        val ciphertextAndTag = frame.copyOfRange(17, frame.size)
        val aad = byteArrayOf(CryptoV2.VERSION) + seqBytes

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        val plain = try {
            cipher.doFinal(ciphertextAndTag)
        } catch (t: Throwable) {
            throw CryptoException("decrypt failed")
        }
        lastRecvSeq = seq
        return plain
    }
}
