package io.github.thatsfguy.reticulum.android.storage

import android.content.Context
import android.util.Base64
import io.github.thatsfguy.reticulum.crypto.CryptoProvider
import io.github.thatsfguy.reticulum.crypto.IdentityVault
import io.github.thatsfguy.reticulum.transport.IfacKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Per-endpoint IFAC credentials for TCP attachments (SPEC §2.1 bit 7).
 *
 * Only the DERIVED 64-byte key is kept, sealed by the same Android Keystore
 * vault as the identity; the network name and passphrase are used once to
 * derive it and never written anywhere. Keyed by `host:port`, so switching
 * to a different node never applies another network's access code (on an
 * IFAC-less node every sealed packet would be dropped as a violation).
 *
 * The key is a network secret: anyone holding it can read and inject on that
 * network's interface. It never leaves the device — not in the .rmid
 * identity export, not in logs.
 */
class IfacStore(
    context: Context,
    private val crypto: CryptoProvider,
    private val vault: IdentityVault = AndroidKeystoreIdentityVault(),
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Endpoints with IFAC configured → tag size in bytes. For the UI. */
    private val _configured = MutableStateFlow(readConfigured())
    val configured: StateFlow<Map<String, Int>> = _configured.asStateFlow()

    suspend fun set(host: String, port: Int, networkName: String, passphrase: String, sizeBytes: Int) {
        require(sizeBytes in IfacKey.MIN_SIZE..IfacKey.MAX_SIZE)
        val key = IfacKey.derive(crypto, networkName, passphrase)
        try {
            val sealed = vault.seal(key)
            prefs.edit()
                .putString(endpoint(host, port), "$sizeBytes:" + Base64.encodeToString(sealed, Base64.NO_WRAP))
                .apply()
        } finally {
            key.fill(0)
        }
        _configured.value = readConfigured()
    }

    fun clear(host: String, port: Int) {
        prefs.edit().remove(endpoint(host, port)).apply()
        _configured.value = readConfigured()
    }

    /** The IFAC key for this endpoint, or null when it has none (or the
     *  sealed blob can no longer be opened — e.g. Keystore key lost). */
    suspend fun keyFor(host: String, port: Int): IfacKey? {
        val stored = prefs.getString(endpoint(host, port), null) ?: return null
        val size = stored.substringBefore(':').toIntOrNull() ?: return null
        val sealed = runCatching { Base64.decode(stored.substringAfter(':'), Base64.NO_WRAP) }.getOrNull()
            ?: return null
        val raw = runCatching { vault.unseal(sealed) }.getOrNull() ?: return null
        return try {
            IfacKey.fromKey(crypto, raw, size)
        } finally {
            raw.fill(0)
        }
    }

    private fun readConfigured(): Map<String, Int> =
        prefs.all.mapNotNull { (k, v) ->
            (v as? String)?.substringBefore(':')?.toIntOrNull()?.let { k to it }
        }.toMap()

    companion object {
        private const val PREFS = "reticulum_ifac"

        fun endpoint(host: String, port: Int): String = "${host.trim().lowercase()}:$port"
    }
}
