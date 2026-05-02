#!/usr/bin/env python3
"""
Validate Reticulum protocol test vectors against the upstream Python
RNS reference implementation. Reads a JSON document produced by our
Kotlin RoundtripGenerator (or any harness emitting the same shape) and
asserts that every artifact — announces, opportunistic LXMF, LRPROOFs,
full link handshake — round-trips cleanly under RNS.

Usage:
    python tools/validate_vectors.py path/to/roundtrip-vectors.json

Exit code 0 on all-pass, 1 on any failure. Designed for CI.

Adapted from reticulum-lora-webclient/tests/run_tests.py — the JSON
shape and validation logic match exactly so the same test surface
proves both implementations stay byte-compatible with RNS.
"""
import json
import os
import sys

os.environ["RNS_LOG_DEST"] = "stderr"

import RNS
from RNS import Identity, Packet, Reticulum
from RNS.Cryptography import (
    Ed25519PrivateKey,
    Ed25519PublicKey,
    X25519PrivateKey,
    X25519PublicKey,
    HKDF,
    Token,
)
import hashlib

try:
    import umsgpack
except ImportError:
    import msgpack as umsgpack

OK = "\033[32mOK\033[0m"
FAIL = "\033[31mFAIL\033[0m"
INFO = "\033[36m..\033[0m"


def load_vectors(path):
    with open(path, "r") as f:
        return json.load(f)


def scenario_announce(vectors, results):
    print(f"{INFO} Scenario 1: announce round-trip")
    js_alice = vectors["alice"]
    raw = bytes.fromhex(vectors["announce"]["packet"])

    pkt = Packet(None, None)
    pkt.raw = raw
    pkt.unpack()

    if pkt.packet_type != RNS.Packet.ANNOUNCE:
        print(f"   {FAIL} unpacked packet_type is not ANNOUNCE ({pkt.packet_type})")
        results.append(False); return
    if pkt.context_flag != RNS.Packet.FLAG_SET:
        print(f"   {FAIL} context_flag is not SET (ratchet announce should have bit 5 high)")
        results.append(False); return
    if pkt.destination_hash.hex() != js_alice["destHash"]:
        print(f"   {FAIL} dest_hash mismatch: kt={js_alice['destHash']} python={pkt.destination_hash.hex()}")
        results.append(False); return

    if Identity.validate_announce(pkt):
        print(f"   {OK} RNS.Identity.validate_announce accepted ratchet announce")
        results.append(True)
    else:
        print(f"   {FAIL} RNS.Identity.validate_announce rejected our announce")
        results.append(False)


def scenario_lxmf_send(vectors, results):
    print(f"{INFO} Scenario 2: opportunistic LXMF round-trip")
    alice_hex = vectors["alice"]
    bob_hex = vectors["bob"]
    raw = bytes.fromhex(vectors["lxmf_send"]["packet"])

    pkt = Packet(None, None)
    pkt.raw = raw
    pkt.unpack()

    if pkt.packet_type != RNS.Packet.DATA:
        print(f"   {FAIL} unpacked packet_type is not DATA")
        results.append(False); return
    if pkt.destination_hash.hex() != bob_hex["destHash"]:
        print(f"   {FAIL} dest_hash {pkt.destination_hash.hex()} != Bob's {bob_hex['destHash']}")
        results.append(False); return

    body = pkt.data
    eph_pub = body[:32]
    token_bytes = body[32:]

    bob_ratchet = X25519PrivateKey.from_private_bytes(bytes.fromhex(bob_hex["ratchetPriv"]))
    eph_pub_obj = X25519PublicKey.from_public_bytes(eph_pub)
    shared = bob_ratchet.exchange(eph_pub_obj)

    bob_identity_hash = bytes.fromhex(bob_hex["identityHash"])
    derived = HKDF.hkdf(64, shared, salt=bob_identity_hash, context=b"")

    try:
        token = Token(derived)
        plaintext = token.decrypt(token_bytes)
    except Exception as e:
        print(f"   {FAIL} Token.decrypt threw: {e}")
        results.append(False); return

    if len(plaintext) < 16 + 64 + 1:
        print(f"   {FAIL} plaintext too short ({len(plaintext)} B)")
        results.append(False); return

    source_hash = plaintext[:16]
    signature = plaintext[16:80]
    msgpack_data = plaintext[80:]

    if source_hash.hex() != alice_hex["destHash"]:
        print(f"   {FAIL} source_hash {source_hash.hex()} != Alice's destHash {alice_hex['destHash']}")
        results.append(False); return

    try:
        payload = umsgpack.unpackb(msgpack_data)
    except Exception as e:
        print(f"   {FAIL} msgpack.unpackb threw: {e}")
        results.append(False); return

    if not isinstance(payload, list) or len(payload) < 4:
        print(f"   {FAIL} LXMF payload not a 4-element array")
        results.append(False); return

    content_bytes = payload[2]
    content = content_bytes.decode("utf-8") if isinstance(content_bytes, (bytes, bytearray)) else content_bytes
    expected_content = vectors["lxmf_send"]["content"]
    if content != expected_content:
        print(f"   {FAIL} decoded content {content!r} != expected {expected_content!r}")
        results.append(False); return

    bob_dest_bytes = bytes.fromhex(bob_hex["destHash"])
    hashed_part = bob_dest_bytes + source_hash + msgpack_data
    message_hash = hashlib.sha256(hashed_part).digest()
    signed_part = hashed_part + message_hash

    alice_sig_pub = Ed25519PublicKey.from_public_bytes(bytes.fromhex(alice_hex["sigPub"]))
    try:
        alice_sig_pub.verify(signature, signed_part)
        print(f"   {OK} LXMF decrypted, unpacked ({content!r}), signature verified")
        results.append(True)
    except Exception as e:
        print(f"   {FAIL} Ed25519 verify of LXMF signature raised: {type(e).__name__}")
        results.append(False)


