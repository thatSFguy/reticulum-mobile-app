#!/usr/bin/env python3
"""
One-shot LXMF sender — uses the test receiver's identity + storage to
send a single opportunistic LXMF message to a target destination,
then waits up to 30s for the delivery proof.

Usage:
    python tools/test_lxmf_sender.py <recipient_dest_hash> [content]

Requires the test receiver to be STOPPED before running (one RNS
instance per configdir). The receiver caches known destinations to
~/.reticulum-mobile-app-test-config/storage/destinations, so this
sender inherits everything the receiver has seen.

Exit 0 = delivered, 1 = no proof, 2 = setup failure.
"""
import os
import sys
import time

os.environ.setdefault("RNS_LOG_DEST", "stderr")

# Same Windows rename safety as the receiver.
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

APP_NAME      = "lxmf"
ASPECT        = "delivery"

# Default = reuse receiver's identity + configdir (one-process-only, requires
# stopping the receiver first). With --separate-identity, use a fresh
# identity + configdir so receiver + sender can run side by side as
# independent RNS instances against the same rnsd.
USE_SEPARATE = "--separate-identity" in sys.argv
if USE_SEPARATE:
    sys.argv.remove("--separate-identity")
    IDENTITY_PATH = os.path.expanduser("~/.reticulum-mobile-app-test-sender-identity")
    STORAGE_PATH  = os.path.expanduser("~/.reticulum-mobile-app-test-sender-storage")
    CONFIG_DIR    = os.path.expanduser("~/.reticulum-mobile-app-test-sender-config")
else:
    IDENTITY_PATH = os.path.expanduser("~/.reticulum-mobile-app-test-identity")
    STORAGE_PATH  = os.path.expanduser("~/.reticulum-mobile-app-test-receiver")
    CONFIG_DIR    = os.path.expanduser("~/.reticulum-mobile-app-test-config")

DEFAULT_TCP = "rns.chicagonomad.net:4242"
TCP_TARGET = os.environ.get("TEST_SENDER_TCP", DEFAULT_TCP)

CONFIG_TEMPLATE = """\
[reticulum]
  enable_transport = False
  share_instance = No
  shared_instance_port = 37438
  instance_control_port = 37439
  panic_on_interface_error = No

[logging]
  loglevel = 4

[interfaces]

  [[Test Sender TCP]]
    type = TCPClientInterface
    interface_enabled = true
    target_host = {host}
    target_port = {port}
    name = test_sender_tcp
"""

ANSI_OK = "\033[32m"
ANSI_INFO = "\033[36m"
ANSI_ERR = "\033[31m"
ANSI_RESET = "\033[0m"

def log(tag, msg, color=ANSI_INFO):
    ts = time.strftime("%H:%M:%S")
    print(f"{color}[{ts} {tag}]{ANSI_RESET} {msg}", flush=True)

def main():
    if len(sys.argv) < 2:
        print(f"usage: {sys.argv[0]} <recipient_dest_hash> [content]", file=sys.stderr)
        sys.exit(2)

    target_hex = sys.argv[1].lower().replace(":", "").replace(" ", "")
    if len(target_hex) != 32:
        log("err", f"recipient_dest_hash must be 32 hex chars, got {len(target_hex)}", ANSI_ERR)
        sys.exit(2)
    content = sys.argv[2] if len(sys.argv) > 2 else "test from python sender"
    target_hash = bytes.fromhex(target_hex)

    if os.path.exists(IDENTITY_PATH):
        identity = RNS.Identity.from_file(IDENTITY_PATH)
        log("init", f"loaded existing sender identity {RNS.prettyhexrep(identity.hash)}", ANSI_OK)
    else:
        identity = RNS.Identity()
        identity.to_file(IDENTITY_PATH)
        log("init", f"created new sender identity at {IDENTITY_PATH}", ANSI_OK)

    # Write isolated config if not present.
    os.makedirs(CONFIG_DIR, exist_ok=True)
    config_path = os.path.join(CONFIG_DIR, "config")
    if not os.path.exists(config_path):
        if ":" in TCP_TARGET:
            host, port = TCP_TARGET.rsplit(":", 1)
        else:
            host, port = TCP_TARGET, "4242"
        with open(config_path, "w") as f:
            f.write(CONFIG_TEMPLATE.format(host=host, port=port))
        log("init", f"wrote sender config to {config_path} (TCP: {host}:{port})", ANSI_OK)

    rns = RNS.Reticulum(configdir=CONFIG_DIR, loglevel=4)
    log("init", "RNS started", ANSI_OK)

    os.makedirs(STORAGE_PATH, exist_ok=True)
    router = LXMF.LXMRouter(identity=identity, storagepath=STORAGE_PATH)
    source_dest = router.register_delivery_identity(identity, display_name="Mobile App Test Receiver")
    log("dest", f"our source dest = {RNS.prettyhexrep(source_dest.hash)}", ANSI_OK)

    # Wait briefly for RNS to settle and verify we know the recipient.
    log("wait", "waiting 8s for RNS to attach + look up recipient identity...", ANSI_INFO)
    time.sleep(8)

    recipient_identity = RNS.Identity.recall(target_hash)
    if recipient_identity is None:
        log("err", f"no cached identity for {target_hex} — request a path first or wait for their announce", ANSI_ERR)
        log("hint", "the receiver must have seen the recipient's announce; check storage/destinations", ANSI_INFO)
        # Try a path request and wait
        log("wait", "issuing RNS.Transport.request_path and waiting 15s...", ANSI_INFO)
        RNS.Transport.request_path(target_hash)
        time.sleep(15)
        recipient_identity = RNS.Identity.recall(target_hash)
        if recipient_identity is None:
            log("err", "still no identity for recipient; aborting", ANSI_ERR)
            sys.exit(2)

    recipient_dest = RNS.Destination(
        recipient_identity,
        RNS.Destination.OUT,
        RNS.Destination.SINGLE,
        APP_NAME, ASPECT,
    )
    log("send", f"recipient identity OK; building LXMF to {RNS.prettyhexrep(recipient_dest.hash)}", ANSI_OK)

    msg = LXMF.LXMessage(
        destination=recipient_dest,
        source=source_dest,
        content=content,
        title="",
        desired_method=LXMF.LXMessage.DIRECT,
    )

    state_evt = {"final": None}
    def on_delivered(message):
        state_evt["final"] = "delivered"
        log("RX-PROOF", f"DELIVERED — content was: {content!r}", ANSI_OK)
    def on_failed(message):
        state_evt["final"] = "failed"
        log("FAIL", "delivery failed", ANSI_ERR)

    msg.register_delivery_callback(on_delivered)
    msg.register_failed_callback(on_failed)

    router.handle_outbound(msg)
    log("send", f"handed to LXMRouter; awaiting proof up to 30s...", ANSI_INFO)

    deadline = time.time() + 30
    while time.time() < deadline and state_evt["final"] is None:
        time.sleep(0.5)

    if state_evt["final"] == "delivered":
        log("done", "✓ delivery proof received", ANSI_OK)
        sys.exit(0)
    elif state_evt["final"] == "failed":
        log("done", "✗ explicit failure callback fired", ANSI_ERR)
        sys.exit(1)
    else:
        log("done", "✗ no proof within 30s", ANSI_ERR)
        sys.exit(1)

if __name__ == "__main__":
    main()
