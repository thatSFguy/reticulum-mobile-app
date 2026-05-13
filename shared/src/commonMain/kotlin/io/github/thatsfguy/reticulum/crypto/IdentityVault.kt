package io.github.thatsfguy.reticulum.crypto

/**
 * Wraps/unwraps the long-term Reticulum identity private keys
 * (X25519 encryption, Ed25519 signing, optional ratchet) using a
 * platform-bound encryption key that the app cannot exfiltrate.
 *
 * Threat addressed (audit 2026-05-13 HIGH-1 follow-up): identity
 * private keys were stored as plain BLOB rows in the Room /
 * SQLDelight database, under app-private storage. An attacker with
 * root, a forensic toolkit on an unlocked device, or malware running
 * as the app's UID could `cat` the database file and exfiltrate the
 * keys — and once they're out, the attacker can forge announces and
 * messages indistinguishable from the user forever (RNS identities
 * don't rotate automatically).
 *
 * Concrete implementations:
 *
 * - **Android**: `AndroidKeystoreIdentityVault` (in androidApp/storage)
 *   uses an Android Keystore-backed AES-256-GCM key. The wrapping
 *   key sits in the TEE (or StrongBox where available) and cannot
 *   be extracted to another device. With `setUnlockedDeviceRequired
 *   (true)` the key is usable only while the device is unlocked,
 *   so a powered-on-but-locked phone in a forensic kit can't decrypt
 *   the rows. No biometric prompt is required per-decrypt — the UX
 *   is identical to the pre-vault behaviour.
 *
 * - **iOS**: currently uses [PlaintextIdentityVault] — a deferred
 *   follow-up tracked in todo.md will swap in a Secure Enclave /
 *   Keychain implementation. The on-disk format is identical so
 *   existing rows migrate cleanly when iOS gains its real vault.
 *
 * - **Tests**: [PlaintextIdentityVault] also serves as the test
 *   stub. The vault contract is "round-trip preserves the
 *   plaintext"; the security guarantee differs between the
 *   pass-through and the Keystore-backed impl, but the engine
 *   doesn't need to care.
 *
 * ## Wire format (sealed bytes)
 *
 * Implementation-defined and opaque to callers. The Android Keystore
 * impl uses:
 *
 * ```
 * iv_len(1)  iv(12)  ciphertext(N)  auth_tag(16)
 * ```
 *
 * with AES-256-GCM. The pass-through impl returns the plaintext
 * unchanged. Either way, the byte string returned by [seal] is
 * what gets persisted; [unseal] is the only operation that recovers
 * the original plaintext.
 *
 * ## Failure semantics
 *
 * - [seal] may throw if the platform's key infrastructure is
 *   unavailable (e.g. Keystore lockup right after a device wipe).
 *   Callers should propagate the error; identity save aborts.
 * - [unseal] throws if the input is malformed, tampered, or
 *   sealed by a different vault (e.g. Keystore key got
 *   invalidated by a biometric-enrollment change). The repository
 *   surfaces this as an "identity unrecoverable" error so the user
 *   knows to re-import their `.rmid` backup. **Do NOT silently
 *   regenerate the identity on unseal failure** — that would create
 *   a new on-mesh identity without the user's awareness.
 */
interface IdentityVault {

    /**
     * Encrypt [plaintext] with the vault's platform-bound key,
     * returning an opaque byte string suitable for storage. The
     * caller persists this byte-for-byte and feeds it back to
     * [unseal] later.
     *
     * Each call produces a unique sealed output even for the same
     * input — implementations use a fresh IV per call. A persisted
     * BLOB is therefore not stable for byte-comparison after a
     * re-save; equality must be checked on the plaintext.
     */
    suspend fun seal(plaintext: ByteArray): ByteArray

    /**
     * Recover the original plaintext from a previously-[seal]ed
     * byte string. Throws if the input was sealed by a different
     * vault, is malformed, or has been tampered (the AEAD tag
     * mismatch surfaces here).
     */
    suspend fun unseal(sealed: ByteArray): ByteArray
}

/**
 * Pass-through vault used by iOS (until the Secure Enclave
 * implementation lands) and by unit tests. Provides no on-disk
 * protection — the "sealed" bytes are identical to the plaintext.
 * The interface contract still holds (round-trip preserves the
 * plaintext) so callers behave correctly; only the threat model
 * differs from a real vault.
 *
 * iOS callers SHOULD migrate to a Keychain / Secure Enclave-backed
 * implementation before the app ships through TestFlight or the App
 * Store — the current pass-through behaviour matches the pre-1.1.27
 * Android behaviour, which the audit flagged as HIGH-1. See
 * todo.md "Security audit follow-ups → iOS vault" for the tracking
 * entry.
 */
class PlaintextIdentityVault : IdentityVault {
    override suspend fun seal(plaintext: ByteArray): ByteArray = plaintext.copyOf()
    override suspend fun unseal(sealed: ByteArray): ByteArray = sealed.copyOf()
}
