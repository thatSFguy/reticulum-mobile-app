# Integrating agnostic-LoRa-Net into a mobile app

**Audience:** developers (and their AI coding agents) adding agnostic-LoRa-Net ("ALN")
mesh connectivity to a mobile app. This document is deliberately self-contained and
explicit — every rule states the field symptom you get when you skip it, because each
one was learned debugging a real integration (this repo's app, the first ALN mobile
client). If you are using an AI agent to write the integration, paste this whole file
into its context first.

**Sources of truth** (this doc summarizes them; they win on conflict):
- `agnostic-lora-net/docs/tcp-bridge.md` — wire contract (envelope, HDLC, reliability)
- `agnostic-lora-net/docs/mobile-app-testing.md` — phone-transport contract + test plan
- `agnostic-lora-net/docs/distributed-lookup-plan.md` — the directory (identity addressing)
- This repo — a complete, field-tested Kotlin implementation (file map in §11)

**Tested against:** node firmware **0.4.6**, this app **v1.2.56** (2026-06-11).

---

## 1. What you're integrating

An ALN **node** (RAK4631 / XIAO nRF52840 + SX1262) is a LoRa mesh router that exposes a
**BLE tunnel** to one phone. Your app sends it opaque datagrams addressed to other nodes;
the mesh routes them (multi-hop, per-hop ARQ); the far node hands them to *its* phone.

```
your app ◄─BLE/NUS─► node ◄─LoRa RF─► mesh (0..n hops) ◄─LoRa RF─► node ◄─BLE/NUS─► peer app
```

Two integration paths:

- **Path A — your app embeds Reticulum (RNS).** The tunnel carries your raw RNS packets
  unmodified; you get end-to-end crypto, links, LXMF, Resources for free. This is what
  this repo does. Extra RNS-specific routing rules apply (§8.2).
- **Path B — custom datagrams.** The payload is opaque to the node (≤768 B per frame,
  auto-fragmented over RF). The mesh gives you per-hop ARQ only — **no end-to-end
  delivery guarantee** — so you must build your own ack/retry.

The node is payload-agnostic either way. Everything below is Path-common except §8.2.

---

## 2. Contract card (pin this)

```
BLE:        Nordic UART Service (NUS), bonded with the node's 6-digit PIN
            service 6e400001-b5a3-f393-e0a9-e50e24dcca9e
            write   6e400002-…   (your app → node)
            notify  6e400003-…   (node → your app)
WRITES:     chunk EVERY write to ≤20 bytes  ← node FIFO limit, NOT the ATT MTU
            one writer lock for text commands AND frames (never interleave)
STREAM:     one byte stream, two layers demuxed by HDLC FLAG:
            inside 0x7E…0x7E  = tunnel frame  |  outside = ASCII console lines
HDLC:       FLAG=0x7E  ESC=0x7D  mask 0x20   (0x7E→7D 5E, 0x7D→7D 5D)
FRAME BODY: [u8 addr_type=0x01][u8 addr_len][addr little-endian][payload]
            outbound: addr = DESTINATION node id    inbound: addr = SOURCE node id
            addr_len = 4 today; READ THE FIELD (it becomes 16 later)
NODE ID:    4-byte mesh address, printed/advertised big-endian hex (e.g. 9828F51B);
            BLE name "AgnLoRa-<id>"; wire form is little-endian (9828F51B → 1B F5 28 98)
PAYLOAD:    ≤178 B rides one LoRa frame; larger is auto-fragmented (SAR) up to 8 KB;
            node→phone host frames capped at TUN_HOST_MAX = 768 B payload
DIRECTORY:  text lines, newline-terminated, hex ids ≤16 B (32 hex chars), CASE-SENSITIVE
            you send:   register <ID>     resolve <ID>     dirdump
            node sends: registered <n>-byte id at <NODE>
                        loc <ID> <NODE>            (resolve reply AND unsolicited push)
                        <ID> -> <NODE>  ttl=<s>s   (dirdump row)
            registrations: RAM-only, TTL 600 s, node re-floods yours every ~240 s,
            re-register on EVERY connect, ≥4 ids per client (4 = guaranteed minimum)
PHY:        SF11/BW250 ≈ 0.8 kbit/s — a 213 B frame is ~2-3 s of airtime PER HOP.
            Design timeouts adaptively (§9); never hardcode them to the current SF.
LIMITS:     ONE BLE client per node • one outbound SAR transfer at a time per node
            (fw ≥0.4.6 queues excess big frames 4-deep — console "[tun] … queued=K",
            "… dequeued", "… dup-drop" (identical payload already queued/airing),
            dropping only on overflow "DROPPED qfull"; fw ≤0.4.5 drops immediately,
            "DROPPED busy" — either way your retry layer covers it)
```

