#!/usr/bin/env python3
"""
Minimal `rlr.telemetry` test beacon.

Announces a destination at the `rlr.telemetry` aspect (name_hash =
SHA256("rlr.telemetry")[:10] = `3eea23374d2a3aedf2cc`) carrying
GPS-bearing app_data in the simple `key=value;` text format the
mobile app's [Telemetry.kt:parseTelemetry] handles. Drives the
Nodes-tab map block end-to-end so we can verify our marker rendering
+ OSM tile fetch without waiting for organic rlr.telemetry traffic
on a public mesh.

Default location: Chicago Loop (-87.6298, 41.8781). Override with
the LAT / LON / BAT / NAME env vars.

Usage:
    pip install rns
    TEST_TEL_TCP=127.0.0.1:7822 python tools/test_rlr_telemetry.py
    TEST_TEL_TCP=RNS.MichMesh.net:7822 python tools/test_rlr_telemetry.py

Re-announces every 60s by default (override with INTERVAL_S). Stop
with Ctrl-C.
"""
import os
import sys
import time

os.environ.setdefault("RNS_LOG_DEST", "stderr")

# Windows rename-atomic safety — same workaround as the other test scripts.
_orig_replace = os.replace
def _safe_rename(src, dst):
    try:
        _orig_replace(src, dst)
    except (FileNotFoundError, PermissionError):
        pass
os.rename = _safe_rename
os.replace = _safe_rename

import RNS

IDENTITY_PATH = os.path.expanduser("~/.reticulum-mobile-app-test-rlr-identity")
CONFIG_DIR    = os.path.expanduser("~/.reticulum-mobile-app-test-rlr-config")
DEFAULT_TCP   = "127.0.0.1:7822"
TCP_TARGET    = os.environ.get("TEST_TEL_TCP", DEFAULT_TCP)

LAT  = float(os.environ.get("LAT",  "41.8781"))
LON  = float(os.environ.get("LON",  "-87.6298"))
BAT  = int  (os.environ.get("BAT",  "3970"))
NAME = os.environ.get("NAME", "rlr-test-beacon")
INTERVAL_S = int(os.environ.get("INTERVAL_S", "60"))

CONFIG_TEMPLATE = """\
[reticulum]
  enable_transport = False
  share_instance = No
  shared_instance_port = 37452
  instance_control_port = 37453
  panic_on_interface_error = No

[logging]
  loglevel = 4

[interfaces]

  [[Test RLR TCP]]
    type = TCPClientInterface
    interface_enabled = true
    target_host = {host}
    target_port = {port}
    name = test_rlr_tcp
"""


def main():
    if os.path.exists(IDENTITY_PATH):
        identity = RNS.Identity.from_file(IDENTITY_PATH)
        print(f"[rlr] loaded identity from {IDENTITY_PATH}", flush=True)
    else:
        identity = RNS.Identity()
        identity.to_file(IDENTITY_PATH)
        print(f"[rlr] new identity at {IDENTITY_PATH}", flush=True)

    os.makedirs(CONFIG_DIR, exist_ok=True)
    config_path = os.path.join(CONFIG_DIR, "config")
    if not os.path.exists(config_path):
        if ":" in TCP_TARGET:
            host, port = TCP_TARGET.rsplit(":", 1)
        else:
            host, port = TCP_TARGET, "4242"
        with open(config_path, "w") as f:
            f.write(CONFIG_TEMPLATE.format(host=host, port=port))
        print(f"[rlr] wrote config to {config_path} (TCP: {host}:{port})", flush=True)

    rns = RNS.Reticulum(configdir=CONFIG_DIR, loglevel=int(os.environ.get("RLR_LOGLEVEL", "4")))

    # rlr.telemetry destination — name_hash 3eea23374d2a3aedf2cc per
    # reference/PROTOCOL_NOTES.md:96.
    destination = RNS.Destination(
        identity, RNS.Destination.IN, RNS.Destination.SINGLE,
        "rlr", "telemetry",
    )

    print()
    print("=" * 64)
    print("RLR TELEMETRY TEST BEACON READY")
    print("=" * 64)
    print(f"DEST_HASH={destination.hash.hex()}")
    print(f"NAME={NAME}")
    print(f"LAT={LAT}  LON={LON}  BAT={BAT}")
    print(f"TCP={TCP_TARGET}")
    print(f"INTERVAL_S={INTERVAL_S}")
    print("=" * 64)
    print()

    next_announce = time.time()
    while True:
        if time.time() >= next_announce:
            # Mirror the upstream `reticulum-lora-repeater` format
            # documented in reference/PROTOCOL_NOTES.md:93. Mobile app's
            # parseTelemetry splits on `;`, then `=`.
            up_seconds = int(time.time())
            app_data_text = (
                f"name={NAME};bat={BAT};up={up_seconds};"
                f"lat={LAT};lon={LON};msl=180"
            )
            destination.announce(app_data=app_data_text.encode("utf-8"))
            print(f"[rlr] announce sent: {app_data_text}", flush=True)
            next_announce = time.time() + INTERVAL_S
        time.sleep(0.5)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("[rlr] stopping")
