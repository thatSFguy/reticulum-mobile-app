package io.github.thatsfguy.reticulum.platform

import io.github.thatsfguy.reticulum.crypto.CryptoProvider
import io.github.thatsfguy.reticulum.crypto.IdentityVault
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned

/**
 * iOS [IdentityVault] backed by an AES master key in the Keychain.
 *
 * This is the iOS counterpart to Android's `AndroidKeystoreIdentityVault`
 * and closes the deferred half of audit 2026-05-13 HIGH-1: previously iOS
 * used [io.github.thatsfguy.reticulum.crypto.PlaintextIdentityVault], so
 * the long-term Reticulum identity private keys sat as raw BLOBs in the
 * app-private SQLite file — a forensic image / root exfiltration of that
 * one file handed an attacker the keys, and an RNS identity can then forge
 * announces and messages forever.
 *
 * ## Construction
 *
 * - A single random 32-byte master key lives in the iOS Keychain under
 *   `kSecAttrAccessibleWhenUnlockedThisDeviceOnly` (get-or-create via the
 *   `rcr_keychain_get_or_create_key` Swift bridge). It is readable only
 *   while the device is unlocked and never syncs to iCloud Keychain or
 *   restores onto another device — so it is *not* present in the same
 *   exfiltratable surface as the database file.
 * - From that master key we derive `encKey(32) || macKey(32)` with
 *   HKDF-SHA256 (RFC 5869), domain-separated by [HKDF_INFO].
 * - [seal] = AES-256-CBC then HMAC-SHA256 (encrypt-then-MAC), reusing the
 *   already-shipping CommonCrypto primitives in [IosCryptoProvider]. This
 *   is the same authenticated-encryption shape Reticulum's own Token uses,
 *   so we lean on proven code rather than introducing AES-GCM interop.
 *
 * ## Wire format (output of [seal])
 *
 * ```
 *   version(1)=0x01  iv(16)  mac(32)  ciphertext(N, AES-CBC PKCS#7-padded)
 * ```
 *
 * The MAC covers `version || iv || ciphertext` (the ciphertext is
 * authenticated, not the plaintext). A 32-byte private key seals to
 * 1 + 16 + 32 + 48 = 97 bytes — always strictly longer than the 32-byte
 * raw key, which is what lets [IosIdentityRepo] distinguish a sealed blob
 * from a pre-Keychain raw-plaintext `*Enc` column during migration.
 *
 * ## Failure semantics
 *
 * - [seal]/[unseal] throw [IllegalStateException] if the Keychain refuses
 *   the master key (device-locked at first launch with no passcode, etc.).
 *   [IosIdentityRepo.save] catches the seal failure and degrades to
 *   plaintext-column storage (mirroring the Android Keystore-unavailable
 *   path) so the app never bricks; [IosIdentityRepo.load] surfaces an
 *   unseal failure to the caller. Per the [IdentityVault] contract we do
 *   NOT silently regenerate the identity on unseal failure.
 * - [unseal] throws on a version mismatch or MAC mismatch (tampered blob,
 *   or a blob sealed by a different master key).
 */
