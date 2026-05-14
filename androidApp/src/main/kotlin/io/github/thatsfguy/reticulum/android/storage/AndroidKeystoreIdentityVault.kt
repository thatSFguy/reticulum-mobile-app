package io.github.thatsfguy.reticulum.android.storage

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.thatsfguy.reticulum.crypto.IdentityVault
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore-backed [IdentityVault]. Wraps the Reticulum
 * identity private keys with an AES-256-GCM key that lives in the
 * TEE (or StrongBox where available); the key bytes never leave
 * secure hardware and cannot be cloned to another device.
 *
 * Audit reference: 2026-05-13 HIGH-1 follow-up.
 *
 * ## Properties
 *
 * - **Algorithm**: AES-256-GCM with a fresh 12-byte IV per [seal].
 *   AES-GCM authenticates the ciphertext, so a tampered row fails
 *   [unseal] with [javax.crypto.AEADBadTagException].
 * - **Key residency**: stored under the alias
 *   [KEY_ALIAS] inside the device's Android Keystore. With
 *   `setIsStrongBoxBacked(true)` requested where the device
 *   supports it (Pixel 3+, Galaxy S20+, etc.); falls back to the
 *   TEE on older / cheaper hardware. Either way the raw key bytes
 *   are non-extractable.
 * - **Authorisation**: `setUnlockedDeviceRequired(true)` (API 28+).
 *   The key is usable only while the device is unlocked. A
 *   forensic kit imaging a powered-on-but-locked phone cannot
 *   decrypt the rows. No per-decrypt biometric prompt — the UX
 *   is identical to pre-vault behaviour.
 * - **Invalidation**: on biometric enrollment changes the Keystore
 *   may invalidate this key (see `setInvalidatedByBiometricEnrollment`
 *   default behaviour). When that happens [unseal] throws — the
 *   repository surfaces a clear error so the user can re-import
 *   their `.rmid` backup instead of silently losing their identity.
 *
 * ## Wire format (output of [seal])
 *
 * ```
 *   iv_len(1)  iv(12)  ciphertext_with_tag(N + 16)
 * ```
 *
 * The auth tag is appended to the ciphertext by the JCA AES-GCM
 * implementation, so the stored BLOB is `1 + 12 + N + 16` bytes
 * for an N-byte plaintext (private keys are 32 bytes → 61 bytes).
 *
 * ## Lifecycle
 *
 * The wrapping key is created on first [seal] call (lazy). Once
 * created it persists across app reinstalls iff the app's package
 * signature matches (Android Keystore keys are scoped to the
 * package + signing key). A fresh install with a different signing
 * key cannot decrypt rows sealed by a previous install — same
 * shape as the .rmid passphrase: if you lose the key, you lose
 * the identity.
 */
internal class AndroidKeystoreIdentityVault : IdentityVault {

    override suspend fun seal(plaintext: ByteArray): ByteArray {
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        require(iv.size == IV_LEN) {
            "Unexpected GCM IV length ${iv.size}; expected $IV_LEN"
        }
        val ct = cipher.doFinal(plaintext)
        // Layout: iv_len(1) || iv(12) || ciphertext_with_tag(N+16)
        val out = ByteArray(1 + iv.size + ct.size)
        out[0] = iv.size.toByte()
        System.arraycopy(iv, 0, out, 1, iv.size)
        System.arraycopy(ct, 0, out, 1 + iv.size, ct.size)
        return out
    }

