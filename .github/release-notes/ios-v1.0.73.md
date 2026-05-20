## Highlights

- **Hash-derived avatar colors** (parity with `android-v1.2.11`) — every Messages / Nodes / destination-detail avatar now picks its background from the first 3 bytes of the destination hash via Meshtastic-Android's exact `Node.colors` algorithm (rec.601 luminance threshold for black-vs-white text contrast). Long contact lists are no longer a wall of identical chips; each peer keeps a stable, distinguishable color across launches and across both platforms.
