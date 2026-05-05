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