def scenario_lxmf_send_via_announce(vectors, results):
    """
    The production sendMessage path: parse a peer's announce, extract
    publicKey/ratchet/identityHash from the parsed result, and encrypt
    using ONLY those parsed values. If this passes but the live app
    fails to deliver, the bug is somewhere AFTER encryption (transport,
    timing, or recipient-side). If this fails, the bug is in announce
    parsing or in how sendMessage uses the parsed values.
    """
    print(f"{INFO} Scenario 2b: parse-announce-then-encrypt round-trip")
    via = vectors.get("lxmf_send_via_announce")
    if not via:
        print(f"   {FAIL} no lxmf_send_via_announce vector")
        results.append(False); return

    bob_hex = vectors["bob"]
    alice_hex = vectors["alice"]

    # 1) Parse Bob's announce as Python RNS would, get back the same
    #    ratchet + identityHash that our handleAnnounce stores.
    announce_raw = bytes.fromhex(via["announcePacket"])
    announce_pkt = Packet(None, None)
    announce_pkt.raw = announce_raw
    announce_pkt.unpack()

    if not Identity.validate_announce(announce_pkt):
        print(f"   {FAIL} Bob's announce failed Python validate_announce")
        results.append(False); return

    # Pull the ratchet field exactly as RNS would (bytes 64+10+10..64+10+10+32)
    payload = announce_pkt.data
    pub_key = payload[:64]
    name_hash = payload[64:74]
    random_hash = payload[74:84]
    if announce_pkt.context_flag == RNS.Packet.FLAG_SET:
        ratchet = payload[84:116]
    else:
        ratchet = None
    identity_hash = hashlib.sha256(pub_key).digest()[:16]

    if pub_key.hex() != bob_hex["publicKey"]:
        print(f"   {FAIL} parsed publicKey != known Bob publicKey")
        results.append(False); return
    if ratchet is None:
        print(f"   {FAIL} no ratchet on Bob's ratchet announce")
        results.append(False); return
    if ratchet.hex() != bob_hex["ratchetPub"]:
        print(f"   {FAIL} parsed ratchet != known Bob ratchetPub")
        results.append(False); return
    if identity_hash.hex() != bob_hex["identityHash"]:
        print(f"   {FAIL} parsed identityHash != known Bob identityHash")
        results.append(False); return

    # 2) Decrypt the production-path packet. Bob uses his ratchet PRIVATE
    #    matching the ratchet PUBLIC parsed from the announce.
    raw = bytes.fromhex(via["packet"])
    pkt = Packet(None, None)
    pkt.raw = raw
    pkt.unpack()

    if pkt.destination_hash.hex() != bob_hex["destHash"]:
        print(f"   {FAIL} dest_hash {pkt.destination_hash.hex()} != Bob's {bob_hex['destHash']}")
        results.append(False); return

    body = pkt.data
    eph_pub = body[:32]
    token_bytes = body[32:]
    bob_ratchet_priv = X25519PrivateKey.from_private_bytes(bytes.fromhex(bob_hex["ratchetPriv"]))
    eph_pub_obj = X25519PublicKey.from_public_bytes(eph_pub)
    shared = bob_ratchet_priv.exchange(eph_pub_obj)
    derived = HKDF.hkdf(64, shared, salt=identity_hash, context=b"")

    try:
        token = Token(derived)
        plaintext = token.decrypt(token_bytes)
    except Exception as e:
        print(f"   {FAIL} Token.decrypt threw: {e}")
        results.append(False); return

    source_hash = plaintext[:16]
    signature = plaintext[16:80]
    msgpack_data = plaintext[80:]

    payload_list = umsgpack.unpackb(msgpack_data)
    content_bytes = payload_list[2]
    content = content_bytes.decode("utf-8") if isinstance(content_bytes, (bytes, bytearray)) else content_bytes
    expected = via["content"]
    if content != expected:
        print(f"   {FAIL} decoded content {content!r} != expected {expected!r}")
        results.append(False); return

    # Signature
    bob_dest_bytes = bytes.fromhex(bob_hex["destHash"])
    hashed_part = bob_dest_bytes + source_hash + msgpack_data
    message_hash = hashlib.sha256(hashed_part).digest()
    signed_part = hashed_part + message_hash
    try:
        Ed25519PublicKey.from_public_bytes(bytes.fromhex(alice_hex["sigPub"])).verify(signature, signed_part)
    except Exception as e:
        print(f"   {FAIL} signature verify raised: {type(e).__name__}")
        results.append(False); return

    print(f"   {OK} parse-announce → encrypt → decrypt → verify all pass")
    results.append(True)


