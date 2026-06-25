package app.padly.android.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable

/**
 * Persists paired-Mac records (server id, cert fingerprint, hmac key) encrypted
 * on disk via Tink + the Android Keystore. Marked excluded from cloud backup.
 */
class SecretStore(context: Context) {
    private val masterKey: MasterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "padly_secret_store",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    @Serializable
    data class PairedMac(
        val serverId: String,
        val name: String,
        val ip: String,
        val port: Int,
        val certFingerprintSha256Hex: String,
        val hmacKeyB64: String,
        val lastSeenEpochMs: Long,
    )

    fun list(): List<PairedMac> {
        val raw = prefs.getString("paired", "[]") ?: "[]"
        return runCatching { json.decodeFromString<List<PairedMac>>(raw) }.getOrDefault(emptyList())
    }

    fun upsert(mac: PairedMac) {
        val next = list().filter { it.serverId != mac.serverId } + mac
        prefs.edit().putString("paired", json.encodeToString(next)).apply()
    }

    fun remove(serverId: String) {
        val next = list().filter { it.serverId != serverId }
        prefs.edit().putString("paired", json.encodeToString(next)).apply()
    }

    fun find(serverId: String): PairedMac? = list().firstOrNull { it.serverId == serverId }

    /** Generated once on first install, used as the device's identity across sessions. */
    fun deviceId(): String {
        val saved = prefs.getString("deviceId", null)
        if (saved != null) return saved
        val gen = java.util.UUID.randomUUID().toString()
        prefs.edit().putString("deviceId", gen).apply()
        return gen
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