    override suspend fun unseal(sealed: ByteArray): ByteArray {
        require(sealed.size > 1 + IV_LEN + GCM_TAG_LEN / 8) {
            "Sealed blob too short: ${sealed.size} bytes"
        }
        val ivLen = sealed[0].toInt() and 0xFF
        require(ivLen == IV_LEN) {
            "Unexpected IV length $ivLen; expected $IV_LEN"
        }
        val iv = sealed.copyOfRange(1, 1 + ivLen)
        val ct = sealed.copyOfRange(1 + ivLen, sealed.size)

        val key = getKeyOrNull()
            ?: error(
                "Android Keystore alias '$KEY_ALIAS' missing — was the key " +
                "invalidated (biometric enrollment, device wipe)? Identity " +
                "is unrecoverable from this vault; re-import a .rmid backup."
            )
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LEN, iv))
        return cipher.doFinal(ct)
    }

    /**
     * Generate (or load) the AES-256 wrapping key with a progressive
     * fallback chain. Different Samsung / OnePlus / Pixel firmware
     * versions reject different combinations of `KeyGenParameterSpec`
     * options at `kg.init(spec)` time, and the failure mode is
     * usually a `ProviderException` or `IllegalStateException` with
     * a vendor-specific message — there's no clean error type to
     * branch on, so we just try strict → relaxed and stop at the
     * first one that survives.
     *
     * Real-world failures we've seen:
     *
     * - **A42 (Samsung, Android 11)**: rejected the strict spec.
     *   Suspected cause: `setUnlockedDeviceRequired(true)` requires
     *   a secure lock screen, and the device either had no PIN set
     *   or hit a Samsung firmware quirk on that constraint. Lifting
     *   that flag in tier 3 fixed it.
     *
     * - **Pixel 6a (StrongBox-capable)**: tier 1 worked.
     *
     * - **Older Galaxy A-series without StrongBox**: tier 1 threw
     *   `StrongBoxUnavailableException`; tier 2 worked.
     *
     * The tiers downgrade the on-device security guarantee but
     * never compromise the wire-format — the key is still in the
     * TEE on tiers 1-3. Tier 4 throws; the Repository catches and
     * falls back to plaintext-column storage (same threat model as
     * pre-1.1.27, app stays usable).
     */
    private fun getOrCreateKey(): SecretKey {
        getKeyOrNull()?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)

        val tiers = mutableListOf<KeyGenParameterSpec.Builder.() -> Unit>()
        // Tier 1: full strict spec. StrongBox + unlocked-device-required.
        tiers += {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                setUnlockedDeviceRequired(true)
                setIsStrongBoxBacked(true)
            }
        }
        // Tier 2: drop StrongBox (devices without a discrete SE
        // throw StrongBoxUnavailableException at kg.init time).
        // Keep unlocked-device-required.
        tiers += {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                setUnlockedDeviceRequired(true)
            }
        }
        // Tier 3: drop unlocked-device-required. Some Samsung
        // firmware quirks (and any device without a secure lock
        // screen set up) reject this constraint with a generic
        // ProviderException. Key still lives in the TEE; we just
        // lose the "locked phone in a forensic kit can't decrypt"
        // property. App-private-storage scoping + Auto Backup off
        // still apply.
        tiers += {
            // Minimal spec — no extra constraints. The
            // BLOCK_MODE_GCM / ENCRYPTION_PADDING_NONE / KEY_SIZE
            // are non-negotiable for AES-GCM correctness so they're
            // set on the base builder below.
        }

        var lastError: Throwable? = null
        for ((tierIndex, tierConfig) in tiers.withIndex()) {
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .apply(tierConfig)
                .build()
            try {
                kg.init(spec)
                return kg.generateKey()
            } catch (t: Throwable) {
                lastError = t
                // Try the next tier. We deliberately swallow the
                // stack here — the final-tier failure is surfaced
                // below with the accumulated lastError as cause.
            }
        }
        // Tier 4: all Keystore-backed tiers exhausted. Surface a
        // typed error the Repository recognises and degrades on.
        throw KeystoreUnavailableException(
            "Android Keystore rejected every key-spec tier on this device " +
                "(StrongBox / unlocked-device-required / minimal). The repository " +
                "will fall back to plaintext-column storage so the app stays " +
                "usable. Threat model degrades to pre-1.1.27 (FBE + app-private " +
                "storage + Auto Backup off, but no per-app key isolation).",
            lastError,
        )
    }

    private fun getKeyOrNull(): SecretKey? {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        return ks.getKey(KEY_ALIAS, null) as? SecretKey
    }

    companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "io.github.thatsfguy.reticulum.identity-vault.v1"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_LEN = 12       // GCM standard
        const val GCM_TAG_LEN = 128  // bits
    }
}

/**
 * Typed marker for "the AndroidKeystore-backed vault could not be
 * brought up on this device at all." Distinct from a per-seal /
 * per-unseal failure because the caller (the
 * [IdentityRepoImpl]) wants to silently degrade to legacy
 * plaintext-column storage rather than crashing the app. Audit
 * reference: 2026-05-13 HIGH-1 follow-up.
 */
internal class KeystoreUnavailableException(
    message: String,
    cause: Throwable?,
) : RuntimeException(message, cause) {
    init {
        // no extra state — exists for type-based exception handling
    }
}
