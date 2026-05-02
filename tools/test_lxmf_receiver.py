#!/usr/bin/env python3
"""
Live LXMF receiver — uses real RNS + LXMF on this machine to act as a
test peer for the mobile app. Eliminates Sideband as a variable when
debugging "outbound from app doesn't reach the recipient."

Usage:
    pip install rns lxmf
    python tools/test_lxmf_receiver.py

What it does:
    1) Initializes RNS using your existing config under ~/.reticulum
       (or creates a default one). All TCP / RNode interfaces in that
       config become available.
    2) Generates (or loads) a fresh test identity for THIS receiver,
       stored in ~/.reticulum-mobile-app-test-identity.
    3) Stands up an LXMRouter delivery destination
       ("Mobile App Test Receiver"), announces every 5 minutes.
    4) Prints its destination hash so you can copy it into the app's
       Nodes tab as a manual entry, OR wait for the announce to
       propagate naturally.
    5) On every received LXMF message, logs source hash, content, and
       whether RNS auto-emitted a delivery proof back to the sender.

So the flow is:
    [your phone running the app] --TCP/BLE--> [some rnsd] --network-->
        [this rnsd] --> [this script]

If the app's outbound message reaches this script with the right
plaintext, the bug is Sideband-specific (config, ratchet, etc.). If
this script never sees the message, the bug is in our send path or in
the network between your transport and this PC.

Stop with Ctrl-C.
"""
import os
import sys
import time

os.environ.setdefault("RNS_LOG_DEST", "stderr")

try:
    import RNS
    import LXMF
except ImportError:
    print("Missing dependency. Install with:", file=sys.stderr)
    print("    pip install rns lxmf", file=sys.stderr)
    sys.exit(2)

DISPLAY_NAME = "Mobile App Test Receiver"
IDENTITY_PATH = os.path.expanduser("~/.reticulum-mobile-app-test-identity")
STORAGE_PATH = os.path.expanduser("~/.reticulum-mobile-app-test-receiver")

ANSI_OK = "\033[32m"
ANSI_INFO = "\033[36m"
ANSI_ERR = "\033[31m"
ANSI_RESET = "\033[0m"

def log(tag, msg, color=ANSI_INFO):
    ts = time.strftime("%H:%M:%S")
    print(f"{color}[{ts} {tag}]{ANSI_RESET} {msg}", flush=True)

def main():
    # Load or create the test identity. Using a stable file means the
    # destination hash is the same across runs, so the app's contact row
    # for this receiver doesn't churn.
    if os.path.exists(IDENTITY_PATH):
        identity = RNS.Identity.from_file(IDENTITY_PATH)
        log("init", f"loaded existing identity from {IDENTITY_PATH}", ANSI_OK)
    else:
        identity = RNS.Identity()
        identity.to_file(IDENTITY_PATH)
        log("init", f"created new identity at {IDENTITY_PATH}", ANSI_OK)

    # Standard RNS init — uses ~/.reticulum if present.
    rns = RNS.Reticulum()

    os.makedirs(STORAGE_PATH, exist_ok=True)
    router = LXMF.LXMRouter(identity=identity, storagepath=STORAGE_PATH)

    delivery_dest = router.register_delivery_identity(
        identity, display_name=DISPLAY_NAME
    )

    def message_received(message):
        src = RNS.prettyhexrep(message.source_hash) if message.source_hash else "???"
        try:
            content = message.content_as_string()
        except Exception:
            content = repr(message.content)
        sig = "OK" if message.signature_validated else "INVALID"
        log(
            "RX",
            f"from={src}  sig={sig}  content={content!r}",
            ANSI_OK if message.signature_validated else ANSI_ERR,
        )
        # LXMRouter automatically emits the delivery proof; nothing to do.

    router.register_delivery_callback(message_received)

    log("dest", f"display_name={DISPLAY_NAME!r}", ANSI_OK)
    log("dest", f"destHash={RNS.prettyhexrep(delivery_dest.hash)}", ANSI_OK)
    log("dest", f"identityHash={RNS.prettyhexrep(identity.hash)}", ANSI_OK)
    log("dest", f"announcing every ~5 min; first announce in 2s", ANSI_OK)

    # Announce loop.
    next_announce = time.time() + 2
    try:
        while True:
            now = time.time()
            if now >= next_announce:
                delivery_dest.announce()
                log("tx", "announce sent", ANSI_OK)
                next_announce = now + 300  # 5 min
            time.sleep(0.5)
    except KeyboardInterrupt:
        log("exit", "stopping (Ctrl-C)", ANSI_OK)

if __name__ == "__main__":
    main()
