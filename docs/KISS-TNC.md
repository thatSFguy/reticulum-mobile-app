# KISS TNC over TCP

Experimental transport (Android `1.2.122`+): the app connects to a KISS TNC
that listens on TCP, instead of an RNode or an rnsd hub. The main use is the
**modem73** software modem running on the same phone, driving an ordinary
HF/VHF/UHF radio through an AIOC cable. Direwolf or any other TCP KISS TNC
should work the same way.

Turn it on under **Settings → Connection → Transports → "KISS TNC over TCP"**.
The default address is `127.0.0.1:8001` (modem73's KISS port).

## What the app sends

- Each Reticulum packet goes out as one plain KISS `CMD_DATA` frame, as upstream
  RNS does with `TCPClientInterface(kiss_framing=True)`. Inbound data frames have
  the KISS port nibble stripped, and non-data commands are ignored.
- **No configuration commands are ever sent.** On a generic TNC, KISS commands
  `0x01`–`0x05` mean TXDELAY / persistence / slottime / TXtail / duplex, not RNode
  radio settings. Configure the radio side in the modem app.
- The link is treated as RF, so announce pacing and retry timing follow the
  LoRa rules, not the TCP-hub ones.

## Known-good modem73 settings

Field-confirmed working on 2026-09-28 (first real-radio test of `1.2.122`):

| Setting    | Value      |
|------------|------------|
| Modulation | OFDM       |
| Constellation | QAM4096 |
| Code rate  | 2/3        |
| Frame size | Normal     |
| Postamble  | Off        |
| CSMA       | On         |
| Mode       | Sync       |

**Fragmentation MUST be off.** Reticulum does not work when the modem fragments
frames. The tester reported this from their setup. We haven't yet worked out
why (for example, whether the modem splits or merges frames at the KISS layer).

## Licensing (US)

Reticulum encrypts all traffic, so which license you transmit under matters.
This is a pointer, not legal advice. The terms of your own license decide.

- **Part 97 (amateur):** §97.113 prohibits messages encoded to obscure their
  meaning. Encrypted Reticulum traffic is a problem on ham bands.
- **Part 90 (private land mobile):** no general ban on encryption. The
  field-confirmed setup above runs under a Part 90 license. You still have to
  follow the license's own rules:
  - **Data emission:** each frequency's authorized emission designators must
    cover a data emission. A voice-only authorization may not.
  - **Bandwidth:** the signal must fit the channel's authorized bandwidth
    (typically 12.5 kHz narrowband on VHF/UHF).
  - **Equipment:** the transmitter must be certified for Part 90. A modified
    amateur radio generally isn't.
  - **Station ID:** identify on the license's schedule. The app doesn't send any
    station ID.

## Limitations

- It doesn't reconnect by itself after the app restarts yet: tap **Connect TNC**.
  The KISS connection isn't included in the saved connection state the app
  restores on a cold start.
- No RSSI/SNR is available on this path.
- Slow radio modes make messages slow.
- iOS has no UI for this transport.
