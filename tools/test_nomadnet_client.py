#!/usr/bin/env python3
"""
One-shot NomadNet page fetcher — mirrors what our app's
ReticulumEngine.fetchNomadPage does, end-to-end through Python RNS.

Usage:
    python tools/test_nomadnet_client.py <node_dest_hash> [path] [tcp_host:port]

Default path: :/page/index.mu
Default TCP : rns.chicagonomad.net:4242

Exit 0 = page fetched, 1 = link/request failed, 2 = setup failure.

This complements NomadNetLiveTest.kt — that one tests our Kotlin
client; this one tests the same protocol path but with Python RNS as
the client, which establishes a baseline of "the network + node work"
before we blame the Kotlin port for any failure.
"""
import os
import sys
import time
import threading

os.environ.setdefault("RNS_LOG_DEST", "stderr")

_orig_replace = os.replace
def _safe_rename(src, dst):
    try:
        _orig_replace(src, dst)
    except (FileNotFoundError, PermissionError):
        pass
os.rename = _safe_rename
os.replace = _safe_rename

import RNS

IDENTITY_PATH = os.path.expanduser("~/.reticulum-mobile-app-test-nomad-client-identity")
CONFIG_DIR    = os.path.expanduser("~/.reticulum-mobile-app-test-nomad-client-config")

CONFIG_TEMPLATE = """\
[reticulum]
  enable_transport = False
  share_instance = No
  shared_instance_port = 37458
  instance_control_port = 37459

[logging]
  loglevel = 4

[interfaces]
  [[Test Nomad Client TCP]]
    type = TCPClientInterface
    interface_enabled = true
    target_host = {host}
    target_port = {port}
    name = test_nomad_client_tcp
"""


def main():
    if len(sys.argv) < 2:
        print(f"usage: {sys.argv[0]} <node_dest_hash> [path] [tcp_host:port]", file=sys.stderr)
        sys.exit(2)
    node_hex = sys.argv[1].lower().replace(":", "").replace(" ", "")
    if len(node_hex) != 32:
        print(f"node_dest_hash must be 32 hex chars, got {len(node_hex)}", file=sys.stderr)
        sys.exit(2)
    page_path = sys.argv[2] if len(sys.argv) > 2 else ":/page/index.mu"
    tcp = sys.argv[3] if len(sys.argv) > 3 else "rns.chicagonomad.net:4242"

    if os.path.exists(IDENTITY_PATH):
        identity = RNS.Identity.from_file(IDENTITY_PATH)
    else:
        identity = RNS.Identity()
        identity.to_file(IDENTITY_PATH)

    os.makedirs(CONFIG_DIR, exist_ok=True)
    config_path = os.path.join(CONFIG_DIR, "config")
    if not os.path.exists(config_path):
        host, port = tcp.split(":") if ":" in tcp else (tcp, "4242")
        with open(config_path, "w") as f:
            f.write(CONFIG_TEMPLATE.format(host=host, port=port))

    rns = RNS.Reticulum(configdir=CONFIG_DIR, loglevel=4)
    print(f"[client] our identity = {RNS.prettyhexrep(identity.hash)}", flush=True)

    # Wait for path
    node_hash = bytes.fromhex(node_hex)
    print(f"[client] requesting path to {node_hex}...", flush=True)
    if not RNS.Transport.has_path(node_hash):
        RNS.Transport.request_path(node_hash)
        deadline = time.time() + 30
        while not RNS.Transport.has_path(node_hash) and time.time() < deadline:
            time.sleep(0.5)
    if not RNS.Transport.has_path(node_hash):
        print(f"[client] no path after 30s — aborting", file=sys.stderr)
        sys.exit(1)
    print(f"[client] path established", flush=True)

    node_identity = RNS.Identity.recall(node_hash)
    if node_identity is None:
        print(f"[client] no cached identity for {node_hex}", file=sys.stderr)
        sys.exit(1)

    node_dest = RNS.Destination(
        node_identity, RNS.Destination.OUT, RNS.Destination.SINGLE,
        "nomadnetwork", "node",
    )

    link = RNS.Link(node_dest)
    link_active = threading.Event()
    request_done = threading.Event()
    response_holder = {"data": None, "failed": False, "reason": None}

    def link_established(lnk):
        print(f"[client] link established", flush=True)
        link_active.set()

    def link_closed(lnk):
        print(f"[client] link closed", flush=True)
        if not request_done.is_set():
            response_holder["failed"] = True
            response_holder["reason"] = "link closed before response"
            request_done.set()

    link.set_link_established_callback(link_established)
    link.set_link_closed_callback(link_closed)

    if not link_active.wait(timeout=30):
        print(f"[client] LRPROOF never received within 30s", file=sys.stderr)
        sys.exit(1)

    def response_received(receipt):
        print(f"[client] response received: {len(receipt.response)} bytes", flush=True)
        response_holder["data"] = receipt.response
        request_done.set()

    def response_failed(receipt):
        response_holder["failed"] = True
        response_holder["reason"] = f"request failed: {receipt.status}"
        request_done.set()

    print(f"[client] sending request for {page_path!r}...", flush=True)
    link.request(
        path=page_path,
        data=None,
        response_callback=response_received,
        failed_callback=response_failed,
    )

    if not request_done.wait(timeout=30):
        print(f"[client] no response within 30s", file=sys.stderr)
        sys.exit(1)

    if response_holder["failed"]:
        print(f"[client] FAIL: {response_holder['reason']}", file=sys.stderr)
        sys.exit(1)

    body = response_holder["data"]
    if isinstance(body, bytes):
        text = body.decode("utf-8", errors="replace")
    else:
        text = str(body)
    print()
    print("=" * 64)
    print("PAGE RECEIVED")
    print("=" * 64)
    print(text)
    print("=" * 64)
    sys.exit(0)


if __name__ == "__main__":
    main()
