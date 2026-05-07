#!/usr/bin/env python
"""
Live interop helper. Reads JSON ops from stdin, writes JSON results to
stdout (one per line). Drives upstream RNS / LXMF directly so a Kotlin
test can exchange announce + opportunistic-LXMF bytes with the canonical
Python implementation without orchestrating rnsd.

Source: ported verbatim from
  reticulum-forwarding-service/tests/interop/python_peer.py
so we get the same op surface (init_rns, make_identity, build_announce,
validate_announce, build_lxmf, decrypt_lxmf). Add new ops here for the
link-handshake and per-packet PROOF tests we layer on top.

Required: pip install rns lxmf

Operations:
  init_rns                                 - initialize a minimal RNS
                                              (call once at start)
  make_identity   label, [priv_hex]        - create or load an identity,
                                              return priv/pub/hash hex
  build_announce  label, full_name,        - produce signed announce wire
                  display_name              bytes; sets up a Destination
                                              with the dotted aspect
  validate_announce  wire_bytes_hex        - unpack + RNS.Identity.
                                              validate_announce; returns
                                              verified + dest_hash + the
                                              announcer's pub_hex
  build_lxmf      src_label, dst_label,    - LXMessage.pack + Token-
                  dst_full_name, title,     encrypt; returns ciphertext
                  content                   plus the opportunistic
                                              plaintext for inspection
  decrypt_lxmf    ciphertext_hex, dst_label,- decrypt with dst's identity,
                  dst_full_name             prepend dst_hash, unpack;
                                              returns verified + title +
                                              content
"""

from __future__ import annotations

import json
import os
import sys
import tempfile

import RNS
from RNS.vendor import umsgpack
import LXMF
from LXMF.LXMessage import LXMessage


_identities: dict[str, "RNS.Identity"] = {}
_destinations: dict[tuple[str, str, int], "RNS.Destination"] = {}
_initialized = False


def _init_rns():
    global _initialized
    if _initialized:
        return
    cfg_dir = tempfile.mkdtemp(prefix="rns-interop-")
    cfg_path = os.path.join(cfg_dir, "config")
    with open(cfg_path, "w", encoding="utf-8") as f:
        f.write("[reticulum]\nenable_transport = No\nshare_instance = No\n")
    RNS.Reticulum(configdir=cfg_dir, loglevel=0)
    _initialized = True


def _split_full_name(full_name: str):
    parts = full_name.split(".")
    if len(parts) < 2:
        raise ValueError("full_name must be dotted, e.g. lxmf.delivery")
    return parts[0], parts[1:]


def _get_or_make_dest(identity, full_name: str, direction: int):
    key = (identity.hash.hex(), full_name, direction)
    if key in _destinations:
        return _destinations[key]
    app_name, aspects = _split_full_name(full_name)
    dest = RNS.Destination(identity, direction, RNS.Destination.SINGLE, app_name, *aspects)
    _destinations[key] = dest
    return dest


# ---------------------------------------------------------------------------

def cmd_init_rns(_):
    _init_rns()
    return {"rns_version": RNS.__version__, "lxmf_version": LXMF.__version__}


def cmd_make_identity(args):
    _init_rns()
    label = args["label"]
    priv_hex = args.get("priv_hex")
    if priv_hex:
        identity = RNS.Identity.from_bytes(bytes.fromhex(priv_hex))
        if identity is None:
            raise ValueError("Identity.from_bytes returned None")
        _identities[label] = identity
    elif label in _identities:
        identity = _identities[label]
    else:
        identity = RNS.Identity()
        _identities[label] = identity
    return {
        "priv_hex": identity.get_private_key().hex(),
        "pub_hex": identity.get_public_key().hex(),
        "hash_hex": identity.hash.hex(),
    }


def cmd_dest_hash(args):
    _init_rns()
    identity = _identities[args["label"]]
    dest = _get_or_make_dest(identity, args["full_name"], RNS.Destination.IN)
    return {"dest_hash_hex": dest.hash.hex()}


