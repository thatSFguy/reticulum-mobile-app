## Highlights

- **Fixes the slow BLE connect introduced in 1.0.86.** The CoreBluetooth write flow-control added in 1.0.86 waited for the radio before *every* write, including the first — but iOS doesn't report write-readiness until after a write has been issued, so each radio-config command stalled on a timeout and the connection took ~20–30s to come up. The app now only waits between writes once the link is actually streaming, so connecting is fast again while still respecting flow control on larger transfers (announces, attachments).
- **Connect log now shows the radio config that was applied.** On BLE connect you'll see a `RNode: radio config applied — <freq> MHz, BW <bw> kHz, SF … CR … <tx> dBm` line in the in-app log, matching Android. This makes it possible to confirm from the log whether the radio settings actually reached the RNode.

## Status on "RNode stays in standby" (#20)

Still being worked. Testing against a BLE RNode on Android confirms the shared "configure radio + turn it on" sequence is correct (the RNode transmits and receives), which narrows the iOS standby report to the BLE write path specifically — the area these last two releases have been fixing. If your RNode still doesn't leave standby on 1.0.87, the new `radio config applied` log line will tell us whether the config is reaching it; please share the log.

## What didn't change

- No wire-format, protocol, or message-handling changes. The shared engine is byte-identical; the changes here are confined to the iOS BLE transport and logging.
