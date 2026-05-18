#!/usr/bin/env python3
"""Send /users directly to Michmesh's delivery hash from a fresh upstream
Python LXMF identity, attached to RNS.MichMesh.net:7822. Compares
behaviour against our Kotlin engine: if Python gets a reply where our
app didn't, the bug is on our side; if Python also gets silence, it's
fwdsvc-side (rate-limit, dedup, or operator policy).
"""

from __future__ import annotations
import os, sys, tempfile, threading, time

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

import RNS, LXMF

MICHMESH_DEST_HEX = "6e54f24ffb316ce6f5e5217e98664fa7"

def main() -> int:
    cfg_dir = tempfile.mkdtemp(prefix="users-direct-")
    with open(os.path.join(cfg_dir, "config"), "w", encoding="utf-8") as f:
        f.write("""[reticulum]
enable_transport = no
share_instance = no
[logging]
loglevel = 4
[interfaces]
  [[Default Interface]]
    type = AutoInterface
    enabled = no
  [[mich]]
    type = TCPClientInterface
    enabled = yes
    target_host = RNS.MichMesh.net
    target_port = 7822
""")
    RNS.Reticulum(cfg_dir, loglevel=4)

    identity = RNS.Identity()
    storage = os.path.join(cfg_dir, "lxmf-storage")
    os.makedirs(storage, exist_ok=True)
    router = LXMF.LXMRouter(identity=identity, storagepath=storage)

    inbox: list[LXMF.LXMessage] = []
    lock = threading.Lock()

    def on_delivery(m: LXMF.LXMessage) -> None:
        with lock:
            inbox.append(m)
        try:
            txt = m.content.decode("utf-8", errors="replace")
        except Exception:
            txt = repr(m.content)
        src = m.source_hash.hex() if m.source_hash else "?"
        print(f"\n[INBOX] from={src[:8]}  ({len(txt)} bytes) {txt[:300]!r}\n", flush=True)

    delivery = router.register_delivery_identity(identity, display_name="users-direct-oracle")
    router.register_delivery_callback(on_delivery)
    delivery.announce()
    print(f"[me] delivery={delivery.hash.hex()} identity={identity.hash.hex()}", flush=True)

    target_hash = bytes.fromhex(MICHMESH_DEST_HEX)

    # Wait for the path to Michmesh + recall its identity so we can encrypt.
    print(f"[target] {MICHMESH_DEST_HEX} — waiting for path / identity recall …", flush=True)
    deadline = time.time() + 60
    target_identity = None
    last_req = 0.0
    while time.time() < deadline:
        time.sleep(1)
        now = time.time()
        if not RNS.Transport.has_path(target_hash) and now - last_req > 5:
            RNS.Transport.request_path(target_hash)
            last_req = now
            print(f"[target] requesting path …", flush=True)
        target_identity = RNS.Identity.recall(target_hash)
        if target_identity is not None:
            print(f"[target] identity recalled (path known={RNS.Transport.has_path(target_hash)})", flush=True)
            break
    if target_identity is None:
        print("[fatal] could not recall target identity in 60s")
        return 2

    target_dest = RNS.Destination(
        target_identity, RNS.Destination.OUT, RNS.Destination.SINGLE,
        "lxmf", "delivery",
    )
    # Sanity-check: derived dest hash must match the one we were told.
    if target_dest.hash != target_hash:
        print(f"[fatal] dest hash mismatch: derived={target_dest.hash.hex()} expected={MICHMESH_DEST_HEX}")
        return 3
    print(f"[target] dest hash confirmed: {target_dest.hash.hex()}", flush=True)

    # Let our announce settle on fwdsvc's side.
    print("[settle] 5s …", flush=True)
    time.sleep(5)

    def send(cmd: str, method=LXMF.LXMessage.DIRECT) -> None:
        print(f"\n>>> {cmd} (method={method})", flush=True)
        m = LXMF.LXMessage(
            destination=target_dest, source=delivery,
            content=cmd.encode("utf-8"), title=b"",
            desired_method=method,
        )
        m.try_propagation_on_fail = False
        router.handle_outbound(m)

    def wait(pred, timeout: float) -> str | None:
        end = time.time() + timeout
        seen = 0
        while time.time() < end:
            time.sleep(1)
            with lock:
                cur = list(inbox)
            for msg in cur[seen:]:
                try:
                    t = msg.content.decode("utf-8", errors="replace")
                except Exception:
                    continue
                if pred(t):
                    return t
            seen = len(cur)
        return None

    # First, /version to confirm anything works at all.
    send("/version")
    v = wait(lambda t: "fwdsvc" in t.lower() or "version" in t.lower(), 45)
    print(f"[/version] {'PASS' if v else 'FAIL'}: {v[:200] if v else '(no reply in 45s)'}")

    time.sleep(2)

    # /join (idempotent for members — fwdsvc replies "already joined").
    send("/join")
    j = wait(lambda t: "join" in t.lower() or "alread" in t.lower(), 45)
    print(f"[/join] {'PASS' if j else 'FAIL'}: {j[:200] if j else '(no reply in 45s)'}")

    time.sleep(2)

    # /users — the original ask.
    send("/users")
    u = wait(lambda t: t.startswith("Users (") or t.startswith("No users"), 120)
    print(f"[/users] {'PASS' if u else 'FAIL'}: {u[:300] if u else '(no reply in 120s)'}")

    return 0 if u else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(130)