def scenario_link_proof(vectors, results):
    print(f"{INFO} Scenario 3: LRPROOF signature check")
    alice_hex = vectors["alice"]
    link_hex = vectors["link"]

    lr_proof = bytes.fromhex(link_hex["lrProofPacket"])
    link_id = bytes.fromhex(link_hex["linkId"])
    signed_data = bytes.fromhex(link_hex["signedData"])

    if len(lr_proof) < 19 + 64 + 32 + 3:
        print(f"   {FAIL} LRPROOF too short: {len(lr_proof)} B")
        results.append(False); return
    if lr_proof[0] != 0x0F:
        print(f"   {FAIL} LRPROOF flags 0x{lr_proof[0]:02x} != 0x0F")
        results.append(False); return
    if lr_proof[18] != 0xFF:
        print(f"   {FAIL} LRPROOF context 0x{lr_proof[18]:02x} != 0xFF")
        results.append(False); return
    if lr_proof[2:18] != link_id:
        print(f"   {FAIL} LRPROOF header link_id mismatch")
        results.append(False); return

    proof_data = lr_proof[19:]
    signature = proof_data[:64]
    ephemeral_x25519_pub = proof_data[64:96]
    signalling = proof_data[96:99]

    responder_sig_pub = bytes.fromhex(alice_hex["sigPub"])
    rebuilt_signed = link_id + ephemeral_x25519_pub + responder_sig_pub + signalling

    if rebuilt_signed != signed_data:
        print(f"   {FAIL} rebuilt signed_data does not match")
        print(f"          kt     : {signed_data.hex()}")
        print(f"          rebuilt: {rebuilt_signed.hex()}")
        results.append(False); return

    try:
        Ed25519PublicKey.from_public_bytes(responder_sig_pub).verify(signature, rebuilt_signed)
        print(f"   {OK} LRPROOF signature verified against Alice's long-term sig pub")
        results.append(True)
    except Exception as e:
        print(f"   {FAIL} LRPROOF signature verification raised: {type(e).__name__}")
        results.append(False)


def scenario_link_handshake(vectors, results):
    print(f"{INFO} Scenario 4: full link handshake + data round-trip")
    handshake = vectors.get("link_handshake")
    if not handshake:
        print(f"   {FAIL} no link_handshake vector")
        results.append(False); return

    if handshake["linkIdInitiator"] != handshake["linkIdResponder"]:
        print(f"   {FAIL} initiator and responder disagree on link_id")
        results.append(False); return

    derived_key = bytes.fromhex(handshake["derivedKey"])
    if len(derived_key) != 64:
        print(f"   {FAIL} derived key is {len(derived_key)} B, expected 64")
        results.append(False); return

    ciphertext = bytes.fromhex(handshake["testCiphertext"])
    expected = handshake["testPlaintext"].encode("utf-8")
    try:
        token = Token(derived_key)
        plaintext = token.decrypt(ciphertext)
    except Exception as e:
        print(f"   {FAIL} Token.decrypt with kt-derived key raised: {e}")
        results.append(False); return

    if plaintext != expected:
        print(f"   {FAIL} link plaintext mismatch: {plaintext!r} != {expected!r}")
        results.append(False); return

    print(f"   {OK} handshake derived-key round-trip succeeds under RNS Token")
    results.append(True)


def main():
    if len(sys.argv) < 2:
        print(f"usage: {sys.argv[0]} <roundtrip-vectors.json>", file=sys.stderr)
        sys.exit(2)

    RNS.loglevel = 0
    Reticulum(loglevel=0)

    print(f"{INFO} loading vectors from {sys.argv[1]}")
    vectors = load_vectors(sys.argv[1])
    print(f"   {OK} loaded; alice.destHash={vectors['alice']['destHash'][:16]}...  bob.destHash={vectors['bob']['destHash'][:16]}...")
    print()

    results = []
    scenario_announce(vectors, results)
    scenario_lxmf_send(vectors, results)
    scenario_lxmf_send_via_announce(vectors, results)
    scenario_link_proof(vectors, results)
    scenario_link_handshake(vectors, results)

    print()
    passed = sum(1 for r in results if r)
    total = len(results)
    if passed == total:
        print(f"{OK} all {total} scenarios passed")
        sys.exit(0)
    else:
        print(f"{FAIL} {passed}/{total} scenarios passed")
        sys.exit(1)


if __name__ == "__main__":
    main()