---

## 3. BLE bring-up

Order matters; each step exists because skipping it produced a real bug:

1. **Bond first.** The node's BLE is PIN-gated (`ble on`, `blepin <6 digits>` on its
   console, or `web/manage.html`). The user pairs in OS Bluetooth settings; your app then
   connects to the bonded device. The node advertises as `AgnLoRa-<nodeid>`.
2. **Connect GATT → discover services → find the NUS characteristics.**
3. **Request `CONNECTION_PRIORITY_HIGH` after connect — and periodically re-assert it**
   (Android: `BluetoothGatt.requestConnectionPriority`). The default connection interval
   lets some centrals — Samsung notably — let the link lapse during sustained LoRa TX, and
   some silently relax the interval back toward power-save minutes later.
   *Symptom if skipped at connect:* GATT `status=133`, CCCD write failures, a reconnect
   storm ~5 s after each mesh transmit. *Symptom if not re-asserted:* phone→node writes
   stay instant while node→phone notifications arrive batched tens of seconds late (looks
   like the app is processing inbound on a slow timer). Re-request every ~30 s.
4. **Request MTU 247, but ignore the answer for writes.** The negotiated ATT MTU is NOT
   the write limit — the node's per-write FIFO is. **Chunk every write to ≤20 bytes.**
   *Symptom if skipped:* small frames work, a ~221 B announce arrives truncated and
   silently dies inside the node. This bug shipped in two independent codebases
   (app v1.2.50 and node fw <0.4.4 in the other direction). Do not negotiate with it.
5. **Subscribe to notify (write the CCCD), and on Android 13+ override the 3-arg
   `onCharacteristicChanged(gatt, characteristic, value)`.** The deprecated 2-arg form
   receives a null `characteristic.value` on API 33+.
   *Symptom if skipped:* perfectly healthy link, outbound works, **zero inbound, ever.**
   Keep the 2-arg override too for API < 33.
6. **One writer lock.** Text commands and tunnel frames share the stream; serialize all
   writes so a `resolve …\n` never lands mid-frame.
   *Symptom if skipped:* sporadic frame corruption that looks like RF loss.
7. iOS: CoreBluetooth equivalents apply (NUS, ≤20 B `writeWithoutResponse` chunks, one
   serial write queue). Not yet field-tested — the rules above are transport-agnostic.

---

## 4. The byte stream: demux text vs frames

The NUS carries **two multiplexed layers**. The demux rule (port of the reference
`AgnosticLoraInterface.py::_read_loop`):

- `0x7E` (FLAG) **toggles** frame state: outside→inside starts collecting a frame,
  inside→outside emits it (if non-empty).
- Inside a frame: `0x7D` (ESC) means "next byte XOR 0x20".
- Outside a frame: bytes accumulate as ASCII; emit a line per `\n` (drop `\r`, skip
  blank lines). **Any FLAG clears the text accumulator** — a frame boundary aborts a
  partial console line, never the other way around.
- Node log lines never contain `0x7E`/`0x7D`, so text can't corrupt frames.
- Cap the frame buffer (this app: 508 B) and the line buffer (200 chars); reset the
  demux state on every (re)connect.

Compact Kotlin reference (full version: `shared/.../transport/NusDemux.kt`, tests in
`NusDemuxTest.kt` — notifications split frames at arbitrary byte boundaries, so feed
byte-at-a-time in your tests to prove reassembly):

```kotlin
class NusDemux(val onFrame: (ByteArray) -> Unit, val onTextLine: (String) -> Unit) {
    private var inFrame = false; private var escape = false
    private val frame = ArrayList<Byte>(); private val text = StringBuilder()
    fun feed(bytes: ByteArray) { for (b in bytes) feedByte(b) }
    private fun feedByte(b: Byte) {
        if (b == 0x7E.toByte()) {                     // FLAG toggles frame state
            if (inFrame && frame.isNotEmpty()) onFrame(frame.toByteArray())
            frame.clear(); escape = false; inFrame = !inFrame
            text.setLength(0)                         // boundary aborts any partial line
            return
        }
        if (inFrame) {
            if (escape) { frame.add((b.toInt() xor 0x20).toByte()); escape = false }
            else if (b == 0x7D.toByte()) escape = true
            else frame.add(b)
        } else when (b) {
            '\r'.code.toByte() -> {}
            '\n'.code.toByte() -> { if (text.isNotBlank()) onTextLine(text.toString()); text.setLength(0) }
            else -> text.append(b.toInt().toChar())
        }
    }
}
```