@OptIn(ExperimentalForeignApi::class)
class KeychainIdentityVault(
    private val crypto: CryptoProvider = IosCryptoProvider(),
    /** Source of the 32-byte master key. Defaults to the Keychain bridge;
     *  tests inject a fixed key so the seal/unseal envelope can be verified
     *  without a keychain-access entitlement (test binaries don't have one,
     *  and the Keychain would return errSecMissingEntitlement). */
    private val masterKeyProvider: () -> ByteArray = ::keychainMasterKey,
) : IdentityVault {

    /** Cached master key — fetched at most once per process. The Keychain
     *  itself is the durable store; this is a hot cache so seal/unseal don't
     *  hit `SecItemCopyMatching` per call. */
    private var cachedMaster: ByteArray? = null

    private fun masterKey(): ByteArray {
        cachedMaster?.let { return it }
        val key = masterKeyProvider()
        require(key.size == MASTER_KEY_LEN) {
            "Identity-vault master key must be $MASTER_KEY_LEN bytes, got ${key.size}"
        }
        cachedMaster = key
        return key
    }

    private suspend fun deriveKeys(): Pair<ByteArray, ByteArray> {
        // 64-byte OKM = encKey(32) || macKey(32). The master key is
        // already full-entropy from SecRandomCopyBytes so an empty salt
        // is fine per RFC 5869 §3.1; [HKDF_INFO] domain-separates these
        // sub-keys from any other use of the same master key.
        val okm = crypto.hkdfDerive(masterKey(), ByteArray(0), HKDF_INFO, 64)
        return okm.copyOfRange(0, 32) to okm.copyOfRange(32, 64)
    }

    override suspend fun seal(plaintext: ByteArray): ByteArray {
        val (encKey, macKey) = deriveKeys()
        val iv = crypto.randomBytes(IV_LEN)
        val ct = crypto.aesCbcEncrypt(encKey, iv, plaintext)

        // MAC input: version || iv || ciphertext (encrypt-then-MAC).
        val macInput = ByteArray(1 + IV_LEN + ct.size)
        macInput[0] = VERSION
        iv.copyInto(macInput, 1)
        ct.copyInto(macInput, 1 + IV_LEN)
        val mac = crypto.hmacSha256(macKey, macInput)

        val out = ByteArray(1 + IV_LEN + MAC_LEN + ct.size)
        out[0] = VERSION
        iv.copyInto(out, 1)
        mac.copyInto(out, 1 + IV_LEN)
        ct.copyInto(out, 1 + IV_LEN + MAC_LEN)
        return out
    }

    override suspend fun unseal(sealed: ByteArray): ByteArray {
        require(sealed.size > 1 + IV_LEN + MAC_LEN) {
            "Sealed blob too short: ${sealed.size} bytes"
        }
        require(sealed[0] == VERSION) {
            "Unknown vault version ${sealed[0].toInt() and 0xFF}; expected $VERSION"
        }
        val iv = sealed.copyOfRange(1, 1 + IV_LEN)
        val mac = sealed.copyOfRange(1 + IV_LEN, 1 + IV_LEN + MAC_LEN)
        val ct = sealed.copyOfRange(1 + IV_LEN + MAC_LEN, sealed.size)

        val (encKey, macKey) = deriveKeys()
        val macInput = ByteArray(1 + IV_LEN + ct.size)
        macInput[0] = VERSION
        iv.copyInto(macInput, 1)
        ct.copyInto(macInput, 1 + IV_LEN)
        val expected = crypto.hmacSha256(macKey, macInput)
        require(constantTimeEquals(expected, mac)) {
            "Vault MAC mismatch — blob tampered or sealed by a different key"
        }
        return crypto.aesCbcDecrypt(encKey, iv, ct)
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    companion object {
        /** Length of a raw Reticulum private key (X25519/Ed25519/ratchet).
         *  [IosIdentityRepo] uses this to tell a raw key from a sealed blob. */
        const val RAW_PRIV_KEY_LEN = 32
        private const val MASTER_KEY_LEN = 32
        private const val IV_LEN = 16
        private const val MAC_LEN = 32
        private const val VERSION: Byte = 0x01
        private val HKDF_INFO =
            "io.github.thatsfguy.reticulum.identity-vault/v1".encodeToByteArray()
    }
}

/**
 * Fetch (or create) the 32-byte identity-vault master key from the iOS
 * Keychain via the `rcr_keychain_get_or_create_key` Swift bridge. Throws
 * if the Keychain is unavailable — [IosIdentityRepo.save] catches this and
 * degrades to plaintext-column storage.
 */
@OptIn(ExperimentalForeignApi::class)
private fun keychainMasterKey(): ByteArray {
    val out = ByteArray(32)
    val rc = out.usePinned { pin ->
        io.github.thatsfguy.reticulum.crypto.cinterop.rcr_keychain_get_or_create_key(
            pin.addressOf(0).reinterpret(),
        )
    }
    check(rc == 0) {
        "Keychain identity-vault master key unavailable: rc=$rc " +
            "(device locked at launch, or Keychain access denied)"
    }
    return out
}
