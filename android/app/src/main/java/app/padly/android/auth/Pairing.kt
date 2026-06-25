package app.padly.android.auth

import android.util.Base64
import app.padly.android.proto.PadlyMessage
import app.padly.android.proto.PairingPayload
import app.padly.android.transport.Encoder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Stateless helpers for the phone side of pairing:
 * - parse a `padly://pair?...` deep link
 * - prepare a PinAttempt message (HMAC(pin, salt) with a fresh salt)
 * - turn the Mac's PairOk response into a [SecretStore.PairedMac] record
 */
object Pairing {
    private val random = SecureRandom()
    private val urlSafeMode = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP

    fun parseLink(link: String): PairingPayload? {
        val uri = runCatching { android.net.Uri.parse(link) }.getOrNull() ?: return null
        if (uri.scheme != "padly" || uri.host != "pair") return null
        val ip = uri.getQueryParameter("ip") ?: return null
        val port = uri.getQueryParameter("port")?.toIntOrNull() ?: return null
        val id = uri.getQueryParameter("id") ?: return null
        val fp = uri.getQueryParameter("fp") ?: return null
        val token = uri.getQueryParameter("t") ?: return null
        return PairingPayload(ip = ip, port = port, serverId = id, certFingerprint = fp, bootstrapToken = token)
    }

    fun bootstrapKey(payload: PairingPayload): ByteArray {
        return Base64.decode(payload.bootstrapToken, urlSafeMode)
    }

    fun buildPinAttempt(pin: String): PadlyMessage.PinAttempt {
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(pin.toByteArray(), "HmacSHA256"))
        mac.update(salt)
        val hash = mac.doFinal()
        return PadlyMessage.PinAttempt(pinHash = hash, salt = salt)
    }

    fun fingerprintMatches(server: PairingPayload, der: ByteArray): Boolean {
        val sha = MessageDigest.getInstance("SHA-256").digest(der)
        val hex = sha.joinToString("") { "%02x".format(it) }
        return hex.equals(server.certFingerprint, ignoreCase = true)
    }
}
