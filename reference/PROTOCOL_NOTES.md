# Reticulum / LXMF Interop Findings

This document captures the non-obvious protocol details that had to be
nailed down to make the web client interoperate with stock Sideband,
MeshChat, and other Reticulum peers. Each finding is either something
the upstream RFC/spec is silent on, something that only appears in the
source of the reference implementation, or something where the
obvious-looking code path is wrong for a specific reason.

All of these were discovered or verified while building the Phase 3 /
Phase 4 / Phase 5 work in this repo. Where a specific commit applied
the fix, it is referenced. Where a finding is based on reading upstream
source, the file and line are cited. The diagnostic tools used to verify
each finding are listed in the final section and live under `tools/`.

Upstream references used throughout this document:

* `reticulum-lora-repeater/.pio/libdeps/Faketec/microReticulum/src/` —
  Chris Attermann's C++ port of Reticulum. This was the primary source
  because it is local, readable, and matches the upstream Python closely
  enough that byte layouts are reliable.
* `micropython-reticulum-master/firmware/urns/lxmf.py` — an older
  "lite" Python reference of LXMF. Useful for the LXMF wire format and
  pack/unpack layout.
* `/c/Users/rob/AppData/Local/Programs/Python/Python313/site-packages/RNS/` —
  the installed Python RNS 1.1.4 used by the `tools/` verifiers.

---

## 1. Destination hash formula

### Finding

A SINGLE destination's hash (the 16-byte address you see in announces
and use as the packet header `destination_hash`) is computed as:

    name_hash = SHA256("lxmf.delivery")[:10]
    dest_hash = SHA256(name_hash || identity.hash)[:16]

The hex-encoded identity hash is **not** part of the `name_hash`
input. It only appears in the human-readable `Destination.name` field
and nowhere else on the wire.

### Why it matters

There is an earlier commit in this repo (`2faf24a`, reverted by
`2103dcc`) that wrongly appended the identity hexhash to the name
string before hashing. That produced destination hashes incompatible
with every other Reticulum node in the network — Sideband computed a
different value from our public key and addressed its replies to that
other value, and we never saw them.

### Source

* `reticulum-lora-repeater/.pio/libdeps/Faketec/microReticulum/src/Destination.cpp`
  lines 115–135 — the static `Destination::hash()` and `Destination::name_hash()`
  methods. Both call `expand_name({Type::NONE}, app_name, aspects)`,
  which is the `identity=None` branch of `expand_name`, which does
  **not** append the hex hash.
* The inline Python references in the same file (`//p name_hash = ...`)
  quote the upstream Python directly.

### Fix

Commits `2103dcc` (revert) and the final `js/identity.js`
`computeDestinationHash` / `computeNameHash`. See also
`tools/identity_info.py`, which reproduces the derivation offline and
produces a destination hash that matches the webclient's derivation.

---

## 2. Announce name-hash filtering

### Finding

The 10-byte `name_hash` field inside an announce payload identifies
which application destination the announce belongs to. Multiple
unrelated application destinations share the same on-wire announce
format (same signature scheme, same header type, same flag bits),
which means a naive "accept every signed announce as a contact"
implementation accumulates rows for repeater telemetry beacons,
heartbeat destinations, auxiliary non-LXMF endpoints on the same
identity as a real LXMF peer, and anything else on the mesh.

The web client must filter incoming announces by matching their
`name_hash` against `SHA256("lxmf.delivery")[:10]` before treating
them as contacts, and should persist the matched `name_hash` on the
saved contact row so future loads can verify it.

### Why it matters

Contact list pollution, with display names like
`bat=3952;up=30;hpf=90720;ro=1;pin=2;pout=2;lat=43.160099;lon=-85.645770;msl=280`
from the `reticulum-lora-repeater`'s telemetry destination. Those
come from `Destination(identity, IN, SINGLE, "rlr", "telemetry")`,
which has `name_hash = SHA256("rlr.telemetry")[:10]`, not the LXMF
one.

### Fix

Commit `ba4cbc2` added the filter and persistence, and a startup
cleanup pass that purges legacy rows whose stored `name_hash` does
not match. Commit `15596a4` tightened it to also purge legacy rows
that have no `name_hash` at all.

