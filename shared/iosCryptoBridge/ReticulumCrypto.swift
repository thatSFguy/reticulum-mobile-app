// SPDX-License-Identifier: MIT
//
// Swift wrapper exposing CryptoKit's Curve25519 APIs as C-callable
// functions, consumed via Kotlin/Native cinterop. CommonCrypto has
// no Curve25519 surface; CryptoKit does, but its types are Swift-only
// and don't bridge cleanly to Obj-C — so we expose a thin C ABI here.
//
// Naming: `rcr_` prefix = "Reticulum CRypto" — short and unique enough
// to avoid colliding with other libraries an iOS app might link.
//
// Return convention:
//   - keygen functions return void (CryptoKit's `init()` can't fail)
//   - parseable-input functions return `Int32`: 0 on success,
//     non-zero on failure (key / signature rejected by CryptoKit)
//   - verify returns 1 = valid, 0 = invalid, -1 = pub key parse failed

import CryptoKit
import Foundation
import Security

// MARK: - X25519 (key agreement)

@_cdecl("rcr_x25519_keygen")
public func rcr_x25519_keygen(_ out: UnsafeMutablePointer<UInt8>) {
    let key = Curve25519.KeyAgreement.PrivateKey()
    key.rawRepresentation.withUnsafeBytes { src in
        if let base = src.baseAddress { memcpy(out, base, 32) }
    }
}

@_cdecl("rcr_x25519_pubkey")
public func rcr_x25519_pubkey(
    _ priv: UnsafePointer<UInt8>,
    _ out: UnsafeMutablePointer<UInt8>
) -> Int32 {
    let privData = Data(bytes: priv, count: 32)
    do {
        let p = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: privData)
        p.publicKey.rawRepresentation.withUnsafeBytes { src in
            if let base = src.baseAddress { memcpy(out, base, 32) }
        }
        return 0
    } catch {
        return -1
    }
}

@_cdecl("rcr_x25519_shared_secret")
public func rcr_x25519_shared_secret(
    _ priv: UnsafePointer<UInt8>,
    _ pub: UnsafePointer<UInt8>,
    _ out: UnsafeMutablePointer<UInt8>
) -> Int32 {
    let privData = Data(bytes: priv, count: 32)
    let pubData  = Data(bytes: pub,  count: 32)
    do {
        let p  = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: privData)
        let pk = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: pubData)
        let secret = try p.sharedSecretFromKeyAgreement(with: pk)
        secret.withUnsafeBytes { src in
            if let base = src.baseAddress { memcpy(out, base, 32) }
        }
        return 0
    } catch {
        return -1
    }
}

// MARK: - Ed25519 (signing)

@_cdecl("rcr_ed25519_keygen")
public func rcr_ed25519_keygen(_ out: UnsafeMutablePointer<UInt8>) {
    let key = Curve25519.Signing.PrivateKey()
    key.rawRepresentation.withUnsafeBytes { src in
        if let base = src.baseAddress { memcpy(out, base, 32) }
    }
}

@_cdecl("rcr_ed25519_pubkey")
public func rcr_ed25519_pubkey(
    _ priv: UnsafePointer<UInt8>,
    _ out: UnsafeMutablePointer<UInt8>
) -> Int32 {
    let privData = Data(bytes: priv, count: 32)
    do {
        let p = try Curve25519.Signing.PrivateKey(rawRepresentation: privData)
        p.publicKey.rawRepresentation.withUnsafeBytes { src in
            if let base = src.baseAddress { memcpy(out, base, 32) }
        }
        return 0
    } catch {
        return -1
    }
}

@_cdecl("rcr_ed25519_sign")
public func rcr_ed25519_sign(
    _ priv: UnsafePointer<UInt8>,
    _ msg: UnsafePointer<UInt8>,
    _ msgLen: Int32,
    _ out: UnsafeMutablePointer<UInt8>
) -> Int32 {
    let privData = Data(bytes: priv, count: 32)
    let msgData  = Data(bytes: msg,  count: Int(msgLen))
    do {
        let p   = try Curve25519.Signing.PrivateKey(rawRepresentation: privData)
        let sig = try p.signature(for: msgData)
        sig.withUnsafeBytes { src in
            if let base = src.baseAddress { memcpy(out, base, 64) }
        }
        return 0
    } catch {
        return -1
    }
}

