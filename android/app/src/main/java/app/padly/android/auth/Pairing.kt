package app.padly.android.auth

import app.padly.android.proto.PairingPayload

/**
 * Phone-side pairing helpers for protocol v2. The QR / deep link carries
 * connect coordinates only — no secrets. Channel security comes from the
 * ECDH handshake; the PIN proof binds it to the human at both screens
 * (see transport.CryptoV2.pinProof).
 */
object Pairing {
    /** Parse `padly://pair?v=2&ip=...&port=...&id=...&name=...`. */
    fun parseLink(link: String): PairingPayload? {
        val uri = runCatching { android.net.Uri.parse(link) }.getOrNull() ?: return null
        if (uri.scheme != "padly" || uri.host != "pair") return null
        val ip = uri.getQueryParameter("ip") ?: return null
        val port = uri.getQueryParameter("port")?.toIntOrNull() ?: return null
        val id = uri.getQueryParameter("id") ?: return null
        val name = uri.getQueryParameter("name") ?: "Mac"
        return PairingPayload(ip = ip, port = port, serverId = id, name = name)
    }
}
