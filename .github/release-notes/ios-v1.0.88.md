## Highlights — connection overhaul

A Meshtastic-style rework of connecting to RNodes, landing on both platforms.

- **Saved nodes.** The Connection screen now keeps a list of nodes you've connected to — tap one to (re)connect, swipe to forget. Switch between your RNode and a TCP node without re-scanning or retyping a host. Forgetting a node also stops it from silently auto-reconnecting.
- **Connected node shown by name.** The status line now reads e.g. `BLE (Rnode B4C1) — Connected` instead of just "BLE — Connected", so you can tell *which* node you're on at a glance.
- **"Add node" wording.** The BLE/RNode scan is now labelled "Add node" to match the Android flow. (iOS connects over BLE or TCP; Bluetooth Classic is Android-only.)

These build on the earlier iOS BLE fixes (CoreBluetooth write flow-control, `ios-v1.0.86`/`87`).

## What didn't change

- No wire-format, protocol, or message-handling changes. The shared engine is byte-identical; this release is the connection UI + persistence.
