## Highlights

- **Fixes RNode stuck in standby over BLE (#20).** On iOS the app could connect to an RNode over BLE and report `announce sent → [Ble]`, yet the RNode stayed in standby with no TX/RX activity and flat counters. The BLE write path wasn't honoring CoreBluetooth's write-without-response flow control (`canSendWriteWithoutResponse`), so writes issued while the send queue was full — including the radio-config burst right after connect that turns the radio on — were silently dropped before they left the phone. The transport now waits for CoreBluetooth to be ready before each write, so the radio config and announces actually reach the RNode.
- **Radio config now accepts fractional kHz bandwidth (#21).** The **BW (kHz)** field in Settings → Radio config previously rejected sub-kHz values such as `62.5`. The unit stays **kHz**; the field now allows a decimal point, parses the value as a decimal and rounds to Hz on save (`62.5 → 62500 Hz`), and renders the saved value back with fractional precision (`62.5`, while whole values still show as `500`). The radio-on log line no longer truncates fractional bandwidth via integer division.
- **User-pickable propagation node now on iOS.** Settings → Connection → Propagation → "Propagation node" lets you pin a specific propagation node (or leave it on Automatic — closest by hops with up to 5 fallbacks). This mirrors the Android picker and reaches the iOS build for the first time in this release.

## What didn't change

- No wire-format, protocol, or message-handling changes since `ios-v1.0.82`. The shared engine is byte-identical; the changes here are in the BLE transport and the Settings UI.

## Internal

- The propagation-node picker's SwiftUI `body` exceeded the Swift type-checker's "reasonable time" budget under Xcode 16.4 (the iOS build moved to 16.4 since `ios-v1.0.82`). Each `Section` was extracted into its own `some View` property and the per-node row into a typed helper. No behavior change. (`ios-v1.0.83` and `ios-v1.0.84` failed to build for this reason and shipped no artifact.)
