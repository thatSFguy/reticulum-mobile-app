# iosCryptoBridge

Swift wrapper exposing CryptoKit's Curve25519 APIs as C-callable functions for Kotlin/Native cinterop. Used by `IosCryptoProvider` (iOS Phase 2B) to fulfill the 7 Ed25519 / X25519 methods on the `CryptoProvider` interface — CommonCrypto has no Curve25519 surface, so we route those operations through CryptoKit.

The other 7 `CryptoProvider` methods (SHA-256, HMAC, AES-CBC, HKDF, randomBytes) are pure CommonCrypto and don't need this bridge — they ship in `IosCryptoProvider.kt` directly.

## Build

macOS only. The main Gradle build invokes `build.sh` automatically before any iOS link task via `:shared:buildIosCryptoBridge`. To build standalone:

```sh
cd shared/iosCryptoBridge
./build.sh
```

Outputs land at `shared/build/iosCryptoBridge/<kotlin-target>/libReticulumCrypto.a` for each of `iosArm64`, `iosSimulatorArm64`, `iosX64`. The Kotlin/Native link step pulls in the matching slice via `-L<built-dir>` set per-target in `shared/build.gradle.kts`.

## C ABI

All functions use the `rcr_` prefix and follow this convention:
- byte-array inputs: `const uint8_t*` (caller owns the bytes)
- byte-array outputs: `uint8_t*` (caller-allocated, sized to the spec)
- return type: `int32_t` — 0 on success, non-zero on failure (key/sig rejected by CryptoKit), or `void` for keygens that can't fail

| Function | Behavior |
|----------|----------|
| `rcr_x25519_keygen(out)` | Write a fresh X25519 private key to `out[32]` |
| `rcr_x25519_pubkey(priv, out)` | Derive the public key for `priv[32]` into `out[32]` |
| `rcr_x25519_shared_secret(priv, pub, out)` | DH agreement: `out[32] = X25519(priv[32], pub[32])` |
| `rcr_ed25519_keygen(out)` | Write a fresh Ed25519 private key seed to `out[32]` |
| `rcr_ed25519_pubkey(priv, out)` | Derive the public key for `priv[32]` into `out[32]` |
| `rcr_ed25519_sign(priv, msg, msgLen, out)` | Sign `msg[msgLen]` under `priv[32]`; signature into `out[64]` |
| `rcr_ed25519_verify(sig, msg, msgLen, pub)` | 1 = valid, 0 = invalid, -1 = pub didn't parse |

The Kotlin-side cinterop binding is at `shared/src/nativeInterop/cinterop/reticulumcrypto.def`. Kotlin callers should treat `-1` from `rcr_ed25519_verify` the same as `0` (invalid) — that's a malformed-input signal, not an authentication success.
