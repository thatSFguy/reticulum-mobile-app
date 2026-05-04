#!/usr/bin/env python3
"""
Minimal NomadNet-style page node for the NomadNetLiveTest integration
test. Hosts a destination at the `nomadnetwork.node` aspect, registers
a request handler at `:/page/index.mu`, announces every 5 minutes.

Usage:
    pip install rns
    python tools/test_nomadnet_node.py

Prints the env vars to set on the Kotlin test side:

    NOMADNET_NODE_HASH=<hex>
    NOMADNET_TCP_HOST=rns.chicagonomad.net
    NOMADNET_TCP_PORT=4242
    NOMADNET_PAGE_PATH=:/page/index.mu
    NOMADNET_PAGE_NEEDLE=Hello

Then in another shell:
    cd shared
    NOMADNET_NODE_HASH=... ./gradlew testDebugUnitTest \\
        --tests io.github.thatsfguy.reticulum.NomadNetLiveTest

Stop with Ctrl-C.

This is a *minimal* NomadNet — it does not implement the full nomadnet
TUI, just enough to serve micron pages over an RNS Link's
REQUEST/RESPONSE flow, which is exactly what our app's fetchNomadPage
exercises.
"""
import os
import sys
import time

os.environ.setdefault("RNS_LOG_DEST", "stderr")

# Same Windows rename safety as the receiver — RNS's atomic rename
# fails on Windows when the destination exists.
_orig_replace = os.replace
def _safe_rename(src, dst):
    try:
        _orig_replace(src, dst)
    except (FileNotFoundError, PermissionError):
        pass
os.rename = _safe_rename
os.replace = _safe_rename

import RNS

IDENTITY_PATH = os.path.expanduser("~/.reticulum-mobile-app-test-nomad-identity")
CONFIG_DIR    = os.path.expanduser("~/.reticulum-mobile-app-test-nomad-config")
DEFAULT_TCP   = "rns.chicagonomad.net:4242"
TCP_TARGET    = os.environ.get("TEST_NOMAD_TCP", DEFAULT_TCP)
DISPLAY_NAME  = "NomadNet Test Node"

# Single canned page so the Kotlin test can assert on a known string.
PAGE_BODY = (
    "`!Welcome to the NomadNet Test Node`!\n"
    "\n"
    "This page is served by tools/test_nomadnet_node.py for the\n"
    "NomadNetLiveTest integration test.\n"
    "\n"
    "If our Kotlin client fetched this page successfully, the entire\n"
    "Link + REQUEST/RESPONSE protocol stack works end to end on the\n"
    "current TCP transport.\n"
    "\n"
    "Hello from Python RNS.\n"
)

CONFIG_TEMPLATE = """\
[reticulum]
  enable_transport = False
  share_instance = No
  shared_instance_port = 37448
  instance_control_port = 37449
  panic_on_interface_error = No

[logging]
  loglevel = 4

[interfaces]

  [[Test Nomad TCP]]
    type = TCPClientInterface
    interface_enabled = true
    target_host = {host}
    target_port = {port}
    name = test_nomad_tcp
"""


def main():
    if os.path.exists(IDENTITY_PATH):
        identity = RNS.Identity.from_file(IDENTITY_PATH)
        print(f"[nomad] loaded existing identity from {IDENTITY_PATH}", flush=True)
    else:
        identity = RNS.Identity()
        identity.to_file(IDENTITY_PATH)
        print(f"[nomad] created new identity at {IDENTITY_PATH}", flush=True)

    os.makedirs(CONFIG_DIR, exist_ok=True)
    config_path = os.path.join(CONFIG_DIR, "config")
    if not os.path.exists(config_path):
        if ":" in TCP_TARGET:
            host, port = TCP_TARGET.rsplit(":", 1)
        else:
            host, port = TCP_TARGET, "4242"
        with open(config_path, "w") as f:
            f.write(CONFIG_TEMPLATE.format(host=host, port=port))
        print(f"[nomad] wrote config to {config_path} (TCP: {host}:{port})", flush=True)

    rns = RNS.Reticulum(configdir=CONFIG_DIR, loglevel=int(os.environ.get("NOMAD_LOGLEVEL", "4")))

    # Hosting destination — IN means "we accept inbound traffic to this".
    destination = RNS.Destination(
        identity, RNS.Destination.IN, RNS.Destination.SINGLE,
        "nomadnetwork", "node",
    )
    destination.set_proof_strategy(RNS.Destination.PROVE_ALL)

    def page_handler(path, data, request_id, link_id, remote_identity, requested_at):
        # Returning bytes makes RNS RESPOND with this body inside a single
        # packet (or as a Resource if it exceeds MDU). The Kotlin client's
        # LinkSession.request() unwraps either form to bytes.
        print(f"[nomad] request: path={path!r} link={RNS.prettyhexrep(link_id) if link_id else None} from={RNS.prettyhexrep(remote_identity.hash) if remote_identity else 'anon'}", flush=True)
        return PAGE_BODY.encode("utf-8")

    # NomadNet path convention is ":/page/foo.mu" with a leading colon
    # (matches what our app's fetchNomadPage default sends and what real
    # NomadNet nodes register). Without the colon the path doesn't match.
    destination.register_request_handler(
        path=":/page/index.mu",
        response_generator=page_handler,
        allow=RNS.Destination.ALLOW_ALL,
    )

    # Print the env vars the Kotlin test needs.
    print()
    print("=" * 64)
    print("NOMADNET TEST NODE READY")
    print("=" * 64)
    print(f"NOMADNET_NODE_HASH={destination.hash.hex()}")
    print(f"NOMADNET_TCP_HOST={TCP_TARGET.split(':')[0]}")
    print(f"NOMADNET_TCP_PORT={TCP_TARGET.split(':')[1] if ':' in TCP_TARGET else '4242'}")
    print(f"NOMADNET_PAGE_PATH=:/page/index.mu")
    print(f"NOMADNET_PAGE_NEEDLE=Hello from Python RNS")
    print("=" * 64)
    print()
    print("[nomad] announcing every ~5 min; first announce in 2s")

    next_announce = time.time() + 2
    try:
        while True:
            now = time.time()
            if now >= next_announce:
                destination.announce()
                print(f"[nomad] announce sent", flush=True)
                next_announce = now + 300
            time.sleep(0.5)
    except KeyboardInterrupt:
        print("[nomad] stopping")


if __name__ == "__main__":
    main()
