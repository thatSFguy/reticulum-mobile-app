#!/usr/bin/env python3
"""
Minimal LXMF propagation node for the PropagationLiveTest integration
test. Mirrors what `test_nomadnet_node.py` does for NomadNet pages —
hosts a destination at the `lxmf.propagation` aspect, pre-loads a
canned LXMF blob into the outbound queue for a recipient identity
passed on the command line, and announces every 5 minutes.

Usage:

    pip install rns lxmf
    python tools/test_propagation_node.py

The script generates a fresh recipient identity, queues a canned LXMF
blob for it, and prints all the env vars the Kotlin test needs —
including the recipient's private keys so the test can rebuild the
matching identity on its side and attempt a /get for it:

    LXMF_PROP_HASH=<propagation_node_hash>
    LXMF_PROP_TCP_HOST=rns.chicagonomad.net
    LXMF_PROP_TCP_PORT=4242
    LXMF_PROP_RECIPIENT_HASH=<recipient identity hash>
    LXMF_PROP_RECIPIENT_ENC_PRIV=<32-byte hex X25519 priv>
    LXMF_PROP_RECIPIENT_SIG_PRIV=<32-byte hex Ed25519 priv>
    LXMF_PROP_NEEDLE=propagation roundtrip ok

The recipient identity is regenerated on every harness restart unless
$RECIP_IDENTITY_PATH is set — that path is honored if it exists, so
re-runs against the same Kotlin test continue to work without
shuffling env vars.

Then in another shell:

    LXMF_PROP_HASH=... ./gradlew :shared:testDebugUnitTest \\
        --tests io.github.thatsfguy.reticulum.PropagationLiveTest

Stop with Ctrl-C.

Pre-v0.1.53 our PropagationClient pre-msgpack-encoded its round-1
list-request body and passed the bytes — upstream LXMRouter's
`isinstance(data, list)` check failed silently and we got an empty
list back. This integration test exercises the full /get flow against
a real LXMRouter so that wire-shape regression class can't slip in
again.
"""
import os
import sys
import time
import RNS
import LXMF

os.environ.setdefault("RNS_LOG_DEST", "stderr")

_orig_replace = os.replace
def _safe_rename(src, dst):
    try:
        _orig_replace(src, dst)
    except (FileNotFoundError, PermissionError):
        pass
os.rename = _safe_rename
os.replace = _safe_rename

IDENTITY_PATH = os.path.expanduser("~/.reticulum-mobile-app-test-prop-identity")
CONFIG_DIR    = os.path.expanduser("~/.reticulum-mobile-app-test-prop-config")
STORAGE_DIR   = os.path.expanduser("~/.reticulum-mobile-app-test-prop-storage")
DEFAULT_TCP   = "rns.chicagonomad.net:4242"
TCP_TARGET    = os.environ.get("TEST_PROP_TCP", DEFAULT_TCP)
DISPLAY_NAME  = "Test Propagation Node"
NEEDLE        = "propagation roundtrip ok"

CONFIG_TEMPLATE = """\
[reticulum]
  enable_transport = False
  share_instance = No
  shared_instance_port = 37468
  instance_control_port = 37469
  panic_on_interface_error = No

[logging]
  loglevel = 4

[interfaces]

  [[Test Prop TCP]]
    type = TCPClientInterface
    interface_enabled = true
    target_host = {host}
    target_port = {port}
    name = test_prop_tcp
"""


RECIP_IDENTITY_PATH = os.environ.get(
    "RECIP_IDENTITY_PATH",
    os.path.expanduser("~/.reticulum-mobile-app-test-prop-recipient-identity"),
)