---

## 3. Identity private-key byte order

### Finding

RNS `Identity.load_private_key(prv_bytes)` expects `prv_bytes` to be
the 64-byte concatenation `[enc_x25519_seed(32) || sig_ed25519_seed(32)]`,
in that order.

The web client's `Export Identity` JSON stores each half as a named
field (`encPrivKey`, `sigPrivKey`), so there is no ambiguity on the JS
side, but any Python tool that loads the JSON and then passes the
bytes to `Identity.load_private_key` must concatenate `enc + sig`, not
the reverse. Getting this backwards produces a completely different
identity hash and destination hash.

### Source

`RNS/Identity.py` `Identity.load_private_key`:

    self.prv_bytes     = prv_bytes[:Identity.KEYSIZE//8//2]   # first half, X25519
    self.sig_prv_bytes = prv_bytes[Identity.KEYSIZE//8//2:]   # second half, Ed25519

### Fix

`tools/rns_responder.py` and `tools/verify_announce.py` both do
`identity.load_private_key(enc_seed + sig_seed)`.

---

## 4. Web Crypto AES-CBC auto-pads PKCS#7

### Finding

The browser `crypto.subtle.encrypt({name: "AES-CBC", iv}, key, data)`
API **always** applies PKCS#7 padding on encrypt and always strips it
on decrypt. There is no `AES-CBC-NO-PADDING` option.

If the application also pads the plaintext manually before passing it
to `crypto.subtle.encrypt`, the result is double-padded: the manual
padding becomes part of the plaintext, then Web Crypto adds its own
full 16-byte block of `0x10`s on top.

On decrypt, Web Crypto strips the outer layer, and then the manual
`pkcs7Unpad` runs against already-unpadded bytes, reads a random byte
from the real plaintext as a padding length, and throws
`PKCS7: invalid padding` — even though the ciphertext is perfectly
valid and the key is correct.

### Why it matters

This was the cause of every inbound LXMF opportunistic message
failing decryption with `PKCS7: invalid padding` until commit
`205fa2d`. Outbound messages "worked" in the same broken state only
because Python's `cryptography` library does manual PKCS#7 unpad once
and tolerates the trailing junk when handing the bytes to msgpack;
msgpack happens to ignore bytes past the end of its decoded value.

### Fix

Commit `205fa2d` removed the manual `pkcs7Pad` / `pkcs7Unpad` calls
from `js/crypto.js` `tokenEncrypt` / `tokenDecrypt`. Reticulum's wire
format is exactly what Web Crypto produces:

    iv(16) + raw AES-CBC(PKCS#7(plaintext)) + hmac(32)

with no manual padding step.

---

## 5. Reticulum Token key derivation

### Finding

For opportunistic single-packet encryption, Reticulum derives a
64-byte Token key from an X25519 ECDH shared secret via HKDF-SHA256
with:

* `ikm`      = X25519 shared secret
* `salt`     = recipient's 16-byte identity hash
* `info`     = empty bytes
* `length`   = 64

The 64-byte derived key is split `derived[0:32] = HMAC-SHA256 signing
key, derived[32:64] = AES-256 encryption key`.

For Link-delivered packets, the same formula is used but with
`salt = link_id` instead of identity hash, and the shared secret is
X25519(our ephemeral priv, peer's ephemeral X25519 pub from the
LINKREQUEST).

### Source

`reticulum-lora-repeater/.pio/libdeps/Faketec/microReticulum/src/Link.cpp`
`Link::handshake` lines 267–288:

    _object->_derived_key = Cryptography::hkdf(
        derived_key_length,
        _object->_shared_key,
        get_salt(),      // returns _link_id
        get_context()    // returns empty
    );

### Verified by

`tools/verify_lrproof.py` phase-1 self test, which does a full
sign-and-verify round trip using RNS's own `HKDF.hkdf` and gets the
same 64-byte derived key that our webclient's `hkdfDerive` produces.

---

## 6. LXMF source_hash field is the destination hash, not the identity hash

### Finding

The 16-byte `source_hash` field in the LXMF packed payload is the
**LXMF delivery destination hash** of the sender, not the sender's
identity hash. Receivers key their contact table on destination
hashes and look up the sender by `source_hash`. If the sender puts
its identity hash there instead, the receiver can't find the sender
in its contact table and renders the message as `Anonymous Peer`
(Sideband) or similar.

### Source

`micropython-reticulum/firmware/urns/lxmf.py` `LXMessage.pack`:

    hashed_part += self._destination.hash  # recipient dest hash
    hashed_part += self._source.hash       # SENDER's LXMF destination hash

and `unpack_from_bytes` mirrors it.

### Fix

Commit `f508519` changed `sendMessage` to pass `myDestHash` as the
`source_hash` argument to `packMessage`, not `myIdentity.hash`.

---

## 7. LXMF wire format differs between opportunistic and link delivery

### Finding

The LXMF "packed" container is always:

    destination_hash(16) || source_hash(16) || signature(64) || msgpack(payload)

**But** for opportunistic single-packet delivery, the sender strips
the leading `destination_hash` before putting the bytes into the RNS
packet — the RNS packet header already carries the destination, so
repeating it in the payload would waste 16 bytes. The on-wire
opportunistic payload is therefore:

    source_hash(16) || signature(64) || msgpack(payload)

For Link-delivered messages, the RNS packet header carries the
`link_id`, not the destination hash, so the LXMF container is sent
whole with its leading `destination_hash` intact. The on-wire link
payload is:

    destination_hash(16) || source_hash(16) || signature(64) || msgpack(payload)

### Why it matters

The web client needs two unpack paths: one that takes the destination
hash from the RNS packet header (opportunistic) and one that takes it
from the first 16 bytes of the decrypted link plaintext (link). If
you use the wrong one, signature verification fails because the
`hashed_part` is built from a destination hash the sender never
signed over.

### Source

`micropython-reticulum/firmware/urns/lxmf.py`:

* `LXMessage.send`: `data = self.packed[self.DESTINATION_LENGTH:]` for
  OPPORTUNISTIC — strips the first 16 bytes.
* `_send_direct.on_established`: `link.send(message.packed, CTX_NONE)` —
  sends the full packed including the leading destination hash.
* `LXMessage.unpack_from_bytes`: always parses
  `destination_hash = lxmf_bytes[:DL]` first.

### Fix

`js/lxmf.js` `unpackLinkMessage` extracts the leading destination hash
and hands the remaining bytes to the shared `unpackMessage`. Link
delivery path in `js/app.js` `handleLinkData` calls
`unpackLinkMessage`; opportunistic path in `handleData` calls
`unpackMessage` directly with `myDestHash`.

---

## 8. LXMF stamp handling for signature verification

### Finding

Modern Sideband appends a **stamp** (proof-of-work or token) as the
5th element of the msgpack payload array. The LXMF signature,
however, is computed over a **re-encoding** of just the first four
elements (timestamp, title, content, fields), with the stamp
excluded.

On receive, the verifier must:

1. Decode the msgpack payload.
2. If it has more than 4 elements, drop the 5th element.
3. Re-encode the first 4 elements with the same msgpack library.
4. Use the re-encoded bytes in the `hashed_part` that feeds the
   signature verifier.

### Risk

`@msgpack/msgpack` and `umsgpack` are mostly but not exactly
byte-compatible. Decoding and re-encoding can drift if the decoder
chooses a narrower numeric encoding than the original (for example,
encoding an exactly-integer timestamp as `uint32` instead of `float64`).
Our current code works against the Sideband builds we tested — the
stripped path verifies every time — but we added a fallback path
that hashes the original msgpack bytes (including the stamp) and
tries verification a second time if the stripped path fails.

### Source

`micropython-reticulum/firmware/urns/lxmf.py` `unpack_from_bytes`:

    if len(unpacked_payload) > 4:
        stamp = unpacked_payload[4]
        unpacked_payload = unpacked_payload[:4]
        packed_payload = umsgpack.packb(unpacked_payload)
    hashed_part = destination_hash + source_hash + packed_payload

### Fix

Commit `3061593`. `js/lxmf.js` `unpackMessage` now precomputes both
`hashedPart` (stripped, re-encoded) and `hashedPartOriginal` (raw
on-wire bytes). `verifyMessageSignature` tries stripped first and
falls back to original.

---

## 9. Reticulum Link protocol: responder state machine

### Finding

Full responder-side state machine, confirmed against
`Link.cpp::validate_request` and `Link.cpp::handshake` /
`Link.cpp::prove`:

1. **Receive** a `LINKREQUEST` (packet_type `0x02`) addressed to the
   SINGLE destination the responder is hosting. Payload is either 64
   bytes (`peer_x25519_pub(32) || peer_ed25519_pub(32)`) or 67 bytes
   (same plus 3 signalling bytes). Anything else is dropped.

2. **Compute link_id**:

        hashable_part = (flags & 0x0F) || raw[2:]        # HEADER_1
        hashable_part = (flags & 0x0F) || raw[18:]       # HEADER_2
        if data.len > ECPUBSIZE:
            hashable_part = hashable_part[: -(data.len - ECPUBSIZE)]
        link_id = SHA256(hashable_part)[:16]

   Both the initiator and the responder MUST compute link_id this way
   to agree on the same 16-byte id. The initiator's HEADER_1 form and
   the responder's HEADER_2 form (if the packet went through a
   relay) produce the same hashable_part because the relay only
   modifies the top 4 bits of the flag byte and inserts a transport
   id, both of which are excluded from the hashable part.

3. **Generate an ephemeral X25519 keypair** for this link. The
   responder does **not** generate an ephemeral Ed25519 keypair — it
   signs with its long-term identity Ed25519 private key. This is
   asymmetric with the initiator side, which generates both.

4. **Derive the 64-byte link session key** via HKDF-SHA256:

        shared  = X25519(ephemeral_priv, peer_x25519_pub)
        derived = HKDF(shared, salt=link_id, info=empty, length=64)

5. **Sign** `link_id || ephemeral_x25519_pub || long_term_sig_pub ||
   signalling` with the long-term Ed25519 private key. The signalling
   bytes here are echoed back from the LINKREQUEST's mtu and mode, or
   built from the defaults if the LINKREQUEST had no signalling.

6. **Emit an LRPROOF packet**:

        flags   = 0x0F  (HEADER_1, DEST_LINK, PROOF)
        hops    = 0
        dest    = link_id
        context = 0xFF  (LRPROOF)
        data    = signature(64) || ephemeral_x25519_pub(32) || signalling(3)

   LRPROOF packets are **not** encrypted. `Packet::pack` in upstream
   has an explicit `if _context == LRPROOF` branch that writes
   `link_id` into the destination slot of the header (instead of the
   SINGLE destination's hash) and writes the raw `_data` as the
   payload without a Token wrapper.

7. **Wait for LRRTT**: after verifying the LRPROOF, the initiator
   sends a `DATA` packet with `packet_type=DATA`, `dest_type=LINK`,
   `dest=link_id`, `context=0xFE (LRRTT)`, data = Token-encrypted
   msgpack of the measured RTT. The responder decrypts it with the
   derived key (decrypt success is the only thing that matters; the
   RTT value itself is informational) and marks the link ACTIVE.

8. **Handle inbound content packets** (`context=0x00`), teardown
   (`context=0xFC LINKCLOSE`, data = encrypted link_id), and
   keepalives (`context=0xFA`, can be ignored for short sessions).

### Source

`reticulum-lora-repeater/.pio/libdeps/Faketec/microReticulum/src/Link.cpp`
lines 192–304 (`validate_request`, `load_peer`, `set_link_id`,
`handshake`, `prove`) and lines 1012–1160 (`receive` dispatch by
context).

### Fix

`js/link.js` module and the `handleLinkRequest` / `handleLinkData`
functions in `js/app.js`. Verified byte-for-byte against RNS using
`tools/rns_responder.py`, which feeds an identical LINKREQUEST into
`RNS.Link.validate_request` with a monkey-patched `Packet.send` to
capture the LRPROOF bytes for diff.

### Constants (from `Type.h` of the microReticulum port)

    ECPUBSIZE         = 64    # 32 X25519 + 32 Ed25519
    KEYSIZE           = 32    # half of ECPUBSIZE
    LINK_MTU_SIZE     = 3
    SIGLENGTH         = 64    # Ed25519 signature

    MODE_AES128_CBC   = 0x00
    MODE_AES256_CBC   = 0x01   # default and the only mode we support
    MODE_AES256_GCM   = 0x02

    CONTEXT_NONE      = 0x00
    REQUEST           = 0x09
    RESPONSE          = 0x0A
    KEEPALIVE         = 0xFA
    LINKIDENTIFY      = 0xFB
    LINKCLOSE         = 0xFC
    LINKPROOF         = 0xFD   # context used for some per-packet proofs
    LRRTT             = 0xFE
    LRPROOF           = 0xFF

---

## 10. Signalling bytes encoding

### Finding

A 3-byte signalling field carries the link MTU (low 21 bits) and the
link mode (top 3 bits):

    val = (mtu & 0x1FFFFF) | ((mode & 0x07) << 21)
    bytes[0] = (val >> 16) & 0xFF
    bytes[1] = (val >>  8) & 0xFF
    bytes[2] =  val        & 0xFF

For mtu=500, mode=1 (AES256_CBC), this produces `20 01 F4`.

### Source

`reticulum-lora-repeater/.pio/libdeps/Faketec/microReticulum/src/Link.cpp`
lines 133–163 (`signalling_bytes`, `mtu_from_lr_packet`,
`mode_from_lr_packet`, `mode_byte`).

### Fix

`js/link.js` `encodeSignalling` / `decodeSignalling`.

---

## 11. Reticulum packet header flag byte layout

### Finding

    bit 7    : unused / reserved for IFAC flag (not parsed by current RNS)
    bit 6    : header_type          (0=HEADER_1, 1=HEADER_2)
    bit 5    : context_flag          (1 = announce includes a ratchet pub)
    bit 4    : transport_type        (0=BROADCAST, 1=TRANSPORT)
    bits 3-2 : destination_type      (SINGLE=0, GROUP=1, PLAIN=2, LINK=3)
    bits 1-0 : packet_type           (DATA=0, ANNOUNCE=1, LINKREQUEST=2, PROOF=3)

The two packet forms are:

    HEADER_1: flags(1) hops(1) dest_hash(16) context(1) data(...)
    HEADER_2: flags(1) hops(1) transport_id(16) dest_hash(16) context(1) data(...)

A transit relay converts HEADER_1 to HEADER_2 by setting bit 6,
inserting its own identity as the transport id, and re-transmitting.
The packet_hash computation in `get_hashable_part` deliberately
excludes `hops` (raw[1]) and the high 4 bits of flags so the same
packet has the same hash before and after transit.

Exception: when `_context == LRPROOF` in `Packet::pack`, the
destination slot in the HEADER_1 layout is filled with `link_id`
instead of `destination.hash()`, and `get_packed_flags` hardcodes
`dest_type = LINK` regardless of the destination passed at
construction. This is why LRPROOF packets always look
`0x0F || hops || link_id || 0xFF || data`.

### Source

`Packet.cpp` `get_packed_flags` lines 91–106 and `pack()` lines
282–390.

---

## 12. link_id derivation excludes transit-visible fields

### Finding

`link_id = SHA256(hashable_part)[:16]` where

    hashable_part = (flags & 0x0F) || raw[2:]           # HEADER_1
    hashable_part = (flags & 0x0F) || raw[18:]          # HEADER_2

Note the leading byte is just the **low nibble** of the flag byte.
This deliberately strips `header_type`, `context_flag`, and
`transport_type`, all of which can be changed by transit nodes.
It preserves `destination_type` and `packet_type`, which can't.

Then `raw[2:]` (or `raw[18:]`) starts at the destination slot, which
always contains the target destination hash — the same bytes whether
HEADER_1 (destination at offset 2) or HEADER_2 (transport_id at
offset 2, destination at offset 18).

For LINKREQUEST packets that have 3 signalling bytes appended to the
64-byte ECPUBSIZE body, those 3 bytes are stripped from the **end** of
hashable_part before hashing. Other packet types do not have this
stripping rule.

### Source

`Link.cpp` `link_id_from_lr_packet` lines 182–190.

### Fix

`js/link.js` `computeLinkId` (with signalling stripping for
LINKREQUEST only) and `computePacketFullHash` (without stripping, for
regular data-packet receipts).

---

## 13. Link packet receipts are mandatory for delivery

### Finding

Every `CONTEXT_NONE` data packet on an established link must be
acknowledged by the responder with a **packet proof** addressed back
to the link. Without that proof, the sender's delivery receipt
timeout fires, it marks the message as failed, and it opens a new
link and retries the entire message queue. This looks exactly like
"the same message keeps arriving every 15 seconds on a new link."

### Upstream behavior

`Link::receive` for `case CONTEXT_NONE` in the DATA branch calls the
packet callback, then calls `packet.prove()`. For a packet whose
destination is a Link, `Packet::prove` routes to
`Link::prove_packet`, which builds:

    packet_hash = SHA256(packet.hashable_part)        # full 32 bytes
    signature   = long_term_sig_prv.sign(packet_hash) # 64 bytes
    proof_data  = packet_hash || signature             # 96 bytes

and sends a `PROOF` packet (`flags=0x0F`, `context=0x00`,
`dest=link_id`) carrying that proof_data.

### Implicit vs explicit proofs

Upstream has a TODO comment noting that newer Python RNS builds can
use "implicit" proofs where `proof_data = signature` (64 bytes, no
hash prefix) and the sender walks its outstanding receipts trying to
verify against each. `RNS.Reticulum.should_use_implicit_proof()`
defaults to `True` in RNS 1.1.4. Both formats are accepted in practice
because the verifier is tolerant.

Our client sends the **explicit** 96-byte form. It works against every
peer tested so far. If a future peer refuses it, switching to implicit
is a one-line change (drop the packet_hash prefix from the proof_data).

### Source

`Link.cpp` `prove_packet` lines 306–320, `Link::receive` lines
1030–1067, `Packet::prove` lines 521–538.

### Fix

Commit `e8deb9f`. `js/link.js` `computePacketFullHash` computes the
32-byte hash of the received packet. `js/app.js` `handleLinkData`
`CTX_NONE` branch signs it with `myIdentity.sigPrivKey` and sends the
proof packet right after dispatching the message upward.

---

## 14. Periodic re-announce is required for link delivery

### Finding

When a relay forwards an inbound LRPROOF, it **validates the
signature first** using an `Identity.recall(destination_hash)` call
that looks up the responder's identity in its own path table / identity
cache. If that cache does not contain the responder, `recall()`
returns empty, the verification fails, and the LRPROOF is silently
dropped at the relay without ever reaching the initiator.

Relay identity caches get GC'd on a schedule, and a one-shot
announce-on-connect is not enough to keep all relays in a mesh
populated — only the nearest ones will have seen it.

Every long-running Python RNS daemon re-announces on a timer.
Sideband defaults to every 30 minutes. Without periodic
re-announcement, the web client looks functional for opportunistic
delivery (because opportunistic packets are forwarded without any
relay-side validation) but silently fails every link handshake
because every LRPROOF gets dropped mid-relay.

### Source

`Transport.cpp` LRPROOF branch, lines 2405–2470 in the microReticulum
port:

    if (Reticulum::transport_enabled() && _link_table.find(...) != end) {
        Identity peer_identity = Identity::recall(link_entry._destination_hash);
        Bytes peer_sig_pub_bytes = peer_identity.get_public_key()[32:64];
        Bytes signed_data = packet.destination_hash() + peer_pub_bytes
                          + peer_sig_pub_bytes + signalling_bytes;
        if (peer_identity.validate(signature, signed_data)) {
            // forward
        } else {
            // drop silently
        }
    }

### Fix

Commit `916947f`. `js/app.js` `startRadio` fires one announce right
after `Radio on` and installs a `setInterval` that re-announces every
5 minutes while `radioOn` is true. The disconnect path clears the
interval.

---

## 15. Clockless sender timestamps

### Finding

LoRa nodes without a real-time clock (embedded devices, many
Reticulum-over-microcontroller builds) return seconds-since-boot from
their `time.time()` call, not seconds-since-1970. Those numbers are
small — a device up for 19 hours returns 68880 — and when interpreted
as Unix timestamps they resolve to `Jan 1, 1970 19:08 UTC`.

Sorting the message list by the stored timestamp puts every such
message at the very top of the conversation view, ahead of every
well-clocked message you exchanged today, producing an extremely
confusing out-of-order display.

### Fix

Commit `04d1e77`. Two parts:

* On **save**, `normalizeLxmfTimestamp` returns `null` for any value
  that resolves to a pre-2020 wall-clock date. The incoming save path
  substitutes `Date.now()` for `null`, so new rows from clockless
  senders get the receive time instead of the bogus sender time.

* On **render**, `renderMessages` sorts by the IndexedDB
  auto-increment `id` rather than by timestamp. `id` is strictly
  insertion order, which is always the order the message was received
  — correct chronological display regardless of what the sender's
  clock claims. Historical rows with bogus stored timestamps show
  `(no time)` in the meta label because there is no way to recover
  the original receive time after the fact.

---

## 16. RNode 1-byte LoRa frame header is transparent to KISS hosts

### Finding

Every RNode prepends a 1-byte proprietary header to every LoRa frame.
The upper nibble is a random sequence number, the lower nibble is
flags (currently only `FLAG_SPLIT=0x01` for multi-frame packets).
**The RNode firmware strips this header on RX before handing the
payload to the KISS host.** It also adds the header on TX before
pushing the bytes to the radio.

This means the web client — which talks to the RNode via Web
Bluetooth / Web Serial KISS — never sees the 1-byte header and never
has to add or strip it. We see raw Reticulum frames on both ends of
the KISS channel.

The sibling `reticulum-lora-repeater` project had to deal with the
1-byte header directly because it drives the SX1262 via RadioLib
(bypassing KISS entirely), and its commit `76c731e` documents the
symptoms of not handling it correctly. That is not our problem — but
it is worth knowing it exists so we don't chase a phantom bug looking
for a missing header byte in our KISS RX.

### Source

`reticulum-rnode/src/Kiss.cpp` `send_rx_packet` and `dispatch_frame`,
and `Radio.cpp` `read_pending` / `transmit` which do the byte
prepend/strip.

---

## 17. Diagnostic tools

All four live in `tools/` and are Python scripts that only depend on
`rns` and `umsgpack` from pip (both already required by Sideband, so
any machine that can run Python RNS can run these).

### `tools/identity_info.py <exported_identity.json>`

Reads an `Export Identity` JSON from the web client and prints the
derived `encPubKey`, `sigPubKey`, combined `publicKey`, 16-byte
`identityHash`, and 16-byte `lxmfDestHash`. Useful for sanity-checking
that the identity file you have on disk matches the LXMF address the
web client displays in its header, and for extracting the long-term
sig pub that the other tools need.

### `tools/verify_lrproof.py [--sigpub HEX] [--lrproof HEX] [--linkid HEX]`

Runs a two-phase verification:

1. **Phase 1** (always runs): a full sign-and-verify self test against
   RNS's own `Ed25519PrivateKey.sign` / `Ed25519PublicKey.verify`,
   plus an HKDF round trip, plus an X25519 ECDH symmetric check.
   Catches RNS API drift or `cryptography` library changes.

2. **Phase 2** (if `--sigpub` is given): takes the hex dump of an
   LRPROOF packet that the web client logged plus the link id, parses
   the proof framing, reconstructs `signed_data`, and runs
   `Ed25519PublicKey.verify` on it. Pass means our LRPROOF bytes are
   byte-compatible with what upstream expects. Fail means we have a
   signing bug.

### `tools/rns_responder.py <identity.json> [--linkreq HEX]`

Runs Python RNS as a full link responder. Loads the supplied identity,
constructs a fake LINKREQUEST from the supplied data field (or
generates a random one), monkey-patches `Packet.send` to capture
instead of transmit, and calls `Link.validate_request` to walk the
same validation and LRPROOF-building path the real stack would.
Dumps the emitted LRPROOF bytes field by field.

Used to prove that, given the same LINKREQUEST data, RNS produces an
LRPROOF **byte-identical** to ours in every field except the random
ephemeral X25519 pub and the resulting signature. This is the test
that definitively closed "the LRPROOF format is right."

### `tools/verify_announce.py <identity.json> [--name WebClient]`

Builds an `lxmf.delivery` announce with RNS using the web client's
identity and display name, packs it through `Destination.announce(
send=False)`, and feeds the resulting raw bytes into
`Identity.validate_announce`. Pass means our announce format is
acceptable to the upstream reference. Used to prove that announces
Sideband refused to display were rejected by Sideband's UI state
(known_destinations cache or blackhole list), not by wire-level
validation.

---

## 18. Known gaps and deferred work

### Ratchet emission on outbound announces

We **parse** ratchet fields on inbound announces so the signature
verifier doesn't mis-align on the following bytes, but we do **not**
emit a ratchet in our own announces. The `context_flag` bit of our
outbound flag byte is always 0. Every node tested so far accepts
ratchet-less announces for backward compatibility, but a peer running
strict ratchet-only validation would reject us. Doing this properly
means generating and persisting an ephemeral X25519 ratchet keypair
alongside the identity, including its public half in the announce
(and in the signed data), setting `context_flag=1`, and teaching the
opportunistic decrypt path to fall back from the identity X25519 key
to the ratchet key. Non-trivial but well-scoped.

### Link initiation (we are responder-only)

We cannot open a Link to another peer. Messages we originate are
always delivered opportunistically, which means they fail silently
for any message larger than roughly 250–300 bytes of content. The
initiator side of Link uses a different state machine (we generate
the X25519+Ed25519 ephemerals on our side, wait for an LRPROOF, verify
it, send an LRRTT, etc.) that is roughly the same amount of code as
the responder side but with no shared surface.

### Resources (multi-packet transfers over Link)

Neither we nor the responder side handle Resource framing, so
messages larger than ~415 bytes via Link delivery fail. In practice
this is fine for text, breaks for anything with an attachment.

### Outbound retry queue

When a send fails (radio off, no path, delivery timeout with no
proof), we mark the row as "outgoing" in IndexedDB and move on. There
is no outbound queue, no retry, no "failed" state in the UI.

### GitHub Actions CI

A minimal workflow that installs `rns` and runs
`python tools/verify_lrproof.py` phase-1 self test would be a useful
smoke test against RNS upstream drift. The next iteration would
synthesize a LINKREQUEST with `rns_responder.py`, feed it into
`js/link.js` under Node (the module only imports `@noble/curves`, no
browser APIs), and round-trip the resulting LRPROOF back through
`RNS.Link.validate_proof` to catch any regression in either wire
direction.

---

## 19. Commit reference

Chronological list of the commits this document refers to, with their
short subjects. `git show <sha>` will show the exact diff for any of
them.

| commit      | subject                                                                  |
|-------------|--------------------------------------------------------------------------|
| `2faf24a`   | (reverted) Fix destination hash computation (include identity hexhash)   |
| `f508519`   | Use destination hash as LXMF source_hash                                 |
| `205fa2d`   | Stop double-PKCS7-padding in Token encrypt/decrypt                       |
| `2103dcc`   | Revert destination hash to no-hexhash form                               |
| `87e75bc`   | Accept incoming Reticulum Links for LXMF delivery                        |
| `e7578c0`   | (reverted in 15596a4) Trace LRPROOF signed data and re-announce before proof send |
| `6ad2b64`   | Auto-announce once when the radio comes up                               |
| `6a18662`   | Add tools/verify_announce.py                                             |
| `7553910`   | Ignore exported identity files                                           |
| `0e2570e`   | Add rns_responder.py                                                     |
| `a5ea2fa`   | Add RNS-based loopback diagnostic tools                                  |
| `ba4cbc2`   | Filter announces by name_hash                                            |
| `15596a4`   | Tighten legacy contact cleanup and drop pre-LRPROOF announce             |
| `0c9bfe8`   | Normalize LXMF timestamps and trace link-request bytes                   |
| `916947f`   | Periodic auto-announce, unread badge, save-path diagnostic               |
| `04d1e77`   | Sort chat by insertion order and handle clockless sender timestamps      |
| `3061593`   | Fall back to unstripped msgpack for LXMF signature verification          |
| `e8deb9f`   | Send link packet receipt after each received content packet              |
| `c3502fd`   | Add a version badge in the header                                        |