---

## 5. The tunnel envelope

Every HDLC frame body is a typed, length-prefixed address plus opaque payload:

```
[ u8 addr_type ][ u8 addr_len ][ addr bytes, LITTLE-endian ][ payload … ]

addr_type 0x01 = LOCATOR (node id).        ← the only live type — reject everything else
addr_type 0x02 = IDENTITY — reserved, NOT live. Current firmware silently discards
                 0x02 frames (no console line until fw adds a loud `DROPPED bad-envelope
                 type=N`). Do not emit — resolve ids to locators yourself via §6.

outbound (app → node): addr = destination node id → mesh routes it there
inbound  (node → app): addr = source node id      ← who originated it
```

**Always read `addr_len`** — it is 4 today and becomes 16 when node ids widen to a
pub-key hash. Hardcoding 4 is a flag-day bug you ship silently.

**Endianness worked example** — node id `9828F51B` (as printed in banners, heartbeats,
BLE name, `loc` lines — big-endian hex) goes on the wire little-endian:

```
id "9828F51B"  →  addr bytes 1B F5 28 98
```

**Full frame worked example** — send 5-byte payload `48 45 4C 4C 4F` ("HELLO") to node
`9828F51B`:

```
body:  01 04 1B F5 28 98 48 45 4C 4C 4F
HDLC:  7E 01 04 1B F5 28 98 48 45 4C 4C 4F 7E        (no 7E/7D in body → no escapes)
BLE:   one 13-byte write (≤20 B, so a single chunk)
```

If the body contained `7E` it would be sent as `7D 5E`; `7D` as `7D 5D`.