def main():
    if os.path.exists(IDENTITY_PATH):
        node_identity = RNS.Identity.from_file(IDENTITY_PATH)
    else:
        node_identity = RNS.Identity()
        node_identity.to_file(IDENTITY_PATH)

    # Recipient identity — kept on disk so re-runs against the same
    # Kotlin test don't require shuffling env vars.
    if os.path.exists(RECIP_IDENTITY_PATH):
        recipient = RNS.Identity.from_file(RECIP_IDENTITY_PATH)
        print(f"[prop] loaded recipient identity from {RECIP_IDENTITY_PATH}", flush=True)
    else:
        recipient = RNS.Identity()
        recipient.to_file(RECIP_IDENTITY_PATH)
        print(f"[prop] generated recipient identity at {RECIP_IDENTITY_PATH}", flush=True)
    recipient_hex = recipient.hash.hex()
    # RNS Identity stores the X25519 + Ed25519 raw privs as bytes on
    # `prv` (32 X25519) and `sig_prv` (32 Ed25519) — internal API but
    # stable. The Kotlin side reconstructs the same Identity from
    # these to derive the matching destination hash.
    recip_enc_priv = recipient.prv_bytes.hex() if hasattr(recipient, "prv_bytes") else recipient.prv.hex()
    recip_sig_priv = recipient.sig_prv_bytes.hex() if hasattr(recipient, "sig_prv_bytes") else recipient.sig_prv.hex()

    os.makedirs(CONFIG_DIR, exist_ok=True)
    os.makedirs(STORAGE_DIR, exist_ok=True)
    config_path = os.path.join(CONFIG_DIR, "config")
    if not os.path.exists(config_path):
        host, port = TCP_TARGET.split(":") if ":" in TCP_TARGET else (TCP_TARGET, "4242")
        with open(config_path, "w") as f:
            f.write(CONFIG_TEMPLATE.format(host=host, port=port))

    rns = RNS.Reticulum(configdir=CONFIG_DIR, loglevel=int(os.environ.get("PROP_LOGLEVEL", "4")))

    # LXMRouter in propagation mode. The `storagepath` keeps queue state
    # across restarts; deletion of the storage dir resets the test.
    router = LXMF.LXMRouter(identity=node_identity, storagepath=STORAGE_DIR)
    router.enable_propagation()

    # Build the canned LXMF for the recipient. The recipient identity
    # may not be locally cached yet — we only need its hash, since
    # propagation queues are keyed by destination hash, not identity.
    # Manufacture a synthetic destination handle for the addressing.
    recipient_hash = bytes.fromhex(recipient_hex)
    # LXMF wants a Destination object with a `.hash` matching the
    # recipient. We can't reconstruct a Destination from just a hash
    # (no public key), so use the propagation node's own
    # `propagate_lxmf_to_node` API path: build the LXMF, set its
    # destination_hash directly, and inject into the queue.
    sender_dest = RNS.Destination(
        node_identity, RNS.Destination.OUT, RNS.Destination.SINGLE,
        "lxmf", "delivery",
    )

    # Synthetic recipient destination — only its `.hash` matters for
    # the propagation queue lookup. Build a real Destination so LXMF
    # can encrypt the body to the recipient's pubkey.
    recipient_dest = RNS.Destination(
        recipient, RNS.Destination.OUT, RNS.Destination.SINGLE,
        "lxmf", "delivery",
    )
    recipient_hash = recipient_dest.hash

    lxm = LXMF.LXMessage(
        destination=recipient_dest,
        source=sender_dest,
        content=NEEDLE.encode("utf-8"),
        title="propagation test".encode("utf-8"),
        desired_method=LXMF.LXMessage.PROPAGATED,
    )
    # Sign with our identity. Without sign() the blob is not a valid
    # LXMF on the wire; with it the recipient still can't decrypt
    # (no ECDH possible without their pubkey), but the propagation
    # node itself just stores opaque bytes — decryption is the
    # recipient's problem.
    lxm.pack()
    # Inject directly into the propagation node's queue. LXMRouter's
    # internal queue-add API:
    if hasattr(router, "handle_outbound"):
        router.handle_outbound(lxm)
    else:
        # Newer LXMF builds expose .propagate_message — adapt as needed.
        router.propagate_message(lxm)

    # Print env vars the Kotlin test needs.
    print()
    print("=" * 64)
    print("LXMF PROPAGATION TEST NODE READY")
    print("=" * 64)
    print(f"LXMF_PROP_HASH={router.propagation_destination.hash.hex()}")
    print(f"LXMF_PROP_TCP_HOST={TCP_TARGET.split(':')[0]}")
    print(f"LXMF_PROP_TCP_PORT={TCP_TARGET.split(':')[1] if ':' in TCP_TARGET else '4242'}")
    print(f"LXMF_PROP_RECIPIENT_HASH={recipient_dest.hash.hex()}")
    print(f"LXMF_PROP_RECIPIENT_IDENT_HASH={recipient.hash.hex()}")
    print(f"LXMF_PROP_RECIPIENT_ENC_PRIV={recip_enc_priv}")
    print(f"LXMF_PROP_RECIPIENT_SIG_PRIV={recip_sig_priv}")
    print(f"LXMF_PROP_NEEDLE={NEEDLE}")
    print("=" * 64)
    print()
    print("[prop] one canned blob queued; announcing every ~5 min; first announce in 2s")

    next_announce = time.time() + 2
    try:
        while True:
            now = time.time()
            if now >= next_announce:
                router.announce(router.propagation_destination.hash)
                print(f"[prop] announce sent", flush=True)
                next_announce = now + 300
            time.sleep(0.5)
    except KeyboardInterrupt:
        print("[prop] stopping")


if __name__ == "__main__":
    main()