def cmd_build_announce(args):
    _init_rns()
    identity = _identities[args["label"]]
    full_name = args["full_name"]
    display_name = args["display_name"].encode("utf-8")
    dest = _get_or_make_dest(identity, full_name, RNS.Destination.IN)

    app_data = umsgpack.packb([display_name, 0])
    pkt = dest.announce(app_data=app_data, send=False)
    pkt.pack()
    return {
        "wire_bytes_hex": pkt.raw.hex(),
        "dest_hash_hex": dest.hash.hex(),
    }


def cmd_validate_announce(args):
    _init_rns()
    raw = bytes.fromhex(args["wire_bytes_hex"])

    pkt = RNS.Packet(None, raw)
    pkt.raw = raw
    pkt.packed = True
    if not pkt.unpack():
        return {"verified": False, "error": "Packet.unpack returned False"}
    ok = bool(RNS.Identity.validate_announce(pkt))
    if not ok:
        return {"verified": False, "error": "validate_announce returned False"}

    cached = RNS.Identity.recall(pkt.destination_hash)
    pub_hex = cached.get_public_key().hex() if cached is not None else None
    return {
        "verified": True,
        "dest_hash_hex": pkt.destination_hash.hex(),
        "pub_hex": pub_hex,
    }


def cmd_build_lxmf(args):
    _init_rns()
    src = _identities[args["src_label"]]
    dst = _identities[args["dst_label"]]
    full_name = args["dst_full_name"]
    title = args["title"].encode("utf-8")
    content = args["content"].encode("utf-8")

    src_dest = _get_or_make_dest(src, full_name, RNS.Destination.IN)
    dst_dest_out = _get_or_make_dest(dst, full_name, RNS.Destination.OUT)

    lxm = LXMessage(
        destination=dst_dest_out,
        source=src_dest,
        content=content,
        title=title,
        fields={},
        desired_method=LXMessage.OPPORTUNISTIC,
    )
    lxm.pack()
    packed = lxm.packed
    opp_plaintext = packed[16:]  # strip leading dest_hash for opportunistic form
    ciphertext = dst_dest_out.encrypt(opp_plaintext)
    return {
        "ciphertext_hex": ciphertext.hex(),
        "opportunistic_plaintext_hex": opp_plaintext.hex(),
        "dst_dest_hash_hex": dst_dest_out.hash.hex(),
    }


def cmd_decrypt_lxmf(args):
    _init_rns()
    dst = _identities[args["dst_label"]]
    full_name = args["dst_full_name"]
    ciphertext = bytes.fromhex(args["ciphertext_hex"])

    dst_dest_in = _get_or_make_dest(dst, full_name, RNS.Destination.IN)
    plaintext = dst_dest_in.decrypt(ciphertext)
    if plaintext is None:
        return {"verified": False, "error": "Token.decrypt returned None"}

    full_lxmf = dst_dest_in.hash + plaintext
    parsed = LXMessage.unpack_from_bytes(full_lxmf)

    return {
        "verified": bool(parsed.signature_validated),
        "title": parsed.title_as_string(),
        "content": parsed.content_as_string(),
        "source_hash_hex": parsed.source_hash.hex(),
    }


HANDLERS = {
    "init_rns":           cmd_init_rns,
    "make_identity":      cmd_make_identity,
    "dest_hash":          cmd_dest_hash,
    "build_announce":     cmd_build_announce,
    "validate_announce":  cmd_validate_announce,
    "build_lxmf":         cmd_build_lxmf,
    "decrypt_lxmf":       cmd_decrypt_lxmf,
}


def main():
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            msg = json.loads(line)
            op = msg["op"]
            handler = HANDLERS.get(op)
            if handler is None:
                resp = {"ok": False, "error": f"unknown op {op!r}"}
            else:
                result = handler(msg)
                resp = {"ok": True, **result}
        except Exception as e:
            resp = {"ok": False, "error": f"{type(e).__name__}: {e}"}
        sys.stdout.write(json.dumps(resp) + "\n")
        sys.stdout.flush()


if __name__ == "__main__":
    main()
