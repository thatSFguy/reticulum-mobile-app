#!/usr/bin/env python3
"""
Use RNS itself to decrypt the v0.1.39 captured token. RNS knows where
the ratchet privkeys live and how to iterate the ratchet ring, which
saves us writing it ourselves.

Loads the same identity + storage path the test_lxmf_receiver uses.
"""
import os
import sys

# Same patches as test_lxmf_receiver.py for Windows ratchet rename race.
_orig_replace = os.replace
def _safe_rename(src, dst):
    try:
        _orig_replace(src, dst)
    except (FileNotFoundError, PermissionError):
        pass
os.rename = _safe_rename
os.replace = _safe_rename

import RNS
import LXMF

IDENTITY_PATH = os.path.expanduser("~/.reticulum-mobile-app-test-identity")
STORAGE_PATH = os.path.expanduser("~/.reticulum-mobile-app-test-receiver")
CONFIG_DIR   = os.path.expanduser("~/.reticulum-mobile-app-test-config")

# 208-byte token from the v0.1.39 dump.
TOKEN = bytes.fromhex(
    "b731732ac41d677aa920c56fe1b59dfddf0a883f2d2bfcd80e9c16140b556b5c"
    "d0d2d76d416d871187e260b5d41135a1"
    "424c611a0f99b1b17bcb8641d84a65a33d09d02bbddf33e60d1e41538ae6"
    "2cee61ffbedbf207a715e5bf4b3fcca35a627e39b3dc2065b9b8ec4a648f7f44a0ab7bf0"
    "07e19919e6da09d94d89e4f686c0f00c629f1e154eff1629deb922575ffca192c493d113"
    "f8488a35d78ddbf97e728b0368e6967d670a32041e5e953e22c5"
    "cbb88bcfd062df054f58e2aaa23b5a7261b20e15f1505d218a3b0883f3a50555"
)

def main():
    if not os.path.exists(IDENTITY_PATH):
        print(f"Identity missing at {IDENTITY_PATH}", file=sys.stderr)
        sys.exit(2)

    identity = RNS.Identity.from_file(IDENTITY_PATH)
    print(f"identity_hash = {identity.hash.hex()}")
    print(f"long-term encPub = {identity.pub_bytes.hex()[:64]}")

    # Need RNS.Reticulum bootstrapped before we can call Identity.decrypt.
    rns = RNS.Reticulum(configdir=CONFIG_DIR, loglevel=2)

    # Register the LXMF delivery destination so its ratchet ring is
    # loaded into Identity.known_ratchets / ratchets[dest_hash].
    router = LXMF.LXMRouter(identity=identity, storagepath=STORAGE_PATH)
    delivery_dest = router.register_delivery_identity(identity, display_name="Replay Decrypter")
    print(f"dest_hash = {delivery_dest.hash.hex()}")

    # delivery_dest.decrypt() does the full Token decode: tries the
    # destination's ratchet ring, then falls back to long-term.
    plain = delivery_dest.decrypt(TOKEN)
    if plain is None:
        print("\n✗ FAIL: delivery_dest.decrypt() returned None")
        print("  Either no candidate key matched HMAC,")
        print("  or our app's outbound encrypt produced an unrecoverable token.")
        sys.exit(1)

    print(f"\n✓ DECRYPTED ({len(plain)}B): {plain.hex()}")
    print()
    # Plaintext shape (for opportunistic LXMF body):
    #   src_hash(16) || signature(64) || msgpack([ts, title, content, fields])
    if len(plain) >= 80:
        src = plain[:16]
        sig = plain[16:80]
        body = plain[80:]
        print(f"  src_hash  = {src.hex()}")
        print(f"  signature = {sig.hex()}")
        print(f"  body      = {body.hex()}")

if __name__ == "__main__":
    main()
