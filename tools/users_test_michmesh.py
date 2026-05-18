#!/usr/bin/env python3
"""Independent oracle test: send /join then /users to Michmesh Chat via
upstream Python RNS + LXMF and report whether replies come back.

If this script gets replies where the Kotlin engine doesn't, the bug is
on our side. If neither gets a reply, it's Michmesh's roster gating —
not an engine bug.

Discovery strategy: listen for inbound announces for up to 12 min (one
full fwdsvc announce interval). Print every lxmf.delivery destination
seen so the operator can spot Michmesh by display name. After discovery
(or timeout), if no automatic match was made, send /version
(no-membership-required) and /users to ANY lxmf.delivery dest whose
display name contains one of a generous keyword list.
"""

from __future__ import annotations
import os
import sys
import tempfile
import threading
import time

# Force UTF-8 stdout so emoji / non-ASCII display names from the announce
# stream don't crash the Windows console (cp1252 default).
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

import RNS
import LXMF


KEYWORDS = (
    "michmesh", "mich mesh", "mich-mesh", "mich_mesh",
    "michchat", "mich chat", "michigan", "mnv ",
    "chicagonomad", "chicago nomad",
    " chat", "fwd", "forward",
)


def write_config(cfg_dir: str) -> None:
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


def candidate_name(app_data: bytes) -> str:
    """Best-effort decode of fwdsvc / LXMF delivery app_data."""
    # Most LXMF delivery announces are msgpack [name, stamp_cost, ...]
    # where name is a `bin` field, OR raw UTF-8 bytes. Try msgpack first.
    try:
        import umsgpack
        parsed = umsgpack.unpackb(app_data)
        if isinstance(parsed, (list, tuple)) and parsed:
            n = parsed[0]
            if isinstance(n, (bytes, bytearray)):
                return n.decode("utf-8", errors="replace")
            if isinstance(n, str):
                return n
    except Exception:
        pass
    try:
        return app_data.decode("utf-8", errors="replace")
    except Exception:
        return repr(app_data)


