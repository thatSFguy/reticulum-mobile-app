package io.github.thatsfguy.reticulum.platform

import io.github.thatsfguy.reticulum.crypto.CryptoProvider
import io.github.thatsfguy.reticulum.crypto.IdentityVault
import io.github.thatsfguy.reticulum.transport.IfacKey
import io.github.thatsfguy.reticulum.transport.IfacTransport
import io.github.thatsfguy.reticulum.transport.Transport
import io.github.thatsfguy.reticulum.transport.hexToBytes
import io.github.thatsfguy.reticulum.transport.toHex
import platform.Foundation.NSUserDefaults

/**
 * iOS twin of Android's `IfacStore`: per-endpoint IFAC keys for TCP
 * (SPEC §2.1 bit 7).
 *
 * Only the DERIVED 64-byte key is kept, sealed by the same
 * [KeychainIdentityVault] as the identity (its master key lives in the
 * Keychain, this-device-only), stored in UserDefaults under
 * `ifac.<host>:<port>`. The network name and passphrase are used once to
 * derive it and never written anywhere. Keyed by endpoint so switching to
 * another node never applies this node's code.
 *
 * Swift sees the suspend functions as `async throws`.
 */
class IosIfacStore(
    private val crypto: CryptoProvider = IosCryptoProvider(),
    private val vault: IdentityVault = KeychainIdentityVault(crypto),
) {
    private val defaults = NSUserDefaults.standardUserDefaults

    @Throws(Exception::class)
    suspend fun save(host: String, port: Int, networkName: String, passphrase: String, sizeBytes: Int) {
        require(sizeBytes in IfacKey.MIN_SIZE..IfacKey.MAX_SIZE) { "IFAC size out of range" }
        val key = IfacKey.derive(crypto, networkName, passphrase)
        try {
            defaults.setObject("$sizeBytes:" + vault.seal(key).toHex(), forKey = prefKey(host, port))
        } finally {
            key.fill(0)
        }
    }

    fun clear(host: String, port: Int) {
        defaults.removeObjectForKey(prefKey(host, port))
    }

    /** Configured tag size in bits for this endpoint, or 0 when IFAC is off. */
    fun configuredBits(host: String, port: Int): Int =
        stored(host, port)?.first?.times(8) ?: 0

    /**
     * Wrap [transport] in IFAC when this endpoint has a key; otherwise return
     * it unchanged. [onViolation] gets a short reason per rejected inbound
     * frame (a wrong code shows up as every frame failing).
     */
    @Throws(Exception::class)
    suspend fun wrap(transport: Transport, host: String, port: Int, onViolation: (String) -> Unit): Transport {
        val (size, sealedHex) = stored(host, port) ?: return transport
        val raw = vault.unseal(sealedHex.hexToBytes())
        val key = try {
            IfacKey.fromKey(crypto, raw, size)
        } finally {
            raw.fill(0)
        }
        return IfacTransport(transport, key, onViolation)
    }

    private fun stored(host: String, port: Int): Pair<Int, String>? {
        val v = defaults.stringForKey(prefKey(host, port)) ?: return null
        val size = v.substringBefore(':').toIntOrNull() ?: return null
        return size to v.substringAfter(':')
    }

    private fun prefKey(host: String, port: Int) = "ifac.${host.trim().lowercase()}:$port"
}

/** Swift entry point — Kotlin default arguments aren't visible from Swift. */
fun createIosIfacStore(): IosIfacStore = IosIfacStore()