**Sizing:** ≤178 B payload = one LoRa frame (fastest). Bigger payloads are transparently
fragmented/reassembled by the node's SAR layer (CRC + missing-fragment retransmit), up to
8 KB — but each node runs **one outbound SAR transfer at a time**. On fw ≥0.4.6 a second
large frame ingested mid-transfer is queued (4-deep FIFO, `[tun] … queued=K`, started
after the active transfer plus a ~10 s NACK-grace window; dropped only on overflow,
`DROPPED qfull`); on fw ≤0.4.5 it is dropped immediately (`DROPPED busy`). Either way,
your retry layer (or RNS's) is the safety net. Node→phone delivery is capped at 768 B
payload per frame.

---

## 6. The directory: identity addressing

The mesh keeps a **distributed directory** mapping an opaque id (≤16 bytes, your choice —
RNS apps use their 16-byte destination hash) to the node currently serving it. All
directory traffic is **newline-terminated ASCII text lines** on the same NUS stream.

**Hex ids are CASE-SENSITIVE in lookups. Pick UPPERCASE everywhere and never deviate** —
register, resolve, and your parser must agree.

### Commands you send

| Command | Effect |
|---|---|
| `register <ID>\n` | Bind `<ID>` → this node, flooded network-wide within seconds. RAM-only: **re-register on every connect.** TTL 600 s; the node re-floods your binding every ~240 s on its own. Up to **4 ids per client** (re-registering the same id refreshes it). |
| `resolve <ID>\n` | Ask for `<ID>`'s node. Cached → instant `loc` line; miss → mesh QUERY, answer arrives seconds later as the same `loc` line. |
| `dirdump\n` | Enumerate the node's current directory (debug / reconciliation fallback). |

### Lines the node sends (parse these; ignore everything else)

| Line | Meaning |
|---|---|
| `registered <n>-byte id at <NODE>` | Register ack. **`<NODE>` is the node you are attached to — record it** (§8.1 rule 1). |
| `loc <ID> <NODE>` | `<ID>` is served by `<NODE>`. Arrives as: resolve reply, **unsolicited push** when the mesh learns a new/moved binding, and as the **initial full-directory dump** right after you subscribe to notify. One parser covers all three. |
| `<ID> -> <NODE>  ttl=<s>s` | `dirdump` row (same fact as a `loc` line). |
| `[hb] up=…  node=<NODE>  nbrs=… routes=… txq=… stk=…` | Heartbeat. `node=` also names your attached node. |
| `[dir] <n> binding(s):`, `[ble] …`, `[tun] …`, `[TX] …` | Diagnostics — ignore, but don't let them confuse your parser (match lines strictly). |

Regexes that work (anchor with match-entire so diagnostics can't false-positive):

```
loc line:     loc\s+([0-9A-Fa-f]+)\s+([0-9A-Fa-f]{8})
dirdump row:  ([0-9A-Fa-f]+)\s*->\s*([0-9A-Fa-f]{8})\s+ttl=\d+s?
register ack: registered\s+\d+-byte\s+id\s+at\s+([0-9A-Fa-f]{8})
heartbeat id: node=([0-9A-Fa-f]{8})      (only on lines starting "[hb]")
```

### Connect-time sequence

```
1. subscribe notify                  → node sends the full directory as loc lines
2. register <YOUR-ID>                → "registered 16-byte id at 9828F51B"
3. (optional) dirdump                → harmless duplicate of the initial dump
4. every loc line, solicited or not  → upsert binding; new peer node? greet it (§8)
```

There is **no announce flood primitive**. Peer discovery = the directory. Presence
broadcast = unicast per known peer node (§8.1 rule 5).

---

## 7. Client-side routing state

Your transport keeps four small tables (full reference implementation:
`shared/.../transport/AgnosticLoraRouter.kt` + `AgnosticLoraRouterTest.kt` — pure logic,
no platform deps, designed to be ported):

| Table | Key → Value | Written by | Read by |
|---|---|---|---|
| bindings | peer id → node | `loc` lines, dirdump rows, inbound-announce source (Path A) | outbound resolution, fanout |
| attached node | (single value) | register ack / heartbeat | every rule in §8.1 |
| pending | FIFO of unroutable outbound (bounded, e.g. 64) | failed resolution | flush on any binding change |
| reverse routes (Path A) | inbound packet trunc-hash → source node | inbound DATA | outbound delivery proofs |

Prune bindings not re-confirmed within 10 min (directory TTL 600 s); while anything is
pending, re-`resolve` its destination every ~5 s.

**All four tables are per-BLE-session.** Rebuild them from scratch on every (re)connect —
and anything *outside* the transport that caches routes derived from them (RNS link
sessions above all, §8.2 rule 9) must be invalidated when the session ends.

**Process inbound strictly in order, learn-then-deliver.** Run each inbound frame through
your routing tables (link pinning, reverse routes, reverse-path bindings) *before* handing
it to the protocol stack above, on a single ordered queue — not concurrently.
*Symptom if skipped:* your stack answers an inbound LINKREQ with an LRPROOF before the
link's route is pinned; the LRPROOF has nowhere to go and nothing ever flushes it, so
that link silently never establishes (the peer just sees a timeout and retries).
Corollary: when a link pin lands, re-scan the pending buffer — a link destination is
never resolvable any other way.

**That single inbound consumer is a single point of failure — make it unkillable.** Wrap
each item's processing so one exception can never break the loop, and hand the packet to
your protocol stack *before* any side-effect write you do in response (greeting a new
peer, flushing a buffered send). Writes can throw when the BLE link is failing — and
during a node reboot the link often stays nominally *connected* while writes fail — so an
unguarded write throw, or one sequenced before the stack handoff, strands every later
inbound packet.
*Symptom if skipped:* after a node reboot, inbound processing dies completely (no acks,
nothing delivered) while outbound keeps working, and only restarting the app process
clears it — because the link never dropped, so nothing rebuilt the dead consumer.

---

## 8. Routing rules (each one is a shipped bug)

### 8.1 Universal (both paths)

1. **Learn your attached node and NEVER address it.** Parse it from the register ack
   (authoritative, arrives every session) and heartbeats. Exclude it from every routing
   decision; purge any binding pointing at it.
   *Symptom if skipped:* frames to your own node never go RF — pre-fw-0.4.5 they vanished
   in the RF echo filter; 0.4.5+ **loops them back to you**. Looks exactly like packet
   loss (bridge issue BR-5).
2. **Drop inbound frames whose source is your attached node.** They are loopbacks of
   your own misaddressed frames (free debugging signal: log them loudly). Never *learn*
   from them — a looped-back frame can poison your routing tables.
3. **Filter your own id(s) out of `loc` handling.** Your registration echoes back in
   resolve replies; without the filter you "discover yourself" and greet your own node.
4. **Buffer-until-resolved; never drop while unresolved.** Bounded queue, flush in order
   the moment a binding appears.
   *Symptom if skipped:* your first/startup messages vanish; an RNS peer shows `no-path`
   forever (this one cost the desktop bring-up a day).
5. **Presence/broadcast = unicast fanout** to every known peer node (deduped), never to
   the attached node. Cache your latest presence payload and send it to each **newly
   discovered** peer node once, immediately — that's how a peer that just joined learns
   you exist without waiting for your next periodic broadcast.
6. **Never invent a "default route" that equals your attached node.** If you offer a
   static fallback/gateway setting, do not auto-fill it from the connected device's BLE
   name — that *is* the attached node, and stale persisted values outlive the code that
   wrote them (the BR-5 root cause).

### 8.2 Reticulum-specific (Path A)

Your RNS stack thinks it has one broadcast-ish interface; the tunnel is unicast-only.
Bridge the gap exactly like this:

1. **Id = your 16-byte destination hash, uppercase hex.** Register it on every connect.
   Multiple destinations (e.g. `lxmf.delivery` + `lxmf.propagation`) → register each
   (≤4).
2. **Announces fan out** per §8.1 rule 5 (an LXMF announce is ~213-221 B ≈ 2-3 s airtime
   per hop — this is why the mesh refuses to flood them).
3. **DEST_PLAIN packets (path requests etc.) fan out too — never buffer them.** Their
   dest hash is not a directory id; buffering queues them forever and spams resolves.
4. **Pin link traffic to the LINKREQUEST's node.** When a LINKREQ passes through in
   either direction, record `link_id → node` (link_id = truncated SHA-256 of the
   LINKREQ's hashable part). All later packets addressed to that link_id use the pinned
   node — link ids are not in the directory.
5. **Keep a reverse table for delivery proofs.** An opportunistic delivery proof is
   addressed to the *proved packet's truncated hash* — never a directory id (upstream
   RNS routes these via `Transport.reverse_table`). On every inbound DATA packet, pin
   `truncated_packet_hash → source node`; route outbound PROOFs through it. If the
   route is missing, **drop the proof** (the peer's retransmit re-pins it) — don't
   buffer it.
   *Symptom if skipped:* messages arrive but are never acked; the sender retries forever
   ("heavy loss" that isn't).
6. **Learn reverse paths from inbound announces:** announce's dest hash → source node is
   a free binding, ahead of the directory push.
7. **Suppress path requests for tunnel-reachable destinations** — there is no RNS
   transport relay on the mesh to answer them; they're pure airtime waste.
8. **MTU:** standard RNS MTU 500 fits (TUN_HOST_MAX = 768 covers it plus Resource SDUs
   of 464 B). No interface-level MTU clamp needed on fw ≥0.4.4. There is no RSSI/SNR
   sidecar — report null, and hide those fields in your UI for this transport.
9. **A link is only as alive as the BLE session that learned its route.** The link-route
   pins (rule 4) die with the session, but your RNS stack's link objects don't — nothing
   flips a link out of ACTIVE when a transport drops. If your stack caches established
   links for reuse, tie each one to the transport session it rode and invalidate it on
   disconnect; on reuse, check that tie — `state == ACTIVE` alone is NOT a liveness test.
   Re-establish fresh after every reconnect (one ~25 s handshake at SF11).
   *Symptom if skipped:* after any BLE drop/reconnect (node reboot, range blip), sends
   wedge forever on the "revived" link — link-addressed DATA buffers with nothing to
   resolve (a link id is not a directory id), while announces and the directory look
   perfectly healthy. The retry loop never recovers because its own liveness check is
   the same lying `ACTIVE` flag.

### Outbound decision procedure (Path A, complete)

```
route(packet):
  if packet is ANNOUNCE for one of MY ids: cache it; send to fanout()       # rule 8.2.2
  elif packet.dest_type == PLAIN:          send to fanout(), or defer if empty   # 8.2.3
  else:
    node = link_routes[dest]   if dest_type == LINK                         # 8.2.4
         | bindings[dest]      or reverse_routes[dest]                      # 8.2.5
         | fallback            (only if explicitly configured by the user)
    if node == attached_node:  treat as unroutable                          # 8.1.1
    if node:                   send to node (and pin link_id if LINKREQ)
    elif packet is PROOF:      drop                                         # 8.2.5
    else:                      buffer (bounded) + resolve dest every ~5 s   # 8.1.4

fanout(): all distinct binding nodes + configured fallback − attached_node  # 8.1.1/5
```

---

## 9. Timing: design for the PHY you can't see

The bench network runs SF11/BW250 (~0.8 kbit/s, range profile) but can be retuned to SF7
(~16× faster) without telling you. Numbers actually measured at SF11:

| Operation | Measured |
|---|---|
| 213 B frame, one hop | ~2-3 s airtime |
| RNS link establishment (LINKREQ → LRPROOF), 1 hop | ~25 s |
| Small LXMF message on a warm link | 1.3-2 s |
| First RNS path setup, cold | up to ~117 s — allow ≥300 s before declaring failure |

Rules:

1. **Never hardcode timeouts to the current SF.** Derive them from a measured RTT once
   you have one (this app: data-proof window = `8 × link RTT + 15 s`, floored at 30 s),
   with a generous ceiling for cold starts (this app: 4× the normal per-hop window for
   mesh-bound destinations).
2. **On timeout, retry on existing state — don't tear down and re-handshake.** A
   re-handshake storm on a half-duplex channel starves the retries themselves (observed:
   5 LINKREQs + 4 path requests in 3 min from one stuck message). One handshake, then
   resend data on the proven channel.
3. Rate-limit presence broadcasts (≥ minutes apart) and resolve retries (~5 s while
   pending, stop when the queue drains).

---

## 10. Bring-up test sequence & troubleshooting

Test in this order — each step isolates one layer (full procedure with pass criteria:
`agnostic-lora-net/docs/mobile-app-testing.md` §2):

1. **BLE survives LoRa traffic** — stay connected through `[TX]` bursts (else §3.3).
2. **Echo a frame** — round-trip bytes via a known-good peer; verify the `← from <node>`
   source id matches.
3. **register/resolve round-trip** — `registered …` ack then `loc …` reply proves your
   demux and parser.
4. **App ↔ desktop RNS peer** — against the proven `AgnosticLoraInterface.py` setup.
5. **App ↔ app across the mesh** — the real thing.

| Symptom | Cause | Fix |
|---|---|---|
| Sends fine, peer never receives | writes not chunked ≤20 B, or writer-lock interleaving | §3.4, §3.6 |
| Healthy link, zero inbound (Android 13+) | only the 2-arg notify callback overridden | §3.5 |
| Healthy link, zero inbound (any) | second BLE client on the node, or addressing the attached node | one client per node; §8.1.1 |
| `status=133`, reconnect storm after TX | default connection interval | §3.3 |
| Peer shows `no-path` forever | dropped while unresolved | §8.1.4 |
| Resolve never answers | hex case mismatch, missing `\n`, or parser anchored wrong | §6 |
| Messages arrive, acks never do | no reverse table for proofs | §8.2.5 |
| Inbound dies entirely (no acks/processing) but outbound is fine; only an app restart fixes it | a thrown exception (e.g. a write failing during a node reboot) killed your single inbound consumer | isolate each inbound item so one failure can't kill the loop; hand packets to the stack *before* any side-effect write (§7) |
| Inbound processed tens of seconds late while outbound stays instant | central relaxed the BLE connection interval back to power-save | re-assert `CONNECTION_PRIORITY_HIGH` periodically, not just once at connect (§3.3) |
| Sends wedge after a BLE reconnect; directory healthy | reusing a link from the previous session (`ACTIVE` flag lies) | §8.2.9 |
| A specific link never establishes; peer times out and retries | LRPROOF raced its LINKREQ's route pin (concurrent inbound processing) | §7 learn-then-deliver |
| Frames "lost" with clean RF; node logs `[tun] … loopback` | you addressed your own node | §8.1.1/2/6 |
| Large payloads never arrive, small ones do | node fw <0.4.4 (notify clamp) — or you, if you "optimized" chunking | flash ≥0.4.4; §3.4 |
| Second of two quick big sends hangs ~1 min | `[tun] … DROPPED busy` — SAR slot busy (fw ≤0.4.5) | flash ≥0.4.6 (queues 4-deep); retry covers it meanwhile; §5 |

**The free debugging signal:** any frame you receive whose source is your own attached
node is a frame *you* misaddressed (fw ≥0.4.5 loopback). Log it loudly in development.

---

## 11. Reference implementation map (this repo)

All protocol logic is platform-independent Kotlin (`shared/src/commonMain`) with unit
tests pinning every rule in this doc — port freely:

| Concern | File |
|---|---|
| Text/frame demux (§4) | `shared/src/commonMain/kotlin/.../transport/NusDemux.kt` (+ `NusDemuxTest.kt`) |
| Envelope encode/decode, endianness (§5) | `shared/src/commonMain/kotlin/.../transport/AgnosticLoraTunnel.kt` (+ test) |
| Directory parsing + all routing rules (§6-8) | `shared/src/commonMain/kotlin/.../transport/AgnosticLoraRouter.kt` (+ 21-test suite) |
| HDLC framing | `shared/src/commonMain/kotlin/.../transport/Hdlc.kt` |
| Android BLE glue (§3): chunked writes, writer lock, 3-arg callback, connection priority, loopback drop | `shared/src/androidMain/kotlin/.../platform/AgnosticLoraBleTransport.kt` |
| Adaptive timeouts (§9) | `ReticulumEngine.kt` — search `linkPatienceFor`, `effectiveDataTimeout` |
| Desktop/Python reference | `agnostic-lora-net/reticulum/interfaces/AgnosticLoraInterface.py` |

---

## 12. Limits & roadmap (so you don't build on sand)

- **One BLE client per node — by design**, not a temporary limitation: the node's
  SoftDevice runs a single peripheral link and its tunnel emit path assumes one attached
  client. A second subscriber steals the node's output. A shared-connection model needs a
  bridge app (a localhost TCP server speaking HDLC-framed packets, rnsd-style) — designed,
  not yet built.
- **`addr_type 0x02 IDENTITY` is reserved**, not live, and silently discarded by current
  firmware. Resolve ids yourself via §6.
- **Node ids widen 4 → 16 bytes** eventually (the 4-byte FICR id is an explicit
  placeholder for a pub-key-derived id, which is why `addr_len` and `LOC_ID_MAX = 16`
  exist). No timeline is committed. Surviving it is free if you read `addr_len` (§5)
  instead of assuming 4.
- **Treat node ids as opaque AND non-authenticating.** They are mesh routing addresses,
  not identities — today's FICR-folded ids can even collide. Never use a node id as a
  trust or identity anchor; that's what your app-layer (RNS) crypto is for.
- **Directory is RAM-only and unauthenticated** — anyone on the mesh can register any
  id. Path A inherits RNS's crypto (a hijacked binding can misroute but not read or
  forge); Path B must authenticate at the application layer.
- **The LoRa side is open by design** — BLE is PIN-gated, RF is not; app-layer crypto is
  the security boundary.
- Firmware floor for this doc: **0.4.4** (big-frame notify chunking), **0.4.5**
  recommended (self-frame loopback, own-binding excluded from the initial dump). **0.4.7**
  adds NACK-timer jitter + RX-aware TX deferral (timing only — no wire-format change;
  mixed-version networks interoperate). **0.5.x** adds CSMA/CAD listen-before-talk and a
  SAR completion-ACK (faster Resource transfers).

---

## Changelog

| Date | Change |
|---|---|
| 2026-06-11 | Initial publication (fw 0.4.5 / app v1.2.55). |
| 2026-06-11 | fw 0.4.6: SAR-busy frames now queue 4-deep (`queued=K`) instead of `DROPPED busy` (§2, §5, §10). |
| 2026-06-11 | Link/session lifecycle rules from the BLE-reconnect wedge (bridge BR-8): per-session routing tables, learn-then-deliver ordered inbound, `ACTIVE` is not liveness (§7, §8.2.9, §10). App v1.2.56. |
| 2026-06-11 | Inbound-consumer resilience + connection-interval keepalive (bridge BR-10): make the single inbound consumer unkillable, hand to the stack before side-effect writes, re-assert `CONNECTION_PRIORITY_HIGH` periodically (§3.3, §7, §10). App v1.2.57. ALN fact-check folded in: `my_regs` ≥4 guaranteed minimum, one-client-per-node by design, fw ≥0.4.6 SAR console lines, node ids non-authenticating, fw 0.4.7/0.5.x notes (§2, §5, §6, §12). |
