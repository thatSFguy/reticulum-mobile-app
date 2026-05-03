#!/usr/bin/env python3
"""
Offline replay-decrypt for the v0.1.39 crypto-dump.

Takes the encrypt parameters logged by ReticulumEngine and tries to
decrypt the resulting Token using the recipient identity's private
keys. Lets us pinpoint whether the outbound bug is in:

  - HKDF salt (would cause HMAC mismatch on every key)
  - eph_pub format (would cause ECDH gibberish)
  - AES key/IV (HMAC ok but plaintext wrong)
  - Padding (decrypt errors with "padding incorrect")

Recipient identity is loaded from the test-receiver path. The receiver
keeps a ratchet ring on disk too — we try both the long-term key and
recent ratchets.
"""
import os
import hashlib
import hmac
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey, X25519PublicKey
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.primitives.padding import PKCS7

# Pulled directly from the v0.1.39 crypto-dump in logcat:
RECIPIENT_IDENTITY_HASH = bytes.fromhex("2793e644d778262db5a971e31d34cb37")
RECIPIENT_LONG_TERM_ENC_PUB = bytes.fromhex(
    "3d372c181e4c9d3cae0ac81f8d06bf36ddbe4f0ed149dd30ac1d7fe31e4dce05"
)
RECIPIENT_RATCHET_PUB = bytes.fromhex(
    "3359f703974b515cf5eb7303706fda604d8351297ee702ed1fbf927461c70323"
)
PLAINTEXT_EXPECTED_HEX = (
    "2fbe6d20c5bb6985fd84ca4fc62ca655"
    "021ce314779fea64e1e6abc4a7c1dd429425a9c5e1bb521d736b0cc676fe03337a72b6c9"
    "f97eafb08dc280c5468959477cd26dc34e9e6067bafecba97128d50494cb41da7de85da2"
    "d0e5c400c4117"
    "6302e312e33392d64756d702d7465737480"
)
TOKEN_EPH_PUB = bytes.fromhex("b731732ac41d677aa920c56fe1b59dfddf0a883f2d2bfcd80e9c16140b556b5c")
TOKEN_IV = bytes.fromhex("d0d2d76d416d871187e260b5d41135a1")
TOKEN_CIPHERTEXT = bytes.fromhex(
    "424c611a0f99b1b17bcb8641d84a65a33d09d02bbddf33e60d1e41538ae6"
    "2cee61ffbedbf207a715e5bf4b3fcca35a627e39b3dc2065b9b8ec4a648f7f44a0ab7bf0"
    "07e19919e6da09d94d89e4f686c0f00c629f1e154eff1629deb922575ffca192c493d113"
    "f8488a35d78ddbf97e728b0368e6967d670a32041e5e953e22c5"
)
TOKEN_HMAC = bytes.fromhex("cbb88bcfd062df054f58e2aaa23b5a7261b20e15f1505d218a3b0883f3a50555")

IDENTITY_PATH = os.path.expanduser("~/.reticulum-mobile-app-test-identity")


def load_recipient_keys():
    """Load receiver privkeys: returns list of (label, X25519PrivateKey)."""
    blob = open(IDENTITY_PATH, "rb").read()
    # RNS on-disk: X25519_priv(32) || Ed25519_priv(32) per spec §1.3.
    x25519_priv = blob[:32]
    out = [("long_term", X25519PrivateKey.from_private_bytes(x25519_priv))]
    # Also try ratchets if present in test-receiver storage.
    storage = os.path.expanduser("~/.reticulum-mobile-app-test-receiver")
    ratchets_dir = os.path.join(storage, "ratchets")
    if os.path.isdir(ratchets_dir):
        for fn in sorted(os.listdir(ratchets_dir), reverse=True)[:8]:
            path = os.path.join(ratchets_dir, fn)
            try:
                rb = open(path, "rb").read()
                if len(rb) == 32:
                    out.append((f"ratchet:{fn}", X25519PrivateKey.from_private_bytes(rb)))
            except Exception as e:
                print(f"  skip {fn}: {e}")
    return out


def derive_keys(eph_pub_bytes: bytes, our_priv: X25519PrivateKey, salt: bytes):
    eph_pub = X25519PublicKey.from_public_bytes(eph_pub_bytes)
    shared = our_priv.exchange(eph_pub)
    derived = HKDF(
        algorithm=hashes.SHA256(),
        length=64,
        salt=salt,
        info=b"",
    ).derive(shared)
    return derived[:32], derived[32:64]  # signing_key, encryption_key


def try_decrypt(label: str, our_priv: X25519PrivateKey, salt: bytes):
    print(f"\n[{label}]")
    sig_key, enc_key = derive_keys(TOKEN_EPH_PUB, our_priv, salt)
    print(f"  signing_key = {sig_key.hex()}")
    print(f"  encryption_key = {enc_key.hex()}")
    expected_hmac = hmac.new(sig_key, TOKEN_IV + TOKEN_CIPHERTEXT, hashlib.sha256).digest()
    print(f"  computed HMAC = {expected_hmac.hex()}")
    print(f"  given    HMAC = {TOKEN_HMAC.hex()}")
    if expected_hmac != TOKEN_HMAC:
        print("  ✗ HMAC mismatch — wrong key or wrong salt")
        return False
    print("  ✓ HMAC matches")
    cipher = Cipher(algorithms.AES(enc_key), modes.CBC(TOKEN_IV))
    dec = cipher.decryptor()
    padded = dec.update(TOKEN_CIPHERTEXT) + dec.finalize()
    unpadder = PKCS7(128).unpadder()
    try:
        plain = unpadder.update(padded) + unpadder.finalize()
    except Exception as e:
        print(f"  ✗ unpad failed: {e}")
        return False
    print(f"  ✓ decrypted {len(plain)}B: {plain.hex()}")
    if plain.hex() == PLAINTEXT_EXPECTED_HEX:
        print("  ✓✓ matches sender's logged plaintext exactly")
    else:
        print("  ⚠ plaintext differs from sender's logged plaintext")
        print(f"     expected: {PLAINTEXT_EXPECTED_HEX}")
    return True


def main():
    keys = load_recipient_keys()
    print(f"Loaded {len(keys)} candidate key(s)")
    print(f"HKDF salt = {RECIPIENT_IDENTITY_HASH.hex()}")
    print(f"eph_pub   = {TOKEN_EPH_PUB.hex()}")

    any_ok = False
    for label, key in keys:
        if try_decrypt(label, key, RECIPIENT_IDENTITY_HASH):
            any_ok = True

    print()
    if any_ok:
        print("RESULT: at least one key produces a valid HMAC + decrypt.")
        print("The outbound encrypt path is correct. The bug is downstream")
        print("(transport routing / receiver-side delivery).")
    else:
        print("RESULT: no candidate key worked.")
        print("Bug is in our outbound encrypt: HKDF salt, eph_pub format,")
        print("AES key bytes, or the recipient's pubkey we used was wrong.")


if __name__ == "__main__":
    main()