def main() -> int:
    cfg_dir = tempfile.mkdtemp(prefix="users-test-")
    write_config(cfg_dir)
    print(f"[setup] cfg_dir = {cfg_dir}")
    RNS.Reticulum(cfg_dir, loglevel=4)

    identity = RNS.Identity()
    storage = os.path.join(cfg_dir, "lxmf-storage")
    os.makedirs(storage, exist_ok=True)
    router = LXMF.LXMRouter(identity=identity, storagepath=storage)

    inbox: list[LXMF.LXMessage] = []
    inbox_lock = threading.Lock()

    def on_delivery(message: LXMF.LXMessage) -> None:
        with inbox_lock:
            inbox.append(message)
        try:
            content = message.content.decode("utf-8", errors="replace")
        except Exception:
            content = repr(message.content)
        src = message.source_hash.hex() if message.source_hash else "?"
        print(f"\n[INBOX] from {src}: {content!r}\n", flush=True)

    delivery = router.register_delivery_identity(identity, display_name="users-test-oracle")
    router.register_delivery_callback(on_delivery)
    delivery.announce()
    print(f"[setup] my delivery hash = {delivery.hash.hex()}")
    print(f"[setup] my identity hash = {identity.hash.hex()}")

    seen: dict[str, tuple[bytes, str]] = {}  # ident_hex -> (dest_hash, display_name)
    discover_deadline = time.time() + 720  # 12 min
    matched: list[tuple[bytes, str]] = []

    print(f"[discover] listening up to 12 min for lxmf.delivery announces …")
    last_status = 0.0
    while time.time() < discover_deadline:
        time.sleep(1.0)
        # Walk RNS's known_destinations (internal but stable across recent
        # versions). Each entry: (timestamp, packet_hash, public_key, app_data).
        kd = getattr(RNS.Identity, "known_destinations", None) or {}
        for raw_ident, tup in list(kd.items()):
            if not isinstance(raw_ident, (bytes, bytearray)):
                continue
            ident_hex = raw_ident.hex()
            if ident_hex in seen:
                continue
            try:
                app_data = tup[3] if len(tup) > 3 else b""
            except Exception:
                continue
            name = candidate_name(app_data) if app_data else ""
            # Compute the lxmf.delivery dest hash. RNS.Destination has
            # several builder helpers; the most portable: instantiate a
            # Destination object and read its .hash.
            try:
                peer_ident = RNS.Identity.recall(raw_ident)
                if peer_ident is None:
                    continue
                dest = RNS.Destination(
                    peer_ident, RNS.Destination.OUT, RNS.Destination.SINGLE,
                    "lxmf", "delivery",
                )
                dest_hash = dest.hash
            except Exception:
                continue
            seen[ident_hex] = (dest_hash, name)
            print(f"[seen] ident={ident_hex} dest={dest_hash.hex()} name={name!r}", flush=True)
            low = name.lower()
            if any(k in low for k in KEYWORDS):
                print(f"[MATCH] {name!r} dest={dest_hash.hex()}", flush=True)
                matched.append((dest_hash, name))

        if matched:
            break

        # Periodic status.
        if time.time() - last_status > 30:
            print(f"[status] {len(seen)} lxmf.delivery dests seen so far; no keyword match yet", flush=True)
            last_status = time.time()

    if not matched:
        print("[discover] FAIL: no lxmf.delivery announce matched any keyword in 12 min")
        return 2

    # Use the first match.
    target_hash, target_name = matched[0]
    target_identity = RNS.Identity.recall(target_hash[:16])  # nope — recall takes ident_hash
    # Recall expects identity_hash, not dest_hash. The dest_hash is
    # SHA-256(name_hash || identity_hash)[:16]; we can't go from dest
    # back to identity. Use the stored ident_hex from the match.
    target_ident_hex = next(h for h, (d, _) in seen.items() if d == target_hash)
    target_identity = RNS.Identity.recall(bytes.fromhex(target_ident_hex))
    if target_identity is None:
        print(f"[error] cannot recall identity for {target_ident_hex}")
        return 3

    target_dest = RNS.Destination(
        target_identity, RNS.Destination.OUT, RNS.Destination.SINGLE,
        "lxmf", "delivery",
    )
    print(f"[target] {target_name!r} dest_hash={target_dest.hash.hex()}")

    # Settle to let our announce reach fwdsvc.
    print("[settle] 5s for our announce to propagate …")
    time.sleep(5)

    def send(content: str) -> None:
        print(f"[send] {content!r}")
        msg = LXMF.LXMessage(
            destination=target_dest,
            source=delivery,
            content=content.encode("utf-8"),
            title=b"",
            desired_method=LXMF.LXMessage.DIRECT,
        )
        msg.try_propagation_on_fail = False
        router.handle_outbound(msg)

    def wait_for(predicate, label: str, timeout: float) -> str | None:
        deadline = time.time() + timeout
        last_n = 0
        while time.time() < deadline:
            time.sleep(2)
            with inbox_lock:
                cur = list(inbox)
            for m in cur[last_n:]:
                try:
                    txt = m.content.decode("utf-8", errors="replace")
                except Exception:
                    continue
                if predicate(txt):
                    return txt
            last_n = len(cur)
        return None

    # /join first — fwdsvc gates /users on membership in most deployments.
    send("/join")
    join = wait_for(lambda t: "join" in t.lower() or "alread" in t.lower(),
                    "/join", 60.0)
    print(f"[/join] reply: {join!r}")

    time.sleep(1.0)

    send("/users")
    users = wait_for(lambda t: t.startswith("Users (") or t.startswith("No users"),
                     "/users", 120.0)
    if users:
        print(f"[/users] PASS — {len(users)} bytes: {users[:300]}")
        return 0

    print(f"[/users] TIMEOUT — no /users-shaped reply in 120s")
    print(f"[inbox] {len(inbox)} message(s) total during test:")
    with inbox_lock:
        for m in inbox:
            try:
                txt = m.content.decode("utf-8", errors="replace")
            except Exception:
                txt = repr(m.content)
            src = m.source_hash.hex() if m.source_hash else "?"
            print(f"  from {src}: {txt[:200]!r}")
    return 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("\n[abort] interrupted")
        sys.exit(130)