@_cdecl("rcr_ed25519_verify")
public func rcr_ed25519_verify(
    _ sig: UnsafePointer<UInt8>,
    _ msg: UnsafePointer<UInt8>,
    _ msgLen: Int32,
    _ pub: UnsafePointer<UInt8>
) -> Int32 {
    let sigData = Data(bytes: sig, count: 64)
    let msgData = Data(bytes: msg, count: Int(msgLen))
    let pubData = Data(bytes: pub, count: 32)
    do {
        let pk = try Curve25519.Signing.PublicKey(rawRepresentation: pubData)
        return pk.isValidSignature(sigData, for: msgData) ? 1 : 0
    } catch {
        return -1
    }
}

// MARK: - Identity-vault wrapping key (Keychain)
//
// The iOS analogue of Android's Keystore-backed identity vault. We keep
// a single random 32-byte AES master key in the iOS Keychain and let
// `KeychainIdentityVault` (Kotlin) derive enc+MAC sub-keys from it and
// seal/unseal the identity private keys with AES-256-CBC + HMAC-SHA256.
//
// Why the key lives here and not in the SQLite DB: the whole threat the
// vault addresses (audit 2026-05-13 HIGH-1) is "attacker `cat`s the
// app-private DB file and walks off with the identity keys." The Keychain
// is stored in a separate, OS-managed, hardware-encrypted store —
// `kSecAttrAccessibleWhenUnlockedThisDeviceOnly` means the item is
// readable only while the device is unlocked AND never leaves this device
// (no iCloud-Keychain sync, no encrypted-backup restore to another
// device). So a DB-file exfiltration yields only ciphertext.
//
// Secure Enclave directly only stores P-256 keys, not arbitrary AES keys;
// the idiomatic "random symmetric key in the Keychain" pattern is the
// correct equivalent of Android's `KeyGenParameterSpec` AES key here.
//
// Return convention (matches the rcr_* family): 0 = success, negative =
// failure. On success `out` holds exactly 32 bytes.
@_cdecl("rcr_keychain_get_or_create_key")
public func rcr_keychain_get_or_create_key(_ out: UnsafeMutablePointer<UInt8>) -> Int32 {
    let service = "io.github.thatsfguy.reticulum.identity-vault"
    let account = "master-key-v1"

    let lookup: [String: Any] = [
        kSecClass as String: kSecClassGenericPassword,
        kSecAttrService as String: service,
        kSecAttrAccount as String: account,
        kSecReturnData as String: true,
        kSecMatchLimit as String: kSecMatchLimitOne,
    ]

    // 1) Return the existing key if one is already stored.
    var item: CFTypeRef?
    let readStatus = SecItemCopyMatching(lookup as CFDictionary, &item)
    if readStatus == errSecSuccess, let data = item as? Data, data.count == 32 {
        data.withUnsafeBytes { src in
            if let base = src.baseAddress { memcpy(out, base, 32) }
        }
        return 0
    }
    // Any error other than "not found" (e.g. interaction-not-allowed
    // while the device is locked) is surfaced so the caller degrades to
    // plaintext-column storage rather than minting a *second* key.
    if readStatus != errSecItemNotFound {
        return -2
    }

    // 2) No key yet — generate one and store it.
    var keyBytes = [UInt8](repeating: 0, count: 32)
    let rngStatus = SecRandomCopyBytes(kSecRandomDefault, 32, &keyBytes)
    if rngStatus != errSecSuccess { return -3 }
    let keyData = Data(keyBytes)

    let insert: [String: Any] = [
        kSecClass as String: kSecClassGenericPassword,
        kSecAttrService as String: service,
        kSecAttrAccount as String: account,
        kSecValueData as String: keyData,
        kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
    ]
    let addStatus = SecItemAdd(insert as CFDictionary, nil)
    if addStatus == errSecSuccess {
        keyData.withUnsafeBytes { src in
            if let base = src.baseAddress { memcpy(out, base, 32) }
        }
        return 0
    }
    // A concurrent caller may have created the key between our read and
    // write; re-read rather than overwrite (overwriting would orphan
    // every row sealed by the first key).
    if addStatus == errSecDuplicateItem {
        var raceItem: CFTypeRef?
        let raceStatus = SecItemCopyMatching(lookup as CFDictionary, &raceItem)
        if raceStatus == errSecSuccess, let data = raceItem as? Data, data.count == 32 {
            data.withUnsafeBytes { src in
                if let base = src.baseAddress { memcpy(out, base, 32) }
            }
            return 0
        }
    }
    return -4
}
